package com.yukiorita.halfbrowser.core

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** Credentials read from an HTML login form. */
data class FormCredentials(
    val username: String,
    val password: String,
)

/**
 * HTML form login capture and autofill.
 *
 * Half-Browser has no JavaScript bridge, so the page cannot push data into the
 * app. Instead the app evaluates two tiny, read-only scripts on demand:
 *
 * - [EXTRACT_JS] reads the visible password field and its nearest user field.
 * - [fillScript] writes a saved pair into empty fields (never submits).
 *
 * Both are only used on https pages, and the extracted value is stored in the
 * same Keystore-encrypted store as HTTP-auth credentials.
 */
object FormLogin {

    /** Realm marker so form logins and HTTP-auth realms never collide. */
    const val REALM = "form-login"

    val EXTRACT_JS = """
(function () {
  function visible(el) {
    if (!el) return false;
    var rect = el.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) return false;
    var style = getComputedStyle(el);
    return style.visibility !== 'hidden' && style.display !== 'none';
  }
  var passwords = document.querySelectorAll('input[type=password]');
  var password = Array.prototype.find.call(passwords, visible);
  if (!password) return null;
  var scope = password.form || document;
  var fields = scope.querySelectorAll(
    'input[autocomplete=username], input[type=email], input[type=text], input[type=tel]'
  );
  var user = Array.prototype.find.call(fields, function (el) { return visible(el) && el.value; });
  if (!user) user = Array.prototype.find.call(fields, visible);
  return {
    u: user && user.value ? String(user.value) : '',
    p: String(password.value || ''),
    a: String((password.form && password.form.action) || location.href)
  };
})()
""".trimIndent()

    fun fillScript(username: String, password: String): String {
        val userJson = Codecs.json.encodeToString(String.serializer(), username)
        val passJson = Codecs.json.encodeToString(String.serializer(), password)
        return """
(function (u, p) {
  function visible(el) {
    if (!el) return false;
    var rect = el.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) return false;
    var style = getComputedStyle(el);
    return style.visibility !== 'hidden' && style.display !== 'none';
  }
  function set(el, value) {
    if (!el) return;
    var descriptor = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), 'value');
    if (descriptor && descriptor.set) descriptor.set.call(el, value); else el.value = value;
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  }
  var password = Array.prototype.find.call(document.querySelectorAll('input[type=password]'), visible);
  if (!password) return 'no-form';
  if (password.value) return 'has-value';
  var scope = password.form || document;
  var fields = scope.querySelectorAll(
    'input[autocomplete=username], input[type=email], input[type=text], input[type=tel]'
  );
  var user = Array.prototype.find.call(fields, visible);
  if (user && !user.value) set(user, u);
  set(password, p);
  return 'filled';
})($userJson, $passJson)
""".trimIndent()
    }

    /** Returns null when the page had no usable password field. */
    fun parseExtraction(raw: String?): FormCredentials? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        if (trimmed == "null") return null
        val obj = runCatching {
            Codecs.json.parseToJsonElement(trimmed) as? JsonObject
        }.getOrNull() ?: return null
        val username = obj["u"]?.jsonPrimitive?.contentOrNullSafe().orEmpty().take(200)
        val password = obj["p"]?.jsonPrimitive?.contentOrNullSafe().orEmpty().take(500)
        if (password.isBlank()) return null
        return FormCredentials(username = username, password = password)
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { content }.getOrNull()
}

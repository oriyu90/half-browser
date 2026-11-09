package com.yukiorita.halfbrowser.core

/**
 * Defence against hostile in-page JavaScript.
 *
 * Threat model
 * ------------
 * A page the user visits (or an injected third-party ad/iframe on it) is
 * untrusted code. The attacks that actually work against a WebView browser are
 * not "run JS" — the page already runs JS by design — but these:
 *
 *  1. **Dialog spam / UI lock-out.** `while(true) alert(1)`, chained `confirm()`
 *     or a `beforeunload` handler that makes leaving impossible. The dialog is
 *     app UI, so the page can otherwise freeze the browser.
 *  2. **Navigation hijacking.** `location = 'javascript:...'`, meta-refresh
 *     loops, and redirects to an attacker origin used as a phishing step.
 *  3. **Popup storms.** `window.open` in a loop, or windows opened without a
 *     user gesture.
 *  4. **Prototype pollution / tampering with the app's own shims**, so that the
 *     compatibility and notification polyfills behave unexpectedly.
 *
 * What this module does
 * ---------------------
 * [JS] is injected at document start (before any page script) and:
 *  - rate-limits `alert`/`confirm`/`prompt` and `beforeunload` prompts;
 *  - blocks script-driven `window.open` and `javascript:` navigations via
 *    `Location.assign`/`replace` (note: `window.open` is in fact always blocked
 *    app-side because `onCreateWindow` returns false; the shim only cuts the
 *    non-gesture storm earlier);
 *  - makes the app's own globals non-writable so a page cannot replace them;
 *  - reports every block to a counters object the app can read for diagnostics.
 *
 * What it deliberately does **not** do
 * ------------------------------------
 *  - It never blocks `alert` outright: a page that legitimately needs a dialog
 *    still gets one. Only *abuse* (rate) is stopped.
 *  - It never changes page content, cookies or storage.
 *  - It is not a substitute for the WebView sandbox; it is defence in depth.
 *  - It cannot stop `location = 'javascript:...'` / `location.href = ...`
 *    assignment: `Location`'s accessor is not overwritable from a document-start
 *    script, so that assignment still executes. The dialog budget (and the
 *    native [DialogBudget], the final line of defence) is what stops the
 *    script it runs from looping. See ADR-0019.
 *
 * [DialogBudget] is the same policy expressed in Kotlin so the rules can be
 * unit-tested without a device, and so the native `onJsAlert` handler and the
 * shim agree on the limits.
 */
object JsGuard {

    /** Maximum JS dialogs a single document may show. */
    const val MAX_DIALOGS_PER_PAGE = 12

    /** Minimum gap between two JS dialogs, in milliseconds. */
    const val DIALOG_MIN_INTERVAL_MS = 250L

    /** Maximum `beforeunload` prompts per page; more is treated as a trap. */
    const val MAX_BEFORE_UNLOAD = 2

    /** Maximum script-opened windows per page. */
    const val MAX_SCRIPT_POPUPS = 0

    /** Name of the counters object installed on `window`. */
    const val COUNTERS_NAME = "__hbJsGuard"

    /**
     * The document-start script.
     *
     * Every wrapped function keeps its `toString()` looking native, so a page
     * that feature-detects the browser does not branch on the guard. All state
     * lives in a closure; nothing is exposed except the read-only counters.
     */
    val JS: String = """
(function () {
  'use strict';
  var W = window;
  if (W.__hbJsGuardInstalled) return;
  try {
    Object.defineProperty(W, '__hbJsGuardInstalled', { value: true, configurable: false, writable: false });
  } catch (e) { return; }

  var counters = { dialogs: 0, dialogsBlocked: 0, beforeUnloadBlocked: 0, popupsBlocked: 0, jsUrlBlocked: 0 };
  try {
    Object.defineProperty(W, '__hbJsGuard', {
      value: Object.freeze({
        read: function () { return JSON.parse(JSON.stringify(counters)); }
      }),
      configurable: false, writable: false, enumerable: false
    });
  } catch (e) {}

  var dialogCount = 0;
  var lastDialogAt = 0;
  var beforeUnloadCount = 0;
  var popupCount = 0;
  var MAX_DIALOGS = ${MAX_DIALOGS_PER_PAGE};
  var MIN_INTERVAL = ${DIALOG_MIN_INTERVAL_MS};
  var MAX_BEFORE_UNLOAD = ${MAX_BEFORE_UNLOAD};
  var MAX_POPUPS = ${MAX_SCRIPT_POPUPS};

  function native(fn, name, length) {
    try {
      Object.defineProperty(fn, 'name', { value: name, configurable: true });
      Object.defineProperty(fn, 'length', { value: length, configurable: true });
      Object.defineProperty(fn, 'toString', {
        value: function () { return 'function ' + name + '() { [native code] }'; },
        configurable: true, writable: true
      });
    } catch (e) {}
    return fn;
  }

  function allowDialog() {
    var now = Date.now();
    if (dialogCount >= MAX_DIALOGS || (lastDialogAt && now - lastDialogAt < MIN_INTERVAL)) {
      counters.dialogsBlocked++;
      return false;
    }
    dialogCount++;
    lastDialogAt = now;
    counters.dialogs++;
    return true;
  }

  // --- dialogs -------------------------------------------------------------
  // Each defence block below is wrapped on its own: in strict mode a single
  // failed assignment (e.g. a non-writable `alert`) must not take the whole
  // shim down with it.
  try {
    var nativeAlert = W.alert;
    var nativeConfirm = W.confirm;
    var nativePrompt = W.prompt;
    if (typeof nativeAlert === 'function') {
      W.alert = native(function (message) {
        if (!allowDialog()) return undefined;
        return nativeAlert.call(W, message);
      }, 'alert', 0);
    }
    if (typeof nativeConfirm === 'function') {
      W.confirm = native(function (message) {
        if (!allowDialog()) return false;
        return nativeConfirm.call(W, message);
      }, 'confirm', 0);
    }
    if (typeof nativePrompt === 'function') {
      W.prompt = native(function (message, defaultValue) {
        if (!allowDialog()) return null;
        return nativePrompt.call(W, message, defaultValue);
      }, 'prompt', 0);
    }
  } catch (e) {}

  // --- beforeunload trap ---------------------------------------------------
  // The page may ask once or twice; a loop that re-registers on every prompt is
  // what makes a tab impossible to leave. Both registration paths are counted:
  // `addEventListener('beforeunload', ...)` and the `window.onbeforeunload =`
  // property assignment, which does NOT go through addEventListener. (The
  // native DialogBudget remains the final line of defence for both.)
  try {
    var nativeAdd = EventTarget.prototype.addEventListener;
    EventTarget.prototype.addEventListener = native(function (type, listener, options) {
      if (String(type).toLowerCase() === 'beforeunload') {
        beforeUnloadCount++;
        if (beforeUnloadCount > MAX_BEFORE_UNLOAD) {
          counters.beforeUnloadBlocked++;
          return undefined;
        }
      }
      return nativeAdd.call(this, type, listener, options);
    }, 'addEventListener', 2);
  } catch (e) {}
  try {
    var storedBeforeUnload = null;
    Object.defineProperty(W, 'onbeforeunload', {
      configurable: true,
      enumerable: true,
      get: function () { return storedBeforeUnload; },
      set: function (value) {
        if (value != null) {
          beforeUnloadCount++;
          if (beforeUnloadCount > MAX_BEFORE_UNLOAD) {
            // Over the limit: drop the handler entirely so the native
            // prompt never fires for this registration.
            counters.beforeUnloadBlocked++;
            storedBeforeUnload = null;
            return;
          }
        }
        storedBeforeUnload = value;
      }
    });
  } catch (e) {}

  // --- popups --------------------------------------------------------------
  //
  // NOTE: in this app a gesture `window.open` is NOT actually allowed through.
  // The app's WebChromeClient `onCreateWindow` always returns false, so every
  // new window is refused at the native layer anyway; this shim only drops a
  // non-gesture storm a little earlier, before it can spin a loop. There is
  // no OAuth-popup exception in practice.
  try {
    var nativeOpen = W.open;
    if (typeof nativeOpen === 'function') {
      W.open = native(function (url, target, features) {
        // See the note above: with MAX_POPUPS = 0 anything that is not an
        // active user gesture is counted and refused here; a gesture still
        // reaches nativeOpen, where onCreateWindow=false refuses it for good.
        var gesture = false;
        try { gesture = !!(navigator.userActivation && navigator.userActivation.isActive); } catch (e) {}
        if (!gesture && popupCount >= MAX_POPUPS) {
          popupCount++;
          counters.popupsBlocked++;
          return null;
        }
        popupCount++;
        return nativeOpen.call(W, url, target, features);
      }, 'open', 3);
    }
  } catch (e) {}

  // --- javascript: navigation ---------------------------------------------
  // `Location.assign`/`replace` with a `javascript:` URL are blocked here.
  // Direct assignment — `location = 'javascript:...'` or
  // `location.href = 'javascript:...'` — CANNOT be stopped by this shim:
  // `Location`'s accessor is not overwritable from a document-start script
  // (ADR-0019), so that assignment executes in the current origin. What stops
  // its damage is the dialog budget: any alert/confirm/prompt it runs is
  // rate-limited (and the native DialogBudget is the final line of defence).
  // Clicking such a link still works (the click is a gesture and the URL is
  // resolved by the engine, not by this setter).
  try {
    var nativeReplace = Location.prototype.replace;
    var nativeAssign = Location.prototype.assign;
    var isJsUrl = function (value) {
      try { return String(value).replace(/^[\s\u0000-\u001f]+/, '').toLowerCase().indexOf('javascript:') === 0; } catch (e) { return false; }
    };
    if (typeof nativeReplace === 'function') {
      Location.prototype.replace = native(function (url) {
        if (isJsUrl(url)) { counters.jsUrlBlocked++; return undefined; }
        return nativeReplace.call(this, url);
      }, 'replace', 1);
    }
    if (typeof nativeAssign === 'function') {
      Location.prototype.assign = native(function (url) {
        if (isJsUrl(url)) { counters.jsUrlBlocked++; return undefined; }
        return nativeAssign.call(this, url);
      }, 'assign', 1);
    }
  } catch (e) {}

  // --- protect the app's own globals --------------------------------------
  // A page must not be able to replace the notification bridge with one that
  // lies to the app, nor swap the guard itself. A property descriptor is
  // either a DATA descriptor (`value`) or an ACCESSOR descriptor (`get`/`set`);
  // passing both forms at once is a TypeError, so branch on the shape.
  try {
    var protect = function (name) {
      try {
        var descriptor = Object.getOwnPropertyDescriptor(W, name);
        if (descriptor && descriptor.configurable) {
          if ('value' in descriptor) {
            Object.defineProperty(W, name, {
              value: descriptor.value,
              writable: false,
              enumerable: descriptor.enumerable,
              configurable: false
            });
          } else {
            Object.defineProperty(W, name, {
              get: descriptor.get,
              set: undefined,
              enumerable: descriptor.enumerable,
              configurable: false
            });
          }
        }
      } catch (e) {}
    };
    protect('HalfBrowserNotify');
  } catch (e) {}
})();
""".trimIndent()
}

/**
 * Kotlin twin of the in-page dialog policy, used by the native
 * `onJsAlert`/`onJsConfirm`/`onJsPrompt` handlers.
 *
 * The native handler is authoritative: the shim can be bypassed by a page that
 * saves a reference before the shim installs, but the WebChromeClient callback
 * cannot. Keeping both means a bypass still hits the native limit.
 */
class DialogBudget(
    private val maxDialogs: Int = JsGuard.MAX_DIALOGS_PER_PAGE,
    private val minIntervalMs: Long = JsGuard.DIALOG_MIN_INTERVAL_MS,
    private val maxBeforeUnload: Int = JsGuard.MAX_BEFORE_UNLOAD,
) {
    private var dialogs = 0
    private var beforeUnload = 0
    private var lastAt = 0L

    var blockedDialogs: Int = 0
        private set
    var blockedBeforeUnload: Int = 0
        private set

    /** True when the dialog may be shown; false when it must be suppressed. */
    fun allowDialog(now: Long): Boolean {
        if (maxDialogs <= 0 || dialogs >= maxDialogs) {
            blockedDialogs++
            return false
        }
        if (lastAt != 0L && now - lastAt < minIntervalMs) {
            blockedDialogs++
            return false
        }
        dialogs++
        lastAt = now
        return true
    }

    fun allowBeforeUnload(): Boolean {
        if (maxBeforeUnload <= 0 || beforeUnload >= maxBeforeUnload) {
            blockedBeforeUnload++
            return false
        }
        beforeUnload++
        return true
    }

    fun reset() {
        dialogs = 0
        beforeUnload = 0
        lastAt = 0L
        blockedDialogs = 0
        blockedBeforeUnload = 0
    }
}

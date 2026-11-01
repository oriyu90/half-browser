package com.yukiorita.halfbrowser.core

/**
 * Small, app-controlled JavaScript compatibility shims injected before any page
 * script runs.
 *
 * Android System WebView is updated independently of the app (and can be frozen
 * on older devices), so a modern site can die on a missing API even though the
 * browser itself supports everything it needs. The DSH web UI, for example,
 * calls `Promise.withResolvers` (Chromium 119) and `AbortSignal.any`
 * (Chromium 116); on WebView 113 the page stays stuck at "Loading plugins…".
 *
 * These shims are standard-library polyfills only: no network access, no DOM
 * access, no bridge to the app. They are injected at document start through
 * `WebViewCompat.addDocumentStartJavaScript`, so page code cannot observe them
 * being added after the fact.
 */
object WebCompat {

    val POLYFILL_JS: String = """
(function () {
  if (typeof Promise.withResolvers !== 'function') {
    Promise.withResolvers = function () {
      var resolve, reject;
      var promise = new Promise(function (res, rej) { resolve = res; reject = rej; });
      return { promise: promise, resolve: resolve, reject: reject };
    };
  }
  if (typeof AbortSignal.any !== 'function') {
    AbortSignal.any = function (signals) {
      var controller = new AbortController();
      var list = Array.prototype.slice.call(signals || []);
      function onAbort(event) {
        controller.abort(event && event.target ? event.target.reason : undefined);
        list.forEach(function (signal) { signal.removeEventListener('abort', onAbort); });
      }
      for (var i = 0; i < list.length; i++) {
        if (list[i].aborted) {
          controller.abort(list[i].reason);
          return controller.signal;
        }
        list[i].addEventListener('abort', onAbort);
      }
      return controller.signal;
    };
  }
  if (typeof AbortSignal.timeout !== 'function') {
    AbortSignal.timeout = function (ms) {
      var controller = new AbortController();
      setTimeout(function () {
        controller.abort(new DOMException('TimeoutError', 'TimeoutError'));
      }, ms);
      return controller.signal;
    };
  }
  if (typeof Object.groupBy !== 'function') {
    Object.groupBy = function (items, callback) {
      var result = Object.create(null);
      var index = 0;
      for (var item of items) {
        var key = String(callback(item, index++));
        (result[key] = result[key] || []).push(item);
      }
      return result;
    };
  }
  if (typeof Map.groupBy !== 'function') {
    Map.groupBy = function (items, callback) {
      var result = new Map();
      var index = 0;
      for (var item of items) {
        var key = callback(item, index++);
        if (!result.has(key)) result.set(key, []);
        result.get(key).push(item);
      }
      return result;
    };
  }
  if (typeof Promise.try !== 'function') {
    Promise.try = function (fn) {
      var args = Array.prototype.slice.call(arguments, 1);
      return new Promise(function (resolve) { resolve(fn.apply(null, args)); });
    };
  }
  if (typeof Array.fromAsync !== 'function') {
    Array.fromAsync = async function (iterable, mapFn) {
      var out = [];
      var index = 0;
      for await (var item of iterable) {
        out.push(mapFn ? await mapFn(item, index++) : item);
      }
      return out;
    };
  }
  if (typeof Set.prototype.union !== 'function') {
    Set.prototype.union = function (other) {
      var out = new Set(this);
      for (var value of other) out.add(value);
      return out;
    };
    Set.prototype.intersection = function (other) {
      var out = new Set();
      for (var value of this) if (other.has(value)) out.add(value);
      return out;
    };
    Set.prototype.difference = function (other) {
      var out = new Set();
      for (var value of this) if (!other.has(value)) out.add(value);
      return out;
    };
    Set.prototype.symmetricDifference = function (other) {
      var out = new Set(this);
      for (var value of other) {
        if (out.has(value)) out.delete(value); else out.add(value);
      }
      return out;
    };
    Set.prototype.isSubsetOf = function (other) {
      for (var value of this) if (!other.has(value)) return false;
      return true;
    };
    Set.prototype.isSupersetOf = function (other) {
      for (var value of other) if (!this.has(value)) return false;
      return true;
    };
    Set.prototype.isDisjointFrom = function (other) {
      for (var value of this) if (other.has(value)) return false;
      return true;
    };
  }
  if (typeof URL.parse !== 'function') {
    URL.parse = function (url, base) {
      try { return new URL(url, base); } catch (error) { return null; }
    };
  }
})();
""".trimIndent()


    /** APIs covered by [POLYFILL_JS]; used by tests and for diagnostics. */
    val COVERED_APIS: List<String> = listOf(
        "Promise.withResolvers",
        "AbortSignal.any",
        "AbortSignal.timeout",
        "Object.groupBy",
        "Map.groupBy",
        "Promise.try",
        "Array.fromAsync",
        "Set.prototype.union",
        "URL.parse",
    )
}

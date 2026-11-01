package com.yukiorita.halfbrowser.core

/**
 * Keeps `Shift` alive for pages that rely on `KeyboardEvent.shiftKey`.
 *
 * Why this exists: the browser itself never drops a modifier (it hands key
 * events straight to Chromium), but two paths can still deliver `Enter` without
 * the modifier bit:
 *
 *  1. an IME that reports the hardware modifier state late (the key-up of Shift
 *     racing the Enter key-down), and
 *  2. a soft keyboard that synthesises `Enter` from an editor action, where the
 *     modifier is not carried over at all.
 *
 * The script tracks Shift from its own key events (which Chromium does deliver)
 * and, when an `Enter` arrives with `shiftKey === false` while Shift is still
 * held, re-defines `shiftKey` on that event instance. The listener is registered
 * in the capture phase at document start, so page handlers — which run later —
 * observe the corrected value. Nothing is inserted, sent or prevented; a page
 * that ignores `shiftKey` (like a plain textarea) behaves exactly as before.
 *
 * `window.__hbKeyEvents` keeps the last few events so the behaviour can be
 * inspected from a test page without a debugger.
 */
object KeyboardGuard {

    val JS: String = """
(function () {
  'use strict';
  if (window.__hbKeyGuard) return;
  window.__hbKeyGuard = true;

  var shiftHeld = false;
  var log = [];
  window.__hbKeyEvents = log;

  function record(event, patched) {
    try {
      log.push({
        key: event.key,
        code: event.code,
        shiftKey: event.shiftKey,
        patched: patched,
        target: event.target && event.target.tagName ? event.target.tagName : '',
        at: Date.now()
      });
      if (log.length > 20) log.shift();
    } catch (e) {}
  }

  function isShift(event) {
    return event.key === 'Shift' || event.code === 'ShiftLeft' || event.code === 'ShiftRight';
  }

  document.addEventListener('keydown', function (event) {
    if (isShift(event)) {
      shiftHeld = true;
      record(event, false);
      return;
    }
    if (event.key !== 'Enter' && event.keyCode !== 13) {
      record(event, false);
      return;
    }
    var nativeShift = event.shiftKey || (typeof event.getModifierState === 'function'
      ? event.getModifierState('Shift') : false);
    if (nativeShift || !shiftHeld) {
      record(event, false);
      return;
    }
    // Shift is physically held but the event lost the modifier: correct this
    // single event instance so page handlers see shiftKey === true.
    try {
      Object.defineProperty(event, 'shiftKey', {
        get: function () { return true; },
        configurable: true
      });
    } catch (e) {}
    record(event, true);
  }, true);

  document.addEventListener('keyup', function (event) {
    if (isShift(event)) shiftHeld = false;
  }, true);

  window.addEventListener('blur', function () { shiftHeld = false; }, true);
  document.addEventListener('visibilitychange', function () { shiftHeld = false; }, true);
})();
""".trimIndent()
}

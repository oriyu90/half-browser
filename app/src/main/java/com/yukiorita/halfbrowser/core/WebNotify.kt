package com.yukiorita.halfbrowser.core

/**
 * JavaScript for the in-app web-notification bridge.
 *
 * Android WebView does not implement the Web Notifications API (its
 * `PermissionRequest` exposes only camera/microphone/MIDI/protected-media, and
 * there is no notification resource), so a page that calls
 * `new Notification(...)` would fail with a ReferenceError. This polyfill
 * installs a spec-shaped `Notification` object that forwards to the app, which
 * posts a real Android notification.
 *
 * The polyfill is injected with [androidx.webkit.WebViewCompat.addDocumentStartJavaScript]
 * so it runs before any page script and cannot be replaced by the page.
 */
object WebNotify {

    /** Name of the injected JavaScript object that carries the messages. */
    const val BRIDGE_NAME = "HalfBrowserNotify"

    val JS: String = """
(function () {
  'use strict';
  if (window.__hbNotifyInstalled) return;
  window.__hbNotifyInstalled = true;
  // A WebView build that ships a real implementation wins over this polyfill.
  if (typeof window.Notification === 'function' && window.Notification.__hbNative) return;

  var state = { permission: 'default' };
  var pending = {};
  var nextId = 1;
  var bridge = null;

  function connect() {
    if (bridge) return true;
    var candidate = window.HalfBrowserNotify;
    if (!candidate || typeof candidate.postMessage !== 'function') return false;
    try {
      candidate.onmessage = function (event) {
        var data = null;
        try { data = JSON.parse(String(event.data)); } catch (e) { return; }
        if (!data || typeof data !== 'object') return;
        if (data.type === 'permission') {
          state.permission = data.value === 'granted' || data.value === 'denied'
            ? data.value : 'default';
          var resolve = pending[data.id];
          if (resolve) {
            delete pending[data.id];
            try { resolve(state.permission); } catch (e) {}
          }
        }
      };
    } catch (e) {
      return false;
    }
    bridge = candidate;
    send({ type: 'hello' });
    return true;
  }

  function send(payload) {
    if (!connect()) return false;
    try {
      bridge.postMessage(JSON.stringify(payload));
      return true;
    } catch (e) {
      return false;
    }
  }

  function HbNotification(title, options) {
    if (!(this instanceof HbNotification)) {
      throw new TypeError("Failed to construct 'Notification': Please use the 'new' operator.");
    }
    if (state.permission === 'denied') {
      throw new TypeError('Failed to construct Notification: permission denied');
    }
    options = options || {};
    this.title = title == null ? '' : String(title);
    this.body = options.body == null ? '' : String(options.body);
    this.tag = options.tag == null ? '' : String(options.tag);
    this.icon = options.icon == null ? '' : String(options.icon);
    this.lang = '';
    this.data = options.data === undefined ? null : options.data;
    this.onclick = null;
    this.onclose = null;
    this.onerror = null;
    this.onshow = null;
    send({ type: 'show', title: this.title, body: this.body, tag: this.tag });
  }

  HbNotification.prototype.close = function () {
    send({ type: 'close', tag: this.tag || '' });
  };
  HbNotification.maxActions = 2;
  Object.defineProperty(HbNotification, 'permission', {
    get: function () { return state.permission; }
  });
  HbNotification.requestPermission = function (callback) {
    var promise = new Promise(function (resolve) {
      if (state.permission === 'granted' || state.permission === 'denied') {
        resolve(state.permission);
        return;
      }
      var id = String(nextId++);
      pending[id] = resolve;
      if (!send({ type: 'request', id: id })) {
        delete pending[id];
        resolve('default');
      }
    });
    // Legacy callback form: Notification.requestPermission(cb) returns undefined.
    if (typeof callback === 'function') {
      promise.then(function (value) {
        try { callback(value); } catch (e) {}
      });
      return undefined;
    }
    return promise;
  };
  Object.defineProperty(HbNotification, '__hbNative', { value: true });

  try {
    Object.defineProperty(window, 'Notification', {
      value: HbNotification,
      configurable: true,
      writable: true
    });
  } catch (e) {
    window.Notification = HbNotification;
  }

  // Service workers cannot push without Google Play Services, but a page that
  // calls registration.showNotification() should still surface a notification.
  try {
    var Registration = window.ServiceWorkerRegistration;
    if (typeof Registration === 'function') {
      var proto = Registration.prototype;
      if (!proto.showNotification) {
        proto.showNotification = function (title, options) {
          if (state.permission !== 'granted') {
            return Promise.reject(new Error('Notifications are not allowed'));
          }
          options = options || {};
          send({
            type: 'show',
            title: title == null ? '' : String(title),
            body: options.body == null ? '' : String(options.body),
            tag: options.tag == null ? '' : String(options.tag)
          });
          return Promise.resolve();
        };
      }
      if (!proto.getNotifications) {
        proto.getNotifications = function () { return Promise.resolve([]); };
      }
    }
  } catch (e) {}

  // The injected object may not exist yet on the very first script of a frame.
  if (!connect()) {
    var tries = 0;
    var timer = setInterval(function () {
      tries += 1;
      if (connect() || tries > 20) clearInterval(timer);
    }, 50);
  }
})();
""".trimIndent()
}

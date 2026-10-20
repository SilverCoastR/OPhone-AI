/* OPhone mini-app runtime, injected before the app's own scripts.
   OPhone.call(tool, args) -> Promise<result object>; check result.ok.
   Only tools declared in the app's permissions work, after the user approves them once. */
(function () {
  'use strict';
  var pending = {}, seq = 0;
  window.__ophoneResolve = function (id, raw) {
    var done = pending[id];
    if (!done) return;
    delete pending[id];
    var value;
    try { value = JSON.parse(raw); } catch (e) { value = { ok: false, error: 'invalid_result' }; }
    done(value);
  };
  function unwrap(raw) { try { return JSON.parse(raw).v; } catch (e) { return null; } }
  window.OPhone = {
    call: function (tool, args) {
      return new Promise(function (resolve) {
        var id = String(++seq);
        pending[id] = resolve;
        OPhoneNative.call(id, String(tool), JSON.stringify(args || {}));
      });
    },
    storage: {
      get: function (key) { return unwrap(OPhoneNative.storageGet(String(key))); },
      set: function (key, value) { OPhoneNative.storageSet(String(key), value === null || value === undefined ? null : String(value)); },
      getJSON: function (key, fallback) { var v = this.get(key); try { return v === null ? fallback : JSON.parse(v); } catch (e) { return fallback; } },
      setJSON: function (key, value) { this.set(key, JSON.stringify(value)); }
    },
    /** URL for an image or video saved in the agent workspace, e.g. a chart written by python_run. */
    workspaceUrl: function (path) { return '/workspace/' + String(path).replace(/^\/+/, '').split('/').map(encodeURIComponent).join('/'); },
    toast: function (text) { OPhoneNative.toast(String(text)); },
    close: function () { OPhoneNative.close(); },
    info: JSON.parse(OPhoneNative.info())
  };
})();

/*
 * The WebExtension API, as much of it as bork's add-ons use, for WebKit.
 *
 * iOS allows no engine but WebKit and WKWebView runs no WebExtensions, so
 * the add-ons' own files run unchanged against this instead: content scripts
 * in an isolated content world per add-on (what a content script is
 * anyway), background and extension pages as ordinary pages on the
 * bork-ext:// scheme. Everything that needs the app — storage, messages
 * between the pieces, the active tab, blocking navigations — goes across
 * the borkExt message handler and comes back as a promise.
 *
 * Prepended by ExtensionRuntime.swift with:
 *   const __BORK_EXT__ = { id, host, kind: "content" | "page" | "background" };
 *
 * Implemented: storage.local (get/set/remove/clear) + storage.onChanged,
 * runtime.{id,getURL,getManifest,sendMessage,onMessage,openOptionsPage},
 * tabs.{query,sendMessage,create}, webRequest.onBeforeRequest (main frame,
 * blocking), i18n.getMessage. contextMenus is accepted and inert, as on
 * GeckoView.
 */
(function () {
  "use strict";
  if (globalThis.__borkExt) return;
  const meta = globalThis.__BORK_EXT__;
  if (!meta) return;

  const handler = globalThis.webkit && webkit.messageHandlers && webkit.messageHandlers.borkExt;

  function call(op, args) {
    if (!handler) return Promise.reject(new Error("bork: no extension bridge here"));
    return handler.postMessage({ ext: meta.id, kind: meta.kind, op, args: args === undefined ? null : args })
      .then((reply) => (reply === undefined ? null : reply));
  }

  /** An event in the WebExtension shape: addListener / removeListener / hasListener. */
  function event() {
    const listeners = new Set();
    return {
      listeners,
      addListener(fn) { listeners.add(fn); },
      removeListener(fn) { listeners.delete(fn); },
      hasListener(fn) { return listeners.has(fn); },
      hasListeners() { return listeners.size > 0; },
    };
  }

  const clone = (v) => (v === undefined ? undefined : JSON.parse(JSON.stringify(v)));

  /* ---------------------------------------------------------------- storage */

  const storageChanged = event();
  const local = {
    get(keys) { return call("storage.get", keys === undefined ? null : keys); },
    set(items) { return call("storage.set", clone(items)).then(() => undefined); },
    remove(keys) { return call("storage.remove", keys).then(() => undefined); },
    clear() { return call("storage.clear").then(() => undefined); },
  };

  /* ---------------------------------------------------------------- runtime */

  const onMessage = event();

  /**
   * Run every onMessage listener the way Firefox does: the first response
   * wins, whether it comes through sendResponse (sync, or async after the
   * listener returns true) or as a returned promise.
   */
  function dispatchMessage(message, sender) {
    return new Promise((resolve) => {
      let settled = false;
      let waiting = 0;
      const done = (value) => {
        if (settled) return;
        settled = true;
        resolve(value === undefined ? null : clone(value));
      };
      for (const fn of [...onMessage.listeners]) {
        let keep = false;
        try {
          const result = fn(clone(message), sender || {}, done);
          if (result === true) keep = true;
          else if (result && typeof result.then === "function") {
            keep = true;
            result.then(done, () => done(null));
          }
        } catch (error) {
          console.error("[bork-ext] onMessage listener threw", error);
        }
        if (keep) waiting += 1;
      }
      if (!waiting) done(null);
    });
  }

  const base = `bork-ext://${meta.host}/`;
  const runtime = {
    id: meta.id,
    getURL(path) { return base + String(path || "").replace(/^\/+/, ""); },
    getManifest() { return meta.manifest || {}; },
    sendMessage(message) { return call("runtime.sendMessage", clone(message)); },
    openOptionsPage() { return call("runtime.openOptionsPage").then(() => undefined); },
    onMessage,
    onInstalled: event(),
    onStartup: event(),
    lastError: null,
  };

  /* ------------------------------------------------------------------- tabs */

  const tabs = {
    query(info) { return call("tabs.query", info || {}); },
    sendMessage(tabId, message) { return call("tabs.sendMessage", { tabId, message: clone(message) }); },
    create(props) { return call("tabs.create", props || {}); },
  };

  /* ------------------------------------------------------------- webRequest */

  const beforeRequest = event();
  const webRequest = {
    onBeforeRequest: {
      addListener(fn) { beforeRequest.addListener(fn); call("webRequest.listen").catch(() => {}); },
      removeListener(fn) { beforeRequest.removeListener(fn); },
      hasListener(fn) { return beforeRequest.hasListener(fn); },
    },
  };

  /** Asked by the app before a top-level navigation. The first listener with an opinion decides. */
  function runBeforeRequest(details) {
    for (const fn of beforeRequest.listeners) {
      try {
        const result = fn(details);
        if (result && (result.redirectUrl || result.cancel)) return result;
      } catch (error) {
        console.error("[bork-ext] onBeforeRequest listener threw", error);
      }
    }
    return {};
  }

  /* ------------------------------------------------------------ the rest */

  const inert = { addListener() {}, removeListener() {}, hasListener: () => false };
  const contextMenus = {
    create() { return ""; },
    update() { return Promise.resolve(); },
    remove() { return Promise.resolve(); },
    removeAll(cb) { if (typeof cb === "function") cb(); return Promise.resolve(); },
    onClicked: inert,
  };

  const api = {
    storage: { local, onChanged: storageChanged },
    runtime,
    tabs,
    webRequest,
    contextMenus,
    menus: contextMenus,
    i18n: { getMessage: () => "", getUILanguage: () => navigator.language },
    extension: { getURL: runtime.getURL },
  };

  Object.defineProperty(globalThis, "browser", { value: api, configurable: true, writable: true });
  Object.defineProperty(globalThis, "chrome", { value: api, configurable: true, writable: true });

  // The app's way back in. Not part of any API an add-on sees.
  Object.defineProperty(globalThis, "__borkExt", {
    value: {
      fireStorage(changes) {
        for (const fn of [...storageChanged.listeners]) {
          try { fn(clone(changes), "local"); } catch (error) { console.error(error); }
        }
      },
      dispatchMessage,
      beforeRequest: runBeforeRequest,
      hasBeforeRequest: () => beforeRequest.hasListeners(),
    },
  });

  // window.close() from a popup or an options page is the app's to honour.
  if (meta.kind !== "content") {
    const close = () => call("window.close").catch(() => {});
    try { Object.defineProperty(globalThis, "close", { value: close, configurable: true }); } catch (_) {}
  }
})();

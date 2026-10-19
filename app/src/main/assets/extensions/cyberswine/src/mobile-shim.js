"use strict";
/**
 * bork-mobile only. GeckoView has no context menus for extensions, and
 * background.js builds its "block this site" entries at startup — an
 * undefined api.contextMenus there throws and takes the rest of the
 * background page (the request blocking) down with it.
 *
 * On a phone the same action lives in the popup ("block the site I am on"),
 * so the menu is stood in for by something that accepts the calls and does
 * nothing. Loaded first, ahead of background.js; a GeckoView that grows real
 * menus keeps them, because nothing here replaces an API that exists.
 */
(function () {
  const api = globalThis.browser ?? globalThis.chrome;
  if (!api || api.contextMenus) return;
  const inert = {
    removeAll(callback) {
      if (typeof callback === "function") callback();
      return Promise.resolve();
    },
    create() {
      return "";
    },
    update() {
      return Promise.resolve();
    },
    remove() {
      return Promise.resolve();
    },
    onClicked: { addListener() {}, removeListener() {}, hasListener: () => false },
  };
  try {
    api.contextMenus = inert;
  } catch (_error) {
    // A frozen namespace: nothing to stand in for, and background.js will
    // say so in the console the same way it would have anyway.
  }
})();

/*
 * YouTube ads, removed before the player ever sees them. bork for iOS.
 *
 * On Android and the desktop uBlock Origin does this. On iOS no extension
 * can run in an app, and WebKit's rule lists cannot help: YouTube serves its
 * video ads from youtube.com itself, in the same data as the video. So this
 * does what uBlock's YouTube filters do, from scratch: it sits in the page's
 * own world, ahead of YouTube's scripts, and takes the ad sections out of
 * that data on the way in.
 *
 * Where the data arrives, and how each route is covered:
 *   - inline on first load: `var ytInitialPlayerResponse = {...}` and
 *     ytInitialData — trapped with setters on window before the page runs;
 *   - from YouTube's API (/youtubei/v1/player, /next, /browse, /search) —
 *     JSON.parse and Response.prototype.json are wrapped, so whichever the
 *     page uses, the parsed object is pruned before YouTube gets it.
 * And if an ad still starts (a route YouTube adds later), the player is
 * fast-forwarded through it and its Skip button pressed.
 *
 * Injected by YouTubeAds.swift into youtube.com, m.youtube.com,
 * youtube-nocookie.com and their frames, at document start, page world.
 */
(function () {
  "use strict";
  if (!/(^|\.)youtube(-nocookie)?\.com$/.test(location.hostname)) return;
  if (window.__borkYouTubeAds) return;
  Object.defineProperty(window, "__borkYouTubeAds", { value: true });

  /** Keys on a player response that carry its ads. */
  const AD_KEYS = ["adPlacements", "playerAds", "adSlots", "adBreakHeartbeatParams"];
  /** Feed, search and watch-next entries that are ads, by renderer name. */
  const AD_ITEMS = new Set([
    "adSlotRenderer",
    "promotedSparklesWebRenderer",
    "promotedSparklesTextSearchRenderer",
    "promotedVideoRenderer",
    "searchPyvRenderer",
    "compactPromotedVideoRenderer",
    "bannerPromoRenderer",
    "statementBannerRenderer",
    "brandVideoSingletonRenderer",
    "brandVideoShelfRenderer",
    "movieOfferModuleRenderer",
  ]);
  /** Strings that mean a JSON payload is worth walking at all. */
  const MARKERS = /adPlacements|playerAds|adSlots|adSlotRenderer|promotedSparkles|promotedVideo|searchPyv|bannerPromo|statementBanner|enforcementMessage/;

  let stripped = 0;
  /** What was taken out, for debug builds' report. */
  const removed = [];
  const note = (what) => { stripped++; if (removed.length < 40) removed.push(what); };

  function isAdItem(item) {
    if (!item || typeof item !== "object" || Array.isArray(item)) return false;
    for (const key of Object.keys(item)) {
      if (AD_ITEMS.has(key)) return true;
      // Grid cells wrap their content: richItemRenderer.content.adSlotRenderer
      const inner = item[key] && item[key].content;
      if (inner && typeof inner === "object") {
        for (const k of Object.keys(inner)) if (AD_ITEMS.has(k)) return true;
      }
    }
    return false;
  }

  /** Remove every ad section in a parsed YouTube payload, in place. */
  function prune(node, depth) {
    if (!node || typeof node !== "object" || depth > 40) return;
    if (Array.isArray(node)) {
      for (let i = node.length - 1; i >= 0; i--) {
        if (isAdItem(node[i])) {
          note(Object.keys(node[i])[0]);
          node.splice(i, 1);
        } else {
          prune(node[i], depth + 1);
        }
      }
      return;
    }
    for (const key of AD_KEYS) {
      if (Object.prototype.hasOwnProperty.call(node, key)) {
        delete node[key];
        note(key);
      }
    }
    // The "ad blockers are not allowed" dialog rides in on the same data.
    const renderers = node.auxiliaryUi && node.auxiliaryUi.messageRenderers;
    if (renderers && renderers.enforcementMessageViewModel) {
      delete renderers.enforcementMessageViewModel;
      note("enforcementMessageViewModel");
    }
    for (const key of Object.keys(node)) {
      const value = node[key];
      if (value && typeof value === "object") prune(value, depth + 1);
    }
  }

  function pruneIfAds(value, text) {
    if (text !== undefined && !MARKERS.test(text)) return value;
    try {
      prune(value, 0);
    } catch (_) {
      // A payload we do not understand is left as it came.
    }
    report();
    return value;
  }

  /* ---- route 1: the data inline in the first page load ---------------- */

  for (const name of ["ytInitialPlayerResponse", "ytInitialData"]) {
    let held;
    try {
      Object.defineProperty(window, name, {
        configurable: true,
        get() { return held; },
        set(value) {
          held = value && typeof value === "object" ? pruneIfAds(value) : value;
        },
      });
    } catch (_) {
      // Defined already (a frame reloaded in place): the other routes remain.
    }
  }

  /* ---- route 2: everything the page parses afterwards ----------------- */

  const parse = JSON.parse;
  JSON.parse = function (text, reviver) {
    const value = parse.call(this, text, reviver);
    if (typeof text === "string" && value && typeof value === "object") {
      return pruneIfAds(value, text);
    }
    return value;
  };
  // Keep it looking native to anything that checks.
  Object.defineProperty(JSON.parse, "toString", { value: () => "function parse() { [native code] }" });

  const json = Response.prototype.json;
  Response.prototype.json = function () {
    const url = this.url || "";
    if (!/youtube(-nocookie)?\.com\/youtubei\//.test(url)) return json.call(this);
    return this.text().then((text) => JSON.parse(text));
  };

  /* ---- belt and braces: an ad that starts anyway ---------------------- */

  const SKIP = ".ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, button[class*='skip-ad'], .ytp-ad-overlay-close-button";

  function skipThrough() {
    const player = document.querySelector(".html5-video-player.ad-showing, .html5-video-player.ad-interrupting");
    if (!player) return;
    const video = player.querySelector("video");
    if (video && isFinite(video.duration) && video.duration > 0) {
      video.muted = true;
      video.currentTime = video.duration;
    }
    for (const button of document.querySelectorAll(SKIP)) button.click();
    note("ad-showing (skipped)");
    report();
  }
  new MutationObserver(skipThrough).observe(document, { subtree: true, attributes: true, attributeFilter: ["class"] });

  /* ---- what hides rather than strips ---------------------------------- */

  const css = `
    ytm-promoted-sparkles-web-renderer, ytm-promoted-video-renderer,
    ytm-companion-ad-renderer, ytm-companion-slot, ad-slot-renderer,
    ytm-ad-slot-renderer, ytm-statement-banner-renderer, ytm-banner-promo-renderer,
    ytm-rich-item-renderer:has(> ad-slot-renderer),
    ytm-rich-item-renderer:has(ytm-ad-slot-renderer),
    ytd-ad-slot-renderer, ytd-in-feed-ad-layout-renderer, ytd-promoted-sparkles-web-renderer,
    ytd-promoted-video-renderer, ytd-banner-promo-renderer, ytd-statement-banner-renderer,
    ytd-companion-slot-renderer, ytd-player-legacy-desktop-watch-ads-renderer,
    ytd-rich-item-renderer:has(ytd-ad-slot-renderer), #masthead-ad,
    .ytp-ad-module, .ytp-ad-overlay-container, .ytp-ad-image-overlay,
    tp-yt-paper-dialog:has(ytd-enforcement-message-view-model),
    ytd-enforcement-message-view-model
    { display: none !important; }`;
  function addStyle() {
    // Constructed, so no Content-Security-Policy can refuse it; <style> only
    // where that is unavailable.
    try {
      const sheet = new CSSStyleSheet();
      sheet.replaceSync(css);
      document.adoptedStyleSheets = [...document.adoptedStyleSheets, sheet];
      return;
    } catch (_) {}
    const style = document.createElement("style");
    style.textContent = css;
    (document.head || document.documentElement).appendChild(style);
  }
  if (document.documentElement) addStyle();
  else document.addEventListener("DOMContentLoaded", addStyle, { once: true });

  /* ---- debug builds only: a count the app can log --------------------- */

  let reported = 0;
  function report() {
    if (stripped === reported) return;
    reported = stripped;
    const debug = window.webkit && webkit.messageHandlers && webkit.messageHandlers.borkDebug;
    if (debug) debug.postMessage({ youtubeAdsStripped: stripped, removed: removed.join(", "), at: location.pathname });
  }
})();

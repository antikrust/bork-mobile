"use strict";
/**
 * YouTube pages on a phone (top frame): channel blocking, subscription tabs,
 * the Shorts switch and a way into live chat.
 *
 * The mobile counterpart of the desktop pages.js. It keeps the same config,
 * the same store and the same matching (filters.js), so a blocklist moved
 * between bork on the desktop and bork-mobile means the same thing on both.
 * What it does not keep is everything built on a mouse:
 *
 *   - The floating SHUT UP / HIDE / TAG bar that follows the pointer. There is
 *     no pointer. Each card gets its own small button row instead, under the
 *     title, and the watch page gets one beside Subscribe.
 *   - HIDE and DONE, which drive YouTube's own ⋮ menu. On m.youtube.com that
 *     menu is a bottom sheet with different items in a different order, and
 *     pressing it on someone's behalf there is a guess, not a feature.
 *   - Save to Watch later through that same menu. The Watch tab still holds
 *     single videos; it just does not press YouTube's button for you.
 *   - The end-of-video suggestion wall. The mobile player does not build one.
 *   - The guide drawer scrape for "which channels do I subscribe to". The
 *     mobile site has no guide drawer; Subscribe buttons and the channel strip
 *     on the subscriptions feed say it instead.
 *
 * Live chat is not shown on m.youtube.com at all. YouTube's own popout chat
 * page (www.youtube.com/live_chat) still works on a phone, and content.js
 * runs there unchanged, so live watch pages get a CHAT button that opens it.
 *
 * Cards are hidden with a class, never removed: YouTube owns these nodes.
 * Desktop card names are listed too, so "Request desktop site" still gets
 * channel blocking — just not the desktop's hover bar.
 */
globalThis.YTCT = globalThis.YTCT || {};
(function (YTCT) {
  if (window.top !== window) return;
  if (location.pathname.startsWith("/live_chat")) return;

  const { store, filters } = YTCT;
  const api = globalThis.browser ?? globalThis.chrome;

  const CARD_TAGS = [
    // m.youtube.com
    "ytm-video-with-context-renderer", // home, search, related under a video
    "ytm-compact-video-renderer", // older related list
    "ytm-rich-item-renderer", // grid layouts (tablet width, channel videos)
    "ytm-video-card-renderer",
    "ytm-reel-item-renderer", // shorts shelf (older)
    "ytm-shorts-lockup-view-model", // shorts shelf (newer)
    "ytm-shorts-lockup-view-model-v2",
    "ytm-compact-channel-renderer", // the channel itself, in search
    // www.youtube.com, for "Request desktop site"
    "ytd-rich-item-renderer",
    "ytd-video-renderer",
    "ytd-channel-renderer",
    "ytd-compact-video-renderer",
    "ytd-grid-video-renderer",
    "ytd-reel-item-renderer",
    "yt-lockup-view-model",
  ];
  const CARDS = CARD_TAGS.join(",");

  /** Rows gathering several videos under one heading. */
  const SHELVES = [
    "grid-shelf-view-model", // shorts shelf in search and home
    "ytm-reel-shelf-renderer",
    "ytm-shorts-shelf-renderer",
    "ytm-rich-section-renderer",
    "ytm-shelf-renderer",
    "ytd-shelf-renderer",
    "ytd-reel-shelf-renderer",
    "ytd-rich-section-renderer",
  ].join(",");

  const SHORTS_LINK = "a[href^='/shorts/']";
  const SUBSCRIBE =
    "ytm-subscribe-button-renderer, ytd-subscribe-button-renderer, yt-subscribe-button-view-model";

  /** The one tab that holds single videos rather than whole channels. */
  const WATCH_TAB = "Watch";
  /** All: everything, filtered by nothing. Not a tab, and not stored as one. */
  const ALL_VIEW = "*";
  /** Where the subscribed-channel names seen so far are kept. */
  const SUBS_KEY = "mobileSubscribed";

  let config = null;
  let blockedChannels = new Set();
  let blockedHandles = new Set();
  let generation = 0;
  let subscribed = new Set();
  let onSubsFeed = false;

  /* ---------------------------------------------------------------- helpers */

  /** Set a class only when it differs — a no-op write still reaches YouTube as a mutation. */
  function setClass(el, name, on) {
    if (el.classList.contains(name) !== !!on) el.classList.toggle(name, !!on);
  }

  function squashHandle(value) {
    return String(value || "")
      .replace(/^@/, "")
      .toLowerCase()
      .replace(/[^a-z0-9]/g, "");
  }

  function text(el) {
    return el ? (el.textContent || "").replace(/\s+/g, " ").trim() : "";
  }

  function rebuild() {
    blockedChannels = filters.blockSet(config.channels);
    blockedHandles = new Set();
    for (const entry of config.channels || []) {
      for (const h of (entry && entry.handles) || []) {
        const squashed = squashHandle(h);
        if (squashed) blockedHandles.add(squashed);
      }
    }
    generation += 1;
    setClass(
      document.documentElement,
      "ytct-m-buttons",
      config.enabled !== false && config.showButton !== false,
    );
    applyTabClass();
  }

  /* ------------------------------------------------------------- subscribed */

  /**
   * Which channels the user follows. The mobile site never lists them in one
   * place that is always there, so this collects what each page admits —
   * a Subscribe button that says Subscribed, the avatar strip along the top
   * of the subscriptions feed — and keeps it, because the next page will not
   * say it again.
   */
  let subsDirty = false;

  function noteSubscribed(name, isSubscribed) {
    const key = filters.normalizeName(name);
    if (!key) return;
    if (isSubscribed && !subscribed.has(key)) {
      subscribed.add(key);
      subsDirty = true;
    } else if (!isSubscribed && subscribed.has(key)) {
      subscribed.delete(key);
      subsDirty = true;
    }
  }

  function flushSubscribed() {
    if (!subsDirty) return;
    subsDirty = false;
    api.storage.local.set({ [SUBS_KEY]: [...subscribed] }).catch(() => {});
  }

  function scrapeChannelStrip() {
    if (!onSubsFeed) return;
    for (const el of document.querySelectorAll(
      "ytm-channel-list-sub-menu-avatar-renderer, ytm-channel-list-sub-menu-renderer a[href^='/@'], ytm-channel-list-sub-menu-renderer a[href^='/channel/']",
    )) {
      const name =
        text(el.querySelector(".channel-list-sub-menu-avatar-title, .subhead, span")) ||
        (el.getAttribute("aria-label") || "").trim();
      if (name && filters.looksLikeChannel(name)) noteSubscribed(name, true);
    }
  }

  /** Does this Subscribe control say you already subscribe? */
  function saysSubscribed(anchor) {
    if (anchor.classList.contains("is-subscribed")) return true;
    if (anchor.classList.contains("is-not-subscribed")) return false;
    if (anchor.hasAttribute("subscribed")) return true;
    const button = anchor.querySelector("button");
    const label = (button && button.getAttribute("aria-label")) || "";
    if (/^unsubscribe/i.test(label)) return true;
    return /^subscribed$/i.test(text(anchor));
  }

  /* ------------------------------------------------------------- extraction */

  /**
   * The channel named on a shorts card. The card carries it in one place
   * only — the title's aria-label, "Title, 399 views, Sky News, 49 minutes
   * ago – play Short" — so it is read from the end, where the shape is fixed,
   * rather than split from the front, where the title can hold anything.
   */
  function shortsChannel(card) {
    const head = card.querySelector("h3[aria-label], [aria-label*='play Short']");
    const label = (head && head.getAttribute("aria-label")) || "";
    const m = /,\s*([^,]+?),\s*[^,]+?\s+[–-]\s+play Short\s*$/i.exec(label);
    const name = m ? m[1].trim() : "";
    return filters.looksLikeChannel(name) ? name : "";
  }

  function channelOf(card) {
    const tag = card.tagName.toLowerCase();

    // The channel card in search names itself in its headline.
    if (tag === "ytm-compact-channel-renderer") {
      const head = text(card.querySelector(".YtmCompactMediaItemHeadline, h4"));
      if (head) return head;
    }
    if (tag.includes("shorts-lockup") || tag === "ytm-reel-item-renderer") {
      return shortsChannel(card);
    }

    // Mobile byline: "Sky News • 10m views • 1 month ago", each a span. The
    // channel is usually first, but on a channel's own pages it is implied
    // and the first span is the view count — take the first that could be a
    // name at all.
    for (const span of card.querySelectorAll(
      ".ytmBadgeAndBylineRendererItemByline, .ytm-badge-and-byline-item-byline, ytm-badge-and-byline-renderer > span",
    )) {
      const value = text(span);
      if (value && value !== "•" && filters.looksLikeChannel(value)) return value;
    }

    // Desktop markup, for the desktop site.
    const named = card.querySelector("ytd-channel-name #text");
    if (named && text(named)) return text(named);
    for (const meta of card.querySelectorAll(
      "yt-content-metadata-view-model span, [class*='content-metadata'] span",
    )) {
      const value = text(meta);
      if (filters.looksLikeChannel(value)) return value;
    }

    // The avatar link: "Go to channel Sky News".
    const avatar = card.querySelector("a[aria-label][href^='/@'], a[aria-label][href^='/channel/']");
    if (avatar) {
      const m = /^go to channel\s+(.+)$/i.exec(avatar.getAttribute("aria-label") || "");
      if (m) return m[1].trim();
    }

    let handle = "";
    for (const a of card.querySelectorAll("a[href^='/@'], a[href^='/channel/']")) {
      const value = text(a);
      if (value && filters.looksLikeChannel(value)) return value;
      const m = /^\/(@[^/?]+)/.exec(a.getAttribute("href") || "");
      if (m && !handle) handle = m[1];
    }
    return handle;
  }

  /** Every /@handle the card links to — see the desktop pages.js on collaborations. */
  function handlesOf(card) {
    const out = [];
    for (const a of card.querySelectorAll("a[href^='/@']")) {
      const m = /^\/@([^/?]+)/.exec(a.getAttribute("href") || "");
      const squashed = m ? squashHandle(m[1]) : "";
      if (squashed && !out.includes(squashed)) out.push(squashed);
    }
    return out;
  }

  /** A blocked name on either side of an " and " in a collaboration byline. */
  function bylineNames(norm) {
    if (blockedChannels.has(norm)) return true;
    if (!norm.includes(" and ")) return false;
    for (const blocked of blockedChannels) {
      if (!blocked) continue;
      if (
        norm.startsWith(`${blocked} and `) ||
        norm.endsWith(` and ${blocked}`) ||
        norm.includes(` and ${blocked} and `)
      ) {
        return true;
      }
    }
    return false;
  }

  /** On a channel's own pages the page names the channel, not the cards. */
  function pageChannel() {
    if (!/^\/(@|channel\/|c\/|user\/)/.test(location.pathname)) return "";
    const header = document.querySelector(
      "yt-page-header-view-model h1, ytm-c4-tabbed-header-renderer h1, .c4-tabbed-header-title, yt-dynamic-text-view-model h1, yt-page-header-renderer h1, #channel-header #text",
    );
    if (header && text(header)) return text(header);
    return document.title.replace(/ - YouTube$/, "").trim();
  }

  function keyOf(card) {
    const a = card.querySelector("a[href*='/watch'], a[href^='/shorts/']");
    const href = (a && a.getAttribute("href")) || "";
    // m.youtube.com adds &pp=… search tracking to watch links; the video is
    // the same video without it, and Watch-tab notes have to find it again.
    const m = /[?&]v=([\w-]{6,})/.exec(href);
    if (m) return `/watch?v=${m[1]}`;
    return href;
  }

  /* ------------------------------------------------------------------ tabs */

  function tabsOfChannel(norm) {
    const tags = (config && config.subTags) || {};
    return (norm && tags[norm]) || [];
  }

  function tabsOfVideo(key) {
    const tags = (config && config.subVideoTags) || {};
    return (key && tags[key]) || [];
  }

  function inTab(norm, key, tab) {
    return tabsOfChannel(norm).includes(tab) || tabsOfVideo(key).includes(tab);
  }

  function inAnyTab(norm, key) {
    return tabsOfChannel(norm).length > 0 || tabsOfVideo(key).length > 0;
  }

  function filtering() {
    return (
      !!config &&
      config.enabled !== false &&
      onSubsFeed &&
      (config.subTab || "") !== ALL_VIEW
    );
  }

  function applyTabClass() {
    setClass(document.documentElement, "ytct-tabbed", filtering());
  }

  /* ----------------------------------------------------------------- cards */

  function isShortsCard(card) {
    const tag = card.tagName.toLowerCase();
    return tag.includes("shorts") || tag.includes("reel") || !!card.querySelector(SHORTS_LINK);
  }

  /** Shorts survive only where something says whose they are and you follow them. */
  function subscribedShorts(el, norm) {
    if (onSubsFeed) return true;
    if (norm && subscribed.has(norm)) return true;
    const page = filters.normalizeName(pageChannel());
    if (page) {
      const sub = document.querySelector(SUBSCRIBE);
      if (sub && saysSubscribed(sub)) return true;
      if (subscribed.has(page)) return true;
    }
    return false;
  }

  function handle(card) {
    const key = keyOf(card);
    if (card.dataset.ytctGen !== String(generation) || card.dataset.ytctKey !== key) {
      card.dataset.ytctGen = String(generation);
      card.dataset.ytctKey = key;
      const found = channelOf(card) || pageChannel();
      if (found) card.dataset.ytctChannel = found;
      card.dataset.ytctHandles = handlesOf(card).join(" ");
      card.dataset.ytctShorts = isShortsCard(card) ? "1" : "";
    }

    const channel = card.dataset.ytctChannel || "";
    const norm = channel ? filters.normalizeName(channel) : "";
    const named = !!norm && bylineNames(norm);
    const handles = (card.dataset.ytctHandles || "").split(" ").filter(Boolean);
    const byHandle = handles.some((h) => blockedHandles.has(h));

    const isShorts = card.dataset.ytctShorts === "1";
    // Desktop parity: shorts nobody can tie to a channel you follow are
    // binned outside the subscriptions feed and channel pages.
    const stray = isShorts && !subscribedShorts(card, norm);
    const shortsOff = onSubsFeed && config.subsShorts === false && isShorts;

    const view = onSubsFeed ? config.subTab || "" : ALL_VIEW;
    let belongs = true;
    if (onSubsFeed) {
      if (view === ALL_VIEW) belongs = true;
      else if (view === "") belongs = !inAnyTab(norm, key);
      else belongs = inTab(norm, key, view);
    }
    const mark = filtering() && belongs ? view || "unsorted" : null;
    if (mark === null) {
      if ("ytctTab" in card.dataset) delete card.dataset.ytctTab;
    } else if (card.dataset.ytctTab !== mark) {
      card.dataset.ytctTab = mark;
    }

    const hidden =
      config.enabled !== false && (named || byHandle || stray || shortsOff);
    setClass(card, "ytct-video-hidden", hidden);

    ensureCardButtons(card, channel, norm, key, isShorts, hidden);
  }

  /* --------------------------------------------------------- card buttons */

  /**
   * Where a card's button row goes: under the title, outside every link, so
   * a tap on it can never also open the video.
   *
   * ytm-media-item holds the thumbnail link and the details block one above
   * the other; a row appended to it sits under the byline. Anything else gets
   * the row at its own end.
   */
  function rowSpot(card) {
    return (
      card.querySelector("ytm-media-item") ||
      card.querySelector(".YtmCompactMediaItemHost") ||
      card.querySelector("#dismissible") ||
      card
    );
  }

  function ensureCardButtons(card, channel, norm, key, isShorts, hidden) {
    const wantBlock =
      !!channel &&
      !onSubsFeed &&
      !subscribed.has(norm) &&
      filters.looksLikeChannel(channel);
    const wantTag = onSubsFeed && !isShorts && (config.subTabs || []).length > 0;
    // A card with its own Subscribe button (the channel itself, in search)
    // gets its SHUT UP beside that button instead — see seat().
    const want =
      config.enabled !== false &&
      config.showButton !== false &&
      !hidden &&
      !isShorts &&
      !card.querySelector(SUBSCRIBE) &&
      (wantBlock || wantTag);

    const spot = rowSpot(card);
    let row = spot.querySelector(":scope > .ytct-m-row");
    if (!want) {
      if (row) row.remove();
      return;
    }
    if (!row) {
      row = document.createElement("div");
      row.className = "ytct-m-row";
      // YouTube listens for taps on the card itself; none of ours may reach it.
      for (const type of ["click", "touchstart", "touchend", "pointerdown", "pointerup", "mousedown", "mouseup"]) {
        row.addEventListener(type, (event) => event.stopPropagation(), { passive: type.startsWith("touch") });
      }
      spot.appendChild(row);
    }

    const signature = JSON.stringify([wantBlock, wantTag, channel, key, tabsOfChannel(norm), tabsOfVideo(key)]);
    if (row.dataset.ytctSig === signature) return;
    row.dataset.ytctSig = signature;
    row.textContent = "";

    if (wantBlock) {
      const block = document.createElement("button");
      block.type = "button";
      block.className = "ytct-m-btn ytct-m-shut";
      block.textContent = "SHUT UP";
      block.title = `Block ${channel} everywhere`;
      block.addEventListener("click", (event) => {
        event.preventDefault();
        blockChannel(channel, handlesOf(card));
      });
      row.appendChild(block);
    }
    if (wantTag) {
      const tag = document.createElement("button");
      tag.type = "button";
      tag.className = "ytct-m-btn";
      const where = [...new Set([...tabsOfChannel(norm), ...tabsOfVideo(key)])];
      tag.textContent = where.length ? `TAG · ${where.join(", ")}` : "TAG";
      tag.title = "Put this in one of the feed's tabs";
      tag.addEventListener("click", (event) => {
        event.preventDefault();
        openTagSheet(channel, key);
      });
      row.appendChild(tag);
    }
  }

  /* ---------------------------------------------------------------- shelves */

  function shelfTitle(shelf) {
    for (const el of shelf.querySelectorAll("h2, #title, #title-text, .shelf-header-layout-title, yt-section-header-view-model span")) {
      if (el.closest(CARDS)) continue;
      const value = text(el);
      if (value) return value;
    }
    return "";
  }

  function handleShelf(shelf) {
    const title = filters.normalizeName(shelfTitle(shelf));
    let blocked = false;
    if (title && config.enabled !== false) {
      for (const name of blockedChannels) {
        if (name && title.endsWith(name)) {
          blocked = true;
          break;
        }
      }
    }
    const shorts = !!shelf.querySelector(SHORTS_LINK) && !shelf.querySelector("a[href*='/watch']");
    // A shelf whose every card has been binned is an empty heading. Hidden
    // with them, rather than left as a title over nothing.
    let emptied = false;
    if (shorts) {
      const cards = [...shelf.querySelectorAll(CARDS)];
      emptied = cards.length > 0 && cards.every((c) => c.classList.contains("ytct-video-hidden"));
    }
    const tabbed = onSubsFeed && filtering() && shorts;
    const shortsOff = onSubsFeed && config.subsShorts === false && shorts;
    setClass(
      shelf,
      "ytct-video-hidden",
      config.enabled !== false && (blocked || emptied || tabbed || shortsOff),
    );
  }

  /* ------------------------------------------------- the subscriptions feed */

  /** The subscriptions page on screen. Hidden earlier pages stay in the DOM. */
  function feedRoot() {
    const pages = [...document.querySelectorAll("ytm-browse, ytd-browse[page-subtype='subscriptions']")];
    return pages.find((p) => !p.hidden && p.offsetParent !== null) || pages[0] || null;
  }

  /**
   * Where the tabs and the Shorts switch go: the top of the feed's list, after
   * the channel strip if there is one. Inserted into YouTube's list rather
   * than floated over it, so they scroll away with the feed like the strip
   * does.
   */
  function feedTop() {
    const root = feedRoot();
    if (!root) return null;
    const strip = root.querySelector("ytm-channel-list-sub-menu-renderer");
    if (strip && strip.parentElement) {
      return { parent: strip.parentElement, before: strip.nextSibling };
    }
    const list = root.querySelector(
      "ytm-section-list-renderer, ytm-rich-grid-renderer, ytd-rich-grid-renderer",
    );
    if (list) return { parent: list, before: list.firstChild };
    return { parent: root, before: root.firstChild };
  }

  function ensureFeedBar() {
    const wanted = onSubsFeed && config.enabled !== false;
    if (!wanted) {
      for (const stale of document.querySelectorAll(".ytct-m-feedbar")) stale.remove();
      return;
    }
    const spot = feedTop();
    if (!spot) return;
    let bar = spot.parent.querySelector(":scope > .ytct-m-feedbar");
    if (!bar) {
      bar = document.createElement("div");
      bar.className = "ytct-m-feedbar";
      spot.parent.insertBefore(bar, spot.before || null);
    }
    for (const stale of document.querySelectorAll(".ytct-m-feedbar")) {
      if (stale !== bar) stale.remove();
    }

    const names = ["", ALL_VIEW, ...(config.subTabs || [])];
    const current = config.subTab || "";
    const shortsOn = config.subsShorts !== false;
    const signature = JSON.stringify([names, current, shortsOn]);
    if (bar.dataset.ytctSig === signature) return;
    bar.dataset.ytctSig = signature;
    bar.textContent = "";

    const tabs = document.createElement("div");
    tabs.className = "ytct-m-tabs";
    for (const name of names) {
      const tab = document.createElement("button");
      tab.type = "button";
      tab.className = "ytct-m-tab";
      tab.textContent = name === "" ? "Unsorted" : name === ALL_VIEW ? "All" : name;
      setClass(tab, "ytct-on", name === current);
      tab.addEventListener("click", (event) => {
        event.preventDefault();
        event.stopPropagation();
        const next = (config.subTab || "") === name ? "" : name;
        config.subTab = next;
        applyTabClass();
        bar.dataset.ytctSig = "";
        scan();
        store.update((c) => {
          c.subTab = next;
          return c;
        });
      });
      tabs.appendChild(tab);
    }

    const shorts = document.createElement("button");
    shorts.type = "button";
    shorts.className = "ytct-m-tab ytct-m-shorts";
    shorts.textContent = shortsOn ? "Shorts ON" : "Shorts OFF";
    shorts.title = shortsOn ? "Hide Shorts on this feed" : "Show Shorts on this feed";
    setClass(shorts, "ytct-on", shortsOn);
    shorts.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
      store.update((c) => {
        c.subsShorts = c.subsShorts === false;
        return c;
      });
    });

    bar.append(tabs, shorts);
  }

  /* -------------------------------------------------------- the empty tab */

  const EMPTY_GRACE = 1200;
  let emptyView = null;
  let emptySince = 0;

  function ensureEmptyNotice() {
    const view = filtering() ? config.subTab || "" : null;
    const matched =
      view !== null && !!document.querySelector("[data-ytct-tab]:not(.ytct-video-hidden)");
    if (view !== emptyView) {
      emptyView = view;
      emptySince = 0;
    }
    if (view === null || matched) emptySince = 0;
    else if (!emptySince) emptySince = Date.now();

    const bar = document.querySelector(".ytct-m-feedbar");
    let note = bar && bar.querySelector(".ytct-m-empty");
    if (!emptySince || Date.now() - emptySince < EMPTY_GRACE || !bar) {
      if (note) note.remove();
      return;
    }
    if (!note) {
      note = document.createElement("p");
      note.className = "ytct-m-empty";
      bar.appendChild(note);
    }
    const want = view === "" ? "Nothing left to sort" : `Nothing in ${view} yet`;
    if (note.textContent !== want) note.textContent = want;
  }

  /* -------------------------------------------------------------- tag sheet */

  let sheet = null;

  function closeSheet() {
    if (!sheet) return;
    sheet.remove();
    sheet = null;
  }

  /**
   * A bottom sheet of tabs, because a phone has no room for a menu beside the
   * card and a thumb reaches the bottom of the screen anyway.
   */
  function openTagSheet(name, key) {
    const norm = name ? filters.normalizeName(name) : "";
    if (!norm && !key) return;
    closeSheet();

    sheet = document.createElement("div");
    sheet.id = "ytct-m-sheet";
    sheet.addEventListener("click", (event) => {
      if (event.target === sheet) closeSheet();
    });
    const panel = document.createElement("div");
    panel.className = "ytct-m-sheet-panel";
    const head = document.createElement("p");
    head.className = "ytct-m-sheet-head";
    head.textContent = name || "this video";
    panel.appendChild(head);

    for (const tab of config.subTabs || []) {
      const perVideo = tab === WATCH_TAB;
      const on = perVideo ? tabsOfVideo(key).includes(tab) : tabsOfChannel(norm).includes(tab);
      const row = document.createElement("button");
      row.type = "button";
      row.className = "ytct-m-sheet-row";
      row.textContent = perVideo ? `${tab} — just this video` : tab;
      setClass(row, "ytct-on", on);
      row.addEventListener("click", async (event) => {
        event.preventDefault();
        event.stopPropagation();
        closeSheet();
        await store.update((c) => {
          const where = perVideo
            ? (c.subVideoTags = c.subVideoTags || {})
            : (c.subTags = c.subTags || {});
          const id = perVideo ? key : norm;
          if (!id) return c;
          const list = new Set(where[id] || []);
          if (list.has(tab)) list.delete(tab);
          else list.add(tab);
          if (list.size) where[id] = [...list];
          else delete where[id];
          return c;
        });
        toast(on ? `Out of ${tab}` : `In ${tab}`);
      });
      panel.appendChild(row);
    }

    const cancel = document.createElement("button");
    cancel.type = "button";
    cancel.className = "ytct-m-sheet-row ytct-m-sheet-cancel";
    cancel.textContent = "Cancel";
    cancel.addEventListener("click", closeSheet);
    panel.appendChild(cancel);

    sheet.appendChild(panel);
    document.body.appendChild(sheet);
  }

  /* ----------------------------------------------------------- watch page */

  /** The channel a Subscribe button belongs to. */
  function channelForAnchor(anchor) {
    const button = anchor.querySelector("button[aria-label]");
    const label = (button && button.getAttribute("aria-label")) || "";
    const m = /^(?:subscribe to|unsubscribe from)\s+(.+?)\.?$/i.exec(label.trim());
    if (m) return m[1].trim();

    const bar = anchor.closest(
      "ytm-slim-video-action-bar-renderer, ytm-slim-owner-renderer, ytd-video-owner-renderer, #owner",
    );
    if (bar) {
      const owner = bar.querySelector("a.slim-video-owner-icon[aria-label], a[aria-label][href^='/@']");
      if (owner) {
        // "Sky News, 9.38m subscribers. Go to channel."
        const n = /^(.*?),\s*[\d.,]+\s*\S*\s+subscribers?\b/i.exec(owner.getAttribute("aria-label") || "");
        if (n) return n[1].trim();
      }
      const named = bar.querySelector(".slim-owner-channel-name, ytd-channel-name #text, #channel-name #text");
      if (named && text(named)) return text(named);
    }
    return pageChannel();
  }

  /** The video id of the watch page on screen. */
  function videoId() {
    if (location.pathname !== "/watch") return "";
    return new URLSearchParams(location.search).get("v") || "";
  }

  /**
   * Is the video on the watch page live right now? English first, because
   * that is what the page says for most people; the player's own answer —
   * an unbounded duration — covers the other languages.
   */
  function isLive() {
    const info = document.querySelector(
      "ytm-slim-video-information-renderer, ytm-slim-video-metadata-section-renderer",
    );
    if (info && /\bwatching( now)?\b/i.test(text(info))) return true;
    const video = document.querySelector("video");
    return !!video && video.duration === Infinity;
  }

  function ensureWatchButtons() {
    for (const anchor of document.querySelectorAll(SUBSCRIBE)) {
      if (anchor.querySelector(SUBSCRIBE) || !anchor.parentElement) continue;
      const name = channelForAnchor(anchor);
      if (!name) continue;
      noteSubscribed(name, saysSubscribed(anchor));
      seat(anchor, name);
    }
    for (const row of document.querySelectorAll(".ytct-m-sub")) {
      const previous = row.previousElementSibling;
      if (!previous || !previous.matches(SUBSCRIBE)) row.remove();
    }
  }

  function seat(anchor, name) {
    let row = anchor.nextElementSibling;
    if (!row || !row.classList.contains("ytct-m-sub")) {
      row = document.createElement("span");
      row.className = "ytct-m-sub";
      for (const type of ["click", "touchstart", "touchend", "pointerdown", "pointerup"]) {
        row.addEventListener(type, (event) => event.stopPropagation(), { passive: type.startsWith("touch") });
      }
      anchor.parentElement.insertBefore(row, anchor.nextSibling);
    }

    const key = filters.normalizeName(name);
    const isBlocked = blockedChannels.has(key);
    const subscribedHere = subscribed.has(key) || saysSubscribed(anchor);
    const live = !!videoId() && isLive();
    const signature = JSON.stringify([name, isBlocked, subscribedHere, live, config.enabled]);
    if (row.dataset.ytctSig === signature) return;
    row.dataset.ytctSig = signature;
    row.textContent = "";
    if (config.enabled === false) return;

    if (!subscribedHere) {
      const block = document.createElement("button");
      block.type = "button";
      block.className = "ytct-m-btn ytct-m-shut";
      setClass(block, "ytct-on", isBlocked);
      block.textContent = isBlocked ? "UNBLOCK" : "SHUT UP";
      block.title = isBlocked ? `Show ${name} again` : `Block ${name} everywhere`;
      block.addEventListener("click", (event) => {
        event.preventDefault();
        if (blockedChannels.has(filters.normalizeName(name))) unblockChannel(name);
        else blockChannel(name, []);
      });
      row.appendChild(block);
    }

    // Only beside the owner bar under a video — channel pages have no chat.
    if (live && anchor.closest("ytm-slim-video-action-bar-renderer, ytm-slim-owner-renderer, ytd-video-owner-renderer, #owner")) {
      const chat = document.createElement("button");
      chat.type = "button";
      chat.className = "ytct-m-btn ytct-m-chat";
      chat.textContent = "CHAT";
      chat.title = "Open the live chat, with your filters";
      chat.addEventListener("click", (event) => {
        event.preventDefault();
        openChat();
      });
      row.appendChild(chat);
    }
  }

  function openChat() {
    const id = videoId();
    if (!id) return;
    const url = `https://www.youtube.com/live_chat?is_popout=1&v=${encodeURIComponent(id)}`;
    // A new tab, so the stream keeps playing in this one.
    const opened = window.open(url, "_blank");
    if (!opened) location.href = url;
  }

  /* ------------------------------------------------------ block / unblock */

  async function blockChannel(name, handles) {
    const key = filters.normalizeName(name);
    if (!key) return;
    if (!filters.looksLikeChannel(name)) {
      console.warn("[SHUT UP] refusing to block a non-channel:", name);
      return;
    }
    const list = Array.isArray(handles) ? handles.filter(Boolean) : [];
    await store.update((c) => {
      const existing = c.channels.find((ch) => filters.normalizeName(ch.name) === key);
      if (existing) {
        existing.handles = [...new Set([...(existing.handles || []), ...list])];
      } else {
        c.channels.push({ name, handles: list, at: Date.now(), why: "shut up button" });
      }
      return c;
    });
    toast(`Blocked ${name}`, () => unblockChannel(name));
  }

  async function unblockChannel(name) {
    const key = filters.normalizeName(name);
    await store.update((c) => {
      c.channels = c.channels.filter((ch) => filters.normalizeName(ch.name) !== key);
      return c;
    });
    toast(`Unblocked ${name}`);
  }

  /* ------------------------------------------------------------------ toast */

  let toastEl = null;
  let toastTimer = 0;

  function toast(message, undo) {
    if (!toastEl || !toastEl.isConnected) {
      toastEl = document.createElement("div");
      toastEl.id = "ytct-page-toast";
      document.documentElement.appendChild(toastEl);
    }
    toastEl.textContent = "";
    const label = document.createElement("span");
    label.textContent = message;
    toastEl.appendChild(label);
    if (undo) {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.textContent = "Undo";
      btn.addEventListener("click", () => {
        hideToast();
        undo();
      });
      toastEl.appendChild(btn);
    }
    toastEl.classList.add("ytct-toast-show");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(hideToast, 5000);
  }

  function hideToast() {
    if (toastEl) toastEl.classList.remove("ytct-toast-show");
  }

  /* ------------------------------------------------------------------- scan */

  let scanTimer = 0;

  function queueScan() {
    if (scanTimer) return;
    scanTimer = setTimeout(() => {
      scanTimer = 0;
      scan();
    }, 200);
  }

  function scan() {
    if (!config) return;
    onSubsFeed = location.pathname.startsWith("/feed/subscriptions");
    applyTabClass();
    scrapeChannelStrip();
    ensureWatchButtons();
    ensureFeedBar();
    for (const card of document.querySelectorAll(CARDS)) {
      // Newer feeds nest one card inside another; judge the outer one only.
      if (card.parentElement && card.parentElement.closest(CARDS)) continue;
      handle(card);
    }
    for (const shelf of document.querySelectorAll(SHELVES)) handleShelf(shelf);
    ensureEmptyNotice();
    flushSubscribed();
  }

  // Exposed for the tests and nothing else; the page never reads it.
  YTCT.pagesMobile = { channelOf, shortsChannel, keyOf, channelForAnchor, CARDS };

  if (globalThis.YTCT_NO_START) return;

  Promise.all([store.load(), api.storage.local.get(SUBS_KEY).catch(() => ({}))]).then(
    ([loaded, subs]) => {
      config = loaded;
      subscribed = new Set(((subs && subs[SUBS_KEY]) || []).map(String));
      rebuild();
      scan();

      store.onChange((next) => {
        const tabChanged = (config && config.subTab) !== next.subTab;
        config = next;
        rebuild();
        if (tabChanged) scan();
        else queueScan();
      });

      new MutationObserver(queueScan).observe(document.documentElement, {
        childList: true,
        subtree: true,
      });

      // m.youtube.com is a single-page app too; these fire on its navigations.
      for (const type of ["yt-navigate-finish", "state-navigateend", "popstate"]) {
        window.addEventListener(type, queueScan);
        document.addEventListener(type, queueScan);
      }
      document.addEventListener("visibilitychange", queueScan);

      setInterval(queueScan, 1000);
    },
  );
})(globalThis.YTCT);

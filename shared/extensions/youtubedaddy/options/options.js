"use strict";
/**
 * Settings page.
 *
 * Everything auto-saves on change — a Save button on a page whose whole job is
 * toggling six checkboxes is a way to lose edits, not to protect them. The
 * content script re-reads and re-applies on every write, so an open chat updates
 * as you type.
 */
(function () {
  const { store, filters } = globalThis.YTCT;

  const $ = (id) => document.getElementById(id);
  const CHECKBOXES = [
    "enabled",
    "wholeWord",
    "caseSensitive",
    "matchAuthorNames",
    "protectStaff",
    "showButton",
    "threads",
    "bumpThreads",
    "allChat",
    "hideShorts",
    "hideWatched",
    "hideMixes",
    "tidySearch",
    "hideHomeFeed",
    "hideRelated",
    "hideComments",
    "hideEndWall",
    "keepPlaying",
    "swipeHide",
  ];
  /** Sending a video id to someone else is "browsing activity" in Firefox's terms. */
  const SHARED = { data_collection: ["browsingActivity"] };

  let config = null;
  let savingTimer = 0;


  function flashSaved() {
    const el = $("saved");
    el.classList.add("show");
    clearTimeout(savingTimer);
    savingTimer = setTimeout(() => el.classList.remove("show"), 1200);
  }

  async function persist() {
    await store.save(config);
    flashSaved();
  }

  /* ------------------------------------------------------------------ render */

  function render() {
    for (const id of CHECKBOXES) $(id).checked = !!config[id];
    $("hideMode").value = config.hideMode;
    $("repeatLimit").value = String(config.repeatLimit);
    if (document.activeElement !== $("words")) {
      $("words").value = config.words.join("\n");
    }
    if (document.activeElement !== $("banWords")) {
      $("banWords").value = config.banWords.join("\n");
    }
    if (document.activeElement !== $("titleWords")) {
      $("titleWords").value = (config.titleWords || []).join("\n");
    }
    $("quality").value = config.quality || "";
    $("speed").value = String(config.speed || 1);
    $("dislikes").checked = !!config.dislikes;
    for (const box of document.querySelectorAll("[data-skip]")) {
      box.checked = (config.skipCategories || []).includes(box.dataset.skip);
    }
    renderRuleErrors();
    renderBlocklist("authors", "authors-empty", "authors");
    renderBlocklist("channels", "channels-empty", "channels");
    renderTabs();
  }

  /* -------------------------------------------------------------------- tabs */

  /** How many things a tab holds, so an unused one is obvious. */
  function tabCounts(name) {
    const channels = Object.values(config.subTags || {}).filter((tabs) =>
      (tabs || []).includes(name),
    ).length;
    const videos = Object.values(config.subVideoTags || {}).filter((tabs) =>
      (tabs || []).includes(name),
    ).length;
    const parts = [];
    if (channels) parts.push(`${channels} channel${channels === 1 ? "" : "s"}`);
    if (videos) parts.push(`${videos} video${videos === 1 ? "" : "s"}`);
    return parts.join(" · ") || "empty";
  }

  /** Renaming has to carry the memberships with it, or the tab empties. */
  function renameEverywhere(from, to) {
    config.subTabs = (config.subTabs || []).map((t) => (t === from ? to : t));
    // A renamed shipped tab is a shipped tab that is no longer there under its
    // own name, so the merge must not bring the original back beside it.
    const binned = new Set(
      (config.subTabsRemoved || []).map((t) => String(t).toLowerCase()),
    );
    binned.add(from.toLowerCase());
    binned.delete(to.toLowerCase());
    config.subTabsRemoved = [...binned];
    for (const map of [config.subTags || {}, config.subVideoTags || {}]) {
      for (const [id, tabs] of Object.entries(map)) {
        if (!(tabs || []).includes(from)) continue;
        map[id] = [...new Set(tabs.map((t) => (t === from ? to : t)))];
      }
    }
    if (config.subTab === from) config.subTab = to;
  }

  function forgetEverywhere(name) {
    config.subTabs = (config.subTabs || []).filter((t) => t !== name);
    // Written down, or the next release that adds a tab brings this one back:
    // the merge that delivers new tabs re-applies the whole shipped list.
    const binned = new Set(
      (config.subTabsRemoved || []).map((t) => String(t).toLowerCase()),
    );
    binned.add(name.toLowerCase());
    config.subTabsRemoved = [...binned];
    for (const map of [config.subTags || {}, config.subVideoTags || {}]) {
      for (const [id, tabs] of Object.entries(map)) {
        const left = (tabs || []).filter((t) => t !== name);
        if (left.length) map[id] = left;
        else delete map[id];
      }
    }
    if (config.subTab === name) config.subTab = "";
  }

  function renderTabs() {
    const list = $("tabs");
    list.textContent = "";
    const tabs = config.subTabs || [];

    for (const name of tabs) {
      const li = document.createElement("li");

      const label = document.createElement("span");
      label.className = "author-name";
      label.textContent = name;

      const meta = document.createElement("span");
      meta.className = "author-meta";
      meta.textContent = tabCounts(name);

      // Renamed in place rather than through a dialog: an options page in a
      // tab is not a reliable place to put one.
      const rename = document.createElement("button");
      rename.type = "button";
      rename.className = "unblock";
      rename.textContent = "rename";
      rename.addEventListener("click", () => {
        const field = document.createElement("input");
        field.type = "text";
        field.value = name;
        field.className = "tabname";
        label.replaceWith(field);
        field.focus();
        field.select();
        let done = false;
        const commit = async (keep) => {
          if (done) return;
          done = true;
          const next = field.value.trim();
          if (keep && next && next !== name && !tabs.includes(next)) {
            renameEverywhere(name, next);
            await persist();
          }
          renderTabs();
        };
        field.addEventListener("keydown", (event) => {
          if (event.key === "Enter") commit(true);
          if (event.key === "Escape") commit(false);
        });
        field.addEventListener("blur", () => commit(false));
      });

      const remove = document.createElement("button");
      remove.type = "button";
      remove.className = "unblock";
      remove.textContent = "remove";
      remove.addEventListener("click", async () => {
        forgetEverywhere(name);
        renderTabs();
        await persist();
      });

      li.append(label, meta, rename, remove);
      list.appendChild(li);
    }
    $("tabs-empty").style.display = tabs.length ? "none" : "";
  }

  function renderRuleErrors() {
    showErrors("rule-errors", config.words);
    showErrors("ban-errors", config.banWords);
    showErrors("title-errors", config.titleWords || []);
  }

  function showErrors(boxId, words) {
    const box = $(boxId);
    box.textContent = "";
    const broken = filters.compile(words, config).filter((r) => r.error);
    for (const rule of broken) {
      const line = document.createElement("div");
      line.textContent = `${rule.src}: ${rule.error}`;
      box.appendChild(line);
    }
  }

  /** Authors and channels render identically: name, why · when, unblock. */
  function renderBlocklist(listId, emptyId, configKey) {
    const list = $(listId);
    list.textContent = "";
    const sorted = config[configKey]
      .slice()
      .sort((a, b) => (b.at || 0) - (a.at || 0));

    for (const entry of sorted) {
      const li = document.createElement("li");

      const name = document.createElement("span");
      name.className = "author-name";
      name.textContent = entry.name;

      const meta = document.createElement("span");
      meta.className = "author-meta";
      // The separator only earns its place between two things. Entries that
      // shipped with the extension carry no date — nothing happened on a day —
      // and used to render as "shipped with the extension · " on every row.
      const when = entry.at ? new Date(entry.at).toLocaleDateString() : "";
      meta.textContent = [entry.why, when].filter(Boolean).join(" · ");
      meta.title = entry.why || "";

      const remove = document.createElement("button");
      remove.type = "button";
      remove.className = "unblock";
      remove.textContent = "unblock";
      remove.addEventListener("click", async () => {
        const key = filters.normalizeName(entry.name);
        config[configKey] = config[configKey].filter(
          (e) => filters.normalizeName(e.name) !== key,
        );
        renderBlocklist(listId, emptyId, configKey);
        await persist();
      });

      li.append(name, meta, remove);
      list.appendChild(li);
    }
    $(emptyId).style.display = sorted.length ? "none" : "";
  }

  /* ------------------------------------------------------------------ wiring */

  /** Debounced textarea -> config list. Both rule lists behave identically. */
  function wireList(elementId, configKey) {
    let timer = 0;
    $(elementId).addEventListener("input", (event) => {
      clearTimeout(timer);
      // Debounced: every keystroke would otherwise be a storage write that the
      // content script answers with a full rescan of the visible chat.
      timer = setTimeout(async () => {
        config[configKey] = event.target.value
          .split("\n")
          .map((line) => line.trim())
          .filter(Boolean);
        renderRuleErrors();
        await persist();
      }, 400);
    });
  }

  /**
   * A config out of a backup file. A whole-config backup (this page's own, or
   * the raw stored config) replaces everything, run through the same defaults
   * as a stored config so a file from an older version gains the newer keys.
   * The old clipboard format held only the four lists, so those are laid over
   * what is here and everything else stays.
   */
  function restoreFrom(doc) {
    if (!doc || typeof doc !== "object") throw new Error("not a backup");
    const whole = doc.config && typeof doc.config === "object" ? doc.config
      : Array.isArray(doc.channels) && "subTabs" in doc ? doc
      : null;
    if (whole) return store.withDefaults(whole);
    const next = store.clone(config);
    let found = false;
    const named = (list) => list
      .map((e) => (typeof e === "string" ? { name: e, at: Date.now() } : e))
      .filter((e) => e && e.name);
    if (Array.isArray(doc.words)) (next.words = doc.words.map(String)), (found = true);
    if (Array.isArray(doc.banWords)) (next.banWords = doc.banWords.map(String)), (found = true);
    if (Array.isArray(doc.authors)) (next.authors = named(doc.authors)), (found = true);
    if (Array.isArray(doc.channels)) (next.channels = named(doc.channels)), (found = true);
    if (!found) throw new Error("nothing in it this page knows");
    return next;
  }

  function wire() {
    for (const id of CHECKBOXES) {
      $(id).addEventListener("change", async (event) => {
        config[id] = event.target.checked;
        await persist();
      });
    }

    $("hideMode").addEventListener("change", async (event) => {
      config.hideMode = event.target.value;
      await persist();
    });

    $("repeatLimit").addEventListener("change", async (event) => {
      const value = Number.parseInt(event.target.value, 10);
      config.repeatLimit = Number.isFinite(value) && value >= 0 ? value : 0;
      event.target.value = String(config.repeatLimit);
      await persist();
    });

    wireList("words", "words");
    wireList("banWords", "banWords");
    wireList("titleWords", "titleWords");

    $("quality").addEventListener("change", async (event) => {
      config.quality = event.target.value;
      await persist();
    });
    $("speed").addEventListener("change", async (event) => {
      config.speed = Number(event.target.value) || 1;
      await persist();
    });

    // Switching either outside service on asks Firefox for permission first,
    // inside the click; refused, the box goes back off.
    async function allowSharing(box) {
      if (!box.checked) return true;
      try {
        if (await api.permissions.request(SHARED)) return true;
      } catch (error) {
        console.error("[YoutubeDaddy]", error);
      }
      box.checked = false;
      return false;
    }
    $("dislikes").addEventListener("change", async (event) => {
      if (!(await allowSharing(event.target))) return;
      config.dislikes = event.target.checked;
      await persist();
    });
    for (const box of document.querySelectorAll("[data-skip]")) {
      box.addEventListener("change", async () => {
        if (!(await allowSharing(box))) return;
        config.skipCategories = [...document.querySelectorAll("[data-skip]:checked")]
          .map((b) => b.dataset.skip);
        await persist();
      });
    }

    $("add-author").addEventListener("submit", async (event) => {
      event.preventDefault();
      const input = $("author-input");
      const name = input.value.trim();
      if (!name) return;
      const key = filters.normalizeName(name);
      if (!config.authors.some((a) => filters.normalizeName(a.name) === key)) {
        config.authors.push({ name, at: Date.now(), auto: false });
      }
      input.value = "";
      renderBlocklist("authors", "authors-empty", "authors");
      await persist();
    });

    $("add-channel").addEventListener("submit", async (event) => {
      event.preventDefault();
      const input = $("channel-input");
      const name = input.value.trim();
      if (!name) return;
      const key = filters.normalizeName(name);
      if (!config.channels.some((c) => filters.normalizeName(c.name) === key)) {
        config.channels.push({ name, at: Date.now(), why: "added by hand" });
      }
      input.value = "";
      renderBlocklist("channels", "channels-empty", "channels");
      await persist();
    });

    $("add-tab").addEventListener("submit", async (event) => {
      event.preventDefault();
      const value = $("tab-input").value.trim();
      if (!value) return;
      config.subTabs = config.subTabs || [];
      if (!config.subTabs.includes(value)) config.subTabs.push(value);
      // Adding one back is a change of mind about having removed it.
      config.subTabsRemoved = (config.subTabsRemoved || []).filter(
        (t) => String(t).toLowerCase() !== value.toLowerCase(),
      );
      $("tab-input").value = "";
      renderTabs();
      await persist();
    });

    $("backup-save").addEventListener("click", () => {
      const doc = { format: "youtubedaddy-backup", saved: new Date().toISOString(), config };
      const day = new Date().toISOString().slice(0, 10);
      download(`youtubedaddy-backup-${day}.json`, "application/json", JSON.stringify(doc, null, 2));
      $("backup-status").textContent = "Saved.";
    });
    $("backup-restore").addEventListener("click", () => $("backup-file").click());
    $("backup-file").addEventListener("change", async () => {
      const file = $("backup-file").files[0];
      $("backup-file").value = "";
      if (!file) return;
      try {
        const next = restoreFrom(JSON.parse(await file.text()));
        if (!confirm(`Replace everything here with ${file.name}?`)) return;
        config = next;
        render();
        await persist();
        $("backup-status").textContent = `Restored from ${file.name}.`;
      } catch (error) {
        $("backup-status").textContent = `Couldn't restore ${file.name}: ${error.message}`;
      }
    });
  }

  /* ----------------------------------------------------------- subscriptions */

  const api = globalThis.browser ?? globalThis.chrome;
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  let subsPort = null;

  function subsStatus(text) {
    $("subs-status").textContent = text;
  }

  function subsBusy(busy) {
    for (const id of ["subs-export-json", "subs-export-csv", "subs-import"]) {
      $(id).disabled = busy;
    }
    $("subs-stop").hidden = !busy;
  }

  /** A YouTube tab to work through: an open one, or a new one in the background. */
  async function youtubeTab() {
    // bork mobile: a phone is sent to m.youtube.com, so look there too.
    const open = await api.tabs.query({ url: ["https://m.youtube.com/*", "https://www.youtube.com/*"] });
    // A discarded tab has no content script to answer.
    const tab = open.find((t) => !t.discarded && !String(t.url || "").includes("/live_chat"));
    if (tab) return tab.id;
    subsStatus("Opening YouTube in a background tab…");
    const made = await api.tabs.create({ url: "https://m.youtube.com/feed/channels", active: false });
    return made.id;
  }

  /** A port to subs.js in that tab, once its script is up. */
  async function connectSubs() {
    const tabId = await youtubeTab();
    for (let attempt = 0; attempt < 30; attempt++) {
      const port = api.tabs.connect(tabId, { name: "ytm-subs", frameId: 0 });
      const ready = await new Promise((resolve) => {
        const timer = setTimeout(() => resolve(false), 1000);
        port.onMessage.addListener(function first(msg) {
          if (msg.type !== "ready") return;
          port.onMessage.removeListener(first);
          clearTimeout(timer);
          resolve(true);
        });
        port.onDisconnect.addListener(() => {
          clearTimeout(timer);
          resolve(false);
        });
      });
      if (ready) return port;
      try {
        port.disconnect();
      } catch {
        // already gone
      }
      await sleep(500);
    }
    throw new Error("Couldn't reach YouTube. Open youtube.com, sign in and try again.");
  }

  /** Run one op over a fresh port; resolves with the first message of `until` type. */
  async function subsRun(op, until) {
    subsBusy(true);
    try {
      subsPort = await connectSubs();
      return await new Promise((resolve, reject) => {
        subsPort.onMessage.addListener((msg) => {
          if (msg.type === "progress") subsStatus(msg.text);
          else if (msg.type === "error") reject(new Error(msg.text));
          else if (msg.type === until) resolve(msg);
        });
        subsPort.onDisconnect.addListener(() => reject(new Error("The YouTube tab went away")));
        subsPort.postMessage(op);
      });
    } finally {
      try {
        subsPort?.disconnect();
      } catch {
        // already gone
      }
      subsPort = null;
      subsBusy(false);
    }
  }

  function download(name, type, text) {
    const url = URL.createObjectURL(new Blob([text], { type }));
    const a = document.createElement("a");
    a.href = url;
    a.download = name;
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 10000);
  }

  function csvField(value) {
    const s = String(value ?? "");
    return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  }

  async function exportSubs(kind) {
    try {
      const { channels } = await subsRun({ op: "list" }, "list");
      const day = new Date().toISOString().slice(0, 10);
      if (kind === "csv") {
        // Google Takeout's subscriptions.csv layout, so it goes anywhere theirs does.
        const rows = ["Channel Id,Channel Url,Channel Title"].concat(
          channels.map((c) => [c.id, `http://www.youtube.com/channel/${c.id}`, c.title].map(csvField).join(",")),
        );
        download(`youtube-subscriptions-${day}.csv`, "text/csv", rows.join("\n") + "\n");
      } else {
        const doc = { format: "youtubedaddy-subscriptions", exported: new Date().toISOString(), channels };
        download(`youtube-subscriptions-${day}.json`, "application/json", JSON.stringify(doc, null, 2));
      }
      subsStatus(`Exported ${channels.length} subscriptions.`);
    } catch (e) {
      subsStatus(`Export failed: ${e.message}`);
    }
  }

  /**
   * Channels out of whatever file this is. Our own JSON says so; anything
   * else — Takeout CSV or JSON, an OPML from another app, a list of links —
   * is searched for channel ids, taking a CSV row's last field as the title.
   */
  function parseSubs(text) {
    try {
      const doc = JSON.parse(text);
      if (doc && Array.isArray(doc.channels)) {
        return doc.channels.filter((c) => c && /^UC[\w-]{22}$/.test(c.id))
          .map((c) => ({ id: c.id, title: String(c.title || "") }));
      }
    } catch {
      // not JSON; fall through
    }
    const found = new Map();
    for (const line of text.split(/\r?\n/)) {
      for (const m of line.matchAll(/UC[\w-]{22}/g)) {
        if (found.has(m[0])) continue;
        let title = "";
        if (line.includes(",") && !/<|\{/.test(line)) {
          title = line.slice(line.lastIndexOf(",") + 1).replace(/^"|"$/g, "").trim();
          if (title.includes(m[0])) title = "";
        }
        found.set(m[0], { id: m[0], title });
      }
    }
    return [...found.values()];
  }

  async function importSubs(file) {
    const channels = parseSubs(await file.text());
    if (!channels.length) {
      subsStatus(`No channel ids found in ${file.name}.`);
      return;
    }
    if (!confirm(`Subscribe to the channels in ${file.name} you aren't already subscribed to? (${channels.length} in the file)`)) {
      return;
    }
    try {
      const r = await subsRun({ op: "import", channels }, "imported");
      const parts = [`Subscribed to ${r.done}`, `${r.skipped} already subscribed`];
      if (r.failed.length) {
        parts.push(`${r.failed.length} failed: ${r.failed.map((c) => c.title || c.id).join(", ")}`);
      }
      subsStatus(`${r.stopped ? "Stopped. " : ""}${parts.join(", ")}.`);
    } catch (e) {
      subsStatus(`Import failed: ${e.message}`);
    }
  }

  function wireSubs() {
    $("subs-export-json").addEventListener("click", () => exportSubs("json"));
    $("subs-export-csv").addEventListener("click", () => exportSubs("csv"));
    $("subs-import").addEventListener("click", () => $("subs-file").click());
    $("subs-file").addEventListener("change", () => {
      const file = $("subs-file").files[0];
      $("subs-file").value = "";
      if (file) importSubs(file);
    });
    $("subs-stop").addEventListener("click", () => subsPort?.postMessage({ op: "stop" }));
  }

  /* -------------------------------------------------------------------- sync */

  function ago(at) {
    if (!at) return "not yet";
    const mins = Math.round((Date.now() - at) / 60000);
    if (mins < 1) return "just now";
    if (mins < 60) return `${mins} min ago`;
    return new Date(at).toLocaleString();
  }

  function showSync(state, note) {
    $("sync-out").hidden = !!state;
    $("sync-in").hidden = !state;
    let text = state ? `Signed in as ${state.user}. Last synced ${ago(state.at)}.` : "";
    if (state && state.error) text += ` Problem: ${state.error}.`;
    if (note) text = text ? `${text} ${note}` : note;
    $("sync-status").textContent = text;
  }

  async function syncAsk(message, busyText) {
    for (const id of ["sync-signin", "sync-signup", "sync-now", "sync-signout"]) $(id).disabled = true;
    if (busyText) $("sync-status").textContent = busyText;
    try {
      const reply = await api.runtime.sendMessage(message);
      if (!reply) return showSync(null, "Sync isn't running. Reload the add-on.");
      if (!reply.ok) {
        const current = await api.runtime.sendMessage({ sync: "status" });
        return showSync(current && current.status, reply.error);
      }
      showSync(reply.status);
    } finally {
      for (const id of ["sync-signin", "sync-signup", "sync-now", "sync-signout"]) $(id).disabled = false;
    }
  }

  function credentials() {
    const user = $("sync-user").value.trim();
    const password = $("sync-pass").value;
    if (!user || password.length < 8) {
      $("sync-status").textContent = "Enter a name and a password of at least 8 characters.";
      return null;
    }
    $("sync-pass").value = "";
    return { user, password };
  }

  function wireSync() {
    $("sync-signin").addEventListener("click", () => {
      const c = credentials();
      if (c) syncAsk({ sync: "signin", ...c }, "Signing in…");
    });
    $("sync-signup").addEventListener("click", () => {
      const c = credentials();
      if (c) syncAsk({ sync: "signup", ...c }, "Signing up…");
    });
    $("sync-now").addEventListener("click", () => syncAsk({ sync: "now" }, "Syncing…"));
    $("sync-signout").addEventListener("click", () => syncAsk({ sync: "signout" }));
    syncAsk({ sync: "status" });
  }

  store.load().then((loaded) => {
    config = loaded;
    render();
    wire();
    wireSubs();
    wireSync();
    // Another tab (or the SHUT UP button in a live chat) may write while this
    // page is open; keep it truthful rather than stale.
    store.onChange((next) => {
      config = next;
      render();
    });
  });
})();

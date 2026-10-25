"use strict";
/**
 * Runs in Google My Activity (myactivity.google.com/page?page=youtube_…).
 * bork mobile.
 *
 * The desktop's activity.js waits for its settings page to connect and hand
 * it the filters. On a phone that page is in another tab, out of sight, so
 * this takes the job comments-mobile.js left in storage instead, shows its
 * progress in a panel on this page (with Stop), and writes the outcome back
 * to storage for the settings page to show. Reading the list, the filters
 * and the deleting are the desktop's, unchanged — the mobile page is built
 * the same way:
 *   c-wiz[data-token]              one entry; data-token is the comment id
 *     data-date                    YYYYMMDD
 *     button[aria-label^=PREFIX]   its delete button; the label carries the full text
 *     .SiEggd                      "Commented on <a>title</a>" and the like
 * The list loads more as it is scrolled to the bottom.
 */
(function () {
  if (window.top !== window || globalThis.__ytmActivity) return;
  globalThis.__ytmActivity = true;

  const api = globalThis.browser ?? globalThis.chrome;
  const PREFIX = "Delete activity item";
  /** A job older than this was abandoned, not meant for this visit. */
  const FRESH_MS = 10 * 60 * 1000;
  /** How much of the log goes back to the settings page. */
  const LOG_MAX = 500;
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  /* ---- from the desktop's activity.js -------------------------------- */

  function read(entry) {
    const button = entry.querySelector(`button[aria-label^="${PREFIX}"]`);
    if (!button) return null;
    const label = button.getAttribute("aria-label").slice(PREFIX.length).trim();
    const line = entry.querySelector(".SiEggd");
    const link = line && line.querySelector("a[href]");
    const title = link ? link.textContent.trim() : "";
    const kind = line ? line.textContent.replace(title, "").trim() : "";
    return {
      token: entry.getAttribute("data-token"),
      date: entry.getAttribute("data-date") || "",
      text: label || (entry.querySelector(".QTGV3c")?.textContent || "").trim(),
      kind,
      title,
      url: link ? link.href : "",
      button,
    };
  }

  /** One rule: "/pattern/flags" is a regex, anything else a case-blind substring. */
  function rule(line) {
    const m = /^\/(.+)\/([a-z]*)$/.exec(line);
    if (m) {
      try {
        const re = new RegExp(m[1], m[2]);
        return (s) => re.test(s);
      } catch {
        return () => false;
      }
    }
    const needle = line.toLowerCase();
    return (s) => s.toLowerCase().includes(needle);
  }

  function rules(lines) {
    const list = (lines || []).map((l) => l.trim()).filter((l) => l && !l.startsWith("#")).map(rule);
    return list.length ? (s) => list.some((r) => r(s)) : null;
  }

  function matcher(f) {
    const text = rules(f.text);
    const title = rules(f.titles);
    const from = (f.from || "").replace(/-/g, "");
    const to = (f.to || "").replace(/-/g, "");
    const picked = (item) => {
      if (from && item.date && item.date < from) return false;
      if (to && item.date && item.date > to) return false;
      if (f.kind === "comments" && /^replied/i.test(item.kind)) return false;
      if (f.kind === "replies" && !/^replied/i.test(item.kind)) return false;
      if (text && !text(item.text)) return false;
      if (title && !title(item.title)) return false;
      return true;
    };
    // "except": the filters name what to keep, and everything else goes.
    return f.mode === "except" ? (item) => !picked(item) : picked;
  }

  /** Press delete, answer a confirmation if one appears, wait for the entry to go. */
  async function remove(entry, button) {
    button.click();
    for (let i = 0; i < 40; i++) {
      await sleep(250);
      const dialog = [...document.querySelectorAll('[role="dialog"], [role="alertdialog"]')]
        .find((d) => d.offsetParent !== null || d.getClientRects().length);
      if (dialog) {
        const ok = [...dialog.querySelectorAll("button")].find((b) => /^\s*delete\s*$/i.test(b.textContent));
        if (ok) ok.click();
      }
      if (!entry.isConnected || !entry.querySelector(`button[aria-label^="${PREFIX}"]`) ||
          entry.getClientRects().length === 0) {
        return true;
      }
    }
    return false;
  }

  async function more(seenCount) {
    // Several nudges: the list only fetches when its sentinel scrolls into view.
    for (let i = 0; i < 6; i++) {
      window.scrollTo(0, document.documentElement.scrollHeight);
      await sleep(1500);
      if (document.querySelectorAll("c-wiz[data-token]").length !== seenCount) return true;
    }
    return false;
  }

  /* ---- the panel ------------------------------------------------------ */

  function panel(onStop) {
    const box = document.createElement("div");
    box.className = "ytm-act-panel";
    const title = document.createElement("div");
    title.className = "ytm-act-title";
    title.textContent = "YouTube Manager";
    const status = document.createElement("div");
    status.className = "ytm-act-status";
    const button = document.createElement("button");
    button.type = "button";
    button.className = "ytm-act-button";
    button.textContent = "Stop";
    button.addEventListener("click", () => {
      if (button.textContent === "Stop") onStop();
      else box.remove();
    });
    box.append(title, status, button);
    document.documentElement.append(box);
    return {
      say(text) { status.textContent = text; },
      finished() { button.textContent = "Close"; },
    };
  }

  /* ---- the job -------------------------------------------------------- */

  function summary(c, verb) {
    const parts = [`Checked ${c.checked}`, `${c.matched} ${verb}`];
    if (c.deleted) parts.push(`${c.deleted} deleted`);
    if (c.failed) parts.push(`${c.failed} could not be deleted`);
    return parts.join(", ");
  }

  async function run(job) {
    let stopped = false;
    const ui = panel(() => (stopped = true));
    const verb = job.delete ? "picked" : "matched";
    const result = { id: job.id, delete: job.delete, state: "running", checked: 0, matched: 0, deleted: 0, failed: 0, items: [] };
    let lastSave = 0;
    const save = (force) => {
      const now = Date.now();
      if (!force && now - lastSave < 1000) return;
      lastSave = now;
      api.storage.local.set({ ytmActivityResult: result }).catch(() => {});
    };
    ui.say(job.delete ? "Deleting — stay on this tab until this says it has finished." : "Finding — stay on this tab until this says it has finished.");
    save(true);

    // The list renders a moment after the page does.
    for (let i = 0; i < 40 && !document.querySelector("c-wiz[data-token]"); i++) await sleep(250);

    const wanted = matcher(job.filters || {});
    const seen = new Set();
    while (!stopped) {
      const fresh = [...document.querySelectorAll("c-wiz[data-token]")]
        .filter((e) => !seen.has(e.getAttribute("data-token")));
      if (!fresh.length) {
        if (!(await more(document.querySelectorAll("c-wiz[data-token]").length))) break;
        continue;
      }
      for (const entry of fresh) {
        if (stopped) break;
        seen.add(entry.getAttribute("data-token"));
        const item = read(entry);
        if (!item) continue;
        result.checked++;
        if (!wanted(item)) continue;
        result.matched++;
        const { button, ...plain } = item;
        let outcome = "found";
        if (job.delete) {
          entry.scrollIntoView({ block: "center" });
          if (await remove(entry, button)) {
            result.deleted++;
            outcome = "deleted";
          } else {
            result.failed++;
            outcome = "failed";
          }
          await sleep(job.delay || 1500);
        }
        if (result.items.length < LOG_MAX) result.items.push({ item: plain, outcome });
        ui.say(`${summary(result, verb)}…`);
        save(false);
      }
      ui.say(`${summary(result, verb)}…`);
    }
    result.state = stopped ? "stopped" : "done";
    save(true);
    let text = `${stopped ? "Stopped" : "Finished"}. ${summary(result, verb)}.`;
    if (!result.checked) text += " Nothing was listed — if Google wants you to sign in or verify, do it here and press Find or Delete again.";
    else text += " The list is on YouTube Manager's Delete comments page.";
    ui.say(text);
    ui.finished();
  }

  async function start() {
    const got = await api.storage.local.get("ytmActivityJob");
    const job = got && got.ytmActivityJob;
    if (!job || Date.now() - job.at > FRESH_MS) return;
    const page = new URLSearchParams(location.search).get("page");
    if (!location.pathname.startsWith("/page") || page !== job.page) return;
    // Taken once: a reload of this tab must not run it again.
    await api.storage.local.remove("ytmActivityJob");
    run(job);
  }

  start();
})();

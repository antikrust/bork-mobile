"use strict";
/**
 * Comment deletion page. bork mobile.
 *
 * The desktop page (comments.js) opens My Activity in a window beside it and
 * drives it over a port. A phone shows one tab at a time, and My Activity
 * only loads more of its list while it is on screen, so here the page leaves
 * the job in storage and opens My Activity in front; activity-mobile.js picks
 * the job up there, runs it with a panel of its own (progress and Stop), and
 * writes what it did back to storage, where this page shows it.
 *
 *   ytmActivityJob     { id, at, delete, delay, filters, page } — taken once
 *   ytmActivityResult  { id, state, checked, matched, deleted, failed, items[] }
 */
(function () {
  const api = globalThis.browser ?? globalThis.chrome;
  const $ = (id) => document.getElementById(id);

  function source() {
    return document.querySelector('input[name="source"]:checked').value;
  }

  function filters() {
    const lines = (id) => $(id).value.split("\n");
    return {
      text: lines("text"),
      titles: lines("titles"),
      from: $("from").value,
      to: $("to").value,
      kind: source() === "youtube_comments" ? $("kind").value : "all",
      mode: $("mode").value,
    };
  }

  function summary(c, verb) {
    const parts = [`Checked ${c.checked}`, `${c.matched} ${verb}`];
    if (c.deleted) parts.push(`${c.deleted} deleted`);
    if (c.failed) parts.push(`${c.failed} could not be deleted`);
    return parts.join(", ");
  }

  function show(result) {
    if (!result) return;
    const verb = result.delete ? "picked" : "matched";
    const lead = { running: "Working in My Activity — ", stopped: "Stopped. ", done: "Done. " }[result.state] || "";
    let text = `${lead}${summary(result, verb)}${result.state === "running" ? "…" : "."}`;
    if (result.state !== "running" && !result.checked) {
      text += " Nothing was listed — if My Activity wanted you to sign in or verify, do it there and press again.";
    }
    $("status").textContent = text;
    $("log").textContent = "";
    for (const { item, outcome } of result.items || []) {
      const li = document.createElement("li");
      li.className = outcome;
      const what = document.createElement("div");
      what.className = "what";
      what.textContent = item.text;
      const meta = document.createElement("div");
      meta.className = "meta";
      const d = item.date;
      const day = d.length === 8 ? `${d.slice(0, 4)}-${d.slice(4, 6)}-${d.slice(6)}` : "";
      meta.textContent = [day, item.kind && item.title ? `${item.kind} ${item.title}` : item.title,
        outcome === "failed" ? "NOT deleted" : ""].filter(Boolean).join(" · ");
      li.append(what, meta);
      $("log").append(li);
    }
  }

  async function run(doDelete) {
    if (doDelete && !confirm("Delete everything these filters pick? This cannot be undone.")) return;
    const page = source();
    const job = {
      id: `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`,
      at: Date.now(),
      delete: doDelete,
      delay: Number($("delay").value) || 1500,
      filters: filters(),
      page,
    };
    await api.storage.local.set({ ytmActivityJob: job, ytmActivityResult: null });
    $("log").textContent = "";
    $("status").textContent = "Opening My Activity — stay on that tab until its panel says it has finished.";
    await api.tabs.create({ url: `https://myactivity.google.com/page?hl=en&page=${page}`, active: true });
  }

  $("find").addEventListener("click", () => run(false));
  $("delete").addEventListener("click", () => run(true));
  for (const radio of document.querySelectorAll('input[name="source"]')) {
    radio.addEventListener("change", () => {
      $("kind-row").style.display = source() === "youtube_comments" ? "" : "none";
    });
  }

  // The last run, and the one going on now, as My Activity reports it.
  api.storage.local.get("ytmActivityResult").then((got) => show(got && got.ytmActivityResult));
  api.storage.onChanged.addListener((changes, area) => {
    if (area === "local" && changes.ytmActivityResult) show(changes.ytmActivityResult.newValue);
  });
})();

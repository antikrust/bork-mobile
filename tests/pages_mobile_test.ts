// Host-side tests for the YoutubeDaddy mobile content script: the parts
// that read YouTube's markup, run against markup captured from the real
// m.youtube.com. No browser and no network.
//
//   deno test --allow-read tests/
import { assertEquals } from "jsr:@std/assert@1";
import { parseHTML } from "npm:linkedom@0.18.12";

const root = new URL("../shared/extensions/youtubedaddy/", import.meta.url);
const fixture = await Deno.readTextFile(new URL("./fixtures/m-youtube.html", import.meta.url));

// deno-lint-ignore no-explicit-any
type Any = any;

function load(): { YTCT: Any; document: Any } {
  const { window, document } = parseHTML(fixture) as Any;
  const g = globalThis as Any;
  g.window = g;
  g.top = g;
  g.document = document;
  g.location = { pathname: "/results", search: "?search_query=sky+news" };
  g.YTCT_NO_START = true;
  g.YTCT = undefined;
  g.Node = window.Node;
  for (const file of ["src/channels.js", "src/store.js", "src/filters.js", "src/pages-mobile.js"]) {
    const src = Deno.readTextFileSync(new URL(file, root));
    // Each file is an IIFE over globalThis.YTCT, as the manifest loads them.
    (0, eval)(src);
  }
  return { YTCT: g.YTCT, document };
}

Deno.test("video card: channel comes from the byline, not the view count", () => {
  const { YTCT, document } = load();
  const card = document.querySelector("ytm-video-with-context-renderer");
  assertEquals(YTCT.pagesMobile.channelOf(card), "Sky News");
});

Deno.test("video card: watch key drops the &pp= search tracking", () => {
  const { YTCT, document } = load();
  const card = document.querySelector("ytm-video-with-context-renderer");
  assertEquals(YTCT.pagesMobile.keyOf(card), "/watch?v=xDWQ3LkccY8");
});

Deno.test("shorts card: channel is read from the end of the aria-label", () => {
  const { YTCT, document } = load();
  const card = document.querySelector("ytm-shorts-lockup-view-model");
  assertEquals(YTCT.pagesMobile.channelOf(card), "Sky News");
});

Deno.test("shorts card: a title full of commas does not confuse it", () => {
  const { YTCT, document } = load();
  const card = document.querySelector("ytm-shorts-lockup-view-model");
  card.querySelector("h3").setAttribute(
    "aria-label",
    "Yes, no, maybe, so, 1,234 views, Some Channel, 2 days ago – play Short",
  );
  assertEquals(YTCT.pagesMobile.shortsChannel(card), "Some Channel");
});

Deno.test("channel card in search names itself", () => {
  const { YTCT, document } = load();
  const card = document.querySelector("ytm-compact-channel-renderer");
  assertEquals(YTCT.pagesMobile.channelOf(card), "Sky News");
});

Deno.test("watch page: Subscribe button names its channel", () => {
  const { YTCT, document } = load();
  const anchor = document.querySelector("#owner-sub");
  assertEquals(YTCT.pagesMobile.channelForAnchor(anchor), "Sky News");
});

Deno.test("watch page: owner icon label is the fallback", () => {
  const { YTCT, document } = load();
  const anchor = document.querySelector("#owner-sub");
  anchor.querySelector("button").removeAttribute("aria-label");
  assertEquals(YTCT.pagesMobile.channelForAnchor(anchor), "Sky News");
});

Deno.test("the shipped blocklist blocks the fixture's channel by name", () => {
  const { YTCT } = load();
  const blocked = YTCT.filters.blockSet(YTCT.store.DEFAULTS.channels);
  assertEquals(blocked.has(YTCT.filters.normalizeName("Sky News")), true);
});

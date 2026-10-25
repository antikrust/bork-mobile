// Host tests for the iOS app's YouTube ad stripper (ios/Bork/Extensions/
// youtube-ads.js), run against YouTube-shaped payloads that carry ads. No
// browser and no network: YouTube decides per session whether to serve ads
// at all, so a live check cannot be relied on to see one.
//   deno test --allow-read tests/
import { assert, assertEquals } from "jsr:@std/assert@1";

// deno-lint-ignore no-explicit-any
const g = globalThis as any;
const SOURCE = Deno.readTextFileSync(new URL("../ios/Bork/Extensions/youtube-ads.js", import.meta.url));

g.document = {
  documentElement: { appendChild() {} },
  head: null,
  createElement: () => ({}),
  querySelector: () => null,
  querySelectorAll: () => [],
  addEventListener() {},
};
g.MutationObserver = class { observe() {} };
g.window = g;

// Off YouTube it must not touch anything: run it there first.
const nativeParse = JSON.parse;
g.location = { hostname: "example.com", pathname: "/" };
(0, eval)(SOURCE);
const untouchedElsewhere = JSON.parse === nativeParse;

// Then as on m.youtube.com.
g.location = { hostname: "m.youtube.com", pathname: "/watch" };
(0, eval)(SOURCE);

const playerResponse = () => ({
  videoDetails: { videoId: "kJQP7kiw5Fk", title: "Despacito" },
  streamingData: { formats: [{ itag: 18 }] },
  adPlacements: [{ adPlacementRenderer: { config: {} } }],
  playerAds: [{ playerLegacyDesktopWatchAdsRenderer: {} }],
  adSlots: [{ adSlotRenderer: {} }],
  adBreakHeartbeatParams: "Q0FB",
  auxiliaryUi: { messageRenderers: { enforcementMessageViewModel: { title: "Ad blockers are not allowed" } } },
});

Deno.test("does nothing on other sites", () => {
  assert(untouchedElsewhere);
});

Deno.test("inline ytInitialPlayerResponse loses its ads, keeps the video", () => {
  g.ytInitialPlayerResponse = playerResponse();
  const r = g.ytInitialPlayerResponse;
  for (const key of ["adPlacements", "playerAds", "adSlots", "adBreakHeartbeatParams"]) {
    assertEquals(key in r, false, key);
  }
  assertEquals(r.videoDetails.videoId, "kJQP7kiw5Fk");
  assertEquals(r.streamingData.formats.length, 1);
  assertEquals("enforcementMessageViewModel" in r.auxiliaryUi.messageRenderers, false);
});

Deno.test("an API response parsed with JSON.parse is stripped, nested too", () => {
  const text = JSON.stringify({ responseContext: {}, playerResponse: playerResponse() });
  const r = JSON.parse(text);
  assertEquals("adPlacements" in r.playerResponse, false);
  assertEquals("playerAds" in r.playerResponse, false);
  assertEquals(r.playerResponse.videoDetails.title, "Despacito");
});

Deno.test("feed and search ad cells are removed, videos kept", () => {
  g.ytInitialData = {
    contents: [
      { richItemRenderer: { content: { adSlotRenderer: { ad: 1 } } } },
      { richItemRenderer: { content: { videoRenderer: { videoId: "a" } } } },
      { promotedSparklesWebRenderer: {} },
      { videoWithContextRenderer: { videoId: "b" } },
      { searchPyvRenderer: {} },
    ],
  };
  const contents = g.ytInitialData.contents;
  assertEquals(contents.length, 2);
  assertEquals(contents[0].richItemRenderer.content.videoRenderer.videoId, "a");
  assertEquals(contents[1].videoWithContextRenderer.videoId, "b");
});

Deno.test("response.json() from YouTube's API is stripped", async () => {
  const res = new Response(JSON.stringify(playerResponse()));
  Object.defineProperty(res, "url", { value: "https://m.youtube.com/youtubei/v1/player?prettyPrint=false" });
  const r = await res.json();
  assertEquals("adPlacements" in r, false);
  assertEquals(r.videoDetails.videoId, "kJQP7kiw5Fk");
});

Deno.test("JSON with no ads comes back exactly as parsed", () => {
  const r = JSON.parse('{"a":[1,2,{"b":"adPlacementsLookalike"}],"c":{"d":true}}');
  assertEquals(r, { a: [1, 2, { b: "adPlacementsLookalike" }], c: { d: true } });
});

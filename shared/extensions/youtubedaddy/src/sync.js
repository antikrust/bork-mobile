"use strict";
/**
 * Sync: the same settings on every bork, through borkbrowser.online.
 *
 * Background script. The password never leaves here: it is stretched
 * (PBKDF2, 300k rounds, salted with the account name) into 64 bytes, the
 * first half sent to the server as proof of who this is, the second half kept
 * as the AES-GCM key the settings are encrypted with. The server holds
 * ciphertext it cannot read (tools/sync-server/server.mjs).
 *
 * What syncs is the config minus what belongs to one screen: which feed tab
 * is open, and the counters.
 *
 *   a local change   pushed a few seconds later
 *   every 5 minutes  pulled; newer data from another device replaces ours
 *   both changed     merged: lists are joined, everything else is ours
 *
 * Joining lists on a clash means an unblock made on one device while the
 * other was also changing things can come back. Without a clash an unblock
 * travels like anything else.
 */
globalThis.YTCT = globalThis.YTCT || {};
(function (YTCT) {
  const api = globalThis.browser ?? globalThis.chrome;
  const { store, filters } = YTCT;
  const BASE = "https://borkbrowser.online/api/sync";
  const STATE = "sync";
  const ROUNDS = 300000;
  const LOCAL_ONLY = new Set(["subTab", "stats"]);
  const PUSH_DELAY = 3000;
  const ALARM = "youtubedaddy-sync";

  /* ------------------------------------------------------------- encoding */

  const hex = (bytes) => [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
  const b64 = (bytes) => btoa(String.fromCharCode(...bytes));
  const unb64 = (text) => Uint8Array.from(atob(text), (c) => c.charCodeAt(0));

  async function derive(user, password) {
    const enc = new TextEncoder();
    const base = await crypto.subtle.importKey("raw", enc.encode(password), "PBKDF2", false, ["deriveBits"]);
    const bits = new Uint8Array(await crypto.subtle.deriveBits(
      { name: "PBKDF2", hash: "SHA-256", salt: enc.encode(`bork-sync:${user}`), iterations: ROUNDS },
      base,
      512,
    ));
    return { auth: hex(bits.slice(0, 32)), key: b64(bits.slice(32)) };
  }

  async function aesKey(state) {
    return crypto.subtle.importKey("raw", unb64(state.key), "AES-GCM", false, ["encrypt", "decrypt"]);
  }

  async function pipe(bytes, stream) {
    return new Uint8Array(await new Response(new Blob([bytes]).stream().pipeThrough(stream)).arrayBuffer());
  }

  /** In big chunks: spreading a long array into fromCharCode overflows the stack. */
  function b64Big(bytes) {
    let out = "";
    for (let i = 0; i < bytes.length; i += 0x8000) out += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
    return btoa(out);
  }

  async function seal(state, payload) {
    const packed = await pipe(new TextEncoder().encode(JSON.stringify(payload)), new CompressionStream("gzip"));
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const ct = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv }, await aesKey(state), packed));
    const out = new Uint8Array(iv.length + ct.length);
    out.set(iv);
    out.set(ct, iv.length);
    return b64Big(out);
  }

  async function open(state, blob) {
    const raw = unb64(blob);
    const packed = new Uint8Array(await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: raw.slice(0, 12) },
      await aesKey(state),
      raw.slice(12),
    ));
    return JSON.parse(new TextDecoder().decode(await pipe(packed, new DecompressionStream("gzip"))));
  }

  /* -------------------------------------------------------------- payload */

  function payloadOf(config) {
    const out = {};
    for (const [k, v] of Object.entries(config)) if (!LOCAL_ONLY.has(k)) out[k] = v;
    return out;
  }

  /** Key order fixed, so the same settings always hash the same. */
  function canonical(value) {
    if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
    if (value && typeof value === "object") {
      return `{${Object.keys(value).sort().map((k) => `${JSON.stringify(k)}:${canonical(value[k])}`).join(",")}}`;
    }
    return JSON.stringify(value);
  }

  async function hashOf(payload) {
    const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(canonical(payload)));
    return hex(new Uint8Array(digest));
  }

  /** Both sides' lists joined, the preferred side's version of everything else. */
  function merge(preferred, other) {
    const out = store.clone(preferred);
    const named = (key) => {
      const seen = new Set((out[key] || []).map((e) => filters.normalizeName(e && e.name)));
      for (const e of other[key] || []) {
        const n = filters.normalizeName(e && e.name);
        if (n && !seen.has(n)) {
          seen.add(n);
          (out[key] = out[key] || []).push(e);
        }
      }
    };
    named("channels");
    named("authors");
    for (const key of ["words", "banWords", "titleWords", "subTabs", "subTabsRemoved", "skipCategories"]) {
      const list = out[key] || [];
      for (const item of other[key] || []) if (!list.includes(item)) list.push(item);
      out[key] = list;
    }
    for (const key of ["subTags", "subVideoTags"]) {
      const map = out[key] || {};
      for (const [id, tabs] of Object.entries(other[key] || {})) {
        map[id] = [...new Set([...(map[id] || []), ...tabs])];
      }
      out[key] = map;
    }
    return out;
  }

  /* --------------------------------------------------------------- server */

  async function call(state, method, path, body) {
    const res = await fetch(`${BASE}${path}`, {
      method,
      headers: {
        "Content-Type": "application/json",
        ...(state ? { Authorization: `Bork ${state.user}:${state.auth}` } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
    });
    const data = res.status === 204 ? null : await res.json().catch(() => null);
    return { status: res.status, data };
  }

  /* ---------------------------------------------------------------- state */

  async function getState() {
    return (await api.storage.local.get(STATE))[STATE] || null;
  }

  async function setState(state) {
    if (state) await api.storage.local.set({ [STATE]: state });
    else await api.storage.local.remove(STATE);
  }

  /** Replace this device's synced settings with a payload, keeping its own screen state. */
  async function adopt(payload) {
    const current = await store.load();
    const next = store.withDefaults({ ...payload, subTab: current.subTab, stats: current.stats });
    await store.save(next);
    return payloadOf(next);
  }

  /* ------------------------------------------------------------ the work */

  /** One thing at a time: a push and a pull racing each other would both win. */
  let queue = Promise.resolve();
  function serial(job) {
    const run = queue.then(job, job);
    queue = run.catch(() => {});
    return run;
  }

  async function push() {
    let state = await getState();
    if (!state) return;
    for (let attempt = 0; attempt < 3; attempt++) {
      const payload = payloadOf(await store.load());
      const hash = await hashOf(payload);
      if (hash === state.hash) return;
      const { status, data } = await call(state, "PUT", "/data", {
        version: state.version,
        blob: await seal(state, payload),
      });
      if (status === 200) {
        state = { ...state, version: data.version, hash, at: Date.now(), error: "" };
        await setState(state);
        return;
      }
      if (status === 409 && data) {
        // Another device got there first. Theirs joined into ours, then try again.
        await adopt(merge(payload, await open(state, data.blob)));
        state = { ...state, version: data.version, hash: null };
        await setState(state);
        continue;
      }
      throw new Error(status === 401 ? "signed out: wrong name or password" : `server said ${status}`);
    }
  }

  async function pull() {
    let state = await getState();
    if (!state) return;
    const { status, data } = await call(state, "GET", "/data");
    if (status === 401) throw new Error("signed out: wrong name or password");
    if (status === 204 || !data) return push();
    if (status !== 200) throw new Error(`server said ${status}`);
    if (data.version === state.version) return push();

    const remote = await open(state, data.blob);
    const local = payloadOf(await store.load());
    // No hash means the last sync never finished, so what is here may be
    // unsent: treat it as changed and join rather than overwrite.
    const changedHere = state.hash === null || (await hashOf(local)) !== state.hash;
    // Unchanged here since the last sync: theirs is simply newer. Changed on
    // both: joined, and the join goes back up.
    const result = changedHere ? merge(local, remote) : remote;
    const adopted = await adopt(result);
    state = { ...state, version: data.version, hash: changedHere ? null : await hashOf(adopted), at: Date.now(), error: "" };
    await setState(state);
    if (changedHere) await push();
  }

  async function guarded(job) {
    try {
      await serial(job);
    } catch (error) {
      const state = await getState();
      if (state) await setState({ ...state, error: String(error.message || error) });
      throw error;
    }
  }

  /* --------------------------------------------------------- the account */

  function cleanUser(user) {
    return String(user || "").trim().toLowerCase();
  }

  async function signUp(user, password) {
    user = cleanUser(user);
    const keys = await derive(user, password);
    const { status, data } = await call(null, "POST", "/signup", { user, auth: keys.auth });
    if (status !== 201) throw new Error((data && data.error) || `server said ${status}`);
    await setState({ user, ...keys, version: 0, hash: null, at: 0, error: "" });
    await guarded(push);
  }

  /**
   * Signing in on a device that already has settings joins them with the
   * account's, account first; a fresh device simply takes the account's.
   */
  async function signIn(user, password) {
    user = cleanUser(user);
    const keys = await derive(user, password);
    const probe = { user, ...keys };
    const { status, data } = await call(probe, "GET", "/data");
    if (status === 401) throw new Error("wrong name or password");
    if (status !== 200 && status !== 204) throw new Error(`server said ${status}`);
    await setState({ ...probe, version: data ? data.version : 0, hash: null, at: 0, error: "" });
    if (data) await adopt(merge(await open(probe, data.blob), payloadOf(await store.load())));
    await guarded(push);
    const state = await getState();
    await setState({ ...state, at: Date.now() });
  }

  async function status() {
    const state = await getState();
    return state ? { user: state.user, at: state.at || 0, error: state.error || "" } : null;
  }

  /* -------------------------------------------------------------- wiring */

  let pushTimer = 0;
  api.storage.onChanged.addListener((changes, area) => {
    if (area !== "local" || !changes.config) return;
    clearTimeout(pushTimer);
    pushTimer = setTimeout(() => guarded(push).catch(() => {}), PUSH_DELAY);
  });

  // Firefox's event pages sleep, so an alarm wakes them. bork's iPhone
  // runtime has no alarms, but its background page never sleeps, so a plain
  // timer does the same there.
  if (api.alarms) {
    api.alarms.create(ALARM, { periodInMinutes: 5 });
    api.alarms.onAlarm.addListener((alarm) => {
      if (alarm.name === ALARM) guarded(pull).catch(() => {});
    });
  } else {
    setInterval(() => guarded(pull).catch(() => {}), 5 * 60 * 1000);
  }
  if (api.runtime.onStartup) api.runtime.onStartup.addListener(() => guarded(pull).catch(() => {}));
  // A background page started for any other reason (the add-on updated, a
  // phone app opened) pulls once too: onStartup only fires at browser start.
  guarded(pull).catch(() => {});

  api.runtime.onMessage.addListener((msg) => {
    if (!msg || !msg.sync) return undefined;
    const reply = (job) => job.then(status).then((s) => ({ ok: true, status: s }), (e) => ({ ok: false, error: e.message }));
    if (msg.sync === "status") return status().then((s) => ({ ok: true, status: s }));
    if (msg.sync === "signup") return reply(signUp(msg.user, msg.password));
    if (msg.sync === "signin") return reply(signIn(msg.user, msg.password));
    if (msg.sync === "now") return reply(guarded(pull));
    if (msg.sync === "signout") return reply(setState(null));
    return undefined;
  });

  // Exposed for the host tests.
  YTCT.sync = { merge, payloadOf, canonical };
})(globalThis.YTCT);

// Run with: node --test relay-worker.test.mjs (Node 20+, no dependencies; the Relay API is faked).
import { test, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { handle, route, tokensMatch } from "./relay-worker.js";

const ENV = { RELAY_API_KEY: "relay-secret-key-123", APP_TOKEN: "app-token-abc" };
const RID = "0x" + "cd".repeat(32);
let calls;
let upstreamReply;

beforeEach(() => {
  calls = [];
  upstreamReply = () => new Response('{"ok":true}', { status: 200, headers: { "set-cookie": "x=1", "x-secret": "leak" } });
  globalThis.fetch = async (url, init) => { calls.push({ url, init }); return upstreamReply(url, init); };
});

const req = (method, path, { token = ENV.APP_TOKEN, body, headers = {} } = {}) =>
  new Request("https://proxy.example" + path, { method, headers: { ...(token === null ? {} : { "x-app-token": token }), ...headers }, body });
const quotePost = (body, { headers = {}, ...rest } = {}) =>
  req("POST", "/quote/v2", { body, headers: { "content-type": "application/json", ...headers }, ...rest });

test("route(): exactly two calls", () => {
  assert.equal(route("POST", "/quote/v2").name, "quote");
  assert.equal(route("GET", "/intents/status/v3").name, "status");
});

test("route(): everything else is refused", () => {
  const no = [["GET", "/quote/v2"], ["POST", "/quote"], ["POST", "/quote/v2/"], ["POST", "/intents/status/v3"], ["GET", "/intents/status/v2"],
    ["GET", "/chains"], ["POST", "/execute/permits"], ["GET", "/requests/v2"], ["DELETE", "/quote/v2"], ["GET", "/"], ["POST", "/quote/v2/extra"]];
  for (const [m, p] of no) assert.equal(route(m, p), null, `${m} ${p}`);
});

test("refuses to run without both secrets or with a bad RELAY_BASE", async () => {
  for (const env of [{}, { RELAY_API_KEY: "k" }, { APP_TOKEN: "t" }, undefined, { ...ENV, RELAY_BASE: "https://evil.example" },
    { ...ENV, RELAY_BASE: "http://api.relay.link" }, { ...ENV, RELAY_BASE: "https://api.relay.link.evil.example" }]) {
    assert.equal((await handle(quotePost("{}"), env)).status, 500);
  }
  assert.equal(calls.length, 0);
});

test("the app token is required and checked", async () => {
  for (const token of [null, "", "wrong", ENV.APP_TOKEN + "x", ENV.RELAY_API_KEY]) {
    const r = await handle(quotePost("{}", { token }), ENV);
    assert.equal(r.status, 401, String(token));
    assert.equal((await r.json()).code, "proxy_unauthorized");
  }
  assert.equal(calls.length, 0);
});

test("unknown paths are 404 with or without a token, never forwarded", async () => {
  for (const token of [ENV.APP_TOKEN, null]) assert.equal((await handle(req("GET", "/chains", { token }), ENV)).status, 404);
  assert.equal(calls.length, 0);
});

test("quote: body forwarded verbatim, key injected as x-api-key, defaults to the testnet API, query dropped", async () => {
  const body = '{"user":"0xabc","originChainId":11155111,"destinationChainId":84532,"amount":"1000"}';
  const r = await handle(quotePost(body, { token: ENV.APP_TOKEN }), ENV);
  assert.equal(r.status, 200);
  const r2 = await handle(new Request("https://proxy.example/quote/v2?evil=1", { method: "POST", body, headers: { "x-app-token": ENV.APP_TOKEN, "content-type": "application/json" } }), ENV);
  assert.equal(r2.status, 200);
  for (const c of calls) {
    assert.equal(c.url, "https://api.testnets.relay.link/quote/v2");
    assert.equal(c.init.body, body);
    assert.equal(c.init.headers["x-api-key"], ENV.RELAY_API_KEY);
    assert.equal(c.init.headers["Content-Type"], "application/json");
  }
});

test("RELAY_BASE selects the mainnet API only when explicitly set", async () => {
  await handle(quotePost("{}"), { ...ENV, RELAY_BASE: "https://api.relay.link/" });
  assert.equal(calls[0].url, "https://api.relay.link/quote/v2");
});

test("client headers are not forwarded", async () => {
  await handle(quotePost("{}", { headers: { authorization: "Bearer evil", cookie: "a=b", "x-api-key": "client-key" } }), ENV);
  assert.deepEqual(Object.keys(calls[0].init.headers).sort(), ["Accept", "Content-Type", "x-api-key"]);
  assert.equal(calls[0].init.headers["x-api-key"], ENV.RELAY_API_KEY);
});

test("quote: bad bodies are refused before reaching Relay", async () => {
  for (const b of ["not json", "[1]", "null", ""]) assert.equal((await handle(quotePost(b), ENV)).status, 400, b);
  assert.equal((await handle(req("POST", "/quote/v2", { body: "{}", headers: { "content-type": "text/plain" } }), ENV)).status, 415);
  const big = '{"a":"' + "x".repeat(9000) + '"}';
  assert.equal((await handle(quotePost(big), ENV)).status, 413);
  assert.equal(calls.length, 0);
});

test("status: only a full requestId is accepted and only it is forwarded", async () => {
  const ok = await handle(req("GET", `/intents/status/v3?requestId=${RID}&evil=1&x=y`), ENV);
  assert.equal(ok.status, 200);
  assert.equal(calls[0].url, `https://api.testnets.relay.link/intents/status/v3?requestId=${RID}`);
  assert.equal(calls[0].init.method, "GET");
  assert.equal(calls[0].init.body, undefined);
  for (const q of ["", "?requestId=", "?requestId=0x1234", `?requestId=${RID}00`, `?requestId=${RID.replace("0x", "")}`, "?requestId=0x" + "zz".repeat(32)]) {
    assert.equal((await handle(req("GET", "/intents/status/v3" + q), ENV)).status, 400, q);
  }
  assert.equal(calls.length, 1);
});

test("Relay's status and body pass through, extra upstream headers do not", async () => {
  upstreamReply = () => new Response('{"message":"Unauthorized","errorCode":"UNAUTHORIZED_QUOTE"}', { status: 401, headers: { "set-cookie": "x=1", "x-secret": "leak" } });
  const r = await handle(quotePost("{}"), ENV);
  assert.equal(r.status, 401);
  assert.equal((await r.json()).errorCode, "UNAUTHORIZED_QUOTE");
  assert.equal(r.headers.get("set-cookie"), null);
  assert.equal(r.headers.get("x-secret"), null);
  assert.equal(r.headers.get("cache-control"), "no-store");
});

test("the Relay key never appears in any response", async () => {
  const rs = [await handle(quotePost("{}", { token: "wrong" }), ENV), await handle(req("GET", "/nope"), ENV), await handle(quotePost("oops"), ENV)];
  upstreamReply = () => { throw new Error("connect failed with key " + ENV.RELAY_API_KEY); };
  rs.push(await handle(quotePost("{}"), ENV));
  for (const r of rs) { const t = await r.text(); assert.ok(!t.includes(ENV.RELAY_API_KEY), t); }
});

test("upstream failure is a clean 502; redirects are not followed", async () => {
  upstreamReply = () => { throw new Error("boom"); };
  const r = await handle(quotePost("{}"), ENV);
  assert.equal(r.status, 502);
  assert.equal((await r.json()).code, "proxy_upstream_unreachable");
  assert.equal(calls[0].init.redirect, "manual");
});

test("tokensMatch", async () => {
  assert.equal(await tokensMatch("abc", "abc"), true);
  assert.equal(await tokensMatch("abc", "abd"), false);
  assert.equal(await tokensMatch(null, "abc"), false);
});

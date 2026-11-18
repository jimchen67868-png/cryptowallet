// Run with: node --test proxy/   (Node 20+). No dependencies: the upstream Across API is faked.
import { test, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { handle, route, tokensMatch } from "./worker.js";

const ENV = { ACROSS_API_KEY: "across-secret-key-123", APP_TOKEN: "app-token-abc" };
const ORDER = "0x" + "ab".repeat(32);
let calls;
let upstreamReply;

beforeEach(() => {
  calls = [];
  upstreamReply = () => new Response('{"ok":true}', { status: 200, headers: { "set-cookie": "x=1", "x-secret": "leak" } });
  globalThis.fetch = async (url, init) => {
    calls.push({ url, init });
    return upstreamReply(url, init);
  };
});

const req = (method, path, { token = ENV.APP_TOKEN, body, headers = {} } = {}) =>
  new Request("https://proxy.example" + path, {
    method,
    headers: { ...(token === null ? {} : { "x-app-token": token }), ...headers },
    body,
  });
const jsonPost = (path, body, extra = {}) =>
  req("POST", path, { body, headers: { "content-type": "application/json", ...extra.headers }, ...extra });

test("route(): exactly three calls are allowed", () => {
  assert.deepEqual(route("GET", "/v1/capabilities").path, "/v1/capabilities");
  assert.deepEqual(route("POST", "/v1/quotes").path, "/v1/quotes");
  assert.deepEqual(route("GET", "/v1/transfers/" + ORDER).path, "/v1/transfers/" + ORDER);
});

test("route(): everything else is refused", () => {
  const no = [
    ["POST", "/v1/capabilities"], ["GET", "/v1/quotes"], ["PUT", "/v1/quotes"], ["DELETE", "/v1/transfers/" + ORDER],
    ["GET", "/v1/transfers"], ["GET", "/v1/transfers/"], ["GET", "/v1/transfers/0x1234"], ["GET", "/v1/transfers/" + ORDER + "/x"],
    ["GET", "/v1/transfers/" + ORDER + "%2f..%2fquotes"], ["GET", "/v1/transfers/../quotes"],
    ["POST", "/v1/quotes/" + ORDER + "/executions"], ["POST", "/v1/webhooks/subscriptions"], ["GET", "/"], ["GET", "/v1"],
    ["GET", "/swap/approval"], ["GET", "/v1/capabilities/extra"], ["POST", "/v1/quotes/"],
  ];
  for (const [m, p] of no) assert.equal(route(m, p), null, `${m} ${p}`);
});

test("refuses to run without both secrets", async () => {
  for (const env of [{}, { ACROSS_API_KEY: "k" }, { APP_TOKEN: "t" }, undefined]) {
    const r = await handle(req("GET", "/v1/capabilities"), env);
    assert.equal(r.status, 500);
  }
  assert.equal(calls.length, 0);
});

test("the app token is required and checked", async () => {
  for (const token of [null, "", "wrong", ENV.APP_TOKEN + "x", ENV.ACROSS_API_KEY]) {
    const r = await handle(req("GET", "/v1/capabilities", { token }), ENV);
    assert.equal(r.status, 401, String(token));
    assert.equal((await r.json()).code, "proxy_unauthorized");
  }
  assert.equal(calls.length, 0, "nothing reaches Across without a valid token");
});

test("unknown paths are 404 with or without a token, and never forwarded", async () => {
  for (const token of [ENV.APP_TOKEN, null]) {
    const r = await handle(req("GET", "/v1/webhooks", { token }), ENV);
    assert.equal(r.status, 404);
  }
  assert.equal(calls.length, 0);
});

test("capabilities: forwards the query and injects the Across key", async () => {
  const r = await handle(req("GET", "/v1/capabilities?originChainId=42161&destinationChainId=8453&include=tokens"), ENV);
  assert.equal(r.status, 200);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, "https://api.across.to/v1/capabilities?originChainId=42161&destinationChainId=8453&include=tokens");
  assert.equal(calls[0].init.method, "GET");
  assert.equal(calls[0].init.headers.Authorization, "Bearer " + ENV.ACROSS_API_KEY);
  assert.equal(calls[0].init.body, undefined);
});

test("client headers are not forwarded: only the key and Accept (and Content-Type on POST)", async () => {
  await handle(req("GET", "/v1/capabilities", { headers: { authorization: "Bearer evil", cookie: "a=b", "x-forwarded-for": "1.2.3.4" } }), ENV);
  assert.deepEqual(Object.keys(calls[0].init.headers).sort(), ["Accept", "Authorization"]);
  assert.equal(calls[0].init.headers.Authorization, "Bearer " + ENV.ACROSS_API_KEY);
});

test("capabilities: over-long queries are rejected", async () => {
  const r = await handle(req("GET", "/v1/capabilities?x=" + "a".repeat(2100)), ENV);
  assert.equal(r.status, 400);
  assert.equal(calls.length, 0);
});

test("quotes: the body is forwarded verbatim, the query is dropped", async () => {
  const body = '{"origin":{"amount":"2000000"},"amountType":"exact_input"}';
  const r = await handle(jsonPost("/v1/quotes?evil=1", body), ENV);
  assert.equal(r.status, 200);
  assert.equal(calls[0].url, "https://api.across.to/v1/quotes");
  assert.equal(calls[0].init.body, body);
  assert.equal(calls[0].init.headers["Content-Type"], "application/json");
  assert.equal(calls[0].init.headers.Authorization, "Bearer " + ENV.ACROSS_API_KEY);
});

test("quotes: bad bodies are refused before reaching Across", async () => {
  assert.equal((await handle(jsonPost("/v1/quotes", "not json"), ENV)).status, 400);
  assert.equal((await handle(jsonPost("/v1/quotes", "[1,2]"), ENV)).status, 400);
  assert.equal((await handle(jsonPost("/v1/quotes", "null"), ENV)).status, 400);
  assert.equal((await handle(jsonPost("/v1/quotes", ""), ENV)).status, 400);
  assert.equal((await handle(req("POST", "/v1/quotes", { body: "{}", headers: { "content-type": "text/plain" } }), ENV)).status, 415);
  assert.equal(calls.length, 0);
});

test("quotes: oversize bodies are refused (declared and actual)", async () => {
  const big = '{"a":"' + "x".repeat(9000) + '"}';
  assert.equal((await handle(jsonPost("/v1/quotes", big), ENV)).status, 413);
  const lying = new Request("https://proxy.example/v1/quotes", {
    method: "POST", body: big, headers: { "x-app-token": ENV.APP_TOKEN, "content-type": "application/json", "content-length": "10" },
  });
  assert.equal((await handle(lying, ENV)).status, 413);
  assert.equal(calls.length, 0);
});

test("transfers: only a full order id is accepted", async () => {
  const ok = await handle(req("GET", "/v1/transfers/" + ORDER + "?x=1"), ENV);
  assert.equal(ok.status, 200);
  assert.equal(calls[0].url, "https://api.across.to/v1/transfers/" + ORDER);
  assert.equal((await handle(req("GET", "/v1/transfers/0xabc"), ENV)).status, 404);
});

test("Across's status and body pass through unchanged, extra upstream headers do not", async () => {
  upstreamReply = () => new Response('{"error":{"type":"request","code":"request.unsupported_param","message":"unsupported field: x","requestId":"r1"}}', { status: 400, headers: { "set-cookie": "x=1", "x-secret": "leak" } });
  const r = await handle(jsonPost("/v1/quotes", '{"x":1}'), ENV);
  assert.equal(r.status, 400);
  assert.equal((await r.json()).error.code, "request.unsupported_param");
  assert.equal(r.headers.get("set-cookie"), null);
  assert.equal(r.headers.get("x-secret"), null);
  assert.equal(r.headers.get("content-type"), "application/json");
  assert.equal(r.headers.get("cache-control"), "no-store");
});

test("the Across key never appears in any response", async () => {
  const responses = [
    await handle(req("GET", "/v1/capabilities", { token: "wrong" }), ENV),
    await handle(req("GET", "/nope"), ENV),
    await handle(jsonPost("/v1/quotes", "oops"), ENV),
    await handle(req("GET", "/v1/capabilities"), ENV),
  ];
  upstreamReply = () => { throw new Error("connect failed to api.across.to with key " + ENV.ACROSS_API_KEY); };
  responses.push(await handle(req("GET", "/v1/capabilities"), ENV));
  for (const r of responses) {
    const text = await r.text();
    assert.ok(!text.includes(ENV.ACROSS_API_KEY), text);
    for (const [, v] of r.headers) assert.ok(!v.includes(ENV.ACROSS_API_KEY));
  }
});

test("upstream failure becomes a clean 502", async () => {
  upstreamReply = () => { throw new Error("boom"); };
  const r = await handle(req("GET", "/v1/capabilities"), ENV);
  assert.equal(r.status, 502);
  assert.equal((await r.json()).code, "proxy_upstream_unreachable");
});

test("upstream redirects are not followed", async () => {
  await handle(req("GET", "/v1/capabilities"), ENV);
  assert.equal(calls[0].init.redirect, "manual");
});

test("tokensMatch: equal only when identical", async () => {
  assert.equal(await tokensMatch("abc", "abc"), true);
  assert.equal(await tokensMatch("abc", "abd"), false);
  assert.equal(await tokensMatch("abc", "abcd"), false);
  assert.equal(await tokensMatch("", ""), true);
  assert.equal(await tokensMatch(null, "abc"), false);
});

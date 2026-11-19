// Relay API proxy for the wallet, as a Cloudflare Worker. Same idea as worker.js (Across) but for Relay:
// Relay requires an API key on /quote/v2 (since Oct 2, 2026) and says to keep it server-side, so the key lives here.
//
// Forwards exactly two calls and adds the key itself (as the x-api-key header):
//   POST /quote/v2                              -> {RELAY_BASE}/quote/v2
//   GET  /intents/status/v3?requestId=0x<64hex> -> {RELAY_BASE}/intents/status/v3?requestId=...
// Everything else is a 404. The app must send X-App-Token (a separate, revocable secret, not the Relay key).
// RELAY_BASE is optional and defaults to the TESTNET API; set it to https://api.relay.link only for mainnet.

const ALLOWED_BASES = ["https://api.testnets.relay.link", "https://api.relay.link"];
const DEFAULT_BASE = "https://api.testnets.relay.link";
const MAX_BODY_BYTES = 8 * 1024;
const UPSTREAM_TIMEOUT_MS = 12000;
const REQUEST_ID = /^0x[0-9a-fA-F]{64}$/;

const JSON_HEADERS = { "Content-Type": "application/json", "Cache-Control": "no-store" };

function fail(status, code, message) {
  return new Response(JSON.stringify({ code, message, details: null }), { status, headers: JSON_HEADERS });
}

/** Maps a request to an upstream call, or null if it is not one of the two allowed calls. */
export function route(method, pathname) {
  if (method === "POST" && pathname === "/quote/v2") return { name: "quote", path: "/quote/v2" };
  if (method === "GET" && pathname === "/intents/status/v3") return { name: "status", path: "/intents/status/v3" };
  return null;
}

async function sha256(text) {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text)));
}

export async function tokensMatch(given, expected) {
  const a = await sha256(given || "");
  const b = await sha256(expected || "");
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}

async function readLimitedBody(request) {
  const declared = Number(request.headers.get("content-length") || "0");
  if (declared > MAX_BODY_BYTES) return { tooLarge: true };
  const buf = await request.arrayBuffer();
  if (buf.byteLength > MAX_BODY_BYTES) return { tooLarge: true };
  return { text: new TextDecoder().decode(buf) };
}

export async function handle(request, env) {
  if (!env || !env.RELAY_API_KEY || !env.APP_TOKEN) {
    return fail(500, "proxy_not_configured", "The proxy is missing its RELAY_API_KEY or APP_TOKEN secret.");
  }
  const base = (env.RELAY_BASE || DEFAULT_BASE).replace(/\/+$/, "");
  if (!ALLOWED_BASES.includes(base)) return fail(500, "proxy_not_configured", "RELAY_BASE is not an allowed Relay API URL.");

  const url = new URL(request.url);
  const target = route(request.method, url.pathname);
  if (!target) return fail(404, "proxy_not_found", "Not found.");

  if (!(await tokensMatch(request.headers.get("x-app-token"), env.APP_TOKEN))) {
    return fail(401, "proxy_unauthorized", "Missing or invalid app token.");
  }

  let upstreamUrl = base + target.path;
  const headers = { "x-api-key": env.RELAY_API_KEY, Accept: "application/json" };
  let body;
  if (target.name === "status") {
    const requestId = url.searchParams.get("requestId") || "";
    if (!REQUEST_ID.test(requestId)) return fail(400, "proxy_bad_request", "requestId must be 0x followed by 64 hex characters.");
    upstreamUrl += "?requestId=" + requestId; // only this parameter is forwarded
  } else {
    const ct = (request.headers.get("content-type") || "").toLowerCase();
    if (!ct.startsWith("application/json")) return fail(415, "proxy_bad_request", "Send application/json.");
    const read = await readLimitedBody(request);
    if (read.tooLarge) return fail(413, "proxy_bad_request", "Request body too large.");
    try {
      const parsed = JSON.parse(read.text);
      if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error("not an object");
    } catch (e) {
      return fail(400, "proxy_bad_request", "Body must be a JSON object.");
    }
    body = read.text;
    headers["Content-Type"] = "application/json";
  }

  let upstream;
  try {
    upstream = await fetch(upstreamUrl, {
      method: request.method,
      headers,
      body,
      redirect: "manual",
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
    });
  } catch (e) {
    return fail(502, "proxy_upstream_unreachable", "Could not reach the Relay API.");
  }
  return new Response(await upstream.text(), { status: upstream.status, headers: JSON_HEADERS });
}

export default { fetch: handle };

// Across API proxy for the wallet, as a Cloudflare Worker.
//
// Why it exists: the Across API needs a secret API key, and Across says to keep it server-side. An Android app cannot
// hold a secret, so the key lives here (as a Worker secret) and the app talks to this proxy instead.
//
// What it does, and nothing else:
//   1. Requires the app's token in the X-App-Token header (a separate, revocable secret: NOT the Across key).
//   2. Forwards exactly three calls to https://api.across.to, adding the Across key itself:
//        GET  /v1/capabilities
//        POST /v1/quotes
//        GET  /v1/transfers/{orderId}
//      Everything else (executions, webhooks, listing transfers, any other path or method) is a 404.
//   3. Passes Across's status and JSON body back unchanged, so the app sees Across's real error codes.
// It never logs request bodies (they contain wallet addresses) and never sends the Across key to the client.

const UPSTREAM = "https://api.across.to";
const MAX_BODY_BYTES = 8 * 1024;
const MAX_QUERY_CHARS = 2000;
const UPSTREAM_TIMEOUT_MS = 12000;
const ORDER_ID = /^0x[0-9a-fA-F]{64}$/;

const JSON_HEADERS = { "Content-Type": "application/json", "Cache-Control": "no-store" };

function fail(status, code, message) {
  return new Response(JSON.stringify({ code, message, details: null }), { status, headers: JSON_HEADERS });
}

/** Maps a request to an upstream path, or null if it is not one of the three allowed calls. */
export function route(method, pathname) {
  if (method === "GET" && pathname === "/v1/capabilities") return { path: "/v1/capabilities", query: true, body: false };
  if (method === "POST" && pathname === "/v1/quotes") return { path: "/v1/quotes", query: false, body: true };
  if (method === "GET" && pathname.startsWith("/v1/transfers/")) {
    const id = pathname.slice("/v1/transfers/".length);
    if (ORDER_ID.test(id)) return { path: "/v1/transfers/" + id, query: false, body: false };
  }
  return null;
}

async function sha256(text) {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text)));
}

/** Compares two secrets without an early exit, by comparing their SHA-256 digests. */
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
  if (!env || !env.ACROSS_API_KEY || !env.APP_TOKEN) {
    return fail(500, "proxy_not_configured", "The proxy is missing its ACROSS_API_KEY or APP_TOKEN secret.");
  }
  const url = new URL(request.url);
  const target = route(request.method, url.pathname);
  // Unknown things look the same as missing things, whether or not the caller has a token.
  if (!target) return fail(404, "proxy_not_found", "Not found.");

  if (!(await tokensMatch(request.headers.get("x-app-token"), env.APP_TOKEN))) {
    return fail(401, "proxy_unauthorized", "Missing or invalid app token.");
  }

  let upstreamUrl = UPSTREAM + target.path;
  if (target.query && url.search) {
    if (url.search.length > MAX_QUERY_CHARS) return fail(400, "proxy_bad_request", "Query string too long.");
    upstreamUrl += url.search;
  }

  const headers = { Authorization: "Bearer " + env.ACROSS_API_KEY, Accept: "application/json" };
  let body;
  if (target.body) {
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
    body = read.text; // forwarded exactly as received
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
    return fail(502, "proxy_upstream_unreachable", "Could not reach the Across API.");
  }
  // Pass the status and body through unchanged; nothing else from the upstream response is forwarded.
  return new Response(await upstream.text(), { status: upstream.status, headers: JSON_HEADERS });
}

export default { fetch: handle };

# Across proxy

The Across API needs a secret API key and Across says to keep it server-side. An Android app cannot keep a secret, so the key
lives in this small Cloudflare Worker and the app talks to the Worker instead. **The Across key is never in the app or in GitHub.**

## What it does
Forwards exactly three calls to `https://api.across.to` and adds the key itself:

| App calls | Forwarded as |
|---|---|
| `GET /v1/capabilities?...` | same |
| `POST /v1/quotes` (JSON object, max 8 KB) | same |
| `GET /v1/transfers/{orderId}` (full 0x + 64 hex id) | same |

Everything else is a 404, including executions, webhooks and listing transfers. Every call needs the header `X-App-Token`
(a second, separate secret that only lets someone reach these three calls through this proxy; it is revocable). Across's status
and JSON body are passed back unchanged. Request bodies are never logged. Client headers are not forwarded.

## Deploy (browser only, no tools)
1. Request an Across API key: form under "Get your API key" at https://docs.across.to/introduction. Approval is manual and can take a while.
2. Make an app token. In Termux: `head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n'` and keep the output.
3. Cloudflare dashboard (free plan is fine) -> Workers & Pages -> Create -> Worker -> name it e.g. `wallet-across-proxy` -> Deploy
   -> Edit code -> replace everything with the contents of `proxy/worker.js` -> Deploy.
4. Worker -> Settings -> Variables and Secrets -> add two **secrets**: `ACROSS_API_KEY` (from Across) and `APP_TOKEN` (from step 2). Deploy again.
5. Check it, in Termux (replace the values; the Across key is not needed here):
   ```
   export PROXY=https://wallet-across-proxy.YOURNAME.workers.dev
   export APP_TOKEN=the_token_from_step_2
   curl -s -H "X-App-Token: $APP_TOKEN" "$PROXY/v1/capabilities?originChainId=42161&destinationChainId=8453&include=chains"
   ```
   Wrong or missing token -> `proxy_unauthorized`. Unknown path -> `proxy_not_found`. A real answer means the key works.
6. GitHub repo -> Settings -> Secrets and variables -> Actions: add **variable** `ACROSS_PROXY_URL` (the https URL, no trailing path) and **secret**
   `ACROSS_PROXY_TOKEN` (same value as `APP_TOKEN`). The next build embeds them. With neither set, the bridge simply stays off.

## Test a quote through the proxy (prices only, sends nothing)
```
curl -s -X POST "$PROXY/v1/quotes" -H "X-App-Token: $APP_TOKEN" -H "Content-Type: application/json" \
 -d '{"origin":{"token":{"chainId":42161,"address":"0xaf88d065e77c8cC2239327C5EDb3A432268e5831"},"amount":"2000000","depositor":"0xYOUR_ADDRESS"},"destination":{"token":{"chainId":8453,"address":"0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913"},"recipient":"0xYOUR_ADDRESS"},"amountType":"exact_input","capabilities":{"operations":["approve_token:evm","send_transaction:evm"]},"authorizationPolicy":{"allowedTypes":["approval"],"approvalAmount":"exact"}}'
```

## Limits you should know about
- The app token is inside the APK, so someone with the APK can extract it. It can only reach these three calls, can be rotated at any
  time (change `APP_TOKEN` in Cloudflare and `ACROSS_PROXY_TOKEN` in GitHub, rebuild), and is not the Across key. If the GitHub repo is
  **public**, anyone signed in to GitHub can download the debug APK artifact: keep the repo private, or treat the token as public and rotate it.
- Add a Cloudflare rate-limiting rule for the Worker's URL if your plan offers one, so a leaked token cannot burn through your Across quota.
- The proxy operator (you) can see wallet addresses and amounts in transit, as Across does.

## Tests
`cd proxy && node --test worker.test.mjs` (Node 20+, no dependencies; Across is faked).

## Relay proxy (testnet bridge, work in progress)
`relay-worker.js` is a second Worker, for the Relay API (Relay needs a key on `/quote/v2` since Oct 2, 2026; keys are self-serve in
dashboard.relay.link, no review). It forwards only `POST /quote/v2` and `GET /intents/status/v3?requestId=0x...`, adds the key as
`x-api-key`, defaults to the TESTNET API (`RELAY_BASE` selects mainnet), and needs `X-App-Token` like the Across one. Secrets:
`RELAY_API_KEY` and `APP_TOKEN`. Tests: `node --test relay-worker.test.mjs`. The app currently calls Relay's testnet API directly without a key (it worked keyless when the quote was captured), so this Worker is only needed if Relay starts requiring a key there; the app side for using it is not wired in.

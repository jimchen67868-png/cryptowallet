#!/bin/sh
# Probes the TRAIN Station API (sandbox host found by capture_train.sh) WITHOUT any key, and reads the testnet web app for request shapes.
# Read-only GET requests only. Never sends funds, never needs or prints a key.  Run:  sh tools/capture_train_api.sh 2>&1 | tee train_api.txt
set -u
API="${1:-https://train-solver-station.dev.lb.layerswap.cloud}"
TMP="${TMPDIR:-/tmp}/train_api"
mkdir -p "$TMP"

probe() {
  path="$1"
  code=$(curl -sS -m 20 -o "$TMP/body.txt" -w '%{http_code}' -H 'Accept: application/json' "$API$path" 2>"$TMP/err.txt" || true)
  echo "--- GET $path -> HTTP ${code:-none} ($(wc -c < "$TMP/body.txt" 2>/dev/null || echo 0) bytes)"
  head -c 600 "$TMP/body.txt" 2>/dev/null; echo
  [ -s "$TMP/err.txt" ] && head -c 200 "$TMP/err.txt"
}

echo "== API host: $API"
for p in / /health /networks /api/v1/networks /api/networks /v1/networks /api/v1/routes /api/v1/orders/ \
         /swagger/index.html /swagger/v1/swagger.json /openapi.json /api/v1/openapi.json /docs; do
  probe "$p"
done

echo; echo "== if an OpenAPI/Swagger file exists, list its paths"
for p in /swagger/v1/swagger.json /openapi.json /api/v1/openapi.json; do
  curl -sS -m 20 "$API$p" -o "$TMP/spec.json" 2>/dev/null || continue
  if grep -q '"paths"' "$TMP/spec.json" 2>/dev/null; then
    echo "spec at $p ($(wc -c < "$TMP/spec.json") bytes), paths:"
    grep -oE '"/[A-Za-z0-9_./{}-]+"[[:space:]]*:[[:space:]]*\{' "$TMP/spec.json" | sort -u | head -60
    cp "$TMP/spec.json" ./train_openapi.json && echo "saved ./train_openapi.json (paste it if small, or tell me)"
    break
  fi
done

echo; echo "== testnet web app: API host and request shapes"
T="https://testnet.train.tech"
curl -fsSL -m 30 "$T" -o "$TMP/t_index.html" 2>/dev/null || echo "could not fetch $T"
grep -oE '/_next/static/[^"'"'"' ]+\.js' "$TMP/t_index.html" 2>/dev/null | sort -u > "$TMP/t_chunks.txt"
: > "$TMP/t_all.js"
while read -r p; do curl -fsSL -m 30 "$T$p" >> "$TMP/t_all.js" 2>/dev/null || true; done < "$TMP/t_chunks.txt"
echo "chunks: $(wc -l < "$TMP/t_chunks.txt")"
echo "-- hosts:"
grep -oE 'https?://[A-Za-z0-9._-]+(:[0-9]+)?(/[A-Za-z0-9._/-]*)?' "$TMP/t_all.js" | grep -iE 'train|station|solver|layerswap' | sort -u | head -20
echo "-- context around the API calls (trimmed):"
for key in '/api/v1/orders' '/reveal-secret' '/networks' '/quote' 'X-API-Key' 'x-api-key' 'Authorization'; do
  echo "[$key]"
  grep -oE ".{0,160}$key.{0,220}" "$TMP/t_all.js" | head -4
done
echo "== done"

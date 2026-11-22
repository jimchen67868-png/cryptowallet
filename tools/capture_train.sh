#!/bin/sh
# Finds the TRAIN Station API address and endpoint names in the public web app (app.train.tech).
# Run in Termux:  sh tools/capture_train.sh   then paste the output back.
# It only reads public files. It never needs or prints any key.
set -eu
BASE="${1:-https://app.train.tech}"
TMP="${TMPDIR:-/tmp}/train_capture"
mkdir -p "$TMP"
echo "== fetching $BASE"
curl -fsSL "$BASE" -o "$TMP/index.html"
grep -oE '/_next/static/[^"'"'"' ]+\.js' "$TMP/index.html" | sort -u > "$TMP/chunks.txt" || true
echo "== $(wc -l < "$TMP/chunks.txt") script chunks"
: > "$TMP/all.js"
while read -r p; do curl -fsSL "$BASE$p" >> "$TMP/all.js" 2>/dev/null || true; done < "$TMP/chunks.txt"
echo "== candidate API hosts (train / station / sandbox)"
grep -oE 'https?://[A-Za-z0-9._-]+(:[0-9]+)?(/[A-Za-z0-9._/-]*)?' "$TMP/all.js" | grep -iE 'train|station|sandbox|solver' | sort -u | head -40
echo "== environment markers"
grep -oE 'NEXT_PUBLIC_[A-Z_]+' "$TMP/all.js" | sort -u | head -20
echo "== endpoint-looking strings"
grep -oE '"/?(api/)?(v[0-9]+/)?(quote|routes?|networks?|swaps?|orders?|reveal|secret|solver|status)[A-Za-z0-9/_{}-]*"' "$TMP/all.js" | sort -u | head -40
echo "== done (files in $TMP)"

#!/bin/sh
# Collects LI.FI quotes for every route the wallet should support, so the guard can pin each network's contracts.
# QUOTES ONLY: nothing is signed or sent, no balance needed. Needs curl (and jq for the printed summary: pkg install curl jq zip).
# Usage: sh tools/capture_lifi_all.sh 0xYourEvmAddress      then upload ~/storage/downloads/lifi_captures.zip to the chat.
ADDR=${1:-$ADDR}
if [ -z "$ADDR" ]; then echo "usage: sh tools/capture_lifi_all.sh 0xYourEvmAddress"; exit 1; fi
OUT="$HOME/storage/downloads/lifi_captures"
mkdir -p "$OUT"
NATIVE=0x0000000000000000000000000000000000000000
ETH_USDC=0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48
BASE_USDC=0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913
ARB_USDC=0xaf88d065e77c8cC2239327C5EDb3A432268e5831
POL_USDC=0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359
BNB_USDC=0x8AC76a51cc950d9822D68b83fE1Ad97B32Cd580d   # 18 decimals on BNB Chain
USDC2=2000000                    # 2 USDC (6 decimals)
BNB2=2000000000000000000         # 2 USDC on BNB Chain (18 decimals)
ETH_SMALL=1000000000000000       # 0.001 native coin

q() { # tag fromChain toChain fromToken toToken amount [across|any]
  tag=$1; mode=${7:-across}
  url="https://li.quest/v1/quote?fromChain=$2&toChain=$3&fromToken=$4&toToken=$5&fromAmount=$6&fromAddress=$ADDR&toAddress=$ADDR"
  [ "$mode" = any ] || url="$url&allowBridges=across"
  curl -s -m 60 "$url" > "$OUT/$tag.json"
  printf '%s: ' "$tag"
  if command -v jq >/dev/null 2>&1; then
    jq -c '{tool, to: .transactionRequest.to, value: .transactionRequest.value, selector: ((.transactionRequest.data // "") | .[0:10]), approval: .estimate.approvalAddress, message}' "$OUT/$tag.json" 2>/dev/null | cut -c1-260
  else
    head -c 200 "$OUT/$tag.json"; echo
  fi
  sleep 2
}

echo "== tokens (Across only: what the guard accepts today)"
q eth_to_arb_usdc      1     42161 $ETH_USDC  $ARB_USDC  $USDC2
q base_to_arb_usdc     8453  42161 $BASE_USDC $ARB_USDC  $USDC2
q pol_to_arb_usdc      137   42161 $POL_USDC  $ARB_USDC  $USDC2
q arb_to_eth_usdc      42161 1     $ARB_USDC  $ETH_USDC  $USDC2
q arb_to_pol_usdc      42161 137   $ARB_USDC  $POL_USDC  $USDC2
echo "== BNB Chain"
q bnb_to_arb_usdc      56    42161 $BNB_USDC  $ARB_USDC  $BNB2
q arb_to_bnb_usdc      42161 56    $ARB_USDC  $BNB_USDC  $USDC2
q bnb_to_arb_usdc_any  56    42161 $BNB_USDC  $ARB_USDC  $BNB2 any   # which bridge would LI.FI use without the Across filter?
echo "== native coin (ETH)"
q eth_to_base_native   1     8453  $NATIVE    $NATIVE    $ETH_SMALL
q base_to_eth_native   8453  1     $NATIVE    $NATIVE    $ETH_SMALL
q arb_to_eth_native    42161 1     $NATIVE    $NATIVE    $ETH_SMALL
q eth_to_arb_native    1     42161 $NATIVE    $NATIVE    $ETH_SMALL
echo "== native coin on BNB / Polygon (any bridge, for information)"
q bnb_native_to_eth_any 56   1     $NATIVE    $NATIVE    $ETH_SMALL any
q pol_native_to_eth_any 137  1     $NATIVE    $NATIVE    $ETH_SMALL any

echo
cd "$HOME/storage/downloads" 2>/dev/null || exit 0
rm -f lifi_captures.zip
if command -v zip >/dev/null 2>&1; then
  zip -q lifi_captures.zip lifi_captures/*.json && echo "Upload this file to the chat: ~/storage/downloads/lifi_captures.zip"
else
  echo "zip is missing (pkg install zip), or upload the files in ~/storage/downloads/lifi_captures/ one by one."
fi

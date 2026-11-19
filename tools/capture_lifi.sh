#!/bin/sh
# Captures a real LI.FI quote so its calldata can be checked and its contracts pinned in the wallet (LiFiPins).
# Prices only: nothing is signed or sent. Needs curl and jq (pkg install curl jq).
# Usage: sh capture_lifi.sh FROM_CHAIN_ID TO_CHAIN_ID FROM_TOKEN TO_TOKEN AMOUNT_RAW [YOUR_ADDRESS]
#   e.g. sh capture_lifi.sh 1 42161 0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48 0xaf88d065e77c8cC2239327C5EDb3A432268e5831 2000000 0xYou
# Native coin: use 0x0000000000000000000000000000000000000000 as the token.
set -e
FROM_CHAIN=$1; TO_CHAIN=$2; FROM_TOKEN=$3; TO_TOKEN=$4; AMOUNT=$5; ADDR=${6:-$ADDR}
if [ -z "$ADDR" ] || [ -z "$AMOUNT" ]; then echo "usage: sh capture_lifi.sh FROM_CHAIN TO_CHAIN FROM_TOKEN TO_TOKEN AMOUNT [ADDRESS]"; exit 1; fi
OUT="$HOME/storage/downloads/lifi_${FROM_CHAIN}_to_${TO_CHAIN}.json"
curl -s "https://li.quest/v1/quote?fromChain=$FROM_CHAIN&toChain=$TO_CHAIN&fromToken=$FROM_TOKEN&toToken=$TO_TOKEN&fromAmount=$AMOUNT&fromAddress=$ADDR&toAddress=$ADDR&allowBridges=across" > "$OUT"
echo "full response saved to $OUT"
jq -c '{tool, from: .action.fromToken.symbol, fromAmount: .action.fromAmount, toAmount: .estimate.toAmount, toAmountMin: .estimate.toAmountMin, approval: .estimate.approvalAddress, tx: .transactionRequest, message: .message}' "$OUT"

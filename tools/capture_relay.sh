#!/bin/sh
# Captures a real Relay testnet quote so its transaction can be checked and its contract pinned in the wallet.
# Prices only: nothing is signed or sent. Needs curl and jq (pkg install curl jq).
# Usage: sh capture_relay.sh [ORIGIN_CHAIN_ID] [DEST_CHAIN_ID] [AMOUNT_WEI] [YOUR_ADDRESS]
#   defaults: Sepolia (11155111) -> Base Sepolia (84532), 0.001 ETH. Set ADDR=0xYou or pass it as the 4th argument.
# Optional: export RELAY_API_KEY=... (from dashboard.relay.link). Try without it first; Relay may answer 401 without one.
# Optional: RELAY_BASE (default https://api.testnets.relay.link).
ORIGIN=${1:-11155111}; DEST=${2:-84532}; AMOUNT=${3:-1000000000000000}; ADDR=${4:-$ADDR}
BASE=${RELAY_BASE:-https://api.testnets.relay.link}
if [ -z "$ADDR" ]; then echo "set ADDR=0xYourAddress or pass it as the 4th argument"; exit 1; fi
OUT="$HOME/storage/downloads/relay_${ORIGIN}_to_${DEST}.json"
BODY="{\"user\":\"$ADDR\",\"originChainId\":$ORIGIN,\"destinationChainId\":$DEST,\"originCurrency\":\"0x0000000000000000000000000000000000000000\",\"destinationCurrency\":\"0x0000000000000000000000000000000000000000\",\"amount\":\"$AMOUNT\",\"tradeType\":\"EXACT_INPUT\"}"
if [ -n "$RELAY_API_KEY" ]; then
  CODE=$(curl -s -o "$OUT" -w "%{http_code}" -X POST "$BASE/quote/v2" -H "Content-Type: application/json" -H "x-api-key: $RELAY_API_KEY" -d "$BODY")
else
  CODE=$(curl -s -o "$OUT" -w "%{http_code}" -X POST "$BASE/quote/v2" -H "Content-Type: application/json" -d "$BODY")
fi
echo "HTTP $CODE, full response saved to $OUT"
jq -c '{message, errorCode, steps: [.steps[]? | {id, kind, requestId, items: [.items[]? | {status, data, check}]}], details: {operation: .details.operation, sender: .details.sender, recipient: .details.recipient, in: .details.currencyIn.amount, out: .details.currencyOut.amount, minOut: .details.currencyOut.minimumAmount}}' "$OUT" 2>/dev/null || head -c 600 "$OUT"

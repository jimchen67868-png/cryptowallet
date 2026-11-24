"""Independent Python port of LiFiGuard's rules, run over captured LI.FI quotes (tools/capture_lifi_all.sh output).
Usage: python3 lifi_guard_check.py DIR_WITH_JSON  [user_address]
Prints PASS / REFUSED(reason) per file. Used to confirm the pins and the native-coin rules before they go into Kotlin."""
import json, sys, glob, os
import lifi_decode as d

DIAMOND = "0x1231deb6f5749ef6ce6943a275a1d3e7486f4eae"
FEE_CONTRACT = "0xde2b920506ee931a6a2abdf566526220214fbbae"
FEE_RECIPIENT = "0xc06ebbefd94032b85424d51906e2a335efae264b"
PINNED = {1, 8453, 137, 42161, 56}
WRAPPED = {1: "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2",
           8453: "0x4200000000000000000000000000000000000006",
           42161: "0x82af49447d8a07e3bd95bd0d56f35241523fbab1"}
ZERO = "0x" + "0" * 40

def addr(v): return "0x" + format(v, "x").rjust(40, "0")

def check(f, user, now):
    tr = f["transactionRequest"]; act = f["action"]; est = f["estimate"]
    fc, tc = int(act["fromChainId"]), int(act["toChainId"])
    frm = act["fromToken"]["address"].lower(); to = act["toToken"]["address"].lower()
    amount = int(act["fromAmount"]); native = frm == ZERO
    if fc not in PINNED: return "origin not pinned"
    if native and (fc not in WRAPPED or tc not in WRAPPED): return "native not enabled for this route"
    if f["tool"] != "across": return "tool " + f["tool"]
    if tr["to"].lower() != DIAMOND: return "to"
    if est["approvalAddress"].lower() != DIAMOND: return "approval"
    if int(tr["chainId"]) != fc: return "chainId"
    if tr["from"].lower() != user.lower(): return "from"
    value = int(tr["value"], 16)
    if native and value != amount: return "native value != amount"
    if not native and value != 0: return "token value != 0"
    sel, b, swaps, a = d.decode(tr["data"])
    if b["bridge"] != "across": return "bridge"
    if b["receiver"].lower() != user.lower(): return "receiver"
    if b["sendingAssetId"].lower() != frm: return "sendingAsset"
    if int(b["destinationChainId"]) != tc: return "dest chain"
    if b["hasDestinationCall"]: return "destCall"
    fee = 0
    if b["hasSourceSwaps"]:
        if len(swaps) != 1: return "swaps"
        s = swaps[0]
        if s["callTo"].lower() != FEE_CONTRACT or s["approveTo"].lower() != FEE_CONTRACT: return "fee contract"
        if s["sendingAssetId"].lower() != frm or s["receivingAssetId"].lower() != frm: return "fee step asset"
        if int(s["fromAmount"]) != amount: return "fee step amount"
        cd = s["callData"]
        if native:
            # 0e8ae67f(FeeDistribution[]): offset 0x20, length 1, (recipient, amount)
            if not (len(cd) == 8 + 4 * 64 and cd.startswith("0e8ae67f")): return "native fee call format"
            w = [int(cd[8 + i * 64: 8 + (i + 1) * 64], 16) for i in range(4)]
            if w[0] != 0x20 or w[1] != 1 or w[2].bit_length() > 160: return "native fee call words"
            recipient, fee = addr(w[2]), w[3]
        else:
            if not (len(cd) == 8 + 5 * 64 and cd.startswith("332d746b")): return "fee call format"
            w = [int(cd[8 + i * 64: 8 + (i + 1) * 64], 16) for i in range(5)]
            if w[1] != 0x40 or w[2] != 1: return "fee call words"
            if addr(w[0]) != frm: return "fee token"
            recipient, fee = addr(w[3]), w[4]
        if recipient != FEE_RECIPIENT: return "fee recipient"
        if fee * 10000 > amount * 100: return "fee too high"
    elif swaps: return "extra steps"
    if int(b["minAmount"]) != amount - fee: return "minAmount"
    wa = [d.u(x) for x in a["words"]]
    if addr(wa[0]) != user.lower() or addr(wa[1]) != user.lower(): return "recipient/refund"
    exp_in = WRAPPED[fc] if native else frm
    exp_out = WRAPPED[tc] if native else to
    if addr(wa[2]) != exp_in: return "input token " + addr(wa[2])
    if addr(wa[3]) != exp_out: return "output token " + addr(wa[3])
    to_amount = int(est["toAmount"]); to_min = int(est["toAmountMin"])
    if wa[4] != to_amount: return "output amount != quote"
    # decimals of the two tokens: outputAmountPercent = output/input in RAW units * 1e18, so it is scaled by the difference
    fd, td = int(act["fromToken"]["decimals"]), int(act["toToken"]["decimals"])
    s_in, s_out = 10 ** fd, 10 ** td
    percent = wa[5] * s_in // s_out
    if wa[5] <= 0 or percent > 10 ** 18 or percent < 9 * 10 ** 17: return f"output percent out of range ({percent})"
    min_amount = int(b["minAmount"])
    if to_amount <= 0 or to_amount * s_in > min_amount * s_out: return "toAmount inconsistent"
    if to_min <= 0 or to_min > to_amount: return "toAmountMin inconsistent"
    if to_min * s_in * 100 < amount * 95 * s_out: return "fees over 5%"
    return None

if __name__ == "__main__":
    src = sys.argv[1]
    user = sys.argv[2] if len(sys.argv) > 2 else "0xa3a0fccdf214159ce35d4a0066bb32fe10733d67"
    for p in sorted(glob.glob(os.path.join(src, "*.json"))):
        n = os.path.basename(p)[:-5]
        f = json.load(open(p))
        if "transactionRequest" not in f:
            print(f"{n:28s} NO QUOTE ({f.get('message')})"); continue
        try:
            r = check(f, user, 0)
        except Exception as e:
            r = f"decode error: {type(e).__name__} {e}"
        print(f"{n:28s} {'PASS' if r is None else 'REFUSED: ' + r}")

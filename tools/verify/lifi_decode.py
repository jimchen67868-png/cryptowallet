"""Independent decoder for LI.FI Diamond calldata (swapAndStartBridgeTokensViaAcrossV4, selector 0x1794958f), used to
cross-check the Kotlin guard. Usage: python3 lifi_decode.py [fixture.json]"""
import json, sys
from ref import keccak256

def words(h): return [h[i*64:(i+1)*64] for i in range(len(h)//64)]
def u(w): return int(w, 16)
def addr(w): return "0x" + w[24:]

def decode(data_hex):
    h = data_hex[2:]
    sel, body = h[:8], h[8:]
    w = words(body)
    off_bridge, off_swaps, off_across = u(w[0]) // 32, u(w[1]) // 32, u(w[2]) // 32
    b = w[off_bridge:]
    bridge = dict(transactionId=b[0], referrer=addr(b[3]), sendingAssetId=addr(b[4]), receiver=addr(b[5]),
                  minAmount=u(b[6]), destinationChainId=u(b[7]), hasSourceSwaps=u(b[8]) == 1, hasDestinationCall=u(b[9]) == 1)
    bridge["bridge"] = bytes.fromhex(w[off_bridge + u(b[1]) // 32 + 1][: 2 * u(w[off_bridge + u(b[1]) // 32])]).decode()
    bridge["integrator"] = bytes.fromhex(w[off_bridge + u(b[2]) // 32 + 1][: 2 * u(w[off_bridge + u(b[2]) // 32])]).decode()
    s = w[off_swaps:]
    n = u(s[0])
    swaps = []
    for i in range(n):
        base = u(s[1 + i]) // 32 + 1            # element offsets are relative to the start of the array content
        e = s[base:]
        cd_off = u(e[5]) // 32
        cd_len = u(e[cd_off])
        cd = "".join(e[cd_off + 1: cd_off + 1 + (cd_len + 31) // 32])[: cd_len * 2]
        swaps.append(dict(callTo=addr(e[0]), approveTo=addr(e[1]), sendingAssetId=addr(e[2]), receivingAssetId=addr(e[3]),
                          fromAmount=u(e[4]), requiresDeposit=u(e[6]) == 1, callData=cd))
    a = w[off_across:]
    across = dict(words=[x for x in a[:11]], messageLength=u(a[u(a[10]) // 32]), totalWords=len(w))
    return sel, bridge, swaps, across

def decode_fee_call(cd):
    # 0x332d746b(address token, FeeDistribution[] (recipient, amount))
    sel, rest = cd[:8], cd[8:]
    ww = words(rest)
    return sel, addr(ww[0]), u(ww[2]), [(addr(ww[3 + 2*i]), u(ww[4 + 2*i])) for i in range(u(ww[2]))]

if __name__ == "__main__":
    f = json.load(open(sys.argv[1] if len(sys.argv) > 1 else "lifi_fixture_arb_base_usdc.json"))
    sel, bridge, swaps, across = decode(f["transactionRequest"]["data"])
    print("selector", sel); print("bridgeData", json.dumps(bridge, indent=1))
    for s in swaps:
        print("swap", {k: v for k, v in s.items() if k != "callData"}); print(" feeCall", decode_fee_call(s["callData"]))
    print("across words:")
    for i, x in enumerate(across["words"]):
        v = u(x); print(" ", i, "addr " + addr(x) if v >= 2**144 else v)
    print("message length", across["messageLength"], "total payload words", across["totalWords"])

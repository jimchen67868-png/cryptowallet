"""Independent ABI encoder for the Chainlink CCIP router calls the wallet builds itself (token-only transfer, native fee).
  getFee(uint64,(bytes,bytes,(address,uint256)[],address,bytes))    ccipSend(uint64,(bytes,bytes,(address,uint256)[],address,bytes))
Struct order is from Chainlink's Client.sol docs: EVM2AnyMessage { receiver, data, tokenAmounts, feeToken, extraArgs }.
Offsets are computed generically here (not hardcoded) so they cross-check the Kotlin encoder."""
from ref import keccak256
from uniswap_v2_golden import to_checksum

def sel(sig): return keccak256(sig.encode())[:4]
def w_int(v): return v.to_bytes(32, 'big')
def w_addr(a): return bytes(12) + bytes.fromhex(a[2:])
def pad32(b): return b + bytes((-len(b)) % 32)
def enc_bytes(b): return w_int(len(b)) + pad32(b)              # dynamic bytes tail

def extra_args_v2(gas_limit, out_of_order):                    # GenericExtraArgsV2: tag + abi.encode(uint256,bool)
    return bytes.fromhex("181dcf10") + w_int(gas_limit) + w_int(1 if out_of_order else 0)

def message(receiver, token, amount, fee_token="0x" + "00" * 20, gas_limit=0, ooo=True):
    tails = [enc_bytes(w_addr(receiver)),                      # receiver = abi.encode(address)
             enc_bytes(b""),                                   # data
             w_int(1) + w_addr(token) + w_int(amount),         # tokenAmounts: length 1, one static (address,uint256)
             enc_bytes(extra_args_v2(gas_limit, ooo))]         # extraArgs
    head_size = 5 * 32
    offs, pos = [], head_size
    for t in tails: offs.append(pos); pos += len(t)
    head = w_int(offs[0]) + w_int(offs[1]) + w_int(offs[2]) + w_addr(fee_token) + w_int(offs[3])
    return head + b"".join(tails)

def call(signature, dest_selector, receiver, token, amount, **kw):
    return sel(signature) + w_int(dest_selector) + w_int(0x40) + message(receiver, token, amount, **kw)

SIG = "(bytes,bytes,(address,uint256)[],address,bytes)"
GET_FEE = "getFee(uint64," + SIG + ")"; CCIP_SEND = "ccipSend(uint64," + SIG + ")"

SEPOLIA = dict(router="0x0BF3dE8c5D3e8A2B34D2BEeB17ABfCeBaf363A59", selector=16015286601757825753, bnm="0xFd57b4ddBf88a4e07fF4e34C487b99af2Fe82a05")
BNB = dict(router="0xE1053aE1857476f36A3C62580FF9b016E8EE8F6f", selector=13264668187771770619, bnm="0xbFA2ACd33ED6EEc0ed3Cc06bF1ac38d22b36B9e9")
BASE = dict(router="0xD3b06cEbF099CE7DA4AcCf578aaebFDBd6e88a93", selector=10344971235874465080, bnm="0x88A2d74F47a237a62e7A51cdDa67270CE381555e")
ARB = dict(router="0x2a9C5afB0d0e4BAb2BCdaE109EC4b0c4Be15a165", selector=3478487238524512106, bnm="0xA8C0c11bf64AF62CDCA6f93D3769B88BdD7cb93D")
USER = "0xa3a0fccdf214159ce35d4a0066bb32fe10733d67"
AMOUNT = 10**15

if __name__ == "__main__":
    print("selectors:", {n: sel(s).hex() for n, s in [("getFee", GET_FEE), ("ccipSend", CCIP_SEND), ("drip", "drip(address)")]})
    for name, d in [("SEPOLIA", SEPOLIA), ("BNB", BNB), ("BASE", BASE), ("ARB", ARB)]:
        for k in ("router", "bnm"):
            print(name, k, d[k], "EIP55_OK" if to_checksum(d[k]) == d[k] else "EIP55_MISMATCH -> " + to_checksum(d[k]))
    print("EXTRA_ARGS_V2 0x" + extra_args_v2(0, True).hex())
    print("GETFEE_SEPOLIA_TO_BNB 0x" + call(GET_FEE, BNB["selector"], USER, SEPOLIA["bnm"], AMOUNT).hex())
    print("SEND_SEPOLIA_TO_BNB   0x" + call(CCIP_SEND, BNB["selector"], USER, SEPOLIA["bnm"], AMOUNT).hex())
    print("SEND_BNB_TO_SEPOLIA   0x" + call(CCIP_SEND, SEPOLIA["selector"], USER, BNB["bnm"], 123456789012345678).hex())
    print("DRIP 0x" + (sel("drip(address)") + w_addr(USER)).hex())
    m = message(USER, SEPOLIA["bnm"], AMOUNT)
    print("message words:", len(m) // 32, "| tuple head offsets:", [int.from_bytes(m[i*32:(i+1)*32], 'big') for i in (0, 1, 2, 4)])

    # Base Sepolia and Arbitrum Sepolia lanes. Router/selector/BnM(Base) match the truncated values in Chainlink's CCIP directory;
    # selectors are the full numbers shown there; every address passes its EIP-55 checksum.
    print("IS_CHAIN_SUPPORTED_SELECTOR", sel("isChainSupported(uint64)").hex())
    print("IS_CHAIN_SUPPORTED_BASE 0x" + (sel("isChainSupported(uint64)") + w_int(BASE["selector"])).hex())
    print("GETFEE_SEPOLIA_TO_BASE 0x" + call(GET_FEE, BASE["selector"], USER, SEPOLIA["bnm"], AMOUNT).hex())
    print("SEND_BASE_TO_ARB   0x" + call(CCIP_SEND, ARB["selector"], USER, BASE["bnm"], AMOUNT).hex())
    print("SEND_ARB_TO_SEPOLIA 0x" + call(CCIP_SEND, SEPOLIA["selector"], USER, ARB["bnm"], 123456789012345678).hex())

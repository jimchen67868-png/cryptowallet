"""Golden calldata for Across depositV3, from an independent ABI encoder (no web3 library)."""
from ref import keccak256

def sel(sig): return keccak256(sig.encode())[:4]
def w_int(v): return v.to_bytes(32, 'big')
def w_addr(a): return bytes(12) + bytes.fromhex(a[2:])

def deposit_v3(depositor, recipient, in_tok, out_tok, in_amt, out_amt, dest_chain, relayer, quote_ts, fill_deadline, excl, message=b""):
    head = [w_addr(depositor), w_addr(recipient), w_addr(in_tok), w_addr(out_tok), w_int(in_amt), w_int(out_amt),
            w_int(dest_chain), w_addr(relayer), w_int(quote_ts), w_int(fill_deadline), w_int(excl)]
    off = 32 * (len(head) + 1)                      # the bytes offset word is the 12th head word
    tail = w_int(len(message)) + message + bytes((-len(message)) % 32)
    return sel("depositV3(address,address,address,address,uint256,uint256,uint256,address,uint32,uint32,uint32,bytes)") + b''.join(head) + w_int(off) + tail

USER = "0x9858effd232b4033e47d90003d41ec34ecaeda94"
WETH_MAIN = "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2"; WETH_BASE = "0x4200000000000000000000000000000000000006"
USDC_MAIN = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"; USDC_ARB = "0xaf88d065e77c8cC2239327C5EDb3A432268e5831"
ZERO = "0x0000000000000000000000000000000000000000"
RELAYER = "0x07aE8551Be970cB1cCa11Dd7a11F47Ae82e70E67"

if __name__ == "__main__":
    # 1 ETH (as WETH) Ethereum -> Base, no exclusive relayer
    print("ETH_TO_BASE 0x" + deposit_v3(USER, USER, WETH_MAIN, WETH_BASE, 10**18, 999_500_000_000_000_000, 8453, ZERO, 1700000000, 1700021600, 0).hex())
    # 250 USDC Ethereum -> Arbitrum with an exclusive relayer and a deadline
    print("USDC_TO_ARB 0x" + deposit_v3(USER, USER, USDC_MAIN, USDC_ARB, 250_000_000, 249_100_000, 42161, RELAYER, 1700000123, 1700021723, 1700000200).hex())

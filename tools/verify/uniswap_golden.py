"""Golden calldata for the Uniswap v3 encoders, from an independent ABI encoder (no web3 library)."""
from ref import keccak256

def sel(sig): return keccak256(sig.encode())[:4]
def word_int(v): return v.to_bytes(32, 'big')
def word_addr(a): return bytes(12) + bytes.fromhex(a[2:])
def pad32(b): return b + bytes((-len(b)) % 32)

def quote_exact_input_single(tin, tout, amount, fee):
    return sel("quoteExactInputSingle((address,address,uint256,uint24,uint160))") + word_addr(tin) + word_addr(tout) + word_int(amount) + word_int(fee) + word_int(0)

def exact_input_single(tin, tout, fee, recipient, amount, min_out):
    return sel("exactInputSingle((address,address,uint24,address,uint256,uint256,uint160))") + word_addr(tin) + word_addr(tout) + word_int(fee) + word_addr(recipient) + word_int(amount) + word_int(min_out) + word_int(0)

def unwrap_weth9(min_out, recipient):
    return sel("unwrapWETH9(uint256,address)") + word_int(min_out) + word_addr(recipient)

def multicall(calls):
    # ABI: multicall(bytes[]) -> head: offset to the array; array: length, element offsets, then each element (length + padded data)
    elements = [word_int(len(c)) + pad32(c) for c in calls]
    offsets, off = [], 32 * len(calls)
    for e in elements: offsets.append(word_int(off)); off += len(e)
    return sel("multicall(bytes[])") + word_int(32) + word_int(len(calls)) + b''.join(offsets) + b''.join(elements)

WETH = "0xfFf9976782d46CC05630D1f6eBAb18b2324d6B14"; USDC = "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238"
USER = "0x9858effd232b4033e47d90003d41ec34ecaeda94"; ADDRESS_THIS = "0x0000000000000000000000000000000000000002"
ONE = 10**18; MIN = 123456789

if __name__ == "__main__":
    print("QUOTE   0x" + quote_exact_input_single(WETH, USDC, ONE, 3000).hex())
    print("EXACT   0x" + exact_input_single(WETH, USDC, 500, USER, ONE, MIN).hex())
    print("EXACT_THIS 0x" + exact_input_single(USDC, WETH, 3000, ADDRESS_THIS, 5_000_000, MIN).hex())
    print("UNWRAP  0x" + unwrap_weth9(MIN, USER).hex())
    mc = multicall([exact_input_single(USDC, WETH, 3000, ADDRESS_THIS, 5_000_000, MIN), unwrap_weth9(MIN, USER)])
    print("MULTI   0x" + mc.hex())
    print("MULTI1  0x" + multicall([unwrap_weth9(7, USER)]).hex())
    print("WITHDRAW 0x" + (sel("withdraw(uint256)") + word_int(ONE)).hex())
    print("DEPOSIT  0x" + sel("deposit()").hex())

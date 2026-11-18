"""Golden calldata for the Uniswap v2 encoders (UniswapV2Router02), from an independent ABI encoder (no web3 library)."""
from ref import keccak256

def sel(sig): return keccak256(sig.encode())[:4]
def word_int(v): return v.to_bytes(32, 'big')
def word_addr(a): return bytes(12) + bytes.fromhex(a[2:])

def addr_array(path):
    # dynamic address[]: length word, then one word per element
    return word_int(len(path)) + b''.join(word_addr(a) for a in path)

def get_amounts_out(amount_in, path):
    # head: amountIn, offset to path (2 head words = 0x40)
    return sel("getAmountsOut(uint256,address[])") + word_int(amount_in) + word_int(0x40) + addr_array(path)

def swap_exact_tokens_for_tokens(amount_in, min_out, path, to, deadline):
    # head: amountIn, amountOutMin, offset (5 head words = 0xa0), to, deadline
    return (sel("swapExactTokensForTokens(uint256,uint256,address[],address,uint256)")
            + word_int(amount_in) + word_int(min_out) + word_int(0xa0) + word_addr(to) + word_int(deadline) + addr_array(path))

def swap_exact_eth_for_tokens(min_out, path, to, deadline):
    # head: amountOutMin, offset (4 head words = 0x80), to, deadline
    return (sel("swapExactETHForTokens(uint256,address[],address,uint256)")
            + word_int(min_out) + word_int(0x80) + word_addr(to) + word_int(deadline) + addr_array(path))

def swap_exact_tokens_for_eth(amount_in, min_out, path, to, deadline):
    return (sel("swapExactTokensForETH(uint256,uint256,address[],address,uint256)")
            + word_int(amount_in) + word_int(min_out) + word_int(0xa0) + word_addr(to) + word_int(deadline) + addr_array(path))

def to_checksum(a):
    a = a.lower()[2:]; h = keccak256(a.encode()).hex()
    return "0x" + "".join(c.upper() if int(h[i], 16) >= 8 else c for i, c in enumerate(a))

ROUTER = "0xeE567Fe1712Faf6149d80dA1E6934E354124CfE3"   # Sepolia UniswapV2Router02 (docs.uniswap.org v2 deployments)
WETH = "0x7b79995e5f793a07bc00c21412e50ecae098e7f9"; USDC = "0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"; DAI = "0x68194a729c2450ad26072b3d33adacbcef39d574"
USER = "0x9858effd232b4033e47d90003d41ec34ecaeda94"
ONE = 10**18; MIN = 123456789; DEADLINE = 1700001200

if __name__ == "__main__":
    print("ROUTER_CHECKSUM_OK", to_checksum(ROUTER) == ROUTER, to_checksum(ROUTER))
    for s in ["getAmountsOut(uint256,address[])", "swapExactTokensForTokens(uint256,uint256,address[],address,uint256)",
              "swapExactETHForTokens(uint256,address[],address,uint256)", "swapExactTokensForETH(uint256,uint256,address[],address,uint256)", "WETH()"]:
        print("SEL", s, sel(s).hex())
    print("AMOUNTS_DIRECT 0x" + get_amounts_out(ONE, [WETH, USDC]).hex())
    print("AMOUNTS_HOP    0x" + get_amounts_out(5_000_000, [USDC, WETH, DAI]).hex())
    print("TOK_TOK        0x" + swap_exact_tokens_for_tokens(5_000_000, MIN, [USDC, DAI], USER, DEADLINE).hex())
    print("TOK_TOK_HOP    0x" + swap_exact_tokens_for_tokens(5_000_000, MIN, [USDC, WETH, DAI], USER, DEADLINE).hex())
    print("ETH_TOK        0x" + swap_exact_eth_for_tokens(MIN, [WETH, USDC], USER, DEADLINE).hex())
    print("TOK_ETH        0x" + swap_exact_tokens_for_eth(5_000_000, MIN, [USDC, WETH], USER, DEADLINE).hex())

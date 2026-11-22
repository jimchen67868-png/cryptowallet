"""Reference calldata for THORChain's EVM router depositWithExpiry (independent of the Kotlin/web3j encoder)."""
from ref import keccak256

SIG = "depositWithExpiry(address,address,uint256,string,uint256)"
SEL = keccak256(SIG.encode())[:4]
print("selector", SEL.hex())

def w(n): return n.to_bytes(32, 'big')
def aw(a): return bytes.fromhex(a[2:].lower()).rjust(32, b'\x00')

def deposit(vault, asset, amount, memo, expiry):
    m = memo.encode()
    pad = (-len(m)) % 32
    return SEL + aw(vault) + aw(asset) + w(amount) + w(0xa0) + w(expiry) + w(len(m)) + m + b'\x00' * pad

VAULT = "0x1111111111111111111111111111111111111111"
NATIVE = "0x0000000000000000000000000000000000000000"
MEMO = "=:BTC.BTC:bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu:4950000"
data = deposit(VAULT, NATIVE, 10**18, MEMO, 1800000900)
print("DEPOSIT", data.hex())
print("DEPOSIT_LEN", len(data))

"""Golden values and published-vector checks for Litecoin and Tron (used by LitecoinTest / TronTest)."""
from ref import *
import hashlib

ABANDON = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
sd = hashlib.pbkdf2_hmac('sha512', ABANDON.encode(), b"mnemonic", 2048, 64)

# ---------- Tron addresses ----------
def uncompressed64(k):
    x, y = mul(k)
    return x.to_bytes(32, 'big') + y.to_bytes(32, 'big')

def tron_addr_bytes(k):
    return b'\x41' + keccak256(uncompressed64(k))[12:]

def b58check(payload):
    return b58enc(payload + dsha(payload)[:4])

# Private key 1 -> Ethereum 0x7E5F...5Bdf -> well-known Tron address
a1 = tron_addr_bytes(1)
print("tron(priv=1):", b58check(a1), a1.hex())
assert b58check(a1) == "TMVQGm1qAQYVdetCeGRRkTWYYrLXuHK2HC", "priv=1 vector"
assert a1[1:].hex() == "7e5f4552091a69125d5dfcb7b8c2659029395bdf"

k = derive(sd, [44 | H, 195 | H, 0 | H, 0, 0])
me = tron_addr_bytes(k)
print("tron m/44'/195'/0'/0/0 (abandon):", b58check(me), me.hex())

# ---------- Tron constants ----------
for name, addr in [("USDT", "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t"), ("USDC", "TEkxiTehnzSmSe2XqrBj4w32RUN966rdz8")]:
    d = b58dec(addr)
    assert len(d) == 25 and d[0] == 0x41 and dsha(d[:21])[:4] == d[21:], name
    print(name, addr, d[:21].hex())

# ---------- Protobuf encoding vs published raw_data_hex ----------
def varint(n):
    out = b''
    while True:
        b = n & 0x7f; n >>= 7
        if n: out += bytes([b | 0x80])
        else: return out + bytes([b])
def ld(tag, data): return bytes([tag]) + varint(len(data)) + data
def vi(tag, n): return bytes([tag]) + varint(n)

def transfer_raw(ref_bytes, ref_hash, exp, ts, owner, to, amount):
    tc = ld(0x0a, owner) + ld(0x12, to) + vi(0x18, amount)
    any_ = ld(0x0a, b"type.googleapis.com/protocol.TransferContract") + ld(0x12, tc)
    contract = vi(0x08, 1) + ld(0x12, any_)
    return ld(0x0a, ref_bytes) + ld(0x22, ref_hash) + vi(0x40, exp) + ld(0x5a, contract) + vi(0x70, ts)

# Chainstack docs capture (mainnet), truncated in the docs after the "to_address" bytes
chain = transfer_raw(bytes.fromhex("5e4b"), bytes.fromhex("47c9dc89341b300d"), 1591089627000, 1591089567635,
                     bytes.fromhex("41608f8da72479edc7dd921e4c30bb7e7cddbe722e"),
                     bytes.fromhex("41e9d79cc47518930bc322d9bf7cddd260a0260a8d"), 1000)
pub1 = ("0a025e4b220847c9dc89341b300d40f8fed3a2a72e5a66080112620a2d747970652e676f6f676c65617069732e636f6d2f70726f746f636f6c2e5472616e73666572436f6e747261637412310a1541608f8da72479edc7dd921e4c30bb7e7cddbe722e121541e9d79cc47518930bc322d9bf7")
assert chain.hex().startswith(pub1), "protobuf prefix mismatch vs published capture 1"
print("transfer raw_data (capture 1):", chain.hex())
print("txID:", sha256(chain).hex())

# Nile capture from tronprotocol docs (amount 1000000, truncated)
nile = transfer_raw(bytes.fromhex("2927"), bytes.fromhex("939ece39d08a7abe"), 1777447527000, 1777447470038,
                    bytes.fromhex("41dd791d6b49e190062d650e6a23c575510d35f2f9"),
                    bytes.fromhex("4192ad11c1bf16b3b14b0bd6b5c7e2db73a0b5e83a"), 1000000)
pub2 = "0a0229272208939ece39d08a7abe40d8c483c1dd335a67080112630a2d747970652e676f6f676c65617069732e636f6d2f70726f746f636f6c2e5472616e73666572436f6e747261637412320a1541dd791d6b49e190062d650e6a23c575510d35f2f912154192ad11c1bf16b3b14b0bd6b5c7e"
assert nile.hex().startswith(pub2), "protobuf prefix mismatch vs published capture 2"
print("transfer raw_data (capture 2):", nile.hex())

# ---------- Golden: signed TRX transfer from the abandon key ----------
to = bytes.fromhex("4192ad11c1bf16b3b14b0bd6b5c7e2db73a0b5e83a")
raw = transfer_raw(bytes.fromhex("2927"), bytes.fromhex("939ece39d08a7abe"), 1777447527000, 1777447470038, me, to, 1000000)
txid = sha256(raw)
r, s = ecdsa_sign(k, txid)
# low-S + recovery id
def recid(k_, h, r_, s_):
    pub = mul(k_)
    for v in (0, 1):
        # recover candidate
        x = r_; ysq = (pow(x, 3, P) + 7) % P; y = pow(ysq, (P + 1) // 4, P)
        if (y & 1) != v: y = P - y
        R = (x, y)
        e = int.from_bytes(h, 'big')
        # Q = r^-1 (sR - eG)
        sR = mul(s_, R); eG = mul(e % N)
        neg = (eG[0], (-eG[1]) % P)
        Q = mul(inv(r_, N), add(sR, neg)) if False else None
        # mul() only multiplies G, so do scalar mult on arbitrary points via double-and-add:
        def smul(kk, pt):
            res = None; addend = pt
            while kk:
                if kk & 1: res = addend if res is None else add(res, addend)
                addend = add(addend, addend); kk >>= 1
            return res
        Q = smul(inv(r_, N), add(smul(s_, R), neg))
        if Q == pub: return v
    raise SystemExit("no recovery id")
v = recid(k, txid, r, s)
assert s <= N // 2, "ecdsa_sign must return low-S"
sig = r.to_bytes(32, 'big') + s.to_bytes(32, 'big') + bytes([v])
print("signature (r||s||v):", sig.hex())
tx = ld(0x0a, raw) + ld(0x12, sig)
print("signed Transaction protobuf hex:", tx.hex())

# ---------- TRC-20 transfer calldata ----------
amount = 5_000_000
data = bytes.fromhex("a9059cbb") + b'\x00' * 12 + to[1:] + amount.to_bytes(32, 'big')
print("trc20 transfer data:", data.hex())
usdt = b58dec("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t")[:21]
tsc = ld(0x0a, me) + ld(0x12, usdt) + ld(0x22, data)
any2 = ld(0x0a, b"type.googleapis.com/protocol.TriggerSmartContract") + ld(0x12, tsc)
contract2 = vi(0x08, 31) + ld(0x12, any2)
raw2 = ld(0x0a, bytes.fromhex("2927")) + ld(0x22, bytes.fromhex("939ece39d08a7abe")) + vi(0x40, 1777447527000) \
    + ld(0x5a, contract2) + vi(0x70, 1777447470038) + bytes([0x90, 0x01]) + varint(100_000_000)
print("trc20 raw_data:", raw2.hex())
print("trc20 txID:", sha256(raw2).hex())

# ---------- Litecoin ----------
kl = derive(sd, [84 | H, 2 | H, 0 | H, 0, 0])
pl = hash160(ser_pub(mul(kl)))
print("LTC m/84'/2'/0'/0/0:", segwit_encode("ltc", 0, pl))
kt = derive(sd, [84 | H, 1 | H, 0 | H, 0, 0])
print("LTC testnet m/84'/1'/0'/0/0:", segwit_encode("tltc", 0, hash160(ser_pub(mul(kt)))))
def p2pkh(ver, h): return b58check(bytes([ver]) + h)
print("LTC P2PKH:", p2pkh(0x30, pl), " P2SH(M):", b58check(bytes([0x32]) + pl), " P2SH(3):", b58check(bytes([0x05]) + pl))
print("tLTC P2SH(Q):", b58check(bytes([0x3a]) + pl), " P2SH(2):", b58check(bytes([0xc4]) + pl))

# ---------- Tron memo (raw_data.data = field 10, between expiration and contract) ----------
memo = b"=:BTC.BTC:bc1qjmxnz78nmc8nq77wuxh25n2es7rzm5c2rkk4wh:1234567"
tc_ = ld(0x0a, me) + ld(0x12, to) + vi(0x18, 1000000)
any_ = ld(0x0a, b"type.googleapis.com/protocol.TransferContract") + ld(0x12, tc_)
contract_ = vi(0x08, 1) + ld(0x12, any_)
raw_memo = ld(0x0a, bytes.fromhex("2927")) + ld(0x22, bytes.fromhex("939ece39d08a7abe")) + vi(0x40, 1777447527000) \
    + ld(0x52, memo) + ld(0x5a, contract_) + vi(0x70, 1777447470038)
print("transfer raw_data with memo:", raw_memo.hex())
print("memo txID:", sha256(raw_memo).hex())

# ---------- SunSwap V2 (Uniswap V2 ABI): selectors and calldata ----------
def sel(sig): return keccak256(sig.encode())[:4]
SIGS = {
    "getAmountsOut": "getAmountsOut(uint256,address[])",
    "swapExactETHForTokens": "swapExactETHForTokens(uint256,address[],address,uint256)",
    "swapExactTokensForETH": "swapExactTokensForETH(uint256,uint256,address[],address,uint256)",
    "swapExactTokensForTokens": "swapExactTokensForTokens(uint256,uint256,address[],address,uint256)",
    "approve": "approve(address,uint256)", "allowance": "allowance(address,address)",
    "WETH": "WETH()", "factory": "factory()",
}
for k, v in SIGS.items(): print("selector", k, sel(v).hex())
assert sel(SIGS["swapExactETHForTokens"]).hex() == "7ff36ab5"   # the well-known Uniswap V2 selectors
assert sel(SIGS["swapExactTokensForETH"]).hex() == "18cbafe5"
assert sel(SIGS["swapExactTokensForTokens"]).hex() == "38ed1739"
assert sel(SIGS["getAmountsOut"]).hex() == "d06ca61f"
assert sel(SIGS["approve"]).hex() == "095ea7b3"

def w(n): return n.to_bytes(32, 'big')
def aw(a21): return b'\x00' * 12 + a21[1:]
WTRX = b58dec("TNUC9Qb1rRpS5CbWLmNMxXBjyFoydXjWFR")[:21]
USDT = b58dec("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t")[:21]
ROUTER = b58dec("TKzxdSv2FZKQrEqkKVgp5DcwEXBEKMg2Ax")[:21]
FACTORY = b58dec("TKWJdrQkqHisa1X8HUdHEfREvTzw4pMAaY")[:21]
for n, a in [("WTRX", WTRX), ("ROUTER", ROUTER), ("FACTORY", FACTORY)]:
    d = b58dec(b58check(a)); assert dsha(a)[:4] == b58dec(b58check(a))[21:], n
    print(n, a.hex())
def tail(path): return w(len(path)) + b''.join(aw(p) for p in path)
path_in = [WTRX, USDT]
path_out = [USDT, WTRX]
deadline = 1777447800
d1 = sel(SIGS["getAmountsOut"]) + w(1_000_000) + w(64) + tail(path_in)
d2 = sel(SIGS["swapExactETHForTokens"]) + w(4_950_000) + w(0x80) + aw(me) + w(deadline) + tail(path_in)
d3 = sel(SIGS["swapExactTokensForETH"]) + w(5_000_000) + w(4_950_000) + w(0xa0) + aw(me) + w(deadline) + tail(path_out)
d4 = sel(SIGS["approve"]) + aw(ROUTER) + w(5_000_000)
print("GETOUT", d1.hex()); print("SWAPTRX", d2.hex()); print("SWAPTOK", d3.hex()); print("APPROVE", d4.hex())
# trigger raw with call_value (TRX -> USDT swap), fee_limit 50 TRX
tsc = ld(0x0a, me) + ld(0x12, ROUTER) + vi(0x18, 5_000_000) + ld(0x22, d2)
any3 = ld(0x0a, b"type.googleapis.com/protocol.TriggerSmartContract") + ld(0x12, tsc)
c3 = vi(0x08, 31) + ld(0x12, any3)
raw4 = ld(0x0a, bytes.fromhex("2927")) + ld(0x22, bytes.fromhex("939ece39d08a7abe")) + vi(0x40, 1777447527000) \
    + ld(0x5a, c3) + vi(0x70, 1777447470038) + bytes([0x90, 0x01]) + varint(50_000_000)
print("SWAPRAW", raw4.hex()); print("SWAPTXID", sha256(raw4).hex())
# getAmountsOut result [5_000_000, 1_234_567]
res = w(32) + w(2) + w(5_000_000) + w(1_234_567)
print("AMOUNTS", res.hex())

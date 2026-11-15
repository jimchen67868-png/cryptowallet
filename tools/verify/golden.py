from ref import *
import struct, hashlib
def le32(v): return struct.pack('<I', v)
def le64(v): return struct.pack('<Q', v)
def varint(n): return bytes([n]) if n < 0xfd else b'\xfd' + struct.pack('<H', n)
ABANDON = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
def seed(m, pw=""): return hashlib.pbkdf2_hmac('sha512', m.encode(), ("mnemonic"+pw).encode(), 2048, 64)
sd = seed(ABANDON)

# 24-word vector
A24 = " ".join(["abandon"]*23 + ["art"])
print("24w TREZOR:", seed(A24, "TREZOR").hex())

# ---------- EIP-55 checks on every hard-coded EVM address ----------
def eip55(a):
    a = a.lower()[2:]; h = keccak256(a.encode()).hex()
    return "0x" + "".join(c.upper() if int(h[i],16) >= 8 else c for i,c in enumerate(a))
addrs = """0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48 0xdAC17F958D2ee523a2206206994597C13D831ec7 0x514910771AF9Ca656af840dff83E8264EcF986CA 0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2
0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238 0x779877A7B0D9E8603169DdbD7836e478b4624789 0xfFf9976782d46CC05630D1f6eBAb18b2324d6B14
0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359 0xc2132D05D31c914a87C6611C10748AEb04B58e8F 0x7ceB23fD6bC0adD59E62ac25578270cFf1b9f619
0x55d398326f99059fF775485246999027B3197955 0x8AC76a51cc950d9822D68b83fE1Ad97B32Cd580d
0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913 0x4200000000000000000000000000000000000006
0xaf88d065e77c8cC2239327C5EDb3A432268e5831 0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9 0x82aF49447D8a07e3bd95BD0d56f35241523fBab1 0x912CE59144191C1204E64559FE8253a0e49E6548""".split()
bad = [a for a in addrs if eip55(a) != a]
print("EIP-55 mismatches:", bad if bad else "none (all", len(addrs), "addresses checksum-valid)")

# ---------- Solana constants decode to 32 bytes ----------
for s in ["ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL","TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA","TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb",
          "11111111111111111111111111111111","4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU","EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v","Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB"]:
    d = b58dec(s); assert len(d) == 32, (s, len(d))
print("Solana constants: all 32 bytes")
import os
cnt = sum(ed_on_curve(os.urandom(32)) for _ in range(400)); print("on-curve fraction ~0.5:", cnt/400)

# ---------- BTC golden: 2-input P2WPKH spend (mainnet key m/84'/0'/0'/0/0) ----------
k = derive(sd,[84|H,0|H,0|H,0,0]); pub = ser_pub(mul(k)); pkh = hash160(pub)
utxos = [("11"*32,0,50000),("22"*32,1,70000)]
dest = bytes.fromhex("0014751e76e8199196d454941c45d1b3a323f1433bd6"); mine = b'\x00\x14'+pkh
outs = [(100000, dest), (15000, mine)]
ops = [bytes.fromhex(t)[::-1] + le32(v) for t,v,_ in utxos]
SEQ = 0xfffffffd
hp = dsha(b''.join(ops)); hs = dsha(b''.join(le32(SEQ) for _ in utxos))
ho = dsha(b''.join(le64(v)+varint(len(s))+s for v,s in outs))
code = bytes([0x19,0x76,0xa9,0x14])+pkh+bytes([0x88,0xac])
wit = []
for i,(t,v,amt) in enumerate(utxos):
    pre = le32(2)+hp+hs+ops[i]+code+le64(amt)+le32(SEQ)+ho+le32(0)+le32(1)
    r,s = ecdsa_sign(k, dsha(pre)); sig = der(r,s)+b'\x01'
    wit.append(b'\x02'+varint(len(sig))+sig+varint(33)+pub)
raw = le32(2)+b'\x00\x01'+varint(2)+b''.join(op+b'\x00'+le32(SEQ) for op in ops)+varint(2)+b''.join(le64(v)+varint(len(s))+s for v,s in outs)+b''.join(wit)+le32(0)
print("\nBTC_PUB", pub.hex()); print("BTC_PKH", pkh.hex()); print("BTC_RAW", raw.hex())

# ---------- SOL golden: SystemProgram.Transfer ----------
SYSTEM = bytes(32)
sk = slip10_ed25519(sd,[44,501,0,0]); pk = ed_pub(sk)
dst = ed_pub(bytes([2])*32); bh = bytes([7])*32
data = struct.pack('<I',2)+struct.pack('<Q',1_000_000_000)
msg = bytes([1,0,1])+bytes([3])+pk+dst+SYSTEM+bh+bytes([1])+bytes([2, 2, 0, 1, len(data)])+data
tx = bytes([1])+ed_sign(sk,msg)+msg
print("\nSOL_PK", b58enc(pk), "SOL_DST", b58enc(dst)); print("SOL_TRANSFER_DATA", data.hex()); print("SOL_MSG", msg.hex()); print("SOL_TX", tx.hex())

# ---------- SPL golden: create ATA (idempotent) + TransferChecked ----------
TOKEN = b58dec("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"); ATAP = b58dec("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL")
mint = b58dec("4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU")
dest_owner = dst; source = ed_pub(bytes([3])*32)
def pda(seeds, prog):
    for bump in range(255,-1,-1):
        h = sha256(b''.join(seeds)+bytes([bump])+prog+b"ProgramDerivedAddress")
        if not ed_on_curve(h): return h, bump
ata, bump = pda([dest_owner, TOKEN, mint], ATAP); print("\nATA", b58enc(ata), "bump", bump)
td = bytes([12])+struct.pack('<Q',1_000_000)+bytes([6]); print("SPL_TRANSFER_DATA", td.hex())
keys = [pk, ata, source, dest_owner, mint, SYSTEM, TOKEN, ATAP]
ix1 = bytes([7, 6, 0,1,3,4,5,6, 1, 1])
ix2 = bytes([6, 4, 2,4,1,0, len(td)])+td
msg2 = bytes([1,0,5])+bytes([len(keys)])+b''.join(keys)+bh+bytes([2])+ix1+ix2
tx2 = bytes([1])+ed_sign(sk,msg2)+msg2
print("SPL_MSG", msg2.hex()); print("SPL_TX", tx2.hex())
# without create (ATA exists): header(1,0,3), keys [owner, ata, source, mint, token]
keys3 = [pk, source, ata, mint, TOKEN]  # source is listed before ata in the transfer instruction
msg3 = bytes([1,0,2])+bytes([5])+b''.join(keys3)+bh+bytes([1])+bytes([4, 4, 1,3,2,0, len(td)])+td
print("SPL_NOCREATE_MSG", msg3.hex())

# ---------- ERC-20 transfer calldata ----------
to = "742d35Cc6634C0532925a3b844Bc454e4438f44e".lower()
print("\nERC20", "0xa9059cbb"+"0"*24+to+(1_000_000).to_bytes(32,'big').hex())
print("ERC20 selector check", keccak256(b"transfer(address,uint256)")[:4].hex())

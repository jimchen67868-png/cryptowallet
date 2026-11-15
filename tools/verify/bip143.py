from ref import *
import struct
def le32(v): return struct.pack('<I', v)
def le64(v): return struct.pack('<Q', v)
def varint(n): return bytes([n]) if n < 0xfd else b'\xfd' + struct.pack('<H', n)

def sighash_p2wpkh(version, outpoints, seqs, outputs, i, pkh, amount, locktime):
    hp = dsha(b''.join(outpoints)); hs = dsha(b''.join(le32(s) for s in seqs))
    ho = dsha(b''.join(le64(v) + varint(len(sc)) + sc for v, sc in outputs))
    code = bytes([0x19,0x76,0xa9,0x14]) + pkh + bytes([0x88,0xac])
    pre = le32(version)+hp+hs+outpoints[i]+code+le64(amount)+le32(seqs[i])+ho+le32(locktime)+le32(1)
    return dsha(pre), hp, hs, ho

# ---- BIP143 native P2WPKH example ----
op0 = bytes.fromhex("fff7f7881a8099afa6940d42d1e7f6362bec38171ea3edf433541db4e4ad969f00000000")
op1 = bytes.fromhex("ef51e1b804cc89d182d279655c3aa89e815b1b309fe287d9b2b55d57b90ec68a01000000")
outs = [(0x06b22c20, bytes.fromhex("76a9148280b37df378db99f66f85c95a783a76ac7a6d5988ac")),
        (0x0d519390, bytes.fromhex("76a9143bde42dbee7e4dbe6a21b2d50ce2f0167faa815988ac"))]
priv = int("619c335025c7f4012e556c2a58b2506e30b8511b53ade95ea316fd8c3286feb9", 16)
pub = ser_pub(mul(priv)); assert pub.hex()=="025476c2e83188368da1ff3e292e7acafcdb3566bb0ad253f62fc70f07aeee6357"
pkh = hash160(pub); assert pkh.hex()=="1d0f172a0ecb48aee1be1f2687d2963ae33f71a1"
sh, hp, hs, ho = sighash_p2wpkh(1, [op0, op1], [0xffffffee, 0xffffffff], outs, 1, pkh, 600000000, 0x11)
print("hashPrevouts", hp.hex()); print("hashSequence", hs.hex()); print("hashOutputs", ho.hex()); print("sighash", sh.hex())
assert hp.hex()=="96b827c8483d4e9b96712b6713a7b68d6e8003a781feba36c31143470b4efd37"
assert hs.hex()=="52b0a642eea2fb7ae638c36f6252b6750293dbe574a806984b8e4d8548339a3b"
assert ho.hex()=="863ef3e1a92afbfdb97f31ad0fc7683ee943e9abcf2501590ff8f6551f47e5e5"
assert sh.hex()=="c37af31116d1b27caf68aae9e3ac82f1477929014d5b917657d0eb49478cb670"
r, s = ecdsa_sign(priv, sh)
print("r", hex(r)); print("s", hex(s))
sig = der(r, s) + b'\x01'; print("sig+hashtype", sig.hex())
assert hex(r)[2:]=="3609e17b84f6a7d30c80bfa610b5b4542f32a8a0d5447a12fb1366d7f01cc44a"
assert hex(s)[2:]=="573a954c4518331561406f90300e8f3358f51928d43c212a8caed02de67eebee"
print("BIP143 sighash + RFC6979 signature MATCH")

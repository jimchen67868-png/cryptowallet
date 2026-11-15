import hashlib, hmac, struct

# ---------------- secp256k1 ----------------
P = 2**256 - 2**32 - 977
N = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141
G = (0x79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798,
     0x483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8)

def inv(a, m): return pow(a, -1, m)
def add(a, b):
    if a is None: return b
    if b is None: return a
    if a[0] == b[0] and (a[1] + b[1]) % P == 0: return None
    if a == b: l = 3*a[0]*a[0] * inv(2*a[1], P) % P
    else: l = (b[1]-a[1]) * inv(b[0]-a[0], P) % P
    x = (l*l - a[0] - b[0]) % P
    return (x, (l*(a[0]-x) - a[1]) % P)
def mul(k, pt=G):
    r = None
    while k:
        if k & 1: r = add(r, pt)
        pt = add(pt, pt); k >>= 1
    return r
def ser_pub(pt): return bytes([2 + (pt[1] & 1)]) + pt[0].to_bytes(32, 'big')

# ---------------- BIP32 ----------------
def bip32_master(seed):
    I = hmac.new(b"Bitcoin seed", seed, hashlib.sha512).digest()
    return int.from_bytes(I[:32], 'big'), I[32:]
def ckd(k, c, i):
    if i >= 0x80000000: data = b'\x00' + k.to_bytes(32, 'big') + struct.pack('>I', i)
    else: data = ser_pub(mul(k)) + struct.pack('>I', i)
    I = hmac.new(c, data, hashlib.sha512).digest()
    return (int.from_bytes(I[:32], 'big') + k) % N, I[32:]
def derive(seed, path):
    k, c = bip32_master(seed)
    for i in path: k, c = ckd(k, c, i)
    return k
H = 0x80000000

# ---------------- hashes ----------------
def sha256(b): return hashlib.sha256(b).digest()
def dsha(b): return sha256(sha256(b))
def hash160(b): return hashlib.new('ripemd160', sha256(b)).digest()

# keccak-256
RC = [0x0000000000000001,0x0000000000008082,0x800000000000808A,0x8000000080008000,0x000000000000808B,0x0000000080000001,0x8000000080008081,0x8000000000008009,0x000000000000008A,0x0000000000000088,0x0000000080008009,0x000000008000000A,0x000000008000808B,0x800000000000008B,0x8000000000008089,0x8000000000008003,0x8000000000008002,0x8000000000000080,0x000000000000800A,0x800000008000000A,0x8000000080008081,0x8000000000008080,0x0000000080000001,0x8000000080008008]
ROT = [[0,36,3,41,18],[1,44,10,45,2],[62,6,43,15,61],[28,55,25,21,56],[27,20,39,8,14]]
M64 = (1<<64)-1
def rol(x, n): n %= 64; return ((x << n) | (x >> (64-n))) & M64 if n else x
def keccak_f(A):
    for rc in RC:
        C = [A[x][0]^A[x][1]^A[x][2]^A[x][3]^A[x][4] for x in range(5)]
        D = [C[(x-1)%5] ^ rol(C[(x+1)%5], 1) for x in range(5)]
        A = [[A[x][y]^D[x] for y in range(5)] for x in range(5)]
        B = [[0]*5 for _ in range(5)]
        for x in range(5):
            for y in range(5): B[y][(2*x+3*y)%5] = rol(A[x][y], ROT[x][y])
        A = [[B[x][y] ^ ((~B[(x+1)%5][y]) & B[(x+2)%5][y]) for y in range(5)] for x in range(5)]
        A[0][0] ^= rc
    return A
def keccak256(data):
    rate = 136
    msg = bytearray(data); msg.append(0x01)
    while len(msg) % rate: msg.append(0)
    msg[-1] |= 0x80
    A = [[0]*5 for _ in range(5)]
    for off in range(0, len(msg), rate):
        blk = msg[off:off+rate]
        for i in range(rate // 8):
            A[i % 5][i // 5] ^= struct.unpack('<Q', bytes(blk[8*i:8*i+8]))[0]
        A = keccak_f(A)
    out = b''
    for i in range(4): out += struct.pack('<Q', A[i % 5][i // 5])
    return out[:32]
def eth_address(k):
    pt = mul(k)
    return '0x' + keccak256(pt[0].to_bytes(32,'big') + pt[1].to_bytes(32,'big'))[-20:].hex()

# ---------------- base58 / bech32 ----------------
B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
def b58enc(b):
    n = int.from_bytes(b, 'big'); s = ''
    while n: n, r = divmod(n, 58); s = B58[r] + s
    return '1' * (len(b) - len(b.lstrip(b'\x00'))) + s
def b58dec(s):
    n = 0
    for c in s: n = n * 58 + B58.index(c)
    raw = n.to_bytes((n.bit_length() + 7) // 8, 'big')
    return b'\x00' * (len(s) - len(s.lstrip('1'))) + raw

CH = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
def polymod(vals):
    gen = [0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3]; chk = 1
    for v in vals:
        b = chk >> 25; chk = (chk & 0x1ffffff) << 5 ^ v
        for i in range(5): chk ^= gen[i] if (b >> i) & 1 else 0
    return chk
def hrp_exp(h): return [ord(c) >> 5 for c in h] + [0] + [ord(c) & 31 for c in h]
def convertbits(data, f, t, pad=True):
    acc = bits = 0; ret = []; maxv = (1 << t) - 1
    for v in data:
        acc = (acc << f) | v; bits += f
        while bits >= t: bits -= t; ret.append((acc >> bits) & maxv)
    if pad and bits: ret.append((acc << (t - bits)) & maxv)
    return ret
def segwit_encode(hrp, ver, prog):
    data = [ver] + convertbits(prog, 8, 5)
    const = 1 if ver == 0 else 0x2bc830a3
    mod = polymod(hrp_exp(hrp) + data + [0]*6) ^ const
    chk = [(mod >> 5 * (5 - i)) & 31 for i in range(6)]
    return hrp + '1' + ''.join(CH[d] for d in data + chk)
def bech32_const1(hrp, ver, prog):   # force classic bech32 checksum
    data = [ver] + convertbits(prog, 8, 5)
    mod = polymod(hrp_exp(hrp) + data + [0]*6) ^ 1
    chk = [(mod >> 5 * (5 - i)) & 31 for i in range(6)]
    return hrp + '1' + ''.join(CH[d] for d in data + chk)

# ---------------- RFC6979 ECDSA (secp256k1, SHA256), low-S ----------------
def rfc6979_k(x, h1):
    xb = x.to_bytes(32, 'big'); V = b'\x01' * 32; K = b'\x00' * 32
    K = hmac.new(K, V + b'\x00' + xb + h1, hashlib.sha256).digest(); V = hmac.new(K, V, hashlib.sha256).digest()
    K = hmac.new(K, V + b'\x01' + xb + h1, hashlib.sha256).digest(); V = hmac.new(K, V, hashlib.sha256).digest()
    while True:
        V = hmac.new(K, V, hashlib.sha256).digest(); k = int.from_bytes(V, 'big')
        if 1 <= k < N: return k
        K = hmac.new(K, V + b'\x00', hashlib.sha256).digest(); V = hmac.new(K, V, hashlib.sha256).digest()
def ecdsa_sign(x, h):
    z = int.from_bytes(h, 'big'); k = rfc6979_k(x, h)
    r = mul(k)[0] % N; s = inv(k, N) * (z + r * x) % N
    if s > N // 2: s = N - s
    return r, s
def der(r, s):
    def i(v):
        b = v.to_bytes((v.bit_length() + 8) // 8, 'big'); return bytes([2, len(b)]) + b
    body = i(r) + i(s); return bytes([0x30, len(body)]) + body

# ---------------- ed25519 (RFC 8032) ----------------
p25 = 2**255 - 19
d25 = -121665 * pow(121666, -1, p25) % p25
q25 = 2**252 + 27742317777372353535851937790883648493
def ed_add(a, b):
    (x1,y1,z1,t1),(x2,y2,z2,t2) = a,b
    A=(y1-x1)*(y2-x2)%p25; B=(y1+x1)*(y2+x2)%p25; C=2*t1*t2*d25%p25; D=2*z1*z2%p25
    E,F,G_,H_=B-A,D-C,D+C,B+A
    return (E*F%p25,G_*H_%p25,F*G_%p25,E*H_%p25)
def ed_mul(s, pt):
    r=(0,1,1,0)
    while s:
        if s&1: r=ed_add(r,pt)
        pt=ed_add(pt,pt); s>>=1
    return r
def sqrt_ratio_x(y, sign):
    x2 = (y*y-1) * pow(d25*y*y+1, -1, p25) % p25
    if x2 == 0: return None if sign else 0
    x = pow(x2, (p25+3)//8, p25)
    if (x*x - x2) % p25: x = x * pow(2, (p25-1)//4, p25) % p25
    if (x*x - x2) % p25: return None
    if (x & 1) != sign: x = p25 - x
    return x
GY = 4 * pow(5, -1, p25) % p25
GX = sqrt_ratio_x(GY, 0)
EG = (GX, GY, 1, GX*GY % p25)
def ed_compress(pt):
    zi = pow(pt[2], -1, p25); x = pt[0]*zi%p25; y = pt[1]*zi%p25
    return int.to_bytes(y | ((x & 1) << 255), 32, 'little')
def ed_on_curve(b):           # "decompresses to a valid point" (Solana's PDA rule)
    y = int.from_bytes(b, 'little'); sign = y >> 255; y &= (1<<255)-1
    if y >= p25: return False
    return sqrt_ratio_x(y, sign) is not None
def sha512(b): return hashlib.sha512(b).digest()
def ed_secret_expand(sk):
    h = sha512(sk); a = int.from_bytes(h[:32], 'little'); a &= (1<<254)-8; a |= 1<<254
    return a, h[32:]
def ed_pub(sk): return ed_compress(ed_mul(ed_secret_expand(sk)[0], EG))
def ed_sign(sk, msg):
    a, prefix = ed_secret_expand(sk); A = ed_compress(ed_mul(a, EG))
    r = int.from_bytes(sha512(prefix + msg), 'little') % q25
    Rs = ed_compress(ed_mul(r, EG))
    h = int.from_bytes(sha512(Rs + A + msg), 'little') % q25
    s = (r + h * a) % q25
    return Rs + int.to_bytes(s, 32, 'little')

def slip10_ed25519(seed, path):
    I = hmac.new(b"ed25519 seed", seed, hashlib.sha512).digest(); k, c = I[:32], I[32:]
    for i in path:
        I = hmac.new(c, b'\x00' + k + struct.pack('>I', i | H), hashlib.sha512).digest(); k, c = I[:32], I[32:]
    return k

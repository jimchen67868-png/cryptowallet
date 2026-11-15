from ref import *
ABANDON = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
def seed(m, pw=""): return hashlib.pbkdf2_hmac('sha512', m.encode(), ("mnemonic"+pw).encode(), 2048, 64)

# BIP39
assert seed(ABANDON,"TREZOR").hex()=="c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e53495531f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04"
print("BIP39 TREZOR ok"); print("empty-pw seed:", seed(ABANDON).hex())

# BIP32 vector 1
s=bytes.fromhex("000102030405060708090a0b0c0d0e0f")
for path,priv,pub in [([], "e8f32e723decf4051aefac8e2c93c9c5b214313817cdb01a1494b917c8436b35","0339a36013301597daef41fbe593a02cc513d0b55527ec2df1050e2e8ff49c85c2"),
                      ([H], "edb2e14f9ee77d26dd93b4ecede8d16ed408ce149b6cd80b0715a2d911a0afea","035a784662a4a20a65bf6aab9ae98a6c068a81c52e4b032c0fb5400c706cfccc56"),
                      ([H,1], "3c6cb8d0f6a264c91ea8b5030fadaa8e538b020f0a387421a12de9319dc93368","03501e454bf00751f24b1b489aa925215d66af2234e3891c3b21a52bedb3cd711c")]:
    k=derive(s,path); assert k.to_bytes(32,'big').hex()==priv, path; assert ser_pub(mul(k)).hex()==pub, path
print("BIP32 vector1 ok")

# SLIP10 ed25519 vector1
assert slip10_ed25519(s,[]).hex()=="2b4be7f19ee27bbf30c667b642d5f4aa69fd169872f8fc3059c08ebae2eb19e7"
assert slip10_ed25519(s,[0]).hex()=="68e0fe46dfb67e368c75379acec591dad19df3cde26e63b93a8e704f1dade7a3"
print("SLIP10 m, m/0H ok; m/0H/1H:", slip10_ed25519(s,[0,1]).hex())

# RFC8032 test 1
sk=bytes.fromhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
assert ed_pub(sk).hex()=="d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
assert ed_sign(sk,b"").hex()=="e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
print("RFC8032 ok")

# Addresses
sd=seed(ABANDON)
assert eth_address(derive(sd,[44|H,60|H,0|H,0,0])).lower()=="0x9858effd232b4033e47d90003d41ec34ecaeda94"; print("ETH ok")
k=derive(sd,[84|H,0|H,0|H,0,0]); addr=segwit_encode("bc",0,hash160(ser_pub(mul(k))))
assert addr=="bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", addr; print("BIP84 bc1 ok")
k=derive(sd,[84|H,1|H,0|H,0,0]); print("testnet addr:", segwit_encode("tb",0,hash160(ser_pub(mul(k)))))
sol=b58enc(ed_pub(slip10_ed25519(sd,[44,501,0,0]))); print("SOL:", sol); assert sol=="HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk"

# Base58 (Bitcoin Core vectors)
for h,e in [("","") ,("61","2g"),("626262","a3gV"),("636363","aPEr"),("73696d706c792061206c6f6e6720737472696e67","2cFupjhnEsSn59qHXstmK2ffpLv2"),
            ("00eb15231dfceb60925886b67d065299925915aeb172c06647","1NS17iag9jJgTHD1VXjvLCEnZuQ3rJDE9L"),("516b6fcd0f","ABnLTmg"),("bf4f89001e670274dd","3SEo3LWLoPntC"),
            ("572e4794","3EFU7m"),("ecac89cad93923c02321","EJDM8drfXA6uyA"),("10c8511e","Rt5zm"),("00000000000000000000","1111111111")]:
    assert b58enc(bytes.fromhex(h))==e,(h,e); assert b58dec(e)==bytes.fromhex(h)
print("Base58 ok")

# Bech32 / bech32m
prog=bytes.fromhex("751e76e8199196d454941c45d1b3a323f1433bd6")
assert segwit_encode("bc",0,prog)=="bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"
assert segwit_encode("tb",0,prog)=="tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"
tp=bytes.fromhex("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")
assert segwit_encode("bc",1,tp)=="bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0"
assert bech32_const1("bc",1,tp)=="bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqh2y7hd"
print("Bech32/m ok")

# P2PKH / P2SH examples
def chk(a): r=b58dec(a); assert dsha(r[:21])[:4]==r[21:]; return r[0], r[1:21].hex()
print(chk("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"), chk("3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy"), chk("mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn"))

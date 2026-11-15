# Independent reference implementation

Pure-Python (standard library only) implementations of BIP-32, secp256k1 ECDSA with RFC 6979, Ed25519 (RFC 8032),
SLIP-0010, Base58, Bech32/Bech32m, Keccak-256 and Solana PDA derivation. They are deliberately separate from the Kotlin
code so a mistake would have to be made twice, in two languages, to go unnoticed.

    python3 published_vectors.py   # asserts BIP-39/32, SLIP-10, RFC 8032, BIP-84, Base58, Bech32 published vectors
    python3 bip143.py              # asserts the BIP-143 sighash and the exact deterministic signature
    python3 golden.py              # prints the golden values used by the Kotlin tests + validates constants

The Kotlin tests in `app/src/test` contain the expected values these scripts produce.

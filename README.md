# CryptoWallet (Android, Kotlin, Compose)

Non-custodial multi-chain wallet starter. One BIP-39 seed derives all accounts.
Testnets by default: Ethereum Sepolia, Bitcoin testnet, Solana devnet.

## Open and run
1. Android Studio (Koala or newer) -> Open -> select this folder. Accept the Gradle sync / wrapper prompt.
2. Run on a device or emulator with Android 11+ (API 30) and a screen lock or biometric set up (the Keystore key requires it).
3. Create a wallet, write down the 12 words, confirm 3 of them. Fund the addresses from faucets:
   - Sepolia ETH: any Sepolia faucet (e.g. Alchemy, Google Cloud Web3)
   - Bitcoin testnet: any testnet faucet
   - Solana devnet: `solana airdrop` or faucet.solana.com

## What works
- Create / import wallet, backup confirmation, biometric/PIN unlock
- Mnemonic encrypted with an AES-256-GCM key in the Android Keystore; seed kept in memory only while unlocked and wiped when the app is backgrounded
- Receive (address + QR + copy) and live balances on ETH, BTC, SOL
- Send ETH (Sepolia) with address validation and a confirmation dialog

## Not implemented yet
- Sending BTC (UTXO fetch, coin selection, fee estimate, P2WPKH signing)
- Sending SOL (build transfer message, sign with ed25519, `sendTransaction`)
- Transaction history, token balances (ERC-20/SPL), fiat prices, multiple accounts
- Mainnet: change RPC URLs, ETH chain id (1), BTC path `m/84'/0'/0'/0/0` with hrp `bc`, SOL RPC to mainnet. Do not do this before a professional security audit.

## Layout
- `security/`  Keystore encryption + biometric prompt
- `crypto/`    BIP-39, BIP-32, SLIP-10, Base58, Bech32
- `chain/`     `ChainProvider` interface + ETH/BTC/SOL implementations
- `data/`      `WalletRepository`
- `ui/`        ViewModel + Compose screens

## Notes
- Dependency versions are pinned to known-good releases; Android Studio may suggest upgrades.
- web3j on Android can need packaging excludes (already set in app/build.gradle.kts). If R8 is enabled for release, add keep rules for web3j/BouncyCastle.
- targetSdk is 34; raise it to the current Play Store requirement before publishing.
- Back up `local.properties`-style secrets never; this app has none. Don't add API keys to source control.

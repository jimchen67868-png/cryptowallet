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

## Mainnet (opt-in)
Home screen -> **Network** -> *Switch to mainnet...* (type MAINNET to confirm). Same seed, all three chains move together:
Ethereum mainnet (publicnode RPC, Blockscout history), Bitcoin mainnet (`bc1q...`, path `m/84'/0'/0'/0/0`, mempool.space), Solana mainnet-beta.
Safeguards built in:
- Warning screen with typed confirmation; red MAINNET banner on the home screen
- Per-transaction cap `AppConfig.MAINNET_TX_LIMIT_USD` (default $100). Assets with no known price (custom tokens, CoinGecko down) cannot be sent on mainnet
- Fingerprint/PIN required again right before every mainnet signature
- Custom tokens are stored per network
Public RPC endpoints are rate-limited; for real use put your own provider keys in `AppContainer`.
Ethereum sends use legacy gas pricing (works, slightly less efficient than EIP-1559).
**Do not use real funds until the code has had an independent security review.**

## EVM networks
Ethereum, Base, Arbitrum, Polygon and BNB Chain, on mainnet and testnet. All share one address (`m/44'/60'/0'/0/0`)
but balances are per network: **a token sent on the wrong network is not visible on another one.** Each network has its own card,
custom-token list, fee estimate (via `eth_estimateGas`) and explorer links.
- History needs a Blockscout API: configured for Ethereum, Base, Arbitrum and Polygon mainnet (and Sepolia, Base/Arbitrum Sepolia).
  Not configured for BNB Chain and Polygon Amoy: the History screen says so. Pass a `historyApi` in `AppContainer` to enable it.
- Adding another EVM chain = one `evm(...)` line in `AppContainer` plus a `Chain` enum entry and a CoinGecko id in `CoinIds`.
- Gas uses legacy pricing (`eth_gasPrice`); fee shown for L2s excludes the small L1 data fee on Base.

## What works
- Create / import wallet, backup confirmation, biometric/PIN unlock
- Mnemonic encrypted with an AES-256-GCM key in the Android Keystore; seed kept in memory only while unlocked and wiped when the app is backgrounded
- Token balances: ERC-20 (known Sepolia tokens + any contract you add via "Add token") and SPL (auto-discovered via getTokenAccountsByOwner, Token + Token-2022 programs)
- Send SOL (SystemProgram transfer) with pre-flight checks for rent-exempt minimums (recipient account creation, leftover balance)
- Send tokens: ERC-20 `transfer` (gas estimated up front, fails early if it would revert) and SPL `TransferChecked` (recipient's associated token account is created automatically if missing; the fee shown includes that one-time rent)
- Transaction history per chain (tap a row to open the block explorer): BTC via mempool.space, ETH + ERC-20 via Blockscout (Sepolia), SOL + SPL via RPC (last 20 transactions)
- USD values (CoinGecko public API, cached 60 s): per coin, per token, portfolio total, live estimate on the send screen and in the confirmation dialog. Tokens are priced by contract/mint address only (never by symbol). Testnet figures are mainnet reference prices and are labelled as such.
- Receive (address + QR + copy) and live balances on ETH, BTC, SOL
- Send ETH (Sepolia) and BTC (testnet) with address validation, fee estimate and a confirmation dialog
- BTC: UTXO fetch (confirmed only), largest-first coin selection, fee from mempool.space (half-hour target), native SegWit signing (BIP-143), change back to the wallet address, RBF enabled, recipients can be bech32/bech32m/P2PKH/P2SH

## Not implemented yet
- Sending SOL (build transfer message, sign with ed25519, `sendTransaction`)
- Token-2022 extensions such as transfer fees, fiat values in history rows (needs historical prices), other currencies (change `vs_currencies`), multiple accounts
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

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

## Tests
`gradle testDebugUnitTest` (also runs in GitHub Actions before every build; the HTML report is uploaded as an artifact).
They are plain JVM unit tests, no device or network needed:
- BIP-39 seeds (Trezor vectors, 12 and 24 words), mnemonic validation
- BIP-32 test vector 1, SLIP-0010 ed25519 test vector 1
- Addresses of the standard mnemonic: BIP-84 `bc1qcr8te...`, Ethereum `0x9858...da94`, Solana `HAgk14...pqk`
- Base58 (Bitcoin Core vectors), Bech32/Bech32m (BIP-173/350 valid + invalid), every Bitcoin address type and wrong-network rejection
- Bitcoin: the BIP-143 sighash and the exact RFC 6979 signature, a full signed 2-input transaction, coin selection invariants
- Solana: RFC 8032 Ed25519 vector, SOL transfer and SPL transfer (+ account creation) transactions, PDA derivation
- Swap guards: EVM calldata decoding (many reject cases), Solana transaction allow-list, parser, and simulation balance checks
- ERC-20 `transfer` calldata, EIP-55 checksum of every hard-coded token address, price-lookup rules
Expected values come from published specs or from `tools/verify` (an independent Python reference implementation).
**Passing tests do not make the wallet safe**: they cover encoding and signing, not the Android key storage, network code or UI.

## Swaps (EVM mainnets only)
**Swap** button on the native coin and on token rows. Quotes and transactions come from the ParaSwap API (keyless);
there is no aggregator liquidity on testnets, so swaps are disabled there.
Because the wallet signs calldata supplied by a third-party API, `SwapGuard` re-checks it locally and fails closed:
- the router must be the allow-listed Augustus V6.2 address and equal the one named in the quote
- the calldata is decoded: source/destination token, exact input amount and payout address (must be you) must match your request
- minimum output may not be below the quote by more than 1%; the price must not move more than 1% before signing
- ERC-20 approvals are for the exact amount only, to an allow-listed spender (a leftover non-zero allowance is reset first)
- the transaction is simulated (`eth_estimateGas`) before sending; the mainnet USD cap and fingerprint/PIN re-check apply
- swap targets are limited to the native coin and the curated token list
Not verified against the live API from the development environment: the first real swap should be a few dollars.
If ParaSwap changes its calldata format, swaps are rejected rather than signed.

## Swaps on Solana (mainnet, via Jupiter)
SOL <-> USDC / USDT, and any token you hold into those. Jupiter returns a complete transaction, so the checks are layered:
1. **Instruction allow-list** (`SolanaSwapGuard`): the fee payer must be you and the only signer; only the ComputeBudget, System (Transfer only),
   Token (SyncNative, CloseAccount back to you), Associated Token Account (for you) and Jupiter v6 programs may appear; priority fee is capped.
2. **Simulation** (`SolSimulationGuard`): the transaction is simulated and compared with your accounts beforehand. It must spend at most the
   swap amount (+ small fees), return at least the minimum, and leave every other balance, owner, delegate and open account untouched.
3. Then it is signed. The price must also not move more than 1% between the quote and signing.
Unlike the EVM side, the Jupiter response format was not verified against the live API from the development environment, and the keyless
endpoint (`lite-api.jup.ag`) may change or need an API key: set `jupiterBaseUrl` in `SolanaProvider`. Anything unexpected is rejected, never signed.

## Swaps on testnets (Uniswap v3)
ParaSwap and Jupiter have no testnet liquidity, so on Sepolia, Base Sepolia and Arbitrum Sepolia swaps go straight to the official
Uniswap v3 contracts (QuoterV2 for the price, SwapRouter02 to trade). The wallet builds the calldata itself, so there is no third-party
transaction to trust. It picks the best of the four fee tiers, approves the exact amount, and sets the minimum output from a fresh
on-chain quote (1% slippage).
- **ETH <-> WETH** is a plain wrap/unwrap on the WETH contract (always works, handy for testing the flow).
- **Uniswap v2 fallback (Sepolia only).** On Sepolia the wallet also asks the official Uniswap v2 router (UniswapV2Router02) for a price,
  trying the direct pair and a hop through WETH, and uses whichever of v3 or v2 gives the higher output (a tie goes to v3). The swap
  confirmation shows "Route: Uniswap v2/v3". Calldata is built by the wallet and checked against an independent encoder
  (`tools/verify/uniswap_v2_golden.py`); the router's WETH is read from the router itself, since v2 and v3 use different WETH
  contracts on Sepolia. Uniswap publishes no official v2 for Base Sepolia or Arbitrum Sepolia, so those stay v3-only.
  UniswapX is not supported on any testnet, and v4 is not implemented.
- Any other pair works only if somebody created a pool for it: otherwise you get "No Uniswap v3 or v2 pool with liquidity". Tokens you add
  with *Add token* appear in the swap list marked "(custom)". Create a pool at app.uniswap.org on the same testnet if you need one.
- Solana devnet has no swap support (no deployed aggregator liquidity). Bitcoin has no swaps.

## More swap tokens
Mainnet EVM lists now include DAI, WBTC, UNI, AAVE, BTCB, WBNB, LINK where each exists (EIP-55 checked in the tests). Solana adds JUP,
BONK, WIF, mSOL and JitoSOL; because a mistyped base58 mint still looks valid, every Solana mint is verified on-chain (exists, is a mint,
expected decimals) before a quote is requested. Tokens you add yourself can be swap targets but carry a warning.

## Moving funds to another network (LI.FI, routed over Across; EVM mainnets)
**Swap screen -> "Move X to another network..."** bridges a token between Ethereum, Base, Arbitrum and Polygon (USDC, USDT, DAI, WBTC,
WETH, LINK where both chains list it). To change asset as well, use **Bridge, then swap on arrival** below, or bridge first and swap after.

**Why LI.FI:** Across deprecated the unauthenticated API this bridge was first written against and now needs a manually approved API key
that must stay server-side; it also has no testnet. LI.FI's REST API is keyless, and with `allowBridges=across` it routes over Across anyway.
(`proxy/` and `AcrossProxy.kt` are a finished, tested Across-v1 proxy and client that are not used by default: a fallback if you get a key.)

**What is checked before anything is signed.** The transaction comes from LI.FI (calldata the wallet did not write), so `LiFiGuard`
decodes it and refuses unless ALL of this holds, with the checks tested against a real captured response
(`tools/verify/lifi_fixture_arb_base_usdc.json`, decoded independently by `tools/verify/lifi_decode.py`):
- the target and approval spender are LI.FI's Diamond contract, pinned per chain (`LiFiPins`); the sender is you; no native value;
- the function is exactly `swapAndStartBridgeTokensViaAcrossV4`, with nothing before or after the expected data;
- receiver, token, destination chain and amounts match your request; no destination call, no referrer;
- the one extra step is LI.FI's fee collection: the known fee contract, a single known recipient, at most 1% of the amount, and
  `bridged amount = amount - fee` exactly (LI.FI's service fee is 0.25%);
- the Across deposit pays you (recipient and refund address), in the right tokens, with a sane output share and deadlines;
- you receive at least 95% of what you put in. Total cost = LI.FI 0.25% + Across fee (about 0.4% on a $2 test).
Also: exact-amount approval only, a fresh quote after the approval confirms, simulation before sending, the $100 mainnet cap and a
fingerprint/PIN prompt. Status comes from LI.FI `/v1/status` by deposit hash.

**Limits of this release.** Only Arbitrum as the origin chain is pinned: its fee-step contract and recipient were observed in a real
quote. For Ethereum, Base and Polygon capture a quote with `tools/capture_lifi.sh`, decode it, and add the addresses to `LiFiPins`
(until then those origins say "isn't enabled in this build yet"). Native ETH is not supported through LI.FI yet (needs a captured sample
too). The facet layout is pinned by its function selector; a different LI.FI facet or version is refused, not guessed at.
Unauthenticated LI.FI limit: 75 quotes per 2 hours per IP. Not run against live networks from the development environment, and
bridges are the riskiest operation in this app: use a small amount first. BNB Chain and Solana are not bridge targets.

### Relay testnet bridge (test ETH, Sepolia <-> Base Sepolia)
So the bridge and bridge-then-swap flows can be tried with worthless funds. **Switch to testnet, Swap on the Ethereum (Sepolia) or Base card,
tap "Move ETH to another network...".** Only ETH, and only between Sepolia and Base Sepolia (Relay's testnets support nothing else; Relay also
caps test transfers at about $1, so use roughly 0.0001 ETH). With "Then swap on arrival" the job card, arrival watcher and swap step work exactly
as on mainnet, with the swap done by the Uniswap testnet routes.

How it is checked (`RelayGuard`, tested against a real captured quote, `tools/verify/relay_fixture_sepolia_basesepolia_eth.json`): exactly one
`deposit` transaction step; the target is Relay's depository contract (pinned; it is the one Relay lists for both testnets); the function is
`depositNative(address,bytes32)` with you as depositor and the exact amount as value; the status link belongs to the quote; and Relay's own response
names you as sender and recipient with consistent amounts (fees up to 50% are accepted on testnet because fixed costs dominate tiny amounts: the real
quote was 11%). Progress is followed by Relay's `requestId` through `/intents/status/v3`.

**Why it is testnet-only:** in Relay's flow the deposit only carries the depositor and an opaque order id. Relay's server, not the transaction, decides where
the funds go, so unlike the LI.FI route the wallet cannot prove the recipient from calldata; it can only check what Relay claims. Fine for test ETH,
not for real money. The API is called keyless; Relay may start requiring a key on testnet too, in which case the app says so and `proxy/relay-worker.js`
(tested, not wired in) is the way to keep a key out of the APK. Not run against live networks from the development environment.

### CCIP testnet bridge (CCIP-BnM test token, Sepolia <-> BNB testnet)
Chainlink CCIP has an official Sepolia <-> BNB Chain testnet lane. **Switch to testnet, tap "Get test CCIP-BnM" on the Ethereum (Sepolia) or BNB card**
(it calls the token's public `drip(address)` faucet), wait for the balance to appear, then **Swap -> "Move CCIP-BnM to another network..."**.
You need a little ETH (Sepolia) or BNB (testnet) on the source chain for the CCIP fee and gas. Only CCIP-BnM moves: CCIP cannot bridge native coins.

How it works, and why it is safer than the Relay testnet route: the wallet calls Chainlink's on-chain Router directly (no API, no key) and builds the transaction
itself, so your address, the token and the amount are in calldata the wallet wrote. It reads the exact fee from the Router (`getFee`; the Router refuses an unsupported
token or lane there), refuses a zero or above-0.05 fee, approves the exact amount to the Router (never unlimited), re-reads the fee after the approval confirms and
refuses if it rose by more than 50%, then sends `ccipSend` with the fee as `msg.value` (a simulation runs first). Arrival is detected by the destination balance, as for
the other routes (CCIP jobs are tagged `ccip:<tx>` so no status API is consulted).

Verified against Chainlink's docs: router addresses and chain selectors for both chains, the CCIP-BnM addresses (matched to the official token directory and an
independent source), the `EVM2AnyMessage` field order, and the `GenericExtraArgsV2` tag (still accepted on CCIP 2.0 lanes). All four addresses pass EIP-55 and the function
selectors match their signatures. `tools/verify/ccip_golden.py` is an independent ABI encoder that produced the golden values in `CcipTest`; the test also cross-checks web3j's
own encoder. Not verified: delivery time, fee size, and anything on live networks. Bridge-then-swap with CCIP-BnM only works if a Uniswap pool exists for the pair on the
destination (unlikely for a faucet token), so expect "no pool" there; it is bridge-only in practice. Base Sepolia and Arbitrum Sepolia are not included yet (their
CCIP-BnM addresses are not verified).

### Bridge, then swap on arrival
On the bridge screen, pick **"Swap to X"** under the destination to move funds *and* change asset. It is two separately
authorised steps, because Across fills on the destination chain after the deposit and a swap cannot be part of that transaction:
1. **Bridge** (as above: caps, `LiFiGuard`, fingerprint/PIN). Before sending, the wallet checks you have gas on the destination
   chain (not needed when bridging native ETH to an ETH-gas chain), takes an indicative swap quote, and records your destination
   balances. Nothing is sent if any of that fails.
2. **Swap on arrival.** A job card appears on Home and the wallet polls LI.FI `/v1/status` plus your destination balance.
   Arrival = balance up by >= 99.9% of the quoted amount **and** LI.FI reports DONE/COMPLETED (or the status API is unreachable or does not know the transfer yet). When it lands
   you tap **Review swap**: a FRESH ParaSwap quote is taken from the real arrived amount (never more than quoted), `SwapGuard`
   checks the calldata as for any swap, the $100 cap is re-applied, and you confirm with fingerprint/PIN again. A warning shows
   if the price is more than 3% worse than the preview.
Jobs are saved (non-secret: chains, amounts, deposit tx id) so a bridge in flight survives the app being closed. Polling only
runs while the app is unlocked; the wallet is locked in the background by design, so reopen it to resume. If the swap fails or you
cancel, the funds simply stay on the destination chain and **Review swap** can be tried again. Native-coin arrivals keep a small
reserve for the swap's network fee (0.006 ETH on Ethereum, 0.0002 on Base/Arbitrum, 0.05 POL on Polygon; a heuristic).
Only built-in (curated) tokens can be the target. Not verified against the live APIs.

## EVM networks
Ethereum, Base, Arbitrum, Polygon and BNB Chain, on mainnet and testnet. All share one address (`m/44'/60'/0'/0/0`)
but balances are per network: **a token sent on the wrong network is not visible on another one.** Each network has its own card,
custom-token list, fee estimate (via `eth_estimateGas`) and explorer links.
- History needs a Blockscout API: configured for Ethereum, Base, Arbitrum and Polygon mainnet (and Sepolia, Base/Arbitrum Sepolia).
  Not configured for BNB Chain and Polygon Amoy: the History screen says so. Pass a `historyApi` in `AppContainer` to enable it.
- Adding another EVM chain = one `evm(...)` line in `AppContainer` plus a `Chain` enum entry and a CoinGecko id in `CoinIds`.
- Gas uses legacy pricing (`eth_gasPrice`); fee shown for L2s excludes the small L1 data fee on Base.

## Litecoin and Tron
**Litecoin (LTC)**: native SegWit (`ltc1q...`, BIP-84 path `m/84'/2'/0'/0/0`; testnet `tltc1q...`, coin type 1). It reuses the Bitcoin
implementation (BIP-143 signing is identical): UTXOs, fee rate and broadcast come from the Litecoin Foundation's keyless
`litecoinspace.org` API (a mempool.space fork), explorer links go to the same site. Destinations may be `ltc1...`, `L...`, `M...`
and legacy `3...` addresses. MWEB (`ltcmweb1...`) addresses are rejected. Dust limit is 5460 sats.
Testnet coins: any Litecoin testnet faucet (e.g. tltc.bitaps.com).

**Tron (TRX) and USDT (TRC-20)**: address `T...` from path `m/44'/195'/0'/0/0` (same as TronLink/Ledger). Transactions are built and signed
locally (hand-written protobuf in `TronTx`); TronGrid (keyless) only supplies a block reference, balances, fee parameters and the broadcast.
The transaction id reported by the node must equal the locally computed one. Testnet is Nile (faucet: nileex.io), mainnet lists USDT only
(Circle stopped issuing USDC on Tron, so it is not listed). Only tokens from the built-in list are shown or sendable.
Things that differ from other chains and are included in the fee shown before you confirm:
- Sending TRX to an address that has never been used burns an extra ~1 TRX (account creation).
- A USDT transfer needs "energy". Without staked TRX the energy is burned from your TRX balance: several TRX, more if the recipient holds no USDT yet.
  You need TRX in the same wallet to send USDT. The fee estimate comes from a `triggerconstantcontract` simulation; `fee_limit` is the estimate + 25% (max 200 TRX).
- The $100 mainnet cap applies to the amount of TRX/USDT, not to the fee.
Not supported: staking/resources, TRC-10 tokens, swaps and bridges for LTC/TRX, custom TRC-20 tokens.
Verified here: addresses (priv key 1 and the standard mnemonic), the protobuf bytes and txID against published TronGrid captures,
signing against an independent Python implementation. **Not run against live networks from the dev environment:** the first real use
should be a few dollars (or Nile/Litecoin testnet coins).

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
- `chain/`     `ChainProvider` interface + ETH/BTC/LTC/SOL/TRX implementations
- `data/`      `WalletRepository`
- `ui/`        ViewModel + Compose screens

## Notes
- Dependency versions are pinned to known-good releases; Android Studio may suggest upgrades.
- web3j on Android can need packaging excludes (already set in app/build.gradle.kts). If R8 is enabled for release, add keep rules for web3j/BouncyCastle.
- targetSdk is 34; raise it to the current Play Store requirement before publishing.
- Back up `local.properties`-style secrets never; this app has none. Don't add API keys to source control.

## Crash reports and a known Compose bug
- **Crash reporter:** if the app crashes, the stack trace is saved privately on the phone and shown on the next launch with Copy / Dismiss
  (no keys or seed, but it can contain addresses or amounts; never uploaded automatically). Unexpected errors in background work show
  "Something went wrong (...)" instead of closing the app.
- **Compose cursor-anchor crash:** a `NullPointerException` in `LegacyCursorAnchorInfoController.updateCursorAnchorInfo` closes the app when the
  keyboard asks for cursor positions while a text field is being laid out (Google issue tracker 313010266, still reported on 2026 Compose
  versions). `UiCrashGuard` swallows exactly that error and nothing else (everything else still crashes and is recorded), and gives up if it
  repeats more than 20 times in 2 seconds. If minification (R8) is ever enabled for release builds, keep the class and method names
  `*CursorAnchorInfoController.updateCursorAnchorInfo`, or the guard will not recognise the error.

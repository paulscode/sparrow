# UX improvements and feature ideas, ranked

Reviewed 2026-10-30 against the `installers` branch (61ca43e8, on top of 2.5.6-blake2b.7). The review
read the code and covered four areas: first run and wallet setup, everyday wallet screens, chain-split
features, and settings, connectivity and the terminal UI. The app was not exercised against a live
server, so a few items are marked *to confirm*.

Ideas are ranked by value against effort, in four tiers. Within a tier the order is the suggested
order of work. Effort: **S** is about a day or less, **M** a few days, **L** a week or more. Almost
everything here is specific to this fork. Items marked *general* would apply to upstream Sparrow too.

Paths are relative to `src/main/java/com/sparrowwallet/sparrow/` unless they start with `drongo/`,
`lark/`, `src/main/resources/` or `docs/`.

## Status

Done on the `installers` branch, for the next release (2.5.6-blake2b.8):

- **#1:** Full Node mode is checked too.
  - Cormorant reports its node's fork point, but only when its headers change format at 961640.
  - The identity check falls back to `blockchain.block.header`, which Cormorant serves.
  - Still to do: test against a live Knots node.
- **#2:** broadcasting a transaction labelled "Not replay protected" that spends coins created before
  the fork now asks first. Nothing is asked when the label could not check.
  - Not done: "don't ask again for this wallet".
- **#3:**
  - Requesting a payjoin without replay protection now asks first.
  - The send screen notes when a payment came from a `bitcoin:` link, a name or a payment code.
- **#4:** upstream Sparrow's wallets are copied into this app's wallets folder, never opened in place.
- **#5:** `ChainMismatchException` is a configuration error, so it is not retried.
  - The refusal is shown once, as a dialog, with what to do.
  - The status bar uses a short form.
  - Test Connection shows the remedy and the new README section, "Connecting to a server".
- **#6:**
  - Help menu: fork documentation, the split guide, and upstream documentation as a separate item.
  - Bug reports go to the fork's issues page, prefilled with version, network, OS and server type.
    Issues must be enabled on paulscode/sparrow for that to work.
  - About and Welcome now describe this build.
  - Not done: links from the protection tooltips. Tooltips can't hold links, so this waits for #34's
    Details dialog.
- **#8:** "Insufficient funds" names the immature amount, as the fee field already did.
- **#12:**
  - The marking dialog lists watch-only keystores, names the firmware known to support the opt-in,
    and links the split guide for stock firmware.
  - "Pre-fork rules" is now "the SHA256d chain", and the keystore row's wording is plainer.
  - Not done: software seeds still show no protection row.
- **#18:**
  - First-run Settings offers Import Wallet.
  - With no server configured, the status bar says so and links to Server Settings.
  - Discover Wallet is hidden in Full Node mode.
  - The fee and fiat settings help text is accurate.
  - The log records the build at startup, and Cormorant's version carries the suffix.

---

## Tier 1: Close real ways to lose money

These are the ideas where the app today lets a user do something that costs them funds or damages a
wallet file, without warning. Each is small.

### 1. Full Node mode never checks which chain the node follows — S–M
- **Problem:** the connect-time chain identity check is skipped when the server type is Full Node
  (`net/ElectrumServer.java:3515`). The comment's reasoning is that the node is the user's own, so
  its chain is "a decision they already made".
  - Cormorant's `server.features` never fills `blake2b_fork`
    (`net/cormorant/electrum/ElectrumServerService.java:59-72`), so in this mode the activation
    height is never corroborated either.
  - A fresh install defaults to Full Node, on `127.0.0.1`, with cookie auth from `~/.bitcoin` or the
    platform equivalent (`settings/ServerSettingsController.java:217-220`, `:488-489`,
    `:1060-1069`). That is exactly a stock Bitcoin Core install.
- **How it goes wrong:** someone with both a SHA256d node and a BLAKE2b node (common on StartOS or
  Umbrel) gives this build the wrong RPC port. Or someone migrating from upstream Sparrow keeps the
  defaults. The wallet then:
  - shows SHA256d balances as if they were on the BLAKE2b chain;
  - sees a v1 tip, so it signs without the opt-in;
  - broadcasts to the SHA256d node.

  The user moves coins on the SHA256d chain believing they are moving them on the BLAKE2b chain.
- **Proposal:**
  - In Full Node mode, fetch the node's raw header at 961640 (`getblockhash` then
    `getblockheader <hash> false`, the way `BitcoindClient.tipHeader` already does). Run it through
    `VariableHeaders.getChainMismatchError`.
  - Refuse on a mismatch. If the node is below the fork height (still syncing), connect but say
    "chain not yet established".
  - Have Cormorant fill `blake2b_fork` from the node.
  - Add a line on the Full Node form saying the node must be a BLAKE2b node.
- **Needs:** a test against a live Knots node. That is already an open manual check.

### 2. Confirm before broadcasting an unprotected spend of coins created before the fork — S
- **Problem:** "Not replay protected" is only a label (send screen `wallet/SendController.java:1139-1205`,
  transaction tab `transaction/HeadersController.java:1108-1170`).
  - `broadcastTransaction()` (`HeadersController.java:1745-1800`) has confirmation steps for RDTS and
    for very high fees, but none for replay.
  - The split guide's own sample found 116 of 120 transactions copied to the other chain.
  - Users of stock hardware-wallet firmware are exactly the people who end up here.
- **Proposal:** when the final label is not protected and at least one input was created before block
  961640, ask first. A coin's height is a fact, so this needs no network calls.
  - Wording along the lines of: "Anyone can broadcast a copy of this on the SHA256d chain. It would
    move the same amount of BTC from these addresses, if those coins are still unspent there."
  - Buttons: Broadcast, Cancel, and Open the split guide. Offer "Don't ask again for this wallet".
  - Never say "safe". Say nothing either way for inputs whose status isn't known.

### 3. Payment requests made for bitcoin get paid on the BLAKE2b chain without a word, and payjoin can move BTC — S–M
- **Problem:** several routes into the send screen come from people who almost certainly expect BTC:
  - `bitcoin:` links go straight to the send screen (`AppServices.openBitcoinUri`, around line 1955).
    The new Windows installer offers this app as a `bitcoin:` handler.
  - BIP353 names (`wallet/PaymentController.java:199`) and PayNym (`paynym/PayNymService.java:271`).
  - **Payjoin is worse.** A BIP78 receiver may broadcast the original transaction as its fallback.
    A BTC receiver will do that on SHA256d, and if the original isn't opted in, the user's BTC moves.
    `payjoin/` has no awareness of the chain.
- **Proposal:**
  - When a payment came from a URI, a BIP353 name, PayNym or payjoin, show a persistent note on the
    send screen: "This request doesn't say which chain it is for. It will be paid on the BLAKE2b
    chain."
  - Only offer Payjoin when the original transaction is replay protected.
  - Longer term, propose a URI parameter that marks a request as BLAKE2b.

### 4. Opening upstream Sparrow's wallet files in place can damage them for upstream — S
- **Problem:** File > Open accepts any `.mv.db`, including files in `~/.sparrow/wallets`.
  `configureWalletsDir` then offers to make that folder this app's default (`AppController.java:1225-1246`).
  - Opening the file applies this fork's `V11__UnifiedSigHash` migration to upstream's file
    (`io/db/DbPersistence.java:507-511`). Upstream ignores it for now as a future migration. Once
    upstream ships its own V11, validation fails and upstream can no longer open the wallet. This is
    the README's migration-numbering hazard, but across the two apps.
  - BLAKE2b history and labels are also written into the other chain's file.
- **Proposal:** recognise upstream's home folders (`~/.sparrow`, `%APPDATA%/Sparrow`,
  `~/Library/Application Support/Sparrow`, XDG `sparrow`). Offer to copy the file into this app's
  wallets folder instead of opening it in place, and never offer to adopt that folder.

### 5. A wrong-chain server is retried every 15 seconds and shown as a long "Server Error" — S
- **Problem:** the refusal throws a plain `ServerException` (`net/ElectrumServer.java:3518`).
  - Only `ServerConfigException` stops the retries (`AppServices.java:362-364`), so the app retries
    every 15 seconds indefinitely.
  - The status bar shows "Connection failed: Server Error (…)" with the whole paragraph on one line,
    every 15 seconds. The terminal UI does the same.
  - The message says what is wrong (`VariableHeaders.java:322-362`) but not what to do about it.
- **Proposal:**
  - Add a `ChainMismatchException` that extends `ServerConfigException`, so the retries stop.
  - Show the refusal once, as a dialog: "This server follows the SHA256d chain. If you pointed Sparrow
    at an upstream electrs or Fulcrum, point it at one backed by a BLAKE2b node instead." Add an
    "Open Server Settings" button.
  - Use a short form in the status bar.

---

## Tier 2: Quick wins

Each is small and fixes a gap, a contradiction with the docs, or a confusing moment.

### 6. Point Help, About and Welcome at this fork, and link the split guide from the app — S
- **Problem:** the app's help, about and first-run screens still describe and link to upstream:
  - **Help menu:** Show Online Documentation, Get Support and Submit Bug Report all open
    sparrowwallet.com (`AppController.java:543-574`). The README tells users to report to
    `github.com/paulscode/sparrow/issues` because upstream can't act on this build.
  - **Connection failures:** they link upstream's `connect-node.html`
    (`settings/ServerSettingsController.java:726`, and the terminal's `ServerTestDialog.java:226`).
    That guide leads to a SHA256d node.
  - **About:** it says "it's possible to connect to a public Electrum server…", but public servers are
    withdrawn in this build. Its only link is upstream's donate page (`about.fxml:22-23`).
  - **Welcome:** "Welcome to Sparrow… a Bitcoin wallet" says nothing about the chain or what to
    connect to (`WelcomeDialog.java:15`, `welcome.fxml`).
  - **Split guide:** `docs/split-with-a-hardware-wallet.md` is not linked from anywhere in the app.
- **Proposal:**
  - **Bug reports:** point Bug Report at the fork's issues page, with a template that carries the
    version and network.
  - **Documentation menu:** add items for the fork's documentation and for "Splitting a hardware
    wallet". Keep upstream's documentation as a separately labelled item.
  - **Split guide links:** link the guide from the "Not replay protected" explanations, the
    device-marking dialog and the keystore's replay protection row.
  - **About:** rename the menu item to "About Sparrow (BLAKE2b)". Rewrite the server paragraph. Add the
    fork height, the repository link and the release key fingerprint. Credit upstream, with its donate
    link labelled as upstream's.
  - **Welcome:** add one page covering which chain this app follows, that replay protection is opt-in,
    that addresses are the same on both chains, and what to connect to.

### 7. Say "chain verified" when Test Connection succeeds — S–M
- **Problem:** `showConnectionSuccess` (`settings/ServerSettingsController.java:673-687`, and the
  terminal's `ServerTestDialog.java:181-193`) prints the server version and banner only. The chain
  identity result is worked out during the connection but never shown.
- **Proposal:** carry the result in `ConnectionEvent` and show it. For example: "Chain: BLAKE2b. Block
  961640 = 0000…, confirmed by the header the server sent." Also show the tip height, and "the server
  does not report its fork point" when it doesn't.

### 8. "Insufficient funds" never mentions immature coins on the usual send path — S
- **Problem:** the immature-aware message is only attached to the fee field, and only when the user
  has typed a fee (`wallet/SendController.java:533`).
  - The normal path shows "Insufficient funds". Its tooltip says "Insufficient confirmed transaction
    value" (`src/main/resources/.../wallet/payment.fxml:63-66`, `wallet/PaymentController.java:543`).
    That tooltip is also inaccurate, since unconfirmed outputs are included by default.
  - This is the support case `internal_docs/coinbase-maturity-ux.md` §4 was written for.
- **Proposal:** use `InsufficientInputsDescription` for the amount status and its validator. Optionally
  show "Spendable: X (Y immature, Z frozen)" beside Max.

### 9. A mined transaction shows no replay protection at all — S
- **Problem:** `refreshOptInStatus()` only labels unmined transactions
  (`transaction/HeadersController.java:1610-1615`). So a user can't check afterwards whether their
  step 3 split transaction (or any past spend) opted in. `updateFinalOptInStatus()` already handles
  final transactions.
- **Proposal:** add a "Protection" row to the mined-transaction form, filled by the same logic.

### 10. A balance row for coins created before the fork — S
- **Problem:** neither balance screen says how much of the wallet also exists on the SHA256d chain. The
  fork height is in `Blake2bDeployment.activationHeight(network)`, and every UTXO has a height.
- **Proposal:** add a row beside Immature: "Pre-fork: X", hidden when zero, with a tooltip that links
  to the split guide. Say "also exists on SHA256d unless already spent there", never "unsplit".

### 11. Make the transaction tab's labels before signing accurate — S
- **Before signing:** the tab says "Replay protected" as if it had been verified
  (`HeadersController.java:1121-1126`, `:1157-1159`, `:1225`). It also drops the send screen's
  multisig caveat ("protected only if one of the marked signers signs").
  - **Proposal:** "Will be replay protected when signed", followed by the marked-signer caveat.
- **Silent payment sends (probable bug):** for a silent payment the tab forces the plain sighash type
  and labels from it (`HeadersController.java:567-571`). `createPSBT` has already set the inputs to
  the opt-in (`AppServices.java:1562-1569`). So the tab tells the user to redo a transaction that may
  already be fine.
  - Not yet confirmed: that signing actually keeps the opt-in for a silent payment.
  - **Proposal:** build the combo from `requiredSigHash.withUnified()` when the inputs carry the opt-in.

### 12. Fix the device-marking dialog's gaps — S
- **Watch-only wallets:** the replay label is clickable for them (`canBeMarked` includes watch-only),
  but `UnifiedSigHashKeystoreDialog` lists only hardware keystores, so the click leads nowhere. Either
  make the label explain itself or don't make it clickable.
- **Supporting firmware:** the dialog says "only you can say which of these support the opt-in" and
  names none (`control/UnifiedSigHashKeystoreDialog.java:39-44`). The guide does name them:
  - Trezor `unified-sighash.2`
  - Coldcard `v5.6.3U-1.5.3QU`
  - SeedSigner `v0.8.7-unified-sighash.6`
  - Krux `v26.09.0-xbt.1`

  Show these beside each model, labelled "known as of this release".
- **Stock firmware:** add "Stock firmware? You can still split by including a coin only this chain
  has", with a link to the guide.
- **Wording:** "Supported by this device" and "pre-fork rules" are jargon. Software seed wallets show
  no protection row at all (`wallet/KeystoreController.java:423`).

### 13. Fee defaults that suit this chain — S (a), M (b)
- **(a) Fallback fee:** the mainnet fallback is 20 sat/vB (`AppServices.java:130`). It is used offline
  and before the first connection. The guide quotes 1 sat/vB fees on this chain, so transactions built
  offline overpay about 20 times without saying so.
  - **Proposal:** fall back to the minimum, labelled "no estimate, using the minimum".
- **(b) Recent Blocks with no fee rates:** Recent Blocks is the default view, but per-block fee rates
  only come from the Full Node server.
  - So electrs and Fulcrum users (the StartOS and Umbrel setup the guide recommends) see block cubes
    with no rates (`net/ElectrumServer.java:2515`, `:2553-2567`; `net/FeeRatesSource.java:223`).
  - **Proposal:** derive a next-block rate from `mempool.get_fee_histogram`, which is already fetched.
    Default to Target Blocks when the server has no block statistics.

### 14. Explain this chain's broadcast rejections in plain words — S
- **Problem:** `HeadersController.java:1855-1890` explains only min relay fee, dust, `missingorspent`
  and mempool min fee.
- **Proposal:**
  - **`bad-txns-premature-spend-of-coinbase`:** explain it. Use the "depth N" in the message to raise
    this session's depth for that coin, only ever upwards (sync plan item 10).
  - **`missingorspent`:** add "if these coins were spent on SHA256d without replay protection, a copy
    of that spend may have spent them here too".
  - **Invalid sighash type:** say the build may be out of date.
  - **RDTS failures:** name the rule that failed.

### 15. Check RDTS limits when a PSBT is loaded, not only at broadcast — S
- **Problem:** the output check runs only in `broadcastTransaction` (`HeadersController.java:1752`). So
  a foreign PSBT is signed on a hardware wallet first and only then refused.
- **Proposal:** warn on the transaction tab as soon as a PSBT is loaded (sync plan item 15, step 3).

### 16. Say so when a signer added no signatures — S–M
- **Problem:** after signing on a device, scanning a QR or loading a file, the PSBT is combined without
  checking that anything new was signed (`HeadersController.java:1559-1567`). The guide says stock
  SeedSigner skips the input rather than signing it.
- **Proposal:** if nothing new was signed and the PSBT asks for the opt-in, say so. Point to unmarking
  the device under Replay protection, with a button that opens the marking dialog.

### 17. Fee bumps and cancels don't touch a copy on the other chain — S
- **Problem:** Cancel Transaction and Increase Fee (`control/EntryCell.java:232-361`) don't mention the
  other chain.
- **Proposal:** when the original wasn't protected, add one line: "This does not cancel a copy that may
  already be on the SHA256d chain."

### 18. Smaller first-run and connection fixes — S each
- **First-run Settings:** it offers only Create New Wallet (`SettingsDialog.java:44-46`). Add Import
  and Restore.
- **No server configured:** the app shows a disabled toggle and no hint of what to do
  (`SparrowDesktop.java:65-73`). Prompt the user to set up a server.
- **Discover Wallet in Full Node mode:** it is offered, then refused
  (`MnemonicWalletKeystoreImportPane.java:57-66`, `:154`; `DevicePane.java:1070-1073`). Hide it in
  that mode, or explain why it isn't available.
- **Fee settings help:** it mentions "an external source" (`general.fxml:42`), but those are withdrawn.
- **Fiat source disclosure:** the Neoxa price source also calls CoinGecko to convert currencies, even
  for USD users. The picker should say so, and could skip CoinGecko for USD.
- **Version in the log:** the log never records which build wrote it, and Cormorant reports
  "Cormorant 2.5.6" without the `-blake2b.N` suffix. Log the full version, network, home folder, Java
  version and server type at startup.

### 19. Fork identity in the terminal UI (Sparrow Server) — S–M
- **Problem:** the terminal UI doesn't identify the fork or relay its warnings:
  - The title is "Sparrow Terminal" (`terminal/SparrowTextGui.java:42`), with no version.
  - Nothing in the terminal shows the activation-height warning that the desktop shows.
  - Connection failures lack the desktop's hidden-service and cookie hints.
- **Proposal:**
  - Title "Sparrow (BLAKE2b) 2.5.6-blake2b.N".
  - Show the activation-height warning on a status line.
  - Show the chain line from #7 in the terminal's Test Connection.
  - Add an About / Diagnostics entry.

### 20. Accessibility of the fork's own indicators — S
- **Problem:**
  - `control/UnifiedSigHashStatusLabel` is a graphic whose meaning lives only in its tooltip. It has no
    accessible text, unlike `UsbStatusButton` and upstream's recent accessibility work.
  - The BLAKE2b badge can't be reached by keyboard.
- **Proposal:** give the label accessible text. Make the badge focusable, and let it open #27.

---

## Tier 3: Has a deadline

### 21. After 2027-09-01, new block rewards will look locked for six months when they need only 100 blocks — S–M
- **Problem:** `LongCoinbaseMaturity` holds every coin mined from 979919 onward to 25920 blocks
  (`drongo/.../protocol/LongCoinbaseMaturity.java:34-65`). Knots drops new rewards back to 100 blocks
  once the parent block's median time past reaches the RDTS expiry.
  - `ReducedDataRules.isActive` uses the wall clock plus two hours rather than median time past.
  - The error is in the safe direction. But from the expiry date, every solo miner's new reward would
    show, and be filtered, as locked for about six months.
- **Proposal:**
  - Work out the parent block's median time past from the header store. `HeaderChainState` already
    computes median time past privately, around line 194.
  - Pass it into `maturityDepth`, and use the tip's median time past for the RDTS check too.
  - Fall back to 25920 whenever a header is missing.
  - Ship well before 2027-09-01, with tests at the boundary.
- **Before starting:** check against the tagged Knots source at the time, since this schedule has
  already moved twice.

---

## Tier 4: Larger investments

These have the biggest effect on everyday use, at more cost. The order reflects the dependencies
between them.

### 22. Remember chain-only proofs across restarts — M
- **Problem:** proofs live only in memory (`ChainOnlyCoins.java:73-74`), and a proof needs the hot
  wallet open (`ElectrumServer.java:4162`).
  - So after a restart, every spend from a hardware wallet split as the guide describes reads "Not
    replay protected", unless the hot wallet is reopened.
  - Once the coin is more than `MAX_DEPTH` = 6 generations from the split, it can never be proven
    again.
  - The guide's "do a fresh step 3 every 6 batches" works around this.
  - This contradicts guide step 8, and teaches users to ignore the label.
- **Proposal:** store each proof (txid → proving txid and input), per network. A proven ancestor then
  ends the search at any depth.
  - Storage: either a separate app-level file, which avoids the migration-numbering hazard, or a V12
    wallet migration.
  - A stored proof was made under the same own-key rule. Keep the `CAVEAT` text.

### 23. Show each coin's chain status on the UTXOs tab — M
- **Problem:** only the transaction being built shows split status. Users can't see which coins still
  need splitting, which is the guide's "Leaving a coin out of step 5" mistake.
- **Proposal:** add a "Chains" column with these states:
  - Both (pre-fork)
  - This chain only, with a tooltip naming the proof
  - Mined here (not counted as protection, matching `ChainOnlyCoins.java:229-232`)
  - Not proven
  - Checking…

  Also:
  - A filter, and a "Select every coin that also exists on SHA256d" action.
  - "View proof" in the context menu.
  - Status and Chains columns in the CSV export.
  - `ChainOnlyCoinsService` stops at the first proven coin, so this needs a variant that checks every
    coin. Reuse the existing fetch budget.
  - It must never turn "not known" into a claim.

### 24. Coin control that helps protect a send — M (after #23)
- **Problem:**
  - When the signers can't opt in, the send screen says "Not replay protected", even when the wallet
    holds a proven chain-only coin that could fix it.
  - Excluding inputs from a fee bump (`SendController.java:1829-1850`) gives no warning when the
    excluded input is the protecting one. The guide lists this under "Mistakes that undo it".
- **Proposal:**
  - Make the label actionable: "Include a chain-only coin", with a note about the privacy cost of the
    extra input.
  - Confirm before excluding the protecting input.

### 25. Detect hardware wallets that can opt in — S–M
- **Problem:** Trezor capability 29 is read only at signing time, in lark
  (`lark/.../trezor/TrezorDevice.java:402-428`). Marking a device is otherwise manual. An unmarked
  device that could opt in is given the plain hash type, so its protection is silently lost.
- **Proposal:**
  - When a Trezor that reports the capability is imported or connected, offer to mark it.
  - Check whether Coldcard's "U" firmware can be recognised from its version string.
  - Leave every other device manual. A wrong mark makes the device refuse to sign, so the failure is
    safe.

### 26. A "Split this wallet" assistant — L (after #22 and #23)
- **Problem:** the hardware wallet split is eight manual steps across two wallets, with documented
  traps: missing a coin, the hot wallet closed, too many generations.
- **Proposal:** a checklist that:
  1. chooses or creates the hot wallet, with a seed backup;
  2. prefills the small send to it;
  3. prefills the opt-in send back to a fresh hardware-wallet address, requiring "Replay protected";
  4. tracks the confirmation;
  5. prefills the consolidation with every coin selected (batched as the guide says), refusing to
     continue unless the label reads protected and the hot wallet is open;
  6. ends with step 7 as a reminder the app can't verify.

  More:
  - Label the coins involved automatically.
  - Offer a separate receiving account for this chain, so addresses aren't shared with upstream
    Sparrow (see #33).
  - Optional privacy mode: one opt-in fan-out, then each pre-fork coin split on its own, so the
    consolidation doesn't link every address.
  - Every step stays an ordinary, reviewed send. Never say "split complete", because the coin can be
    re-signed until it is spent on SHA256d.
- **Cheaper first version:** a "Select all for split" button.

### 27. A chain and connection diagnostics panel, opened from the BLAKE2b badge — M
- **Problem:** the data already exists but nothing shows it together:
  - the chain identity result, and the server-reported versus built-in fork height;
  - the announced and verified tips, and the header store state;
  - Tor and proxy state, fee and fiat source status;
  - the log path.

  Header sync shows no progress.
- **Proposal:** a read-only dialog showing all of it, with a "Copy for bug report" button.

### 28. Guided server setup with presets — M
- **Problem:** this build only works with your own server, so setup is the main barrier to adoption.
  - The Private Electrum form is bare: host, port, SSL and certificate.
  - The README and guide recommend electrs on StartOS or Umbrel, but the app doesn't help with either.
- **Proposal:**
  - A "Common setups" chooser that fills in the details: StartOS electrs (.onion, 50001, TCP), Umbrel
    (`umbrel.local:50001`), local electrs, and Fulcrum (50002, SSL).
  - Short instructions on where to find each value.
  - Test Connection runs automatically and shows the chain result from #7.
  - Optional probing of localhost only.

### 29. A signed update check, and the fork's key in Verify Download — M
- **Problem:**
  - Update checks are disabled (`AppServices.java:122`).
  - The update feed is upstream's, and its version comparison ignores `-blake2b.N`
    (`VersionCheckService.java`).
  - drongo bundles upstream's signing key and not this fork's. So verifying this fork's
    `SHA256SUMS.asc` needs the key supplied by hand, while an upstream release verifies cleanly
    (`DownloadVerifierDialog.java:331`).
- **Proposal:**
  - Bundle the fork's key, FF76…0E1A. Have Verify Download warn "valid, but this is upstream Sparrow"
    for releases signed with upstream's key.
  - Then add an opt-in update check: GitHub releases fetched over Tor or the proxy when set. Trust the
    version only from file names inside the signed `SHA256SUMS`. Notify only, never install.
  - Consensus-relevant releases, such as a changed maturity schedule, are why this matters.

### 30. Let the server supply chain rules, with the built-in values as a floor — M
- **Problem:** the activation height, the maturity schedule (changed twice) and the RDTS expiry are all
  compiled in.
- **Proposal:**
  - Extend `server.features` with a `chain_rules` object, filled by Cormorant from the node where Knots
    exposes it.
  - The wallet only ever takes the stricter of the built-in and served values.
  - Any disagreement raises a "this build may be out of date" warning, the way
    `UnifiedSigHashScheduleEvent` does.
- **Needs:** the electrs fork, a written spec, and Fulcrum's cooperation. Ties in with #21.

### 31. Features for solo miners — S–M
- **Notifications:** the new-transaction notification doesn't say a payment is a mined reward.
  - Say "Mined reward: X, spendable from block N (about 6 months)".
  - Notify again when rewards mature.
- **Maturity calendar:** unlock heights and approximate dates, summed by month, with CSV export.
- **Send Selected:** explain why it is disabled when only immature coins are selected. Its tooltip
  still reads "Use Ctrl+click to select multiple".
- **Rewards mined here:** when every input is a reward mined on this chain after the fork, add a
  neutral "mined on this chain" note, without claiming protection.

### 33. Replay context in the history and on Receive — M
- **Transactions tab:**
  - Mark outgoing transactions as protected, not protected or unknown, with "also valid on the
    SHA256d chain" where it applies.
  - Add a fork marker in the list and the balance chart.
  - Optionally flag confirmed outgoing transactions this app didn't create and that have no opt-in
    signatures: "not created in this app, possibly a copy of a SHA256d spend".
- **Receive:** note that the address is also a valid Bitcoin address. Label "Last used" as "on this
  chain". Suggest a separate account so receiving addresses don't overlap with upstream Sparrow's.
- **Wording:** never present anything about the other chain as fact. Point only to upstream Sparrow,
  so the wording can't be used for phishing.

### 34. Smaller long-term items
- **Other-chain explorer:** an explorer setting for the other chain, empty by default, for guide
  step 7. It is an explicit action that discloses the txid, and it never feeds a label.
- **Exchange rate:** a sanity bound to catch a BTC-like price, and a second exchange-rate venue.
- **Send screen layout:** replace the long protection tooltips (about 900 characters, hover-only, not
  copyable) with a short label and a Details dialog. Check that the send screen's footer doesn't
  overlap at narrow widths (*to confirm* on screen).
- **Settings defaults:** default to a BLAKE2b node's data folder and port if they differ from Bitcoin
  Core's (to confirm with the Knots build).

---

## Suggested grouping into releases

- **Next release (.8 or .9):** Tier 1 (#1 to #5) and the Tier 2 items that are only text or links
  (#6, #8, #12, #18). #1 needs the live Knots test anyway.
- **Following release:** the rest of Tier 2, and #21 (well ahead of 2027-09-01).
- **Then:** #22 and #23 together. They underpin #24 and #26, and close the biggest gap between what the
  guide promises and what the app shows.
- **Ongoing:** #27 to #34 as time allows. #29 and #30 are the long-term answer to the chain's rules
  changing under users.

## Declined

- **Block-time wording (was 13c):** the difficulty adjustment pulls the chain back to 10-minute
  blocks, so wording based on today's faster interval would only be right for a while.
- **Showing "XBT" instead of "BTC" (was 32):** XBT is an exchange ticker, needed only because BTC was
  taken. BTC is the unit of account on this chain too. Item numbers are kept, so 32 is skipped.

## Already handled (checked, not proposed)

- **Coinbase maturity:** the Immature balance row, status cell, tooltips, terminal parity, and the
  spendability filter.
- **Protection labels:** the send-screen label with its reason, and the transaction tab's three states,
  the chain-only check and the ACP/SINGLE check.
- **RDTS check:** the output check runs at broadcast.
- **Explorers:** old mempool.space and blockstream explorer settings are migrated.
- **Fee and fiat defaults:** chosen by name on a fresh install.
- **Public servers:** hidden on desktop, and explained in the terminal UI.

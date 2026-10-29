# Upstream and Shrike: what to take, and in what order

## Why this exists

On 2026-10-15 we reviewed everything that has happened in the projects this fork tracks or runs
alongside, since the 2.5.6 merge:

- **Upstream** (sparrowwallet/sparrow, drongo, lark): 39, 5 and 2 commits past what we merged.
- **Shrike** (privkeyio/shrike, drongo, lark): an independent fork for the same chain, with 213
  Sparrow commits we don't have. Most are branding or its own versions of things we already do.
  Some are fixes to bugs that we have as well.
- **Issues and PRs** across all nine repositories.

Everything was compared against `blake2b` at `98faeb96`, which is the tag `v2.5.6-blake2b.6`. That is
what the latest release was built from, so every finding below describes released code unless it says
otherwise. Submodule pointers at that tag: drongo `3039857b`, lark `0a9aa329`.

This document records what was found, so that it survives the conversation it came out of, and how each item
was to be done. **As of 2026-10-29 most of it has landed** on `replay-safe-inputs` (with drongo and lark on their
`next-release` branches) for 2.5.6-blake2b.7; the table below says which. Items 16 and 18 are deliberately
deferred.

**Updated 2026-10-29** after a second look at both projects:

- upstream sparrow `abec7909..abb1c2cc` (59 commits), drongo `499a656..13059eb`, lark
  `07a467f..d6fa9ed`;
- shrike sparrow `f2a00271..86345c91` (its releases .28 and .29), drongo `8ddb165..c792a05`, lark
  `..2413cc3`.

New items: 19 and 20. Item 10 was rewritten against Knots v29.4.3. Item 9 gained 9a, a release blocker for
the merge, and 9b.

## Where things stand

| # | Item | Severity | Kind of work | Status |
|---|---|---|---|---|
| 1 | Header sync accepts v1 headers above the fork | **Critical** | drongo, new code | Done |
| 2 | A private key sweep never opts in | High | Port shrike 6f0f4021 and ca96c5ee | Done |
| 3 | The PayNym notification transaction never opts in | High | One line | Done |
| 4 | The transaction screen's replay label goes stale | High | Port the idea of shrike 431fdcb8 and #28 by hand | Done |
| 5 | Bitcoin Core mode builds an 80-byte tip | High (if Core mode is supported) | Adapt shrike 1fb0355a | Done; not yet run against a live node |
| 6 | Finalizing can discard the only opted-in signature | High | Port shrike drongo 33edc7c by hand | Done |
| 7 | drongo signature-handling hardening | Medium | Port four shrike drongo commits | Done |
| 8 | The udev rules dialog points at upstream's install path | Medium | Adapt shrike 40d20732 | Done |
| 9 | Merge upstream (sparrow, drongo, lark) | Medium; **blocked on 9a** | Merge, plus build and CI hand edits | Done (merged untagged upstream master at abb1c2cc, with 9a) |
| 10 | Coinbase maturity: Knots 29.4.3 holds rewards mined from 979919 for 25920 blocks | **High**, time-bound | drongo `LongCoinbaseMaturity` per coin, plus wording | Done |
| 11 | CI runs the tests on every push | Medium | Port shrike #54 | Done |
| 12 | Electrum client robustness, and the `blake2b_fork` spec | Low to medium | Spec text and small client fixes | Client half done; the `blake2b_fork` spec lives outside this repository |
| 13 | Smaller items | Low | Various | Done, except the download verifier |
| 14 | Repository hygiene | Low | Local git configuration | Done (local tags and tagOpt; the fork's stale `master` left alone) |
| 15 | Wallet-built transactions against the RDTS rules | Medium | Audit, plus tests pinning the limits relied on | Done (output rule checked at broadcast) |
| 16 | A split from the SHA256d side, using RDTS | Low, research first | Research, then possibly a guide section | Not started (research) |
| 17 | Whether watch-only wallets count as the user's keys | Decided: they don't | Done with the chain-only-coins work | Done |
| 18 | An explicit "these keys are mine" flag on watch-only keystores | Low, only if users ask | Migration, toggle, test | Not started (only if users ask) |
| 19 | The bundled Tor can no longer join the network | **Critical** | Dependency bump (shrike #88) | Done, bootstrapped live |
| 20 | Server settings throw on the empty public server list | Medium | One line (shrike #85) | Done |

Suggested order (revised 2026-10-29): 19 and 10 first, in one release, since both are live for users now, with 20
alongside; then 1, then 2 and 3 together, then 4 (alongside the chain-only-coins work it overlaps), 5,
6 and 7 as one drongo batch, then 8, 11 and 9 when upstream tags.

---

## 1. Header sync accepts v1 headers above the fork (critical)

**What is wrong.** `HeaderChainState.add` (`drongo/.../protocol/HeaderChainState.java:55`) checks four
things: that the header links to its parent, that it claims the required difficulty bits, that it meets
its own proof of work, and that its time is after the median time past. It never checks the header's
*version* against its height. A v1 header's proof of work is SHA256d, so a v1 header is checked as
SHA256d whatever its height.

At 961640 the required target is eased 2^22-fold (`applyTargetShift`) to suit BLAKE2b. An attacker can
mine v1 headers from 961639 onward at that eased target with ordinary SHA256 ASICs. At about 4 million
times easier than SHA256 mainnet, that is cheap. Retargets then proceed from there, and our state
machine accepts every one.

The fork-height pin (`Blake2bDeployment.activationBlockHash`) is only compared in
`VariableHeaders.getServedHeaderError`. That is a single header fetched at connect. Nothing ties
the stored header at 961640 to the pin, so a server can pass the connect check and then serve a
different chain during chunk sync.

**What it costs.** A malicious server could get a forged header chain into the header store, then
"prove" transactions against it. Header verification exists to stop exactly that.

**How to fix it.** In `HeaderChainState.add`, before the proof-of-work check:

1. Let `activation = Blake2bDeployment.activationHeight(Network.get())`. If it is not `NOT_DEPLOYED`
   (`Integer.MAX_VALUE`), require `header.isV2() == (height + 1 >= activation)`. Otherwise throw a
   `VerificationException` naming the height and the version found. Shrike does this in d01e0c3 as
   `requireExpectedHeaderVersion`. Read theirs, but write ours against our `Blake2bDeployment`.
2. At `height + 1 == activation`, also require
   `header.getHash().equals(Blake2bDeployment.activationBlockHash(net))` wherever that hash is non-null
   (mainnet only, today). Shrike doesn't do this half. It turns the connect-time pin into a property
   of the stored chain.
3. Regtest is `NOT_DEPLOYED` but can carry v2 headers. Make no version rule there, as today.
   Testnet4 has an activation height and no pinned hash: apply rule 1 only.

The reorg path re-walks a throwaway `HeaderChainState` from the anchor (see the comment at line 168),
so it gets the new rule without further work. Check that `HeaderStore.load` re-verification and
`chainStateAt` both go through `add`.

**Ported fix with it (A2).** `drongo/.../protocol/Block.java:22` hashes a block with SHA256d whatever its
header version. Use `blockHeader.getHash()` instead (shrike 69ecf14). Nothing in the app constructs a
`Block` today, so this is latent, but it is a one-line fix and belongs in the same commit.

**Tests.** In `HeaderChainStateTest`:

- A v1 header at the activation height with correct linkage, bits and SHA256d work is rejected.
- A v2 header below the activation height is rejected.
- A v2 header at the activation height whose hash is not the pinned one is rejected on mainnet.
- Real headers 961632–961641 are accepted end to end. Shrike has these in drongo
  `src/test/resources/headers/mainnet-activation.txt`, and they have been checked to link under our
  hashing. Port that file and Shrike's `testnet4_v2_headers.tsv` as ground truth.

**Release note.** Existing header stores were verified without this rule. `HeaderStore.load`
re-verifies from the anchor on every load, so a store with a v1 header above the fork is discarded
and re-downloaded automatically. Confirm that, and say so in the release notes.

---

## 2. A private key sweep never opts in (high)

**What is wrong.** `PrivateKeySweepDialog.java:480` builds `new PSBT(transaction)` and signs it with
the swept key, with no unified sighash. Paper wallets and other pre-fork keys are exactly the coins
that exist on both chains, so every sweep is replayable on the SHA256d chain. The user ends up with
nothing to show for the XBT side.

**How to fix it.**

- Port shrike 6f0f4021. It cherry-picks cleanly. It makes `AppServices.applyUnifiedSigHash` public and
  applies it to the sweep PSBT.
- Port shrike ca96c5ee, which refactors the sweep into `sweepPsbt()`. It also sizes the fee with
  `TransactionSignature.dummy(...)` at `SigHash.UNIFIED_ALL`. A Schnorr signature with a non-default
  hash type is 65 bytes, not 64, so without this a taproot input is undercounted by one byte and the fee
  comes out slightly low. At the minimum fee rate that can fall below relay.
- **The decision.** The swept key is a software key, so it can always produce the opt-in. Whether to
  apply it depends only on the chain. There is no keystore to ask, so use the chain half of the
  decision (`AppServices.chainDecision` / the network-level `isUnifiedSigHashActive`), not the wallet
  half. Check what shrike passes and make sure an offline sweep doesn't silently skip the opt-in.
  Either decline loudly or follow the shipped schedule.
- Show the outcome in the dialog ("Replay protected" or the reason it isn't), with the same wording the
  send tab uses.

**Tests.** Port shrike's `SweptKeyOptInTest`. Add a case that the taproot sweep's virtual size includes the
65-byte signature.

---

## 3. The PayNym notification transaction never opts in (high)

**What is wrong.** `PayNymController.java:548` calls `finalWalletTx.createPSBT()` directly. Every other
spend goes through `AppServices.createPSBT(walletTransaction)`, which applies the opt-in decision. This
transaction is signed by a software wallet, so the opt-in is always available.

**How to fix it.** Call `AppServices.createPSBT(finalWalletTx)`. Also grep for any other direct
`.createPSBT()` on a `WalletTransaction` outside `AppServices` and treat each the same way. Upstream's
3c1e6560 touches the same area (the broadcast failure message), so do this after the upstream merge
or expect a trivial conflict.

**Tests.** A wiring test in the style of `SignatureOptInWiringTest`, asserting that no call site outside
`AppServices` builds a PSBT from a `WalletTransaction`.

---

## 4. The transaction screen's replay label goes stale (high)

**What is wrong.** `HeadersController.updateOptInStatus` runs only in three places:

- `initializeView` (line 586),
- the sighash combo listener (line 587),
- the uncommitted chain-only-coins callback.

None of these handlers recompute it: `keystoreSigned` (2118), `psbtCombined` (2073),
`psbtFinalized` (2088), `finalizeTransaction` (2045), `openWallets` (1972), `transactionChanged` (1863),
`walletHistoryChanged` (2165), `newBlock` (2217), `connection` (2258), and the `signingWalletProperty`
listener (609).

Two consequences:

- **A false "protected".** A fresh PSBT with no signatures and a unified declaration reads "Replay
  protected" from the declaration. A device that can't opt in then signs the base-type copy from
  `psbtForDevice`, and the label goes on saying protected over a transaction that isn't.
- **A stuck "not checked".** A signed PSBT opened from a file is evaluated before `requestOpenWallets`
  sets the signing wallet. It reads "not checked" and stays that way.

**Done for PSBTs (with the chain-only-coins work).** `refreshOptInStatus()` now runs at the end of
`updateSignedKeystores`, which every signature-changing path goes through (signing, `psbtCombined`,
`psbtFinalized`, the signing-wallet listener), and from `finalizeTransaction`. The background lookup's
callback uses it too. Its result is read from the shared caches against whatever the PSBT is at the
time, so it can't paint one PSBT's answer onto another. What follows is the original plan, kept for
the final-transaction half and for the handlers not yet covered: `openWallets`, `transactionChanged`,
`walletHistoryChanged`, `newBlock` and `connection`.

**How to fix it.** Add one `refreshOptInStatus()` that calls `updateOptInStatus(sigHash.getValue())` on
the FX thread. Call it from each handler above, after the handler has done its own state changes.
Shrike's version is in 431fdcb8 and #28 (1bae88c6). Port the idea, not the diff: #28 has eight
conflicts with our `HeadersController`.

Do this together with the chain-only-coins work, which adds a background lookup feeding the same label:

- The background lookup's callback must call the same refresh.
- A lookup started for one PSBT must not paint its answer onto another. Compare the creating txids it
  was started with against the current PSBT's before applying the result.

**Final transactions** (P6 from the review). `updateOptInStatus` only runs on the PSBT path, so a
final transaction shows an empty field. At minimum, say "Replay protection not checked" and how to
check it (shrike #80, f2a00271). Better, since we have the machinery:

- run `ChainOnlyCoins` on the final transaction's inputs;
- verify the transaction's own signatures through `Wallet.getSignedKeystores(Transaction)` once item 7's
  15cbcd9 port gives that a unified branch.

**Tests.** A controller-free test of whichever decision function the refresh calls, fed PSBT states
before signing, after an unmarked signer signs the device copy, after a combine, and after finalizing.

---

## 5. Bitcoin Core mode builds an 80-byte tip (high, if Core mode is supported)

**What is wrong.** In Cormorant (Bitcoin Core / Knots mode), `BitcoindClient` gets the tip from
`getblockheader <hash>` in verbose form. `VerboseBlockHeader.getBlockHeader()` then rebuilds an
80-byte header from the JSON fields. That happens at:

- `BitcoindClient.java:130`, at connect;
- line 153, after the initial block download wait;
- line 762, on each new block.

A 164-byte v2 header can't be represented that way. The rebuilt tip has the wrong hash, and depending
on how its version word reads, either fails to parse or is taken as v1. That puts
`AppServices.chainDecision` (which branches on `blockHeader.isV2()`) at `CHAIN_NOT_ACTIVATED`, and the
wallet never opts in.

`blockchain.block.header` (`ElectrumServerService.java:169-176`) already fetches raw hex, so only the tip
is affected. This has not been run live; confirm it against a Knots node before and after.

**How to fix it.** Adapt shrike 1fb0355a, which cherry-picks cleanly. It uses
`getBlockHeader(hash, false)` and constructs `ElectrumBlockHeader(height, hex)`. One improvement on
theirs: at line 762 they take the height from a separate `getblockchaininfo` call, which can disagree
with `lastblock` when a block arrives between the two calls. Take the height from the verbose response
for the same hash (we already fetch it), and the bytes from the raw call. Or fetch the raw header by
height through the same path `blockchain.block.header` uses.

Then remove `VerboseBlockHeader.getBlockHeader()`, so nothing can build a header from JSON again.

**Follow-on (optional).** Shrike 626e0954 reads `getdeploymentinfo` to corroborate the activation height
in Core mode. Today we only take a node's activation height from Electrum's `server.features`
(`blake2b_fork`), so in Core mode `nodeHardforkHeight` is always unset. It has three conflicts with our
code; do it as a separate change.

**Tests.** A unit test that the tip header passed on from a canned v2 raw `getblockheader` response
round-trips to the BLAKE2b hash. Then a manual run against regtest Knots past activation.

---

## 6. Finalizing can discard the only opted-in signature (high)

**What is wrong.** drongo `Wallet.finalise` passes every partial signature to the script builder, and
`getMultisigScriptSig` keeps the first `threshold` of them in key order. When a quorum holds more
signatures than it needs, and the device that opted in sorts last, its signature is discarded and the
transaction goes out unprotected. A 2-of-3 combined from parallel signings is enough to get there.

**How to fix it.** Port shrike drongo 33edc7c (`retainThreshold`, their #7) and wire it as shrike
sparrow 04daf3e6 (#59) does:

- `retainThreshold` keeps verified opted-in signatures first, then fills up to the threshold.
- The `Wallet.java` half auto-merges. The `PSBTInput.java` half conflicts.
- Theirs calls `getVerifiedPartialSignatures`, which we don't have. Write it against our
  `getVerifiedSignatures(Set<ECKey>)`, which already returns key→signature.
- Take two smaller pieces from the same commit: `namesItsKeys` ignoring a tap key-path field on a
  non-taproot input, and the verification cap counting checks made rather than signatures present.

Upstream 40f77206 (merged in item 9) trims the progress bar for surplus signatures. Make sure the two
agree on which signatures survive.

**Tests.** Port shrike's `QuorumFinaliseTest`. Our `VerifiedSignaturesTest` was deleted on our side, so
rebuild what it covered.

---

## 7. drongo signature-handling hardening (medium)

All in `drongo/src/main/java/com/sparrowwallet/drongo/`. Do these in one batch with item 6, since they
touch the same files.

| Shrike drongo | What we lack | Effect today | Effort |
|---|---|---|---|
| 3061687 | `PSBTInput.verifySignatures` checks each signature under whatever hash type it carries | A partial signature with `SIGHASH_NONE` or `SINGLE\|ACP` on an input that declares ALL verifies and is finalized. That is looser than upstream, which accepts only the declared type. | Moderate: `isRequestedType` (the declared type, with or without the unified bit) and `requireDigest` |
| 15cbcd9 (**now a merge prerequisite, see 9a**) | `Wallet.getSignedKeystores(Transaction)` has no unified branch, and `getHashForUnifiedSignature` throws on a reserved taproot unified byte | "Signed by" (`HeadersController:965`) is blank for opted-in signers of a broadcast multisig | Easy: copy the private helper and the static `Transaction.getTapLeafHash` |
| f496dc0, dc23de1 | `getVerifiedSignatures` calls `getSigningScript()` without a guard | A P2SH input with no redeem script and a script sig with no push throws an NPE out of `AppServices.signatureOptInCounts`, unguarded at `HeadersController:1098`. A crafted PSBT breaks the transaction tab. | Trivial |
| 065a256 | `PSBT_IN_TAP_SCRIPT_SIG` (0x14) is dropped at parse; an off-curve key throws `IllegalArgumentException` from `PSBT.verifySignatures()` (`AppController:2108`) | A combine loses taproot script-path signatures; a crafted PSBT can throw | Moderate |

Also note, and leave for later:

- Shrike's unmerged `bound-derivation-fallback` branch caps `getSigningNodeFromDerivation` at 50,000
  derivations per transaction; they measured 9 s on a hostile PSBT. Our opt-in count calls
  `getSigningNodes(psbt, false)` with the fallback off, so we are mostly not exposed.
- Shrike also caps checks at 4096 per transaction in the opt-in count, where ours caps per input.
  A crafted PSBT can only stall the UI thread.

**From the same review, in `AppServices.signatureOptInCounts`** (P7):

- **Anyone Can Pay.** We count liftable Anyone Can Pay signatures only among the verified ones, so an
  unverifiable co-signer's ACP signature never raises the "can be lifted" warning. Count every candidate
  for the warning, as shrike does. A warning that is withheld is the dangerous direction.
- **Taproot script-path signatures.** These aren't in `total`, so a PSBT carrying only those reads
  `total=0` and falls back to the declaration. Once 065a256 keeps them, count them.

---

## 8. The udev rules dialog points at upstream's install path (medium)

**What is wrong.** `AppController.installUdevRules` (lines 602–611) hardcodes `/opt/sparrowwallet` and
`bin/Sparrow`. Our image is "Sparrow BLAKE2b", so the commands it shows point at a directory we never
create, and Linux users following them get no hardware-wallet rules.

**How to fix it.** Adapt shrike 40d20732. It applies cleanly but uses their name. Derive the path from
the running image rather than hardcoding a second name, and quote it: our launcher path contains a
space.

While in `build.gradle`: `--linux-deb-maintainer mail@sparrowwallet.com` is still upstream's address.
It only matters if we ship a .deb, but it is wrong.

---

## 9. Merge upstream (medium; waiting on a tag)

**Where upstream is.** Upstream has not tagged 2.5.6. Its last tag is 2.5.5, and "bump to v2.5.6"
(f7f36d00), which we merged as our 2.5.6, is untagged master. The README says we merge upstream "as
they are released". Either wait for 2.5.7, or decide that untagged merges are the policy now and say so.
Two drongo fixes are worth cherry-picking ahead of a merge (below).

**What merges.** `git merge-tree` shows only the drongo and lark submodule pointers conflict. Every
other overlapping file merges cleanly: AppController, AppServices, Config, DbPersistence, ElectrumServer,
HeadersController, KeystoreController, SendController, SettingsWalletForm, UtxosController, WalletForm,
WalletTransactionsEntry and build.gradle. Upstream adds no new migration, so `V11__UnifiedSigHash.sql`
is safe.

**How to do it.**

1. In drongo, merge `upstream/master` into `blake2b`. Clean.
2. In lark, merge `origin/master` into `blake2b`. Here sparrowwallet/lark is the `origin` remote; there is
   no `upstream` remote. Clean.
3. Push both to `fork`.
4. In the superproject, merge `upstream/master` and resolve the submodule conflicts with
   `git add drongo lark` at *our* merge commits. Do not take upstream's pointers (499a656, 07a467f):
   they lack every BLAKE2b change.
5. **Fix b037c5a0 by hand.** It adds a `patchLauncherUuid` Gradle task, which gives the macOS launcher a
   Mach-O UUID of its own. It hardcodes `build/jpackage/Sparrow.app` and `Contents/MacOS/Sparrow`. Ours
   is `${appImageName}.app` ("Sparrow BLAKE2b.app") with launcher `sparrow`, so as merged it fails both
   macOS CI jobs. It also seeds the UUID from `"com.sparrowwallet.sparrow.${osArch}"`, which would give
   us upstream's UUID: the exact collision the commit exists to prevent (sparrow#2088, whose reporter has
   Shrike installed too). Use `layout.buildDirectory.dir("jpackage/${appImageName}.app")`,
   `Contents/MacOS/sparrow`, and seed it from `"com.paulscode.sparrowblake2b.${osArch}"`.
6. Run `MigrationVersionsTest`, `TransactionProofTest`, drongo `CombinedOptInTest` and `PSBTTest`, then the
   full suite.

**What to watch for.**

- ae0d1ff0 ("check stored transaction block hashes against the header store") is the one commit that
  touches our header code. It is compatible: it only reads `HeaderStore.getHash`, which is BLAKE2b for
  v2, against block hashes that were stored from the same `getHash`. It starts above the checkpoints.
  It also has a helpful side effect: a wallet file brought over from stock Sparrow holds the other
  chain's block hashes, and those within `MAX_REORG_DEPTH` are now flagged and proven again.
- d212f4a (drongo) refuses a combine that swaps an input's UTXO or script. It lives in
  `PSBT.verifyCombinedSignatures` and doesn't touch our `withDecidedOptIn` in `PSBTInput.combine`. Our
  device and export copies only change the sighash, so its new checks can't fire on them.
- 8f98f045 routes lnurl-auth and auth47 callbacks through the app HTTP client. It is the only new
  outbound path, and it is user-initiated.
- No commit re-enables a withdrawn source, assumes 80-byte headers, or touches `VariableHeaders`,
  `ServerFeatures` or the chain identity check.

**Worth cherry-picking now** if the merge waits:

- drongo d212f4a: PSBT combine hardening. A security fix, reported upstream by Shrike's maintainer.
- drongo 00aa6ec with sparrow 0d2dec40: importing an `addr()` descriptor crashes with
  `IndexOutOfBoundsException` (sparrow#2080, shrike#65).

**Also arriving with the merge:**

- 40f77206: the progress bar after an over-signed finalize.
- 31de9cbc, 8db14617: message signing on an encrypted or locked wallet (#2090, shrike#72).
- 01c04f24: black QR preview with MJPG webcams on Linux (#2091).
- 9e316f07 with drongo 499a656: duplicate cosigner xpubs.
- 711ded46: incomplete entries skewing the balance.
- 8b440c44: encryption in a queued update.
- 26a06c9e, 17ab7f08: wallet lock.
- lark 824738d3: Trezor and BitBox02 on regtest, which matters for our own regtest testing (lark#12).

---

### 9a. Upstream's unconfirmed-transaction signature check rejects our own opted-in sends (release blocker for the merge)

Found in the 2026-10-29 review. Upstream e7454d97 with drongo 13059eb verifies the wallet-input signatures
of every unconfirmed transaction before adding it to wallet history:

- `ElectrumServer.getReferencedTransactions`, upstream about line 1057, and the silent payments path about
  line 2965;
- skipped only in Bitcoin Core mode (`isVerifyingSignatures`).

The check calls `Wallet.getUnsignedInputs` (upstream drongo `Wallet.java:1640`), which goes through
the private `getSignedKeystores(transaction, inputTransactions, signingNodes)`. That function hashes with
one of three digests only, taproot, witness v0 or legacy (lines 1591–1595), and none of them looks at
`SigHash.UNIFIED_FLAG`. Our drongo has the same three branches (`Wallet.java:1555-1559`). Only
`PSBTInput.getHashForSignature` dispatches to `hashForUnifiedSignature`.

**What a user would see if it shipped as merged.** Opting in is the default for software seeds and marked
devices, so this hits nearly every send. With any Electrum server:

- their own unconfirmed send, and everything descending from it, disappears from history until it
  confirms;
- the coins it spent look spendable again, and the change is missing;
- an error dialog says the transaction is "not validly signed" and that "the server is either faulty or
  dishonest". On this fork that is their own server;
- the label typed at send time is lost.

**Fix, in drongo, before or with the merge:**

1. In `getSignedKeystores(transaction, inputTransactions, signingNodes)`, dispatch on the unified bit first,
   as `PSBTInput.getHashForSignature` does:
   - taproot key path → `UnifiedScriptType.TAPROOT`;
   - witness v0 → `WITNESS_V0` with the signing script as script code;
   - otherwise `BARE`.

   Return null, meaning not signed, when the spent outputs are unknown, for a tapscript (Sparrow's P2TR
   wallets are key-path only), or when `hashForUnifiedSignature` throws `IllegalArgumentException` on an
   undefined taproot type.
2. Compute `spentOutputs` whenever any wallet input carries a unified-flag signature, not only when one is
   taproot. The unified message commits to every spent amount and script.
3. In upstream's `ElectrumServer.fetchSpentTransactions` (about merged line 1306), widen "P2TR signing
   node" to "P2TR signing node, or any wallet input with a unified-flag signature". Without it, an
   opted-in spend with an input outside the wallet (a payjoin) is still unverifiable, and is dropped.
4. Better still: one drongo helper mapping (spent outputs, index, script type, signing script, hash type)
   to a digest. Use it from `PSBTInput.getHashForSignature`, from `Wallet`, and from `ChainOnlyCoins`,
   which hand-rolls the same mapping today.

This is shrike drongo 15cbcd9 (item 7) generalized to the three-argument overload. It also fixes the
"Signed by" display for opted-in signers, a bug we already ship (`HeadersController:965`, and
`updateSignedKeystores` for a final transaction with no PSBT).

**Tests:**

- unconfirmed opted-in spends from P2WPKH and from P2TR are not flagged;
- a 2-of-3 with one unified and one legacy signature passes;
- an opted-in P2WPKH spend with a foreign input fetches that input and passes;
- a signature that doesn't verify still flags the transaction.

Payjoin (3a63d346 with drongo 6236bf0) is **safe**. It verifies through
`PSBTInput.verifyFinalizedSignatures`, which reaches the unified-aware `getHashForSignature`. One nit,
shared with upstream: a missing input UTXO throws `IllegalArgumentException` there, which escapes as a
generic error rather than a "receiver" error.

### 9b. The rest of the 2026-10-29 upstream batch

Upstream still has no tag past 2.5.5, and `build.gradle` says 2.5.6. Our `blake2b` has merged neither
batch, so a merge brings both: 39 commits from the first review and 59 from this one. No new migrations:
upstream ends at `V10`.

**Conflicts.**

- Superproject: only the `drongo` and `lark` pointers, as before.
- drongo: one conflict, in `PSBTInput.java`. We have our `getVerifiedSignatures` block and a
  package-private `verifyFinalizedSignatures`; upstream makes `verifyFinalizedSignatures` public. Keep
  ours and make that method public.
- lark: clean.
- **The chain-only-coins work** (once committed) overlaps upstream at one point: `clearPreviousServerState`
  in `ElectrumServer` (about line 265), where e7454d97 rewrites the `walletSyncLocks` line into a block.
  Keep `ChainOnlyCoins.forgetUnproven()` alongside upstream's new `unsignedWarnedTxids.clear()` and
  `storedProofsChecked = false`.

**Hand edits the merge doesn't make for us.**

- d8e679d9, Java 25.0.5: it updates upstream's `package.yaml`. Ours is `package-blake2b.yaml`, which pins
  25.0.2 at lines 38 and 42; bump it. `.sdkmanrc` and the README auto-merge.
- 7bcdf3ad: in `package-blake2b.yaml:26`, change `macos-14` to `macos-15`.
- 8ea8e302 (JavaFX 27.0.1) and f13a4d9b (the camera preview fix that needs it) go together.
- b037c5a0 `patchLauncherUuid`, carried over from the first batch: step 5 above.

**Reviewed and compatible.**

- 7923c5f5 (skip reorganizing the header store when it has synced past a replaced block), ca2be7f7 and
  d61b2a6a (reorg handling): they compare `BlockHeader.getHash()` and `HeaderStore.getHash`, both
  BLAKE2b for v2, like ae0d1ff0.
- c931bfb3 (Bitcoin Core 32.1 `rpcallowip` hint, `vsize_adjusted`): it touches `BitcoindTransport` and
  `MempoolEntry` only, leaving item 5's tip bug as it is.
- Jackson 2.21.8 and its verification metadata: the fork has never edited `verification-metadata.xml`,
  so it applies as is.
- SecureString (b9d3bbbf, drongo d31b024): a real bug fix. It's the only one worth cherry-picking early
  if the merge waits.
- The 31-commit accessibility series merges cleanly.

**Accessibility gap of our own.** The series names fields and buttons for screen readers. Our
`optInStatus` label in `send.fxml` becomes clickable when the decision is `EXTERNAL_SIGNER`
(`SendController:1171-1178`), but only by mouse, and its explanation lives only in the tooltip. Give it
a keyboard path and `accessibleHelp`, in the style of e5c0cb11.

**Merge order.**

1. drongo: merge, resolve the `PSBTInput` conflict, then do 9a with its tests.
2. lark: merge.
3. Superproject: merge, set the pointers, then the hand edits above.
4. Run `MigrationVersionsTest`, `UnconfirmedSpendTest`, `PayjoinTest`, `HeaderSyncTest`, drongo `WalletTest`,
   `CombinedOptInTest` and `PSBTTest`, then everything.

## 10. Coinbase maturity: rewards mined from 979919 are held for 25920 blocks (high, time-bound)

**What changed.** Bitcoin Knots v29.4.3.knots20260508 (released 2026-10-28) replaced the flat schedule.
Read from the tagged source, not from release notes:

- `src/kernel/chainparams.cpp:139-158`: `CoinbaseMaturityLongStartHeight = 979919`,
  `CoinbaseMaturityLong = 180 * 144` (25920), and `CoinbaseMaturityLongUntilRdts = true`. The old window is
  kept as `CoinbaseMaturityHistorical` (973440 to 979920). `RdtsExpiryTime = 1819756800`
  (2027-09-01 00:00 UTC).
- `src/consensus/tx_verify.cpp:195-205` (`CoinbaseMaturityPolicy`) and `:246-249` (`CheckTxInputs`): the
  depth is chosen per coin by its mining height.
- `src/validation.cpp:1021-1025`: the mempool calls `CheckTxInputs` with
  `coinbase_maturity_long = 25920`, `long_maturity_start_height = 979919`, the RDTS end height, and
  `coinbase_maturity_before_start = CoinbaseMaturityBeforeRolling()` (6480).

So relay, which is what the wallet lives under, now requires:

| Coinbase mined at | Depth | Note |
|---|---|---|
| below 979919 | 6480 | every older coinbase, as before: still a flat depth for all of them |
| 979919 or later | **25920** | about 180 days; the first is spendable in block 1005839 |
| a block whose parent's median time past is at or after 2027-09-01 | 100 | the rule ends with RDTS |

Testnet4 is unchanged: 6705 flat, with no historical window and no `UntilRdts`.

**What it costs today.** `LongCoinbaseMaturity.maturityDepth(network)` is 6480 for every coin. Since
979919, every reward is shown as spendable about 135 days early ("spendable from block X" and "in about
N days" are wrong). From about 6480 blocks after 979919, the wallet offers those coins for spending and
builds transactions that nodes refuse with `bad-txns-premature-spend-of-coinbase`. That is the failure
`coinbase-maturity-ux.md` exists to prevent, and it is coming back for exactly the solo miners it was
written for.

**How to fix it.**

1. In drongo, change `LongCoinbaseMaturity.maturityDepth(Network)` to `maturityDepth(Network, int
   coinbaseHeight)`: on mainnet `coinbaseHeight >= 979919 ? 25920 : 6480`; testnet4 6705; elsewhere 100.
   Every caller already has the coin's height:
   - `spendableFromHeight`, `isSpendable` and `isFrozenByLongRule` call it internally;
   - `CoinbaseTxoFilter:54`, `HashIndexEntry:126,163` and `ConfirmationsDescription:41-45` go through
     those;
   - `DateCell:67` names the depth directly and needs the coin's height passed in.
2. Leave `isDeployed(Network)` network-level, defined as "the depth at the current schedule exceeds 100".
3. **RDTS expiry.** It is defined by the mining block's parent median time past, which the wallet doesn't
   track. Overstating is the safe direction (a coin is held, never offered early), so keep 25920 for every
   coin mined from 979919, and write down that rewards mined after 2027-09-01 are overstated. Revisit
   before then: a header-store median-time-past lookup would make it exact.
4. Update the class javadoc. It cites the v29.4.2 release candidate and says relay passes
   `long_maturity_start_height = 0`. That was true then and isn't now: relay passes 979919 and applies
   6480 below it. The conclusion (every older coinbase still needs 6480) is unchanged.
5. **Wording.** `ConfirmationsDescription:26` and the `LongCoinbaseMaturity` javadoc say "forty-five days".
   Check `MaturityEstimate` and the send screen's `InsufficientInputsDescription`, which may assume one
   depth. Update `coinbase-maturity-ux.md` with the new schedule. Its UX holds unchanged; only the number
   and its source move.
6. **Shrike's version** is drongo c792a05 (their #13, wired as shrike #84). It changes
   `Network.getCoinbaseMaturity(int height)` and their `CoinbaseTxoFilter`/`Wallet`. Our code is shaped
   differently (`LongCoinbaseMaturity`), so port the rule and their boundary tests, not the diff.

**Tests.** In `LongCoinbaseMaturityTest`:

- 979918 and 979919 on each side of the boundary, mined after it is passed (shrike's case: a reward
  mined at 979918 with the tip already past 979919 keeps 6480);
- 979919 spendable exactly at tip 1005838 (block 1005839) and not one block earlier;
- testnet4 still 6705;
- every other network still 100.

Update `CoinbaseTxoFilterTest`, `HashIndexEntryTest`, `MaturityEstimateTest` and
`ConfirmationsDescriptionTest` with a post-979919 coin.

**Still worth considering** (from the original item): read the depth back from a
`bad-txns-premature-spend-of-coinbase … depth N` rejection, and at least show that rejection in plain
words. The schedule has now moved twice in two months.

From the earlier comparison, two small differences, neither needing action:

- Shrike's `CoinbaseTxoFilter` falls back to the master wallet's stored height for an unscanned child
  account. Ours refuses until the child is scanned, which fails closed.
- Shrike shows an immature row in the terminal `TransactionsDialog`; ours shows one only in
  `UtxosDialog`.

---

## 11. CI runs the tests on every push (medium)

Today the tests run only inside the manually dispatched `package-blake2b.yaml`. That stops a red release
(good), but nothing tells us a branch is red until release day.

Port shrike #54 (10eea0c4, `test.yaml`). It applies cleanly. Drop its manifest-script step and add
`:lark:test`, and keep the same JDK pin as the packaging job. The drongo tests carry this fork's
consensus code, so they must be in it.

---

## 12. Electrum client robustness, and the `blake2b_fork` spec (low to medium)

**The spec.** shrike#67 cites our `docs/electrum-header-v2.md`. Shrike is not reading `blake2b_fork`
from `server.features` yet, because "the spec names no such field yet". We already read it, in
`ServerFeatures`. Make sure the spec names the field, its type, and its meaning (the server's
activation height) plainly, so the two clients converge.

**The client.** The same issue reports two failures with servers other than Fulcrum, and both apply to
us:

- A reply with no `jsonrpc` field is silently dropped, and the client retries forever.
- Requests are batched as a JSON array; a server that answers only the first object looks hung.

Neither is a safety issue. Both make a misbehaving server look like a hang, not an error. Reproduce
each against a stub server, then fail with a message that names the server and what it sent.

---

## 13. Smaller items (low)

- **Alert sizing**, shrike #76 (34df0d2e): a blank line isn't counted when sizing an alert. One line, it
  applies cleanly, and upstream has the bug too.
- **Artifact names**, shrike #51: every `2.5.6-blake2b.N` release produces identically named dmg, zip
  and tar files. Put the release suffix (`SparrowWallet.APP_VERSION_SUFFIX`) in the `build.gradle`
  artifact names. A hand port, with eight conflicts.
- **Text fit**, shrike #77: wrap the long opt-in tooltips (`setWrapText`, `setMaxWidth(640)`).
  `UnifiedSigHashKeystoreDialog` uses a fixed `setPrefHeight(255 + n*30)`, which clips when the count
  line wraps.
- **Welcome tour**, shrike 77a0cc13: our welcome flow still shows "Connecting to a Public Server",
  which we withdrew.
- **Download verifier**, shrike 7a3bc8c1: it looks for `sparrow-*` and `-manifest.txt`; our releases
  use `SHA256SUMS`. Only worth doing if we keep the verifier.
- **Hardware support notes**: shrike#30 is an independent byte-for-byte reproduction of the Trezor
  `unified-sighash.2` firmware on all five models. shrike#40 lists other firmware (Coldcard 5.6.2U, the
  SeedSigner unified build; Krux stock has no support and rejects 0x21 in a 2-of-3). Our lark can make
  only Trezor firmware reporting capability 29 sign the opt-in. Keep
  `docs/split-with-a-hardware-wallet.md` consistent with this.
- **Fiat**: shrike PR#81 (open) prices from the Neoxa BTCB2/USDC ticker, opt-in and USD-only. Our
  BTCB2 pricing already covers this. Watch it as a second source.

**Not taking:** Shrike's rename, icons, artwork, About text, donate link, maintainer names, entitlements,
issue templates, American spelling, release bumps and update checker (it needs a hosted feed). Also
the light theme (#43), which is mostly their red rebrand; Gradle verification metadata (#71, only
`reason=` attributes); manifest gates tied to their checksum job; and the MSI, deb and dmg packaging
fixes (#53, bb86638d, #63, 80f4f15d). We publish none of those formats except the dmg, and ours is
made by jpackage, which already has the Applications link. Revisit #53 and bb86638d if we ever ship
msi or deb.

---

## 14. Repository hygiene (low)

- The local tag namespace holds 23 of shrike's tags (`v2.5.4-blake2b.4`–`.6` and
  `v2.5.5-blake2b.7`–`.27`), fetched in September. No name collides with one of ours. Even so, a
  `git push --tags fork` would publish them in our repository, and `git tag` makes our release history
  look longer than it is. Delete them locally, and set `git config remote.shrike.tagOpt --no-tags` in all
  three repositories.
- Local and `fork` `master` branches are stale pre-fork upstream. The release line is `blake2b` in all
  three repositories. Consider deleting `master` on the fork, or resetting it to upstream, so nobody
  opens a PR against it.

## 15. Wallet-built transactions against the RDTS rules (medium)

**Why this matters.** The BLAKE2b chain enforces the RDTS consensus restrictions (for example, limits on
large OP_RETURN outputs), and the SHA256d chain doesn't. Upstream builds its transactions against the
SHA256d chain's rules only. Anything this fork builds that breaks an RDTS rule is refused by the chain
the fork follows. The user then sees a broadcast failure, or a PSBT that signs and can never confirm.
None of the code or docs mentions RDTS today.

**What to do.**

1. Write down the RDTS rules this chain enforces, with the activation height and the source (the Knots
   release and BIP), in `internal_docs/`. The audit needs an exact list, not a summary from memory.
2. Go through every place the wallet builds outputs or witnesses and check each against that list:
   - The send tab's payments and change.
   - The PayNym notification transaction: an 80-byte OP_RETURN payload, which sits at the usual size
     limit.
   - Silent payment outputs.
   - Sweeps, CPFP and RBF.
   - Taproot spends: script-path spends, control block depth, any annex.
   - Anything that passes a user-supplied script through, such as a pasted OP_RETURN or a PSBT built
     elsewhere and only signed here.
3. Where the wallet *builds* something that could break a rule, refuse it before signing, with a message
   naming the rule. Where it only *signs* or *displays* a foreign PSBT, warn on the transaction tab that
   the transaction can't confirm on this chain.
4. Add tests that pin each limit the wallet relies on (for example, the largest OP_RETURN the
   notification transaction produces), so a rule change is a deliberate edit.

**Not affected:** the "Replay protected" label. RDTS only ever makes a transaction invalid on this chain,
so it can make coins exist only on the SHA256d chain, never only on ours, and it can't produce a false
"protected".

## 16. A split from the SHA256d side, using RDTS (low, research first)

**The idea.** `docs/split-with-a-hardware-wallet.md` splits from the BLAKE2b side: a hot wallet's opt-in
signature makes a coin only this chain has. RDTS allows the opposite. Move the BTC in a SHA256d
transaction that breaks an RDTS rule, for example with an oversized OP_RETURN output. It's valid there
and refused here, so the XBT stays where it was.

That needs no hot wallet, no unofficial firmware, and no opt-in signature, just an ordinary signature
over an unusual output. If it holds up, the guide's framing that a stock signer needs a hot wallet or
unofficial firmware to split is stronger than it needs to be.

**What has to be checked before it goes anywhere near users:**

- Which RDTS rule is the most reliable to break. It should fail on this chain under every client and
  policy, and be cheap and harmless on the SHA256d chain.
- Whether SHA256d nodes and miners relay and mine it. That depends on their OP_RETURN policy, which has
  changed across Core releases. A transaction that doesn't propagate there splits nothing.
- Whether hardware wallets sign such an output, and what they show the user when they do.
- Whether someone could copy it onto this chain in a form that passes. They can't change the outputs
  without invalidating the signatures, but confirm it for each sighash type a user might choose.
- Which wallet builds it. Upstream Sparrow doesn't build arbitrary OP_RETURN outputs from its send
  tab, and this fork shouldn't build a transaction that its own chain rejects (item 15). This may be a
  procedure using other software, documented rather than built.

**Outcome:** either a second method in the guide with every claim above verified and dated, or a note in
the guide saying why it isn't recommended.

## 17. Whether watch-only wallets count as the user's keys (decided: they don't)

**Decision (2026-10-15): option A.** `ChainOnlyCoins.holdsItsKeys` leaves out any wallet with a `SW_WATCH`
keystore. `ChainOnlyCoinsService` still reads such a wallet's history, but takes no owned scripts from it.
The guide says that a watch-only copy doesn't count.

**Correction to the original write-up.** Payment-code (`SW_PAYMENT_CODE`) keystores are *not* watch-only.
They are the PayNym (BIP47) accounts of a seed wallet and hold their private keys
(`Keystore.java:158`). `AppServices` already treats them as software signers that can opt in, so they
count. The options below are kept as they were argued.

The chain-only-coins label counts a proof only when the opted-in signature behind it was made by a key
in a wallet open in the app. Whoever holds that key could re-sign the proving transaction for the
SHA256d chain and undo the split, so the key must be the user's. Today ownership is read from the
output scripts of every open wallet, including watch-only (`SW_WATCH`) and payment-code
(`SW_PAYMENT_CODE`) wallets. Those have no private keys, and the xpub may be someone else's.

Upstream has no equivalent question. It uses "a script in an open wallet" only for bookkeeping, never as
evidence about who could sign. The question exists only because one txid can be valid on two chains with
different witnesses.

**Option A: exclude wallets with any `SW_WATCH` keystore (chosen).**
A change in `ChainOnlyCoinsService`'s constructor, a test, and a line in the guide.

**Option B: keep counting them, and say so.** No code change; a sentence in the guide and the tooltip.

What each means for a user:

- **A:**
  - A user who opens someone else's xpub as a watch-only wallet can't be shown a false "protected".
  - A user whose only copy of their own hot wallet is watch-only gets "Not replay protected" until they
    open the real wallet.
  - That copy has no keys, so the opt-in signature was made somewhere else, and opening the wallet that
    made it (the guide's step 5 already asks for this) fixes it. The cost is small.
- **B:**
  - Nothing changes for anyone doing the guide as written.
  - A user monitoring another person's wallet (an exchange's or a friend's xpub) can be shown "Replay
    protected" over a coin that person could un-split at will. Nothing on screen distinguishes that from
    a real proof.

## 18. An explicit "these keys are mine" flag on watch-only keystores (low, only if users ask)

Item 17 inconveniences one rare case: a user whose own key is loaded here only as an xpub, and who
made the opt-in signature elsewhere (in Knots, in Shrike, in another install, or with a hardware wallet
added by pasting its xpub). Today they have to open the wallet that signed. If users run into this, add
an explicit attestation:

- **Where.** Per keystore, not per address: every address derives from the one xpub.
- **Storage.** A nullable column in the pattern of `V11__UnifiedSigHash.sql`, so that existing wallets
  read as "not mine". It would be `V12`. Mind the README's numbering hazard: upstream's next migration
  would then have to be renumbered.
- **UI.** A toggle on the keystore tab whose wording states the risk. For example: "Only turn this on if
  you hold the seed or device for this wallet. Anyone who holds these keys can undo a split that relies
  on them." `holdsItsKeys` would then accept a watch-only keystore with the flag set.
- **Don't reuse the replay-protection mark** (`AppServices.canBeMarked` already lets it be set on
  watch-only keystores). It means "this signer can opt in". Making it also mean "this is mine" gives
  one toggle two questions, and this is the toggle a scammer would talk someone into flipping.
- **What a flag can't do.** It can't prove anything. Being the *only* holder of a key can't be proven: a
  challenge signature only shows that someone holds it, and a scammer can sign whatever challenge they're
  sent. The flag only records that the user says so.

## 19. The bundled Tor can no longer join the network (critical)

**What is wrong.** The Tor network consensus now lists `Desc=2-3` and `Microdesc=2-3` under
`required-client-protocols`. The Tor we bundle, 0.4.8.21 (kmp-tor `resource-exec-tor-gpl` 408.21.0, at
`build.gradle:6,79`), supports only versions 1 and 2. It stops bootstrapping at 30% with "missing protocols:
Desc=3 Microdesc=3", so every connection through the built-in Tor fails: `.onion` servers, and the Tor
proxy option. Reported against Shrike in shrike#87, whose reporter saw the same in upstream Sparrow.
Upstream has it open as sparrow#2099, and upstream master still pins 408.21.0.

**How to fix it.** Take shrike #88 (69edb0aa):

- bump the `io.matthewnelson.kmp.tor.resource-filterjar` plugin and `resource-exec-tor-gpl` from
  408.21.0 to 409.5.0 (Tor 0.4.9.5);
- keep the kmp-tor runtime at 2.5.0;
- add the new artifacts to `gradle/verification-metadata.xml`. Generate our own entries rather than
  copying theirs, and check the checksums against Maven Central as shrike did.

**When upstream fixes #2099,** its bump arrives with the merge (item 9). Expect a trivial conflict on
these two lines; take whichever version is newer.

**Verify:**

- bootstrap the bundled Tor against the live network on each platform we ship;
- connect to a `.onion` Electrum server through it;
- make sure the macOS `LC_UUID` issue upstream fixed for kmp-tor in 2025 (sparrow PR #1730) hasn't come
  back with the new resource jar.

## 20. Server settings throw on the empty public server list (medium)

**What is wrong.** This fork withdrew the public servers, so `PublicElectrumServer.getServers()`
(`PublicElectrumServer.java:75-77`) returns `List.of()`. `ServerSettingsController:228` wraps that list
for the public server combo box, and `:460` sets the box's value to `null` (on this fork there is never
a configured public server). The combo box then calls `indexOf(null)` on the list, and `List.of()`'s
`indexOf(null)` throws `NullPointerException` by contract.

**How to fix it.** Shrike #85 (dc2229d0): return `new ArrayList<>()`, and add their assertion that
`getServers().indexOf(null) == -1` to our withdrawn-sources tests (`WithdrawnSourceConfigTest` or
`Sha256SourcesWithdrawnTest`). Confirm the failure in the app first (open Settings, then Server), so the
release note can say what it fixes.

## Left open from the chain-only-coins review

That work was reviewed adversarially on 2026-10-15, and the gaps found were fixed before it was committed:

- A proof must now be signed by a key in an open wallet that holds its keys; watch-only wallets don't
  count.
- SINGLE without a matching output withholds the label.
- The label refreshes.
- The lookup service: thread-safe snapshot, early exit, backoff, round count, depth-aware memo, and
  forgetting "unproven" on a wallet open or a server change.

These were left:

- **Watch-only wallets.** Decided in item 17; the follow-up is item 18.
- **Tapscript signatures** aren't visible to `anInputCanBeLifted` before finalizing, because drongo drops
  `PSBT_IN_TAP_SCRIPT_SIG` at parse. This goes away with the 065a256 port in item 7.
- **Final transactions** get no chain-only check (item 4).
- **Cost.** `verifiesOptedIn` recomputes the unified message's aggregates for every input, which is
  quadratic on very large ancestors. It runs on a background thread, so only CPU is spent.
- **Proofs only survive a restart for 6 generations** (`MAX_DEPTH`). The guide tells large-wallet users
  to do a fresh step 3 every 6 batches if they restart.

## Things from Shrike we already match or do better

These were compared and need nothing. They are recorded so the comparison isn't repeated:

- **Consensus is identical.**
  - The unified sighash files are byte-identical. That includes the five-byte locktime, the BIP341
    layout, the hash type values 0x21/22/23/A1/A2/A3 and the Knots vectors.
  - The v2 header serialization and BLAKE2b hash agree: Shrike's real headers 961632–961641 link under
    ours, and 961640 hashes to our pin.
  - The target shift (22 on mainnet, after the retarget) agrees and matches the real block's bits.
  - The checkpoints agree; both stop at 961631.
  - The maturity depths agree.
- **Protocol 1.3–1.8 and both header response forms** (shrike #24): equivalent.
- **The verified tip decision** (#14) and **a signer that can't opt in taking its turn** (#41): already
  ported.
- **Fiat**: Shrike disables it; we price against BTCB2.
- **Maturity UX** (#64, #79): ours is per-coin and at least as complete.
- **Our lark** already carries all of Shrike's Trezor unified sighash work.
- **Ours, not theirs**, and possibly worth offering them: the pinned activation block hash and
  connect-time chain identity check, `BlockHeaderV2` kept separate, BouncyCastle BLAKE2b, and the tests
  `CombinedOptInTest`, `VerifiedSignatureOptInTest`, `CoinbaseTxoFilterTest`, `Blake2bDeploymentTest`
  and `LongCoinbaseMaturityTest`.

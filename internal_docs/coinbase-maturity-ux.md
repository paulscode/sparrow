# Making coinbase maturity legible

## Why this exists

Upstream Sparrow never had to think hard about coinbase maturity. On the SHA256 chain a mined
coin is locked for a hundred blocks, about sixteen hours, and the wallet's handling of that is
one constant and one filter. Nobody notices sixteen hours.

This chain changed that. Knots deploys a temporary rule
([bitcoinknots/bitcoin#419](https://github.com/bitcoinknots/bitcoin/pull/419)) that raises the
wait to 6480 blocks — roughly forty-five days, and the release notes say a full year is being
considered for October. The wallet already follows the rule correctly, as of
`LongCoinbaseMaturity` and the change to `CoinbaseTxoFilter`. What it does not do is *explain*
it.

The rule has two faces and only one of them matters here. Consensus refuses a spend only inside
a deployment window bounded by heights 973440 and 979920, and only for coins mined inside it.
Relay is blunter: an upgraded node requires the full 6480 blocks of *every* coinbase spend,
whatever height the coin was mined at, and it keeps requiring it after the window closes. A
wallet lives under relay, because a transaction nothing will carry is a transaction that fails.
So the wallet's model is a flat depth, not a window, and the visible consequence is that coins
mined in the forty-five days *before* the deployment stop being spendable when the network
upgrades.

The failure this is aimed at: a solo miner mines a block, sees the money arrive, and cannot
spend it. Nothing in the interface says the coin is locked, nothing says why, nothing says
until when, and the balance counts it as though it were spendable. The Send screen's answer is
"insufficient funds", which is true and actively misleading.

That is our own doing as much as the chain's: our StartOS and Umbrel mining instructions tell
solo miners to send payouts to an external wallet like this one, so these are exactly the coins
our users hold.

## Where things stand

**All six sections are implemented.** This document is now a record of why the code is shaped the way it
is, and of the four places the build departed from the plan, rather than a proposal.

| Section | Landed as |
|---|---|
| 1. `isSpendable` knows about maturity | `HashIndexEntry.isImmatureCoinbase()`, called by `isSpendable()`; `HashIndexEntryTest` |
| 2. Immature figure under the balance | `WalletUtxosEntry.getImmatureBalance()`, a row on `transactions.fxml` and `utxos.fxml` |
| 3. Say when | `MaturityEstimate`, the immature branch in `DateCell`, matching wording in `ConfirmationsDescription` |
| 4. Send screen reason | `InsufficientInputsDescription`, wired at `SendController.addValidation()` |
| 5. Close the fail-open | `CoinbaseTxoFilter`, rewritten to split by what is known; `CoinbaseTxoFilterTest` |
| 6. Terminal | `DateTableCell`, using `MaturityEstimate.describeShort()` |

### One thing this document had wrong

An earlier review claimed that selecting an immature coin on the UTXOs screen produced a transaction that
would be signed and then rejected at broadcast, on the grounds that `SendController.spendUtxos()` sets
`txoFilterProperty` to null. That is not what happens, and the reason is worth writing down so nobody
re-derives the wrong conclusion:

- `getTxoFilters()` (`SendController.java:763`) includes `CoinbaseTxoFilter` **unconditionally**.
  `txoFilterProperty` carries an *additional* filter, such as an `ExcludeTxoFilter`; clearing it does not
  clear the coinbase one.
- `PresetUtxoSelector.select()` intersects its presets against the candidate set it is handed, and that set
  has already been filtered. A coin the filter refuses is simply not there to select.

So the wallet was never able to build an invalid spend. What section 1 fixes is a coin that looks ordinary,
can be selected, and then yields "Insufficient Inputs" with no explanation. That is a user-experience defect,
not a safety one.

## Design principles

**Reuse the vocabulary that exists.** Sparrow already has a notion of a coin you hold but
cannot spend: a frozen UTXO. It has a greyed row style, exclusion from selection, and exclusion
from the spend menus. An immature coinbase is the same idea with a different cause — frozen by
consensus rather than by the owner. Every proposal below routes through that existing machinery
rather than inventing a parallel one.

**Height is the truth, time is the intuition.** The rule is defined in block heights, so a
height is a fact and a duration is a guess: it depends on an assumed block interval, and this
chain's has not been near ten minutes. Show both, and make which is which obvious. A user
asking "when can I spend this" wants the duration; a user checking our work wants the height.
Neither is served by showing only the other, and nobody is served by a duration precise enough
to look like a promise.

**Never show a coin as spendable when it is not, and never hide it.** It is the owner's money.
It belongs in the balance and in the UTXO list. It must be visibly distinct and must not be
selectable.

**Say why, not just no.** Every place that currently refuses should be able to name the reason.

## The changes

### 1. Make the UI's notion of spendable know about maturity

**The single highest-leverage change**, and the one that gives the rest of this document
something to call.

Sections 2, 3 and 4 all need to ask the same question — *is this particular coin an immature
coinbase* — and none of them can ask `isSpendable()`, because that is also false for a frozen
coin and for an unconfirmed one, and those need different wording and must not be counted in an
immature total. So introduce the question once, as a method on `HashIndexEntry` beside
`isSpendable()`:

```java
public boolean isImmatureCoinbase()
```

`isSpendable()` then calls it, and sections 2, 3 and 4 call it directly. One definition, and
section 1 stops being merely a fix and becomes the thing the others are built on.

`HashIndexEntry.isSpendable()` is the UI's answer to "can this coin be spent". Before this it read:

```java
return !isSpent()
        && (hashIndex.getHeight() > 0 || Config.get().isIncludeMempoolOutputs())
        && (hashIndex.getStatus() == null || hashIndex.getStatus() != Status.FROZEN);
```

It knew about spent, unconfirmed and frozen, and not about maturity, so the UI's answer and the
wallet's had diverged since `CoinbaseTxoFilter` changed.

The maturity term added is `&& !isImmatureCoinbase()`. The entry has `wallet` and `hashIndex`, which is everything needed:
`wallet.getWalletTransaction(hashIndex.getHash())` gives the transaction (and so
`isCoinBase()`), and the tip comes from `AppServices.getCurrentBlockHeight()` falling back to
`wallet.getStoredBlockHeight()` — the same convention `TransactionEntry.calculateConfirmations()`
already uses, so there is precedent for reaching `AppServices` from this package.

What this buys, with no further work:

| Call site | Effect |
|---|---|
| `EntryCell.java:876` | Row gets the `unspendable` style, already defined at `wallet.css:59` |
| `UtxosController.java:166` | Coin cannot be selected for spending on the UTXOs screen |
| `EntryCell.java:186, 391, 460, 801` | Dropped from the spend and send context menus |

Two call sites that look like they belong in that table and do not. `EntryCell.java:222` filters
`Type.INPUT && isSpendable()`, and `isSpendable()` begins `!isSpent()` while `isSpent()` is true
for every `INPUT` (`HashIndexEntry.java:59`), so that filter has never matched anything and
nothing about it changes. `DateCell.java:39` sits inside the `height <= 0` branch, so it is
unreachable for a confirmed coinbase; section 3 covers what that means for the wording.

**Testable without a display?** Partly. Both predicates are testable the way
`CoinbaseTxoFilterTest` is — build a wallet holding a coinbase, assert the entry — and
`isImmatureCoinbase()` is worth testing in its own right rather than only through
`isSpendable()`, because three other sections depend on it meaning exactly what it says. The CSS
and the table behaviour are not testable here; they need eyes.

**Watch for:** `isSpendable()` is called per cell per repaint. `getWalletTransaction()` is a map
lookup and the tip is a field read, so this should be cheap, but it is worth confirming on a
wallet with many UTXOs before calling it done.

**Do not make maturity a `Status`.** `Status` (`drongo/.../wallet/Status.java`) has exactly one
member, `FROZEN`. It is set by the owner, persisted in the wallet file, and round-tripped
through labels import and export (`WalletLabels.java:103`, `:282`). Maturity is none of those
things: it is derived from a height and a tip, it changes on its own as blocks arrive, and
writing it down would make it wrong the moment the chain moved.

These two want to *look* alike on screen and must not *be* alike in the model. Say so in a
comment next to whatever you add, because the resemblance is exactly the kind that invites a
later tidy-up into one concept, and that tidy-up would persist a derived fact.

### 2. An immature figure under the headline balance

Both balance screens already carry the same two-line structure — `Balance:` and `Mempool:` —
at `transactions.fxml:37` and `utxos.fxml:38`. So "part of this balance is not like the rest"
is an established idea with an established shape, and the immature figure is a third line of
exactly that kind rather than something new to learn.

Put it on **both** screens, not just the UTXOs one. The headline balance is where people look;
a figure confined to the UTXOs screen is only found by someone who already suspects there is
something to find, which is precisely the user this is for.

The sum belongs on `WalletUtxosEntry` beside the two that exist (`WalletUtxosEntry.java:90`):

```java
public long getMempoolBalance() {
    return getChildren().stream()
            .filter(entry -> ((UtxoEntry)entry).getHashIndex().getHeight() <= 0)
            .mapToLong(Entry::getValue).sum();
}
```

Add `getImmatureBalance()` alongside it, filtering the children on `isImmatureCoinbase()` from
section 1. Not on `!isSpendable()`: that is also true of a frozen coin and an unconfirmed one,
and neither belongs in this total.

`WalletForm` exposes both entries (`getWalletTransactionsEntry()` at `WalletForm.java:501`,
`getWalletUtxosEntry()` at `:510`), so the transactions screen can read the figure from the
UTXO entry without the sum being computed twice.

**The three figures do not overlap, and that is worth knowing before someone checks the
arithmetic.** Mempool is `height <= 0`; an immature coinbase requires `height > 0`, because
`CoinbaseTxoFilter` refuses anything without a height. The sets are disjoint, so nothing is
counted twice and immature is always a subset of the confirmed balance.

**The balance itself does not change.** It stays the total, because the coins are the owner's.
The new line says how much of that total cannot move yet. This is deliberately not the bigger
move — redefining the headline as spendable-only — which would change behaviour for every
wallet rather than only mining ones, and is a separate decision.

Show it **only when non-zero**, or every non-mining wallet carries a permanent zero row
explaining a rule that will never apply to it.

Wording: "Immature" is what Bitcoin Core calls it and what a miner will have seen. Keep the
unlock height out of the label. Every coin waits the same depth from its own block, so a wallet
holding coins from several blocks has as many unlock heights as it has blocks, and no single
height in a summary line is right. The per-coin detail belongs on the per-coin row.

**Testable:** yes, the sum is a pure function of the children.

### 3. Say when, where the eye already goes

**The UTXOs screen date/status column.** `DateCell.java:39` already writes
"Unconfirmed (Not yet spendable)", so the column is understood to carry spendability rather
than only a date — but that line is inside the `height <= 0` branch and a confirmed coinbase
never reaches it. A confirmed coin falls to the `else if` that prints the date
(`DateCell.java:41`), and that is where the new case goes: a branch before it, on
`isImmatureCoinbase()`, carrying both the guess and the fact.

```
Immature — about 4 weeks (block 979,920)
```

That is longer than what the column holds today. Check it is not truncated before calling this
done; if it is, the height moves to the tooltip and the duration stays, since the duration is
the question being asked.

**The tooltip is already there and already about height.** `DateCell` builds one at
`DateCell.java:50` showing the block height, or "Mempool" (`:53`). Extending that is a smaller change
than adding a tooltip, and it is where a sentence fits: name the cause rather than only the
effect, because this is a network rule and not something Sparrow decided.

Keep this and the Transactions screen wording (`ConfirmationsDescription`) consistent. They
describe the same coin and should not describe it two ways.

#### The estimate

Blocks remaining is exact: `spendableFromHeight - (tip + 1)`. Turning it into a duration needs
an assumed interval, and the choice matters less than being honest about it.

**Use the ten-minute target, not a measured rate.** It is stable, documented, and the same
number the rest of Bitcoin quotes. A measured rate is more accurate and much more code, and it
makes the figure move around for reasons the user cannot see. The target also errs in the safer
direction: this chain has been running faster than ten minutes, so an estimate built on the
target reads long, and a lock that opens earlier than promised is the failure nobody complains
about.

**Round hard.** The precision is fake, so it should not look real. Something like:

| Blocks remaining | Reads as |
|---|---|
| > 10 weeks | about N months |
| > 2 weeks | about N weeks |
| > 3 days | about N days |
| > 6 hours | about N hours |
| anything less | less than an hour |

Always prefixed "about". Never a date, never a decimal: "about 4 weeks" is useful and honest,
"44.7 days" and "2 November" are neither, because both imply we know when a block will be found.

**Testable:** yes, entirely, and it should be — a bucketing function with an off-by-one at a
boundary is exactly the sort of thing that reads fine and is wrong. Put it beside
`ConfirmationsDescription` as another pure function of its inputs.

### 4. Tell the Send screen the real reason

Today an attempt to spend a wallet whose coins are all immature produces
`InsufficientFundsException`, surfaced near `SendController.java:651`, and the validation label
at `:500` says "Insufficient Inputs". Both are true and neither is the answer.

The cheapest honest improvement: when a transaction cannot be funded, ask
`getImmatureBalance()` from section 2 whether the wallet holds enough immature coinbase to have
covered it, and if so say that instead of implying the money is not there.

Do not put a single unlock height in this message. It is a summary of possibly several coins,
and for the same reason section 2 keeps the height off the balance label — each coin unlocks a
fixed depth after its own block, so several coins means several heights — one height would
usually be wrong. Name the amount and send the reader to the coins:

```
Insufficient spendable funds — 6.25 BTC is immature. See the UTXOs tab.
```

This is the change most likely to stop a support message being written, and it is the one a
miner hits first. It is also the one with the least existing structure to lean on, which is why
it is last in the order below rather than first.

### 5. Close the fail-open in `CoinbaseTxoFilter`

`CoinbaseTxoFilter.isEligible()` puts every guard inside the condition, so anything it cannot
evaluate falls through to *eligible*:

```java
if(blockTransaction != null && blockTransaction.getTransaction() != null
        && blockTransaction.getTransaction().isCoinBase()
        && wallet.getStoredBlockHeight() != null) {
    ...
}
return true;
```

This is upstream's shape and is safe today for a reason that is not written down anywhere near
it: a wallet cannot hold a txo whose transaction it failed to fetch, because `ElectrumServer`
throws `IllegalStateException` rather than admitting one (`ElectrumServer.java:2053` and
`:2070`). It is a real invariant, and the filter's safety depends on it. If it ever softens —
and a longer maturity wait makes a restore against a pruned node more likely to be the case
that softens it — this filter silently starts offering immature coins for spending.

Do not simply invert it. Failing closed on every unknown would make an ordinary wallet
unspendable the moment a transaction is missing. Split by what is actually known:

- **Known coinbase, but height or tip unknown** — refuse. Maturity cannot be evaluated, and the
  blast radius is coinbases only. This is a small, safe tightening.
- **Cannot tell whether it is a coinbase** (transaction absent) — allow, as now, but log it
  once. Turning a silent fail-open into a noisy one is the whole improvement; the alternative
  freezes ordinary wallets over a transient fetch failure.

**Testable:** yes, entirely. `CoinbaseTxoFilterTest` already has the harness.

### 6. The terminal interface

`SparrowTerminal` has its own UTXO screen (`terminal/wallet/UtxosDialog.java`) and its own cell
rendering (`terminal/wallet/EntryTableCellRenderer.java`), and it already special-cases frozen
UTXOs at `UtxosDialog.java:63`. If section 1 lands, the terminal gets correct *behaviour* for
free, because it uses the same entries. It will not get the wording.

Not urgent — a miner running Sparrow Server on a headless box is a narrower case than the
desktop — but it should not be forgotten, and whoever does section 3 should check it.

## Order built

Built in the order this section originally suggested, which held up:

1. **Section 5** (fail-open). Pure safety, fully testable, no UI.
2. **Section 1** (`isSpendable`). Everything else leans on it.
3. **Section 3** (say when). Small, once 1 is in.
4. **Section 2** (immature balance). FXML on two screens, one sum.
5. **Section 4** (Send message).
6. **Section 6** (terminal).

1, 2, 3 and 5 are worth having whatever happens to the deployment schedule. They are correct for the
hundred-block rule too; that rule was simply short enough that nobody minded.

## Where the build departed from the plan

Four places, each because the plan turned out to be underspecified rather than wrong.

**The duration buckets are hour-granular all the way down.** The table here read "> 6 hours: about N hours"
and "anything less: less than an hour", which says "less than an hour" for a five hour wait. That
understates, and understating is the direction that generates the complaint. The buckets now run down to one
hour, and only a genuine sub-hour wait says "less than an hour".

**The unit is chosen from the rounded count, not from the raw block figure.** With thresholds on raw blocks,
431 blocks read "about 72 hours" and 432 read "about 3 days": the same wait, said two ways, one block apart.
`MaturityEstimate` now rounds first and promotes, so no count ever reaches the threshold of the unit above
it. `MaturityEstimateTest` walks the whole range asserting that.

**The status column carries the duration and the tooltip carries the height.** The plan wanted
`Immature - about 4 weeks (block 979,920)` in the cell, with the height moving to the tooltip if it did not
fit. It cannot be measured without a display, so the build took the plan's own fallback: the cell reads
`Immature (about 6 weeks)` and the tooltip carries the height, the unlock height, and a sentence naming the
rule. If the cell turns out to have room, moving the height back is a one-line change.

**Neither screen reads the figure from the cached UTXO entry.** The plan said the Transactions screen could
read it from `WalletUtxosEntry`, so the sum would not be computed twice. That would have shipped a stale
figure: `WalletUtxosEntry.updateUtxos()` is called only by `UtxosController` and the terminal's
`UtxosDialog`, so a wallet whose owner never opens the UTXOs tab would have built that entry once and shown
an immature total frozen at that moment. `HashIndexEntry.getImmatureBalance(wallet, tip)` is now a static
that sums over the wallet's own UTXOs, and both screens call it. The definition is shared; the cache is not.

**The Send message names the immature amount whenever there is one.** The plan asked whether the immature
balance would have covered the shortfall, and to speak only if it would. Working the shortfall out at that
point means unpicking the fee iteration, and the sentence is worth saying either way: the difference it
makes is between "your money is gone" and "your money is waiting".

## What needs a human with a display

There is no display on the build machine, so this split was load-bearing throughout:

- **Tested headless:** the `isImmatureCoinbase()` predicate and the block count beside it
  (`HashIndexEntryTest`), the filter (`CoinbaseTxoFilterTest`), the duration buckets in both forms and the
  terminal width fallback (`MaturityEstimateTest`), and both wording functions
  (`ConfirmationsDescriptionTest`, `InsufficientInputsDescriptionTest`).
- **Needs eyes:** everything in "Still open" above.

The immature balance sum is the one piece of logic with no direct test. It is a filter and a sum over
`isImmatureCoinbase()`, which is itself tested; building a `WalletUtxosEntry` in a test needs a wallet with
populated nodes and was judged not to earn its keep. If it grows a second condition, that judgement changes.

## Decisions taken

**Heights and a coarse estimate, not one or the other.** The height is verifiable and the
duration is what the user actually asked. Show both, built on the ten-minute target rather than
a measured rate, rounded hard enough that nobody mistakes it for a promise. Section 3 has the
buckets.

**The immature figure goes under the headline balance, on both screens, and the balance itself
does not change.** The coins are the owner's, so the total stays the total; the new line says
how much of it cannot move yet. Confining the figure to the UTXOs screen would only reach
someone already looking for it. Redefining the headline as spendable-only remains a separate
and much larger decision, not taken here, because it would change what every wallet shows
rather than only mining ones.

**Maturity is derived and must never become a `Status`.** They will look alike on screen and
must stay apart in the model, for the reasons in section 1. Say so in a comment where it would
be tempting to unify them.

## Still open

**Everything that needs a display.** Nothing is blocking, and nothing is known to be wrong; these are the
claims the build could not check itself:

- That the third balance row fits both screens without crowding the two it joins.
- That `Immature (about 6 weeks)` is not truncated in the UTXOs date column. If it is, the height is already
  in the tooltip and the duration can stay in the cell.
- That the `unspendable` row style reads as "different" rather than "disabled".
- That selection behaves when a mixed set of mature and immature coins is selected.
- That the cost is not noticeable. `isSpendable()` runs per cell per repaint and now calls
  `isImmatureCoinbase()`, which is a map lookup and a field read; `getImmatureBalance()` does one of those
  per UTXO on every balance update. Both should be cheap and neither has been measured on a wallet with
  many UTXOs.

The terminal's version of the width question **is** settled, because that column is a fixed eighteen
characters: `DateTableCell` uses the short form of the estimate, and falls back to a bare "Immature" if even
that does not fit, which is what a year-long part two would need. `MaturityEstimateTest` asserts both.

## What not to do

**Do not hide immature coins.** They are the owner's. Hiding them produces a support message
about missing money, which is worse than the one about unspendable money.

**Do not hardcode 6480, 973440 or 979920 anywhere new.** The depth lives in
`LongCoinbaseMaturity` and nowhere else, it came from a release candidate for a pull request
that was still open, and the numbers behind it have already moved once.

**Do not couple any of this to the 45-day figure.** Part two is a year. Everything here should
read the depth from `LongCoinbaseMaturity` and be indifferent to how large it is.

**Do not reintroduce the window.** The deployment heights are a consensus detail; reading them
into the wallet produces two wrong answers, offering pre-deployment coins that will not relay
and hiding testnet4 coins that would. `LongCoinbaseMaturity` deliberately does not expose
them.

# Making coinbase maturity legible

## Why this exists

Upstream Sparrow never had to think hard about coinbase maturity. On the SHA256 chain a mined
coin is locked for a hundred blocks, about sixteen hours, and the wallet's handling of that is
one constant and one filter. Nobody notices sixteen hours.

This chain changed that. Knots deploys a temporary rule
([bitcoinknots/bitcoin#419](https://github.com/bitcoinknots/bitcoin/pull/419)) under which every
coinbase mined at or after height 973440 is unspendable until 979920 — roughly forty-five days,
and the release notes say a full year is being considered for October. The wallet already
follows the rule correctly, as of `LongCoinbaseMaturity` and the change to `CoinbaseTxoFilter`.
What it does not do is *explain* it.

The failure this is aimed at: a solo miner mines a block, sees the money arrive, and cannot
spend it. Nothing in the interface says the coin is locked, nothing says why, nothing says
until when, and the balance counts it as though it were spendable. The Send screen's answer is
"insufficient funds", which is true and actively misleading.

That is our own doing as much as the chain's: our StartOS and Umbrel mining instructions tell
solo miners to send payouts to an external wallet like this one, so these are exactly the coins
our users hold.

## Where things stand

Already done, tested, on `main` (unpushed):

- `LongCoinbaseMaturity` — the rule, per network, with the Knots expression transcribed in its
  test.
- `CoinbaseTxoFilter` asks it, so frozen coins are excluded from `getSpendableUtxos()` and
  cannot be selected into a transaction.
- `ConfirmationsDescription` — the amount tooltip on the **Transactions** screen says
  "immature coinbase, spendable from block 979920".

What that leaves, and what this document is about:

1. The **UTXOs screen** shows nothing at all. The row looks ordinary and is selectable.
2. The **balance** counts coins that cannot be spent, with no breakdown.
3. **Send** says "insufficient funds" when the real answer is "your coins are locked until
   block N".
4. `CoinbaseTxoFilter` **fails open** on inputs it cannot evaluate.

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

**The single highest-leverage change.** Everything in section 2 and most of section 3 follows
from it for free.

`HashIndexEntry.isSpendable()` (`src/main/java/com/sparrowwallet/sparrow/wallet/HashIndexEntry.java:63`)
is the UI's answer to "can this coin be spent". Today:

```java
return !isSpent()
        && (hashIndex.getHeight() > 0 || Config.get().isIncludeMempoolOutputs())
        && (hashIndex.getStatus() == null || hashIndex.getStatus() != Status.FROZEN);
```

It knows about spent, unconfirmed and frozen. It does not know about maturity, so the UI's
answer and the wallet's have diverged since `CoinbaseTxoFilter` changed.

Add a maturity term. The entry has `wallet` and `hashIndex`, which is everything needed:
`wallet.getWalletTransaction(hashIndex.getHash())` gives the transaction (and so
`isCoinBase()`), and the tip comes from `AppServices.getCurrentBlockHeight()` falling back to
`wallet.getStoredBlockHeight()` — the same convention `TransactionEntry.calculateConfirmations()`
already uses, so there is precedent for reaching `AppServices` from this package.

What this buys, with no further work:

| Call site | Effect |
|---|---|
| `EntryCell.java:876` | Row gets the `unspendable` style, already defined at `wallet.css:59` |
| `UtxosController.java:166` | Coin cannot be selected for spending on the UTXOs screen |
| `EntryCell.java:186, 222, 391, 460, 801` | Dropped from the spend and send context menus |
| `DateCell.java:39` | Already distinguishes "(Spendable)" from "(Not yet spendable)" |

**Testable without a display?** Partly. The predicate itself is testable the way
`CoinbaseTxoFilterTest` is — build a wallet holding a coinbase, assert the entry. The CSS and
the table behaviour are not; they need eyes.

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

Add `getImmatureBalance()` alongside it, filtering the children on being an immature coinbase.
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
unlock height out of the label. During the window every coin mined in it unlocks at the same
height, so one height would usually be right — but coins mined near the end of the window
unlock later, on the ordinary hundred-block rule, so a single height in a summary line is not
always true. The per-coin detail belongs on the per-coin row.

**Testable:** yes, the sum is a pure function of the children.

### 3. Say when, where the eye already goes

**The UTXOs screen date/status column.** `DateCell.java:39` already writes
"Unconfirmed (Not yet spendable)" for a mempool output, so the column is understood to carry
spendability rather than only a date. Extend it for an immature coinbase, carrying both the
guess and the fact:

```
Immature — about 4 weeks (block 979,920)
```

That is longer than what the column holds today. Check it is not truncated before calling this
done; if it is, the height moves to the tooltip and the duration stays, since the duration is
the question being asked.

**The tooltip** can afford a sentence, and should name the cause rather than only the effect —
this is a network rule, not something Sparrow decided.

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

The cheapest honest improvement: when a transaction cannot be funded, check whether the wallet
holds immature coinbases that would have covered it, and if so say so — "Insufficient spendable
funds: N BTC is immature until block 979,920" — rather than implying the money is not there.

This is the change most likely to stop a support message being written, and it is the one a
miner hits first.

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
and a longer maturity window makes a restore against a pruned node more likely to be the case
that softens it — this filter silently starts offering frozen coins.

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

## Suggested order

1. **Section 5** (fail-open). Pure safety, fully testable, no UI. Independent of the rest.
2. **Section 1** (`isSpendable`). Everything else leans on it, and it alone fixes the worst of
   the problem: a coin that looks spendable and is not.
3. **Section 3** (say when). Small, once 1 is in.
4. **Section 2** (immature balance). FXML on two screens, one sum. The label is settled; what
   is not is whether the row fits beside the two it joins.
5. **Section 4** (Send message). Most valuable to a user, most fiddly, benefits from the rest
   being settled first.
6. **Section 6** (terminal).

1, 2, 3 and 5 are worth doing whatever happens to the deployment schedule. They are correct for
the hundred-block rule too; that rule was simply short enough that nobody minded.

## What needs a human with a display

Everything visual. There is no display on the build machine, so this splits cleanly:

- **Testable headless:** the `isSpendable` predicate, the immature balance sum, the wording
  functions including the duration buckets, the filter.
- **Needs eyes:** that the `unspendable` style reads as "different" rather than "disabled";
  that a third balance row fits both screens without crowding; that the status column holds
  "Immature — about 4 weeks (block 979,920)" without truncating; that selection behaves when a
  mixed set is selected.

Follow the pattern already used for the wording: put the decision in a pure function with
tests, and let the untestable part be only the plumbing.

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

**Nothing blocking.** The one genuinely unresolved question is the column width in section 3 —
whether "Immature — about 4 weeks (block 979,920)" fits the UTXOs date column or has to be
split between the cell and its tooltip. That needs a display, so it is a question for whoever
implements it rather than one to settle on paper.

## What not to do

**Do not hide immature coins.** They are the owner's. Hiding them produces a support message
about missing money, which is worse than the one about unspendable money.

**Do not hardcode 973440 or 979920 anywhere new.** They live in `LongCoinbaseMaturity` and
nowhere else, they came from a release candidate for a pull request that was still open, and
they have already moved once.

**Do not couple any of this to the 45-day figure.** Part two is a year. Everything here should
read the window from `LongCoinbaseMaturity` and be indifferent to how wide it is.

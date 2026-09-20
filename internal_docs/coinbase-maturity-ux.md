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

**Height is the truth, time is the intuition.** The rule is defined in block heights. A
duration is an estimate that depends on an assumed block interval, and this chain's has not
been near ten minutes. Say the height; offer the estimate as an estimate, or not at all.

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

### 2. An "Immature" balance, beside the two that already exist

The UTXOs screen already shows two figures, at `utxos.fxml:38` and `:41`, backed by:

```java
public long getBalance() {
    return getChildren().stream().mapToLong(Entry::getValue).sum();
}

public long getMempoolBalance() {
    return getChildren().stream()
            .filter(entry -> ((UtxoEntry)entry).getHashIndex().getHeight() <= 0)
            .mapToLong(Entry::getValue).sum();
}
```

(`WalletUtxosEntry.java:90`)

So the precedent for "part of this balance is not like the rest" is established, and a third
figure is a direct parallel: filter the children on being an immature coinbase, sum, and add a
label to the FXML beside the other two.

Show it **only when non-zero**. Every wallet that is not mining would otherwise carry a
permanent zero row explaining a rule that will never apply to it.

Wording: "Immature" is the term Bitcoin Core uses and the one a miner will have seen. Pair it
with the unlock height in the adjacent status text rather than in the label.

**Testable:** yes, the sum is a pure function of the children.

### 3. Say when, where the eye already goes

Two places, in order of value:

**The UTXOs screen date/status column.** `DateCell.java:39` already writes
"Unconfirmed (Not yet spendable)" for a mempool output, so the column is understood to carry
spendability. Extend it for an immature coinbase: the height is the fact, so
`Immature — spendable at block 979,920`.

**The tooltip**, which can afford more words than a column: the count, the reason, the unlock
height, and — if we decide to estimate — the rough wait.

Keep the Transactions screen wording (`ConfirmationsDescription`) and this consistent. They
should not describe the same coin two ways.

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
4. **Section 2** (immature balance). Needs FXML work and a decision on the label.
5. **Section 4** (Send message). Most valuable to a user, most fiddly, benefits from the rest
   being settled first.
6. **Section 6** (terminal).

1, 2, 3 and 5 are worth doing whatever happens to the deployment schedule. They are correct for
the hundred-block rule too; that rule was simply short enough that nobody minded.

## What needs a human with a display

Everything visual. There is no display on the build machine, so this splits cleanly:

- **Testable headless:** the `isSpendable` predicate, the immature balance sum, the wording
  functions, the filter.
- **Needs eyes:** that the `unspendable` style actually reads as "different" rather than
  "disabled"; that the new balance row does not crowd the two beside it; that the status column
  is not truncated by the unlock height; that selection behaves when a mixed set is selected.

Follow the pattern already used for the wording: put the decision in a pure function with
tests, and let the untestable part be only the plumbing.

## Open decisions

**Do we estimate a wait in human terms?** "About 45 days" is what a person wants. It is also
wrong whenever the chain's block rate is not ten minutes, which on this chain is most of the
time. Options: heights only; a deliberately coarse estimate ("about six weeks"); or an estimate
derived from recent block times, which is more honest and more code. My inclination is heights
plus a coarse estimate, with the estimate clearly hedged.

**Does the headline wallet balance change?** This document proposes adding an immature figure to
the UTXOs screen only, leaving the main balance as the total. The alternative — showing the
headline balance as spendable-only — is arguably more honest and is a much bigger change in
behaviour that would affect every wallet, not just mining ones. Deliberately not proposed here.

**A new `Status` value?** `Status` (`drongo/.../wallet/Status.java`) has exactly one member,
`FROZEN`, and it is user-set and persisted. Maturity is derived, not stored, so it should *not*
become a `Status` — but the two want to look similar on screen. Worth being explicit about that
distinction when implementing section 1, so a future reader does not try to unify them.

## What not to do

**Do not hide immature coins.** They are the owner's. Hiding them produces a support message
about missing money, which is worse than the one about unspendable money.

**Do not hardcode 973440 or 979920 anywhere new.** They live in `LongCoinbaseMaturity` and
nowhere else, they came from a release candidate for a pull request that was still open, and
they have already moved once.

**Do not couple any of this to the 45-day figure.** Part two is a year. Everything here should
read the window from `LongCoinbaseMaturity` and be indifferent to how wide it is.

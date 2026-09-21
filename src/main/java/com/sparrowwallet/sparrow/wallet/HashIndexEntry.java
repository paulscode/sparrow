package com.sparrowwallet.sparrow.wallet;

import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.protocol.LongCoinbaseMaturity;
import com.sparrowwallet.drongo.wallet.BlockTransaction;
import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.drongo.wallet.Status;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.control.DateLabel;
import com.sparrowwallet.sparrow.event.WalletEntryLabelsChangedEvent;
import com.sparrowwallet.sparrow.io.Config;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class HashIndexEntry extends Entry implements Comparable<HashIndexEntry> {
    private final BlockTransactionHashIndex hashIndex;
    private final Type type;
    private final KeyPurpose keyPurpose;

    public HashIndexEntry(Wallet wallet, BlockTransactionHashIndex hashIndex, Type type, KeyPurpose keyPurpose) {
        super(wallet.isNested() ? wallet.getMasterWallet() : wallet, hashIndex.getLabel(), hashIndex.getSpentBy() != null ? List.of(new HashIndexEntry(wallet, hashIndex.getSpentBy(), Type.INPUT, keyPurpose)) : Collections.emptyList());
        this.hashIndex = hashIndex;
        this.type = type;
        this.keyPurpose = keyPurpose;

        labelProperty().addListener((observable, oldValue, newValue) -> {
            if(!Objects.equals(hashIndex.getLabel(), newValue)) {
                hashIndex.setLabel(newValue);
                EventManager.get().post(new WalletEntryLabelsChangedEvent(wallet, this));
            }
        });
    }

    public BlockTransactionHashIndex getHashIndex() {
        return hashIndex;
    }

    public Type getType() {
        return type;
    }

    public KeyPurpose getKeyPurpose() {
        return keyPurpose;
    }

    public BlockTransaction getBlockTransaction() {
        return getWallet().getWalletTransaction(hashIndex.getHash());
    }

    public String getDescription() {
        return (type.equals(Type.INPUT) ? "Spent by input " : "Received from output ") +
                getHashIndex().getHash().toString().substring(0, 8) + "..:" +
                getHashIndex().getIndex() +
                (getHashIndex().getHeight() <= 0 ? " (Unconfirmed)" : " on " + DateLabel.getShortDateFormat(getHashIndex().getDate()));
    }

    public boolean isSpent() {
        return getType().equals(HashIndexEntry.Type.INPUT) || getHashIndex().getSpentBy() != null;
    }

    public boolean isSpendable() {
        return !isSpent() && (hashIndex.getHeight() > 0 || Config.get().isIncludeMempoolOutputs())
                && (hashIndex.getStatus() == null || hashIndex.getStatus() != Status.FROZEN)
                && !isImmatureCoinbase();
    }

    /**
     * Is this a mined coin the network will not yet accept a spend of?
     *
     * <p>Asked separately from {@link #isSpendable()} rather than folded into it, because the callers that
     * need it cannot use the wider question: {@code isSpendable()} is also false for a spent coin, a frozen
     * one and an unconfirmed one, and those need different wording and must not be counted in an immature
     * total.
     *
     * <p><b>This is not a {@link Status} and must never become one.</b> {@code Status} is set by the owner,
     * persisted in the wallet file, and round-tripped through labels import and export. Maturity is none of
     * those: it is derived from a height and a tip, and it changes on its own as blocks arrive. Writing it
     * down would make it wrong the moment the chain moved. The two look alike on screen and must stay apart
     * in the model, so resist the tidy-up that unifies them.
     */
    public boolean isImmatureCoinbase() {
        return !isSpent() && isImmatureCoinbase(getWallet(), hashIndex, getCurrentBlockHeight());
    }

    /**
     * The same question asked of a wallet and a txo directly, for callers that hold no entry.
     *
     * <p>The Transactions screen needs the immature total but must not read it off {@link WalletUtxosEntry},
     * whose children are only refreshed by the UTXOs screen: a wallet whose owner never opens that tab would
     * show a figure frozen at whenever the entry happened to be built. So the two screens each sum over the
     * wallet's own UTXOs, and share this one definition rather than the cached entry.
     */
    public static boolean isImmatureCoinbase(Wallet wallet, BlockTransactionHashIndex hashIndex, Integer currentBlockHeight) {
        if(hashIndex.getHeight() <= 0) {
            return false;
        }

        BlockTransaction blockTransaction = wallet.getWalletTransaction(hashIndex.getHash());
        if(blockTransaction == null || blockTransaction.getTransaction() == null
                || !blockTransaction.getTransaction().isCoinBase()) {
            return false;
        }

        if(currentBlockHeight == null) {
            //Nothing to measure depth against. CoinbaseTxoFilter refuses this case, but saying "immature"
            //here would put a coin in the immature total on no evidence, so the interface stays quiet.
            return false;
        }

        return !LongCoinbaseMaturity.isSpendable(Network.get(), hashIndex.getHeight(), currentBlockHeight);
    }

    /**
     * Sums the immature coinbase value across a wallet's UTXOs, read fresh rather than from any cached entry.
     *
     * <p>Does not overlap the mempool figure: that one is {@code height <= 0}, and an immature coinbase
     * requires a height. So the figures are disjoint and immature is always a subset of the confirmed
     * balance.
     */
    public static long getImmatureBalance(Wallet wallet, Integer currentBlockHeight) {
        return wallet.getWalletUtxos().keySet().stream()
                .filter(hashIndex -> isImmatureCoinbase(wallet, hashIndex, currentBlockHeight))
                .mapToLong(BlockTransactionHashIndex::getValue).sum();
    }

    /**
     * How many blocks until this coin can be spent, or zero if it already can be.
     *
     * <p>Exact, unlike any duration built from it: the rule is defined in heights.
     */
    public int getBlocksUntilMature() {
        Integer currentHeight = getCurrentBlockHeight();
        if(!isImmatureCoinbase() || currentHeight == null) {
            return 0;
        }

        return Math.max(0, getSpendableFromHeight() - (currentHeight + 1));
    }

    /** The first block height at which this coin may be spent. Only meaningful for a coinbase. */
    public int getSpendableFromHeight() {
        return LongCoinbaseMaturity.spendableFromHeight(Network.get(), hashIndex.getHeight());
    }

    private Integer getCurrentBlockHeight() {
        return AppServices.getCurrentBlockHeight() == null
                ? getWallet().getStoredBlockHeight() : AppServices.getCurrentBlockHeight();
    }

    @Override
    public Long getValue() {
        return hashIndex.getValue();
    }

    @Override
    public String getEntryType() {
        return type == Type.INPUT ? "Input" : "Output";
    }

    @Override
    public Function getWalletFunction() {
        return Function.ADDRESSES;
    }

    public enum Type {
        INPUT, OUTPUT
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HashIndexEntry)) return false;
        HashIndexEntry that = (HashIndexEntry) o;
        return super.equals(that) &&
                hashIndex == that.hashIndex &&
                type == that.type &&
                keyPurpose == that.keyPurpose;
    }

    @Override
    public int hashCode() {
        return Objects.hash(getWallet(), System.identityHashCode(hashIndex), type, keyPurpose);
    }

    @Override
    public int compareTo(HashIndexEntry o) {
        if(!getType().equals(o.getType())) {
            return o.getType().ordinal() - getType().ordinal();
        }

        if(getHashIndex().getHeight() != o.getHashIndex().getHeight()) {
            return o.getHashIndex().getComparisonHeight() - getHashIndex().getComparisonHeight();
        }

        return Long.compare(o.getHashIndex().getIndex(), (int)getHashIndex().getIndex());
    }
}

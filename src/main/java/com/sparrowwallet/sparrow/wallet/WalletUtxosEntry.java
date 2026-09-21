package com.sparrowwallet.sparrow.wallet;

import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletNode;
import com.sparrowwallet.sparrow.io.Config;

import java.util.*;
import java.util.stream.Collectors;

public class WalletUtxosEntry extends Entry {
    public static final int DUST_ATTACK_THRESHOLD_SATS = 1000;
    public static final int DUST_ATTACK_THRESHOLD_SP_SATS = 5000;

    public WalletUtxosEntry(Wallet wallet) {
        super(wallet, wallet.getName(), wallet.getWalletUtxos().entrySet().stream().map(entry -> new UtxoEntry(entry.getValue().getWallet(), entry.getKey(), HashIndexEntry.Type.OUTPUT, entry.getValue())).collect(Collectors.toList()));
        calculateDuplicates();
        calculateDust();
    }

    @Override
    public Long getValue() {
        return 0L;
    }

    @Override
    public String getEntryType() {
        return "Wallet UTXOs";
    }

    @Override
    public Function getWalletFunction() {
        return Function.UTXOS;
    }

    protected void calculateDuplicates() {
        Map<String, UtxoEntry> addressMap = new HashMap<>();

        for(Entry entry : getChildren()) {
            UtxoEntry utxoEntry = (UtxoEntry)entry;
            String address = utxoEntry.getAddress().toString();

            UtxoEntry duplicate = addressMap.get(address);
            if(duplicate != null) {
                duplicate.setDuplicateAddress(true);
                utxoEntry.setDuplicateAddress(true);
            } else {
                addressMap.put(address, utxoEntry);
                utxoEntry.setDuplicateAddress(false);
            }
        }
    }

    protected void calculateDust() {
        if(getWallet().getPolicyType() == PolicyType.SINGLE_SP) {
            long dustAttackThreshold = Config.get().getDustAttackThresholdSp();
            for(Entry entry : getChildren()) {
                UtxoEntry utxoEntry = (UtxoEntry) entry;
                utxoEntry.setDustAttack(utxoEntry.getValue() <= dustAttackThreshold && !utxoEntry.getWallet().allInputsFromWallet(utxoEntry.getHashIndex().getHash()));
            }
        } else {
            long dustAttackThreshold = Config.get().getDustAttackThreshold();
            Set<WalletNode> duplicateNodes = getWallet().getWalletTxos().values().stream()
                    .collect(Collectors.groupingBy(e -> e, Collectors.counting()))
                    .entrySet().stream().filter(e -> e.getValue() > 1).map(Map.Entry::getKey).collect(Collectors.toSet());

            for(Entry entry : getChildren()) {
                UtxoEntry utxoEntry = (UtxoEntry) entry;
                utxoEntry.setDustAttack(utxoEntry.getValue() <= dustAttackThreshold && duplicateNodes.contains(utxoEntry.getNode()) && !utxoEntry.getWallet().allInputsFromWallet(utxoEntry.getHashIndex().getHash()));
            }
        }
    }

    public void updateUtxos() {
        List<Entry> current = getWallet().getWalletUtxos().entrySet().stream().map(entry -> new UtxoEntry(entry.getValue().getWallet(), entry.getKey(), HashIndexEntry.Type.OUTPUT, entry.getValue())).collect(Collectors.toList());
        List<Entry> previous = new ArrayList<>(getChildren());

        List<Entry> entriesAdded = new ArrayList<>(current);
        entriesAdded.removeAll(previous);
        getChildren().addAll(entriesAdded);

        List<Entry> entriesRemoved = new ArrayList<>(previous);
        entriesRemoved.removeAll(current);
        getChildren().removeAll(entriesRemoved);

        calculateDuplicates();
        calculateDust();
    }

    public long getBalance() {
        return getChildren().stream().mapToLong(Entry::getValue).sum();
    }

    public long getMempoolBalance() {
        return getChildren().stream().filter(entry -> ((UtxoEntry)entry).getHashIndex().getHeight() <= 0).mapToLong(Entry::getValue).sum();
    }

    /**
     * How much of the balance is mined coin the network will not yet accept a spend of.
     *
     * <p>Filtered on {@code isImmatureCoinbase()} rather than on {@code !isSpendable()}, because the latter
     * is also true of a frozen coin and an unconfirmed one and neither belongs in this total.
     *
     * <p>This does not overlap the mempool figure: that one is {@code height <= 0}, and an immature coinbase
     * requires a height. So the three figures are disjoint, nothing is counted twice, and immature is always
     * a subset of the confirmed balance.
     */
    public long getImmatureBalance() {
        return getChildren().stream().filter(entry -> ((UtxoEntry)entry).isImmatureCoinbase()).mapToLong(Entry::getValue).sum();
    }
}

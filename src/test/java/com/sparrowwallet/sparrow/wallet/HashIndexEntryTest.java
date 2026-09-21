package com.sparrowwallet.sparrow.wallet;

import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.Script;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.wallet.BlockTransaction;
import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.drongo.wallet.Wallet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The predicate the interface asks about maturity, and the block count it quotes.
 *
 * <p>Three of the plan's four surfaces lean on {@code isImmatureCoinbase()} rather than on
 * {@code isSpendable()}, because the wider question is also false for a spent coin, a frozen one and an
 * unconfirmed one, and those need different wording and must not be counted in an immature total. So this
 * tests what those three actually call.
 *
 * <p>No display needed: these are functions of a height, a tip and a transaction.
 */
public class HashIndexEntryTest {
    private static final int START = 973440;
    private static final int LONG = 979920 - START;

    @AfterEach
    public void tearDown() {
        Network.set(null);
    }

    private static Transaction coinbaseTransaction() {
        Transaction transaction = new Transaction();
        transaction.addInput(Sha256Hash.ZERO_HASH, 0xFFFFFFFFL, new Script(new byte[0]));
        return transaction;
    }

    private static Transaction ordinaryTransaction() {
        Transaction transaction = new Transaction();
        transaction.addInput(Sha256Hash.wrap(Utils.hexToBytes("cc".repeat(32))), 0, new Script(new byte[0]));
        return transaction;
    }

    private static HashIndexEntry entry(Transaction transaction, int height, Integer tip) {
        Wallet wallet = new Wallet();
        wallet.setPolicyType(PolicyType.SINGLE_HD);
        wallet.setScriptType(ScriptType.P2WPKH);
        wallet.setStoredBlockHeight(tip);
        wallet.updateTransactions(Map.of(transaction.getTxId(),
                new BlockTransaction(transaction.getTxId(), height, null, 0L, transaction)));

        BlockTransactionHashIndex hashIndex =
                new BlockTransactionHashIndex(transaction.getTxId(), height, null, 0L, 0, 100000L);
        return new HashIndexEntry(wallet, hashIndex, HashIndexEntry.Type.OUTPUT, KeyPurpose.RECEIVE);
    }

    /** An ordinary coin is never immature, whatever its depth. */
    @Test
    public void ordinaryCoinsAreNeverImmature() {
        Network.set(Network.MAINNET);
        assertFalse(entry(ordinaryTransaction(), START, START).isImmatureCoinbase());
        assertFalse(entry(ordinaryTransaction(), START, START + 1).isImmatureCoinbase());
    }

    /** A coinbase is immature until the relay depth, and then is not. */
    @Test
    public void aCoinbaseIsImmatureUntilTheRelayDepth() {
        Network.set(Network.MAINNET);
        assertTrue(entry(coinbaseTransaction(), START, START + 100).isImmatureCoinbase());
        assertTrue(entry(coinbaseTransaction(), START, START + LONG - 2).isImmatureCoinbase());
        assertFalse(entry(coinbaseTransaction(), START, START + LONG - 1).isImmatureCoinbase());
    }

    /**
     * A coin mined before the deployment is immature too. The interface must say so, or it shows a coin as
     * ordinary that the network will not carry a spend of.
     */
    @Test
    public void aCoinbaseMinedBeforeTheDeploymentIsAlsoImmature() {
        Network.set(Network.MAINNET);
        int height = START - 5000;
        assertTrue(entry(coinbaseTransaction(), height, height + 200).isImmatureCoinbase());
    }

    /** Unconfirmed coins belong to the mempool figure, not the immature one, so the sets stay disjoint. */
    @Test
    public void anUnconfirmedCoinIsNotCountedAsImmature() {
        Network.set(Network.MAINNET);
        assertFalse(entry(coinbaseTransaction(), 0, START).isImmatureCoinbase());
        assertFalse(entry(coinbaseTransaction(), -1, START).isImmatureCoinbase());
    }

    /**
     * With no tip the interface stays quiet rather than calling the coin immature on no evidence.
     * CoinbaseTxoFilter separately refuses to offer it for spending, so quiet here is not permissive.
     */
    @Test
    public void withNoTipNothingIsClaimed() {
        Network.set(Network.MAINNET);
        assertFalse(entry(coinbaseTransaction(), START, null).isImmatureCoinbase());
    }

    /** A network without the deployment keeps the hundred block rule. */
    @Test
    public void undeployedNetworksUseTheHundredBlockRule() {
        Network.set(Network.REGTEST);
        assertTrue(entry(coinbaseTransaction(), 500, 598).isImmatureCoinbase());
        assertFalse(entry(coinbaseTransaction(), 500, 599).isImmatureCoinbase());
    }

    /** The block count is exact, and reaches zero exactly when the coin becomes spendable. */
    @Test
    public void theBlockCountIsExactAndReachesZeroOnMaturity() {
        Network.set(Network.MAINNET);
        assertEquals(LONG - 1, entry(coinbaseTransaction(), START, START).getBlocksUntilMature());
        assertEquals(1, entry(coinbaseTransaction(), START, START + LONG - 2).getBlocksUntilMature());
        assertEquals(0, entry(coinbaseTransaction(), START, START + LONG - 1).getBlocksUntilMature());
    }

    /** A coin that is not an immature coinbase has no wait to quote. */
    @Test
    public void anythingNotImmatureHasNoWait() {
        Network.set(Network.MAINNET);
        assertEquals(0, entry(ordinaryTransaction(), START, START).getBlocksUntilMature());
        assertEquals(0, entry(coinbaseTransaction(), START, null).getBlocksUntilMature());
    }

    /** The unlock height is the coin's own block plus the depth, not a height shared with other coins. */
    @Test
    public void theUnlockHeightIsPerCoin() {
        Network.set(Network.MAINNET);
        assertEquals(START + LONG, entry(coinbaseTransaction(), START, START).getSpendableFromHeight());
        assertEquals(START + 1 + LONG, entry(coinbaseTransaction(), START + 1, START).getSpendableFromHeight());
    }
}

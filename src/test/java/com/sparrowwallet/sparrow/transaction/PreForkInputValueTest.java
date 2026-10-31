package com.sparrowwallet.sparrow.transaction;

import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.wallet.BlockTransaction;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * Broadcasting a transaction labelled not replay protected asks first when it spends coins created before the fork,
 * since those also exist on the chain that kept SHA256d. Only coins whose height the wallet knows are counted, so an
 * unconfirmed parent, whose ancestry is not known here, raises nothing.
 */
public class PreForkInputValueTest {
    private static final int FORK = 961640;

    private static Transaction parent(long... values) {
        Transaction tx = new Transaction();
        tx.addInput(Sha256Hash.twiceOf(new byte[] {9}), 0, new Script(new byte[0]));
        for(long value : values) {
            tx.addOutput(value, ScriptType.P2WPKH.getOutputScript(PolicyType.SINGLE_HD, new ECKey()));
        }
        return tx;
    }

    private static BlockTransaction at(int height, Transaction tx) {
        return new BlockTransaction(tx.getTxId(), height, null, null, tx);
    }

    @Test
    public void countsOnlyCoinsMinedBeforeTheFork() {
        Transaction old = parent(5000, 7000);
        Transaction recent = parent(11000);
        Transaction pending = parent(13000);
        Transaction spend = new Transaction();
        spend.addInput(old.getTxId(), 1, new Script(new byte[0]));
        spend.addInput(recent.getTxId(), 0, new Script(new byte[0]));
        spend.addInput(pending.getTxId(), 0, new Script(new byte[0]));
        spend.addInput(Sha256Hash.twiceOf(new byte[] {7}), 0, new Script(new byte[0]));    //not the wallet's

        Map<Sha256Hash, BlockTransaction> wallet = Map.of(old.getTxId(), at(FORK - 1, old), recent.getTxId(), at(FORK, recent), pending.getTxId(), at(0, pending));

        Assertions.assertEquals(7000, HeadersController.preForkInputValue(spend, wallet, FORK));
    }

    @Test
    public void nothingCreatedBeforeTheForkMeansNothingToAsk() {
        Transaction recent = parent(11000);
        Transaction spend = new Transaction();
        spend.addInput(recent.getTxId(), 0, new Script(new byte[0]));

        Assertions.assertEquals(0, HeadersController.preForkInputValue(spend, Map.of(recent.getTxId(), at(FORK + 10, recent)), FORK));
    }
}

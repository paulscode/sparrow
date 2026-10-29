package com.sparrowwallet.sparrow.transaction;

import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * A final transaction protected by one opted-in signature can still carry another that commits only to its own input,
 * which can be lifted out and spent on the chain that kept SHA256d. The label warns about those, as the PSBT path does.
 */
public class FinalTransactionLiftableTest {
    private static Transaction twoInputs(SigHash first, SigHash second, int outputs) {
        ECKey key = new ECKey();
        Sha256Hash digest = Sha256Hash.twiceOf(new byte[] {1});
        Transaction tx = new Transaction();
        tx.addInput(Sha256Hash.twiceOf(new byte[] {2}), 0, new Script(new byte[0]), new TransactionWitness(tx, key, key.sign(digest, first, TransactionSignature.Type.ECDSA)));
        tx.addInput(Sha256Hash.twiceOf(new byte[] {3}), 0, new Script(new byte[0]), new TransactionWitness(tx, key, key.sign(digest, second, TransactionSignature.Type.ECDSA)));
        for(int i = 0; i < outputs; i++) {
            tx.addOutput(1000L, ScriptType.P2WPKH.getOutputScript(PolicyType.SINGLE_HD, new ECKey()));
        }
        return tx;
    }

    @Test
    public void countsOnlySignaturesThatDidNotOptInAndCanBeLifted() {
        Assertions.assertEquals(0, HeadersController.liftableWithoutOptIn(twoInputs(SigHash.UNIFIED_ALL, SigHash.ALL, 1)));
        Assertions.assertEquals(1, HeadersController.liftableWithoutOptIn(twoInputs(SigHash.UNIFIED_ALL, SigHash.ANYONECANPAY_ALL, 1)), "Anyone Can Pay");
        Assertions.assertEquals(0, HeadersController.liftableWithoutOptIn(twoInputs(SigHash.UNIFIED_ALL, SigHash.UNIFIED_ANYONECANPAY_ALL, 1)), "opted in, so not valid there");
        Assertions.assertEquals(1, HeadersController.liftableWithoutOptIn(twoInputs(SigHash.UNIFIED_ALL, SigHash.SINGLE, 1)), "Single with no output of its own");
        Assertions.assertEquals(0, HeadersController.liftableWithoutOptIn(twoInputs(SigHash.UNIFIED_ALL, SigHash.SINGLE, 2)), "Single with its output");
    }
}

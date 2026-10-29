package com.sparrowwallet.sparrow;

import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.wallet.BlockTransaction;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * A transaction the BLAKE2b chain accepted, rather than one built to agree with the code reading it.
 *
 * <p>f6e6ea43… was confirmed on BTCB2 mainnet and has no counterpart on the SHA256d chain. Its three P2WPKH inputs each
 * carry an opted-in signature. The fixtures are the raw transaction and the three it spends, as served by mempool.guide
 * on 2026-10-15; their txids are recomputed from the bytes, so a fixture that was altered cannot pass for the original.
 */
public class ChainOnlyCoinsMainnetTest {
    private static final Sha256Hash SPLIT = Sha256Hash.wrap("f6e6ea43d597fdf217de9de483e605a980239b742ba3426ea925e34d4bf0710d");
    private static final List<String> FIXTURES = List.of(
            "f6e6ea43d597fdf217de9de483e605a980239b742ba3426ea925e34d4bf0710d",
            "35f56dceb90e2d32ff41c2a6ee39e1dc0a8ac39c0f88fe3869d292f1d770810a",
            "e1e1493cb9d128dafd8f4ebc5dc38961bb727216243ada6fe23652e4e8cc2796",
            "fbea06cbe9244e060fad066caf976d692322bc596b5880b03c23d7b6fe1f7ea5");

    private final Map<Sha256Hash, BlockTransaction> known = new HashMap<>();

    @BeforeEach
    public void setUp() throws IOException {
        ChainOnlyCoins.clear();
        known.clear();
        for(String txid : FIXTURES) {
            try(InputStream in = getClass().getResourceAsStream("chainonly/" + txid + ".hex")) {
                Transaction transaction = new Transaction(Utils.hexToBytes(new String(in.readAllBytes(), StandardCharsets.US_ASCII).trim()));
                Assertions.assertEquals(txid, transaction.getTxId().toString(), "fixture bytes do not hash to their name");
                known.put(transaction.getTxId(), new BlockTransaction(transaction.getTxId(), 977300, null, null, transaction));
            }
        }
    }

    @Test
    public void aMainnetSplitIsProven() {
        //As though the wallet that signed it were open here: these are someone else's coins, and the case is about
        //reading real bytes, which ownership has no bearing on
        Assertions.assertEquals(new ChainOnlyCoins.Proven(SPLIT, 0), ChainOnlyCoins.prove(SPLIT, known::get, script -> true, Network.MAINNET));
    }

    @Test
    public void theSameSplitIsNotProofForAnyoneWhoDidNotSignIt() {
        ChainOnlyCoins.Proof proof = ChainOnlyCoins.prove(SPLIT, known::get, script -> false, Network.MAINNET);
        Assertions.assertFalse(proof instanceof ChainOnlyCoins.Proven, "a signature by a key nobody here holds was taken as proof: " + proof);
    }

    /**
     * Every input, not only the first one found: a reading that happened to work for one would say little about the
     * parsing.
     */
    @Test
    public void everyOptedInInputVerifies() {
        Transaction split = known.get(SPLIT).getTransaction();
        List<TransactionOutput> spent = spentOutputs(split);
        for(int i = 0; i < split.getInputs().size(); i++) {
            Assertions.assertTrue(ChainOnlyCoins.verifiesOptedIn(split, spent, i), "input " + i);
        }
    }

    /**
     * The opted-in message commits to every spent amount, so one satoshi off on any of them fails every signature.
     * This is what shows the check is reading the committed data rather than passing on shape.
     */
    @Test
    public void aWrongSpentAmountFailsEverySignature() {
        Transaction split = known.get(SPLIT).getTransaction();
        List<TransactionOutput> spent = spentOutputs(split);
        TransactionOutput first = spent.getFirst();
        spent.set(0, new TransactionOutput(split, first.getValue() + 1, first.getScript()));
        for(int i = 0; i < split.getInputs().size(); i++) {
            Assertions.assertFalse(ChainOnlyCoins.verifiesOptedIn(split, spent, i), "input " + i + " with a wrong amount");
        }
    }

    private List<TransactionOutput> spentOutputs(Transaction transaction) {
        List<TransactionOutput> spent = new ArrayList<>();
        for(TransactionInput input : transaction.getInputs()) {
            spent.add(known.get(input.getOutpoint().getHash()).getTransaction().getOutputs().get((int)input.getOutpoint().getIndex()));
        }
        return spent;
    }
}

package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.psbt.PSBTInput;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Every path that signs a transaction with a key the wallet holds has to ask the opt-in question. Two did not: a private
 * key sweep built its own PSBT, and the PayNym notification transaction called createPSBT on the wallet transaction
 * directly. Both are signed by software keys, so both could always have opted in, and a sweep in particular moves the
 * coins most likely to exist on both chains.
 */
public class SendPathsOptInTest {
    private static Transaction sweep(int inputs) {
        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        for(int i = 0; i < inputs; i++) {
            transaction.addInput(Sha256Hash.twiceOf(new byte[] {(byte)i}), 0, new Script(new byte[0]));
        }
        transaction.addOutput(90000L, ScriptType.P2WPKH.getOutputScript(PolicyType.SINGLE_HD, new ECKey()));
        return transaction;
    }

    private static List<TransactionOutput> spent(int inputs) {
        return Stream.generate(() -> new TransactionOutput(null, 50000L, ScriptType.P2WPKH.getOutputScript(PolicyType.SINGLE_HD, new ECKey()).getProgram()))
                .limit(inputs).toList();
    }

    @Test
    public void aSweepOptsInWhereTheChainHasIt() {
        PSBT psbt = PrivateKeySweepDialog.sweepPsbt(sweep(3), spent(3), true);
        for(PSBTInput psbtInput : psbt.getPsbtInputs()) {
            Assertions.assertEquals(SigHash.UNIFIED_ALL, psbtInput.getSigHash());
            Assertions.assertNotNull(psbtInput.getWitnessUtxo(), "every spent output is needed for the opted-in message");
        }
    }

    @Test
    public void aSweepSignsTheOldWayWhereTheChainDoesNot() {
        PSBT psbt = PrivateKeySweepDialog.sweepPsbt(sweep(2), spent(2), false);
        for(PSBTInput psbtInput : psbt.getPsbtInputs()) {
            Assertions.assertNull(psbtInput.getSigHash());
        }
    }

    /**
     * WalletTransaction.createPSBT knows nothing of the opt-in; AppServices.createPSBT applies the wallet's decision to
     * it. A call to the former anywhere else is a send path that never opts in, which is how the PayNym notification
     * transaction went without it.
     */
    @Test
    public void noSendPathBuildsItsPsbtAroundTheDecision() throws IOException {
        List<String> offenders;
        try(Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            offenders = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("AppServices.java"))
                    .filter(path -> {
                        try {
                            return Files.readString(path).contains(".createPSBT()");
                        } catch(IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .map(Path::toString).toList();
        }

        Assertions.assertEquals(List.of(), offenders, "use AppServices.createPSBT, which applies the opt-in decision");
    }
}

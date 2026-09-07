package com.sparrowwallet.sparrow;

import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.psbt.PSBTInput;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.wallet.DeterministicSeed;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletNode;
import com.sparrowwallet.drongo.wallet.DeterministicSeed.Type;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The wiring between the wallet and the verification, which the other tests do not reach.
 *
 * <p>drongo's side is covered against keys handed to it directly, and the arithmetic is covered against
 * numbers. Between them sits the part that decides which keys to hand over: matching an input to a node
 * by its spent output, then deriving that node's key in the form a signature for it commits to. Getting
 * that wrong fails silently and in the safe direction, which is the problem. Every opt-in would go
 * uncounted, every transaction would read as "not checked", and nothing would look broken.
 */
public class SignatureOptInWiringTest {
    private static final String MNEMONIC = "absent essay fox snake vast pumpkin height crouch silent bulb excuse razor";

    private static Wallet wallet() throws Exception {
        return wallet(MNEMONIC, ScriptType.P2WPKH);
    }

    private static Wallet wallet(String mnemonic, ScriptType scriptType) throws Exception {
        DeterministicSeed seed = new DeterministicSeed(mnemonic, "", 0, Type.BIP39);
        Wallet wallet = new Wallet();
        wallet.setPolicyType(PolicyType.SINGLE_HD);
        wallet.setScriptType(scriptType);
        wallet.getKeystores().add(Keystore.fromSeed(seed, PolicyType.SINGLE_HD, scriptType.getDefaultDerivation()));
        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE_HD, scriptType, wallet.getKeystores(), 1));
        wallet.getNode(KeyPurpose.RECEIVE).fillToIndex(wallet, 1);
        return wallet;
    }

    private static WalletNode firstReceiveNode(Wallet wallet) {
        return wallet.getNode(KeyPurpose.RECEIVE).getChildren().iterator().next();
    }

    /**
     * A PSBT spending an output this wallet owns, signed by the key this wallet derives for it.
     */
    private static PSBT signedPsbt(Wallet wallet, SigHash sigHash) throws Exception {
        WalletNode node = firstReceiveNode(wallet);
        Script spk = wallet.getScriptType().getOutputScript(wallet.getPolicyType(), wallet.getKeystores().getFirst().getPubKey(node));

        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        transaction.addInput(Sha256Hash.wrap(Utils.hexToBytes("cc".repeat(32))), 0, new Script(new byte[0]));
        transaction.addOutput(90000L, spk);

        PSBT psbt = new PSBT(transaction);
        PSBTInput psbtInput = psbt.getPsbtInputs().getFirst();
        psbtInput.setWitnessUtxo(new TransactionOutput(null, 100000L, spk.getProgram()));
        psbtInput.setSigHash(sigHash);

        ECKey privKey = wallet.getKeystores().getFirst().getKey(node);
        psbtInput.sign(wallet.getScriptType().getOutputKey(wallet.getPolicyType(), privKey));
        return psbt;
    }

    /**
     * The wiring works: an opted-in signature made by this wallet is found, verified and counted.
     *
     * <p>If the key derivation here were the wrong shape, this is the assertion that fails. Without it
     * the feature would report "not checked" for every transaction the wallet ever signs, which is a
     * safe answer and an entirely useless one.
     */
    @Test
    public void anOptedInSignatureFromThisWalletIsVerifiedAndCounted() throws Exception {
        Wallet wallet = wallet();
        AppServices.OptInCounts counts = AppServices.signatureOptInCounts(signedPsbt(wallet, SigHash.UNIFIED_ALL), wallet);

        Assertions.assertEquals(1, counts.total(), "one signature is present");
        Assertions.assertEquals(1, counts.verified(), "and this wallet can vouch for it");
        Assertions.assertEquals(1, counts.optedIn(), "and it opts in");
        Assertions.assertTrue(counts.isProtected());
        Assertions.assertFalse(counts.isUncertain());
    }

    @Test
    public void aLegacySignatureFromThisWalletIsVerifiedAndReadsAsUnprotected() throws Exception {
        Wallet wallet = wallet();
        AppServices.OptInCounts counts = AppServices.signatureOptInCounts(signedPsbt(wallet, SigHash.ALL), wallet);

        Assertions.assertEquals(1, counts.verified(), "checked, so the absence of an opt-in can be stated");
        Assertions.assertEquals(0, counts.optedIn());
        Assertions.assertTrue(counts.isKnownUnprotected());
        Assertions.assertFalse(counts.isUncertain());
    }

    /**
     * The same transaction read without the wallet that owns it, which is what happens when a PSBT is
     * opened on its own. The signature is real and does opt in, but nothing here can show that, so it
     * must read as unchecked rather than as unprotected.
     */
    @Test
    public void theSameTransactionWithoutItsWalletReadsAsUnchecked() throws Exception {
        Wallet wallet = wallet();
        PSBT psbt = signedPsbt(wallet, SigHash.UNIFIED_ALL);

        AppServices.OptInCounts counts = AppServices.signatureOptInCounts(psbt, null);
        Assertions.assertEquals(1, counts.total());
        Assertions.assertEquals(0, counts.verified());
        Assertions.assertTrue(counts.isUncertain(), "no wallet to vouch means nothing is known");
        Assertions.assertFalse(counts.isKnownUnprotected(), "and it must not be called unprotected");
    }

    /**
     * A different wallet's inputs. The signature verifies against nothing this wallet derives, and the
     * spent output is not one of its scripts, so it is not counted either way.
     */
    @Test
    public void anotherWalletsInputsAreNotVouchedFor() throws Exception {
        Wallet owner = wallet();
        PSBT psbt = signedPsbt(owner, SigHash.UNIFIED_ALL);

        DeterministicSeed otherSeed = new DeterministicSeed(
                "sudden vault detail pistol vintage trend rubber trigger caught scan hedgehog fatigue", "", 0, Type.BIP39);
        Wallet stranger = new Wallet();
        stranger.setPolicyType(PolicyType.SINGLE_HD);
        stranger.setScriptType(ScriptType.P2WPKH);
        stranger.getKeystores().add(Keystore.fromSeed(otherSeed, PolicyType.SINGLE_HD, ScriptType.P2WPKH.getDefaultDerivation()));
        stranger.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE_HD, ScriptType.P2WPKH, stranger.getKeystores(), 1));
        stranger.getNode(KeyPurpose.RECEIVE).fillToIndex(stranger, 1);

        AppServices.OptInCounts counts = AppServices.signatureOptInCounts(psbt, stranger);
        Assertions.assertEquals(1, counts.total());
        Assertions.assertEquals(0, counts.verified(), "a wallet that does not own the input vouches for nothing");
        Assertions.assertTrue(counts.isUncertain());
    }

    /**
     * The derivation fallback is deliberately off, and this is why.
     *
     * <p>getSigningNodes can match an input by reading candidate keys out of the PSBT when the output
     * script is not one the wallet derived. Those keys are the file's, so counting an opt-in from a
     * signature they verify would be the file agreeing with itself. Matching only on a script this
     * wallet derives is what makes the count evidence.
     */
    @Test
    public void anInputIsOnlyVouchedForWhereTheWalletDerivedItsScript() throws Exception {
        Wallet wallet = wallet();
        PSBT psbt = signedPsbt(wallet, SigHash.UNIFIED_ALL);

        Assertions.assertEquals(1, wallet.getSigningNodes(psbt, false).size(),
                "the wallet derived this output script, so it is matched without any fallback");
    }

    /**
     * A liftable signature is counted only where it was verified too, and in the same pass.
     */
    @Test
    public void aLiftableSignatureIsCountedOnceAndOnlyWhenVerified() throws Exception {
        Wallet wallet = wallet();
        AppServices.OptInCounts counts = AppServices.signatureOptInCounts(signedPsbt(wallet, SigHash.ANYONECANPAY_ALL), wallet);

        Assertions.assertEquals(1, counts.verified());
        Assertions.assertEquals(0, counts.optedIn());
        Assertions.assertEquals(1, counts.liftable(),
                "a legacy Anyone Can Pay signature can be lifted onto the chain that kept SHA256d");

        Assertions.assertEquals(0, AppServices.signatureOptInCounts(signedPsbt(wallet, SigHash.ANYONECANPAY_ALL), null).liftable(),
                "with nothing verified there is nothing to report as liftable either");
    }

    @Test
    public void aWalletThatCannotDeriveVouchesForNothing() throws Exception {
        Wallet empty = new Wallet();
        Assertions.assertFalse(empty.isValid(), "precondition: an empty wallet is not valid");

        Wallet owner = wallet();
        AppServices.OptInCounts counts = AppServices.signatureOptInCounts(signedPsbt(owner, SigHash.UNIFIED_ALL), empty);
        Assertions.assertEquals(0, counts.verified());
        Assertions.assertTrue(counts.isUncertain());
    }

    /**
     * The same thing on taproot, which is where the key form actually matters.
     *
     * <p>For P2WPKH the output-key transform is the identity, so the tests above would pass with it
     * missing. On taproot what signs is the tweaked output key rather than the keystore's own, so
     * handing over the untweaked one verifies nothing. That would be invisible: taproot transactions
     * would silently read as "not checked" forever, and taproot is exactly where the reading was wrong
     * to begin with, since a 65 byte control block is what read as an opted-in signature.
     */
    @Test
    public void anOptedInTaprootSignatureIsVerifiedAndCounted() throws Exception {
        Wallet wallet = wallet(MNEMONIC, ScriptType.P2TR);
        AppServices.OptInCounts counts = AppServices.signatureOptInCounts(signedPsbt(wallet, SigHash.UNIFIED_ALL), wallet);

        Assertions.assertEquals(1, counts.total());
        Assertions.assertEquals(1, counts.verified(), "the tweaked output key is what a taproot signature commits to");
        Assertions.assertEquals(1, counts.optedIn());
        Assertions.assertTrue(counts.isProtected());
    }

    /**
     * And the taproot key form is not the keystore's own key, which is the mistake being guarded
     * against. If these were the same value the test above would prove nothing.
     */
    @Test
    public void theTaprootKeyThatSignsIsNotTheKeystoreKey() throws Exception {
        Wallet wallet = wallet(MNEMONIC, ScriptType.P2TR);
        WalletNode node = firstReceiveNode(wallet);
        ECKey keystoreKey = wallet.getKeystores().getFirst().getPubKey(node);
        ECKey outputKey = wallet.getScriptType().getOutputKey(wallet.getPolicyType(), keystoreKey);

        Assertions.assertNotEquals(keystoreKey, outputKey,
                "taproot tweaks the key, so handing over the untweaked one would verify nothing");
    }
}

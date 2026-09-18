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
import com.sparrowwallet.drongo.wallet.DeterministicSeed.Type;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.KeystoreSource;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * A signer that cannot produce the opt-in must still be able to take its turn.
 *
 * <p>The opt-in is per signature, so one opted-in signature makes a transaction unreplayable whatever the
 * rest carry. A PSBT, though, declares one hash type per input, so while that declaration asks for the
 * opt-in an unmarked signer is asked for something it cannot produce and refuses.
 *
 * <p>That is survivable while the marked signers can still meet the threshold. It stops being survivable
 * the moment they cannot: a 2-of-3 whose two marked signers are exactly the threshold becomes unspendable
 * when one of them is lost, because the third is asked for a hash type it will not make. Funds, not
 * convenience.
 *
 * <p>{@code psbtForDevice} answers this over USB and was already here, but nothing called it, and a signer
 * reached by QR or by file has no device to ask in the first place. Found and fixed in Shrike, whose
 * reading of it this follows.
 */
public class PsbtForExportTest {
    private static final String SEED_A = "absent essay fox snake vast pumpkin height crouch silent bulb excuse razor";
    private static final String SEED_B = "sudden vault detail pistol vintage trend rubber trigger caught scan hedgehog fatigue";

    private static Keystore seedKeystore(String mnemonic, String label) throws Exception {
        DeterministicSeed seed = new DeterministicSeed(mnemonic, "", 0, Type.BIP39);
        Keystore keystore = Keystore.fromSeed(seed, PolicyType.MULTI_HD, ScriptType.P2WSH.getDefaultDerivation());
        //Distinct, because a wallet with duplicate keystore labels is not valid and an invalid wallet
        //vouches for nothing, which is the correct behaviour and made this test look like a product bug
        keystore.setLabel(label);
        return keystore;
    }

    /**
     * A keystore whose signer cannot produce the opt-in: watch only, and not marked.
     *
     * <p>canBeMarked accepts a watch-only keystore precisely so its owner can state what the signer behind
     * it does. Left unmarked, it is the stock co-signer this whole path exists for.
     */
    private static Keystore unmarkedKeystore(String mnemonic, String label) throws Exception {
        Keystore keystore = seedKeystore(mnemonic, label);
        //Genuinely watch only: the seed goes, so nothing here can sign and the wallet is the shape this
        //path is about, one signer that cannot produce the opt-in
        keystore.setSeed(null);
        keystore.setSource(KeystoreSource.SW_WATCH);
        keystore.setUnifiedSigHashSupported(false);
        return keystore;
    }

    /** A 1-of-2 multisig: one signer that can opt in, one that cannot. */
    private static Wallet mixedWallet() throws Exception {
        Wallet wallet = new Wallet();
        wallet.setPolicyType(PolicyType.MULTI_HD);
        wallet.setScriptType(ScriptType.P2WSH);
        wallet.getKeystores().add(seedKeystore(SEED_A, "Signer A"));
        wallet.getKeystores().add(unmarkedKeystore(SEED_B, "Signer B"));
        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.MULTI_HD, ScriptType.P2WSH, wallet.getKeystores(), 1));
        wallet.getNode(KeyPurpose.RECEIVE).fillToIndex(wallet, 1);
        return wallet;
    }

    /** The same shape, but every signer can opt in. */
    private static Wallet allCapableWallet() throws Exception {
        Wallet wallet = new Wallet();
        wallet.setPolicyType(PolicyType.MULTI_HD);
        wallet.setScriptType(ScriptType.P2WSH);
        wallet.getKeystores().add(seedKeystore(SEED_A, "Signer A"));
        wallet.getKeystores().add(seedKeystore(SEED_B, "Signer B"));
        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.MULTI_HD, ScriptType.P2WSH, wallet.getKeystores(), 1));
        wallet.getNode(KeyPurpose.RECEIVE).fillToIndex(wallet, 1);
        return wallet;
    }

    private static WalletNode firstReceiveNode(Wallet wallet) {
        return wallet.getNode(KeyPurpose.RECEIVE).getChildren().iterator().next();
    }

    /**
     * A PSBT spending an output this wallet owns, declaring the opt-in, optionally already signed by the
     * keystore that can produce it.
     */
    private static PSBT psbt(Wallet wallet, boolean signed) throws Exception {
        WalletNode node = firstReceiveNode(wallet);
        //From the wallet's own derivation, so the spent output is one it recognises as its own. Hand-rolling
        //the script is how this test first failed: getSigningNodes matched nothing and vouched for nothing.
        Script spk = wallet.getOutputScript(node);
        Script multisig = ScriptType.MULTISIG.getOutputScript(1, wallet.getKeystores().stream()
                .map(keystore -> keystore.getPubKey(node)).toList());

        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        transaction.addInput(Sha256Hash.wrap(Utils.hexToBytes("cc".repeat(32))), 0, new Script(new byte[0]));
        transaction.addOutput(90000L, spk);

        PSBT psbt = new PSBT(transaction);
        PSBTInput psbtInput = psbt.getPsbtInputs().getFirst();
        psbtInput.setWitnessUtxo(new TransactionOutput(null, 100000L, spk.getProgram()));
        psbtInput.setWitnessScript(multisig);
        psbtInput.setSigHash(SigHash.UNIFIED_ALL);

        if(signed) {
            ECKey privKey = wallet.getKeystores().getFirst().getKey(node);
            psbtInput.sign(privKey);
        }

        return psbt;
    }

    /**
     * Nothing to clear while every signer can produce the opt-in, so the declaration stands and the PSBT is
     * handed out untouched. Returned by identity, which is what says no copy was taken.
     */
    @Test
    public void aWalletWhoseSignersCanAllOptInExportsUnchanged() throws Exception {
        Wallet wallet = allCapableWallet();
        PSBT psbt = psbt(wallet, true);

        Assertions.assertSame(psbt, AppServices.psbtForExport(wallet, psbt));
    }

    /**
     * Before the first opted-in signature the declaration is the only thing asking for the opt-in, so it is
     * left alone and the marked signers go first. Clearing it here would give up the protection entirely.
     */
    @Test
    public void theDeclarationSurvivesUntilASignatureCarriesTheOptIn() throws Exception {
        Wallet wallet = mixedWallet();
        PSBT psbt = psbt(wallet, false);

        Assertions.assertSame(psbt, AppServices.psbtForExport(wallet, psbt),
                "with no opted-in signature yet, the declaration is all the protection there is");
        Assertions.assertTrue(psbt.getPsbtInputs().getFirst().getSigHash().isUnified());
    }

    /**
     * The fix. One opted-in signature is present, so the transaction is already unreplayable and the
     * declaration has done its work. Dropping it is what lets the unmarked signer sign at all.
     */
    @Test
    public void theOptInIsDroppedOnceASignatureCarriesIt() throws Exception {
        Wallet wallet = mixedWallet();
        PSBT psbt = psbt(wallet, true);

        Assertions.assertTrue(AppServices.signatureOptInCounts(psbt, wallet).isProtected(),
                "precondition: the transaction already carries a verified opt-in");

        PSBT exported = AppServices.psbtForExport(wallet, psbt);

        Assertions.assertNotSame(psbt, exported);
        SigHash exportedSigHash = exported.getPsbtInputs().getFirst().getSigHash();
        Assertions.assertFalse(exportedSigHash.isUnified(),
                "an unmarked signer is asked for a hash type it can actually produce");
        Assertions.assertEquals(SigHash.ALL, exportedSigHash,
                "and it is the type the opt-in was built on, not something else");
    }

    /**
     * The exported copy must still carry the signature that made it safe to drop the declaration. Handing
     * out a PSBT with the opt-in cleared and the opted-in signature gone would lose the protection rather
     * than preserve it.
     */
    @Test
    public void theSignatureThatMadeItSafeIsStillThere() throws Exception {
        Wallet wallet = mixedWallet();
        PSBT psbt = psbt(wallet, true);
        PSBT exported = AppServices.psbtForExport(wallet, psbt);

        Assertions.assertEquals(psbt.getPsbtInputs().getFirst().getPartialSignatures().size(),
                exported.getPsbtInputs().getFirst().getPartialSignatures().size());
        Assertions.assertTrue(AppServices.signatureOptInCounts(exported, wallet).isProtected(),
                "the exported PSBT is still protected by the signature it carries");
    }

    /**
     * The PSBT open in the tab must not change under the user. Export is a read, and the in-memory
     * transaction keeps declaring the opt-in so that the next signer asked is still asked for it.
     */
    @Test
    public void theOpenTransactionIsNotMutated() throws Exception {
        Wallet wallet = mixedWallet();
        PSBT psbt = psbt(wallet, true);

        AppServices.psbtForExport(wallet, psbt);

        Assertions.assertTrue(psbt.getPsbtInputs().getFirst().getSigHash().isUnified(),
                "exporting must not clear the declaration on the transaction being worked on");
    }

    /** Nothing to decide without a wallet or a PSBT, and this is reached from paths that may have neither. */
    @Test
    public void absentInputsAreHandedBackUnchanged() throws Exception {
        Wallet wallet = mixedWallet();
        PSBT psbt = psbt(wallet, true);

        Assertions.assertSame(psbt, AppServices.psbtForExport(null, psbt));
        Assertions.assertNull(AppServices.psbtForExport(wallet, null));
    }
}

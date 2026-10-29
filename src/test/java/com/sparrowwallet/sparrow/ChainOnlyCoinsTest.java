package com.sparrowwallet.sparrow;

import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.wallet.BlockTransaction;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.KeystoreSource;
import com.sparrowwallet.drongo.wallet.Wallet;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

/**
 * The split a wallet with a stock signer makes: a hot key sends a coin to the wallet with the opt-in, and the wallet
 * spends it alongside everything else with ordinary signatures. The second transaction is the one whose label was
 * wrong, and every case here is about whether that label can be trusted in the one direction that matters.
 */
public class ChainOnlyCoinsTest {
    private static final int POST_FORK = 970000;
    private static final int PRE_FORK = 900000;

    private final Map<Sha256Hash, BlockTransaction> known = new HashMap<>();

    /** The output scripts of the user's own wallets. Only an opted-in signature spending one of these is proof. */
    private final Set<Script> owned = new HashSet<>();

    @BeforeEach
    public void setUp() {
        ChainOnlyCoins.clear();
        known.clear();
        owned.clear();
    }

    @Test
    public void aVerifiedOptedInSignatureProvesTheCoinsItCreates() {
        ECKey hot = new ECKey();
        Transaction funding = fund(p2wpkh(hot), POST_FORK);
        Transaction split = spendP2wpkh(funding, hot, sign -> sign.unified(), POST_FORK);

        ChainOnlyCoins.Proof proof = prove(split);
        Assertions.assertEquals(new ChainOnlyCoins.Proven(split.getTxId(), 0), proof);
        Assertions.assertEquals(ChainOnlyCoins.Status.PROVEN, ChainOnlyCoins.status(Set.of(split.getTxId())));
    }

    /**
     * The consolidation itself: ordinary signatures throughout, protected only by the coin it spends.
     */
    @Test
    public void spendingAProvenCoinIsProtectedWhateverItsOwnSignaturesAre() {
        ECKey hot = new ECKey();
        Transaction funding = fund(p2wpkh(hot), POST_FORK);
        Transaction split = spendP2wpkh(funding, hot, sign -> sign.unified(), POST_FORK);

        Transaction preFork = fund(p2wpkh(new ECKey()), PRE_FORK);
        Transaction consolidation = new Transaction();
        consolidation.addInput(preFork.getTxId(), 0, new Script(new byte[0]));
        consolidation.addInput(split.getTxId(), 0, new Script(new byte[0]));
        consolidation.addOutput(140000, p2wpkh(new ECKey()));
        add(consolidation, POST_FORK + 1);

        Assertions.assertEquals(new ChainOnlyCoins.Proven(split.getTxId(), 0), prove(consolidation));
    }

    /**
     * The opt-in byte on a signature made over the ordinary message. It decodes, carries the bit, and is still
     * nothing: only a signature that verifies against the unified message is evidence.
     */
    @Test
    public void theOptInByteOnAnOrdinarySignatureProvesNothing() {
        ECKey hot = new ECKey();
        Transaction funding = fund(p2wpkh(hot), PRE_FORK);
        Transaction split = spendP2wpkh(funding, hot, sign -> sign.forgedByte(), POST_FORK);

        Assertions.assertInstanceOf(ChainOnlyCoins.Unproven.class, prove(split));
    }

    @Test
    public void anOrdinarySignatureProvesNothing() {
        ECKey hot = new ECKey();
        Transaction funding = fund(p2wpkh(hot), PRE_FORK);
        Transaction split = spendP2wpkh(funding, hot, sign -> sign.ordinary(), POST_FORK);

        Assertions.assertInstanceOf(ChainOnlyCoins.Unproven.class, prove(split));
        Assertions.assertEquals(ChainOnlyCoins.Status.UNPROVEN, ChainOnlyCoins.status(Set.of(split.getTxId())));
    }

    /**
     * The unified message commits to the output being spent, so without it nothing can be checked, and the answer
     * is what to fetch rather than a verdict.
     */
    @Test
    public void aMissingSpentOutputIsAskedForRatherThanJudged() {
        ECKey hot = new ECKey();
        Transaction funding = fund(p2wpkh(hot), POST_FORK);
        Transaction split = spendP2wpkh(funding, hot, sign -> sign.unified(), POST_FORK);
        known.remove(funding.getTxId());

        Assertions.assertEquals(new ChainOnlyCoins.Missing(Set.of(funding.getTxId())), prove(split));
        Assertions.assertEquals(ChainOnlyCoins.Status.UNKNOWN, ChainOnlyCoins.status(Set.of(split.getTxId())));
    }

    @Test
    public void aTaprootKeyPathOptInIsProven() {
        ECKey hot = new ECKey();
        Script outputScript = new Script(Utils.concat(new byte[] {0x51, 0x20}, hot.getPubKeyXCoord()));
        Transaction funding = fund(outputScript, POST_FORK);

        Transaction split = new Transaction();
        split.addInput(funding.getTxId(), 0, new Script(new byte[0]));
        split.addOutput(90000, p2wpkh(new ECKey()));
        Sha256Hash hash = split.hashForUnifiedSignature(List.of(funding.getOutputs().getFirst()), 0, UnifiedScriptType.TAPROOT,
                null, SigHash.UNIFIED_ALL.value, null, null, null);
        TransactionSignature signature = hot.sign(hash, SigHash.UNIFIED_ALL, TransactionSignature.Type.SCHNORR);
        split.getInputs().getFirst().setWitness(new TransactionWitness(split, signature));
        add(split, POST_FORK);

        Assertions.assertEquals(new ChainOnlyCoins.Proven(split.getTxId(), 0), prove(split));
    }

    /**
     * A script spend whose witness holds a 65 byte push ending in the opt-in byte. That reads as an opted-in Schnorr
     * signature to anything that looks at bytes, and is exactly what must not be called protection.
     */
    @Test
    public void aPushThatOnlyLooksLikeAnOptedInSignatureProvesNothing() {
        byte[] witnessScript = new byte[] {0x51};   //OP_TRUE
        Script p2wsh = ScriptType.P2WSH.getOutputScript(Sha256Hash.hash(witnessScript));
        Transaction funding = fund(p2wsh, PRE_FORK);

        byte[] lookalike = new byte[65];
        new Random(1).nextBytes(lookalike);
        lookalike[64] = SigHash.UNIFIED_ALL.value;

        Transaction spend = new Transaction();
        spend.addInput(funding.getTxId(), 0, new Script(new byte[0]));
        spend.addOutput(90000, p2wpkh(new ECKey()));
        spend.getInputs().getFirst().setWitness(new TransactionWitness(spend, List.of(lookalike, witnessScript)));
        add(spend, POST_FORK);

        Assertions.assertInstanceOf(ChainOnlyCoins.Unproven.class, prove(spend));
    }

    /**
     * A coinbase after the fork is left unproven: its bytes are the miner's to choose, on both chains.
     */
    @Test
    public void aPostForkCoinbaseIsNotTakenAsProof() {
        Transaction coinbase = new Transaction();
        coinbase.addInput(Sha256Hash.ZERO_HASH, 0xFFFFFFFFL, new Script(new byte[] {0x03, 0x01, 0x02, 0x03}));
        coinbase.addOutput(312500000L, p2wpkh(new ECKey()));
        add(coinbase, POST_FORK);

        Assertions.assertInstanceOf(ChainOnlyCoins.Unproven.class, prove(coinbase));
    }

    /**
     * History from before the fork is shared, so the walk stops there without asking for anything older.
     */
    @Test
    public void historyFromBeforeTheForkIsNotWalked() {
        Transaction preFork = new Transaction();
        preFork.addInput(Sha256Hash.twiceOf(new byte[] {1}), 0, new Script(new byte[0]));
        preFork.addOutput(100000, p2wpkh(new ECKey()));
        add(preFork, PRE_FORK);

        Assertions.assertInstanceOf(ChainOnlyCoins.Unproven.class, prove(preFork),
                "a pre-fork transaction must not ask for its own inputs");
    }

    @Test
    public void coinsLookedAtAndLeftUnprovenAreNotLookedAtAgain() {
        Sha256Hash txid = Sha256Hash.twiceOf(new byte[] {2});
        Assertions.assertEquals(ChainOnlyCoins.Status.UNKNOWN, ChainOnlyCoins.status(Set.of(txid)));
        ChainOnlyCoins.markUnproven(Set.of(txid));
        Assertions.assertEquals(ChainOnlyCoins.Status.UNPROVEN, ChainOnlyCoins.status(Set.of(txid)));
    }

    /**
     * An Anyone Can Pay input commits to nothing but itself, so it can be lifted into a transaction of its own on the
     * other chain, and a coin elsewhere in this one protects nothing about it. Each place the hash type can be read
     * from is enough to withhold the claim.
     */
    @Test
    public void anyoneCanPayAnywhereWithholdsTheClaim() {
        Transaction tx = new Transaction();
        tx.addInput(Sha256Hash.twiceOf(new byte[] {3}), 0, new Script(new byte[0]));
        tx.addInput(Sha256Hash.twiceOf(new byte[] {4}), 0, new Script(new byte[0]));
        tx.addOutput(90000, p2wpkh(new ECKey()));

        Assertions.assertFalse(ChainOnlyCoins.anInputCanBeLifted(new PSBT(tx), SigHash.ALL));
        Assertions.assertTrue(ChainOnlyCoins.anInputCanBeLifted(new PSBT(tx), SigHash.ANYONECANPAY_ALL), "declared on the control");

        PSBT declared = new PSBT(tx);
        declared.getPsbtInputs().get(1).setSigHash(SigHash.ANYONECANPAY_ALL);
        Assertions.assertTrue(ChainOnlyCoins.anInputCanBeLifted(declared, SigHash.ALL), "declared on one input");

        PSBT signed = new PSBT(tx);
        ECKey key = new ECKey();
        signed.getPsbtInputs().get(1).getPartialSignatures().put(key,
                key.sign(Sha256Hash.twiceOf(new byte[] {5}), SigHash.ANYONECANPAY_ALL, TransactionSignature.Type.ECDSA));
        Assertions.assertTrue(ChainOnlyCoins.anInputCanBeLifted(signed, SigHash.ALL), "carried by a signature");
    }

    /**
     * A payer who funds the user through a transaction they signed with the opt-in. Their signature verifies, and
     * it is still not proof: a txid does not commit to the witness, so they could sign the same transaction again
     * under the other chain's rules once the user had consolidated with the coin, and the consolidation would follow.
     */
    @Test
    public void anOptInByAKeyThatIsNotTheUsersProvesNothing() {
        ECKey payer = new ECKey();
        Transaction funding = fund(p2wpkh(payer), POST_FORK);
        owned.remove(p2wpkh(payer));
        Transaction payment = spendP2wpkh(funding, payer, sign -> sign.unified(), POST_FORK);

        //Not proven, and not settled either: the walk goes on to the payer's own ancestry, which nobody has here
        ChainOnlyCoins.Proof proof = prove(payment);
        Assertions.assertFalse(proof instanceof ChainOnlyCoins.Proven, "a payer's opt-in was taken as proof: " + proof);
        Assertions.assertNotEquals(ChainOnlyCoins.Status.PROVEN, ChainOnlyCoins.status(Set.of(payment.getTxId())));
    }

    /**
     * A server that holds the key to a pre-fork transaction's input serves it with an opted-in witness it made
     * itself, and reports it unconfirmed so the walk does not stop at the fork. The txid is the real one, so the
     * substitution is not caught by the txid check, and the user's own spend of it must still not read as proven.
     */
    @Test
    public void aSharedTransactionReWitnessedBySomeoneElseProvesNothing() {
        ECKey other = new ECKey();
        Transaction funding = fund(p2wpkh(other), PRE_FORK);
        owned.remove(p2wpkh(other));
        Transaction shared = spendP2wpkh(funding, other, sign -> sign.unified(), 0);

        Transaction usersSpend = new Transaction();
        usersSpend.addInput(shared.getTxId(), 0, new Script(new byte[0]));
        usersSpend.addOutput(80000, p2wpkh(new ECKey()));
        add(usersSpend, POST_FORK);

        Assertions.assertInstanceOf(ChainOnlyCoins.Unproven.class, prove(usersSpend));
    }

    /**
     * A transaction first reached at the depth limit is cut short there, and the same transaction reached again
     * nearer the coin has to be walked again rather than given the cut-short answer.
     */
    @Test
    public void aTransactionReachedAgainNearerTheCoinIsWalkedAgain() {
        ECKey hot = new ECKey();
        Transaction funding = fund(p2wpkh(hot), POST_FORK);
        Transaction split = spendP2wpkh(funding, hot, sign -> sign.unified(), POST_FORK);

        //Spends the split with an ordinary signature, so it is proven only through its parent
        Transaction middle = new Transaction();
        middle.addInput(split.getTxId(), 0, new Script(new byte[0]));
        middle.addOutput(30000, p2wpkh(new ECKey()));
        middle.addOutput(30000, p2wpkh(new ECKey()));
        add(middle, POST_FORK);

        //A chain long enough that middle is first met at the depth limit, from output 0
        Sha256Hash previous = middle.getTxId();
        for(int i = 0; i < ChainOnlyCoins.MAX_DEPTH - 1; i++) {
            Transaction link = new Transaction();
            link.addInput(previous, 0, new Script(new byte[0]));
            link.addOutput(20000 - i, p2wpkh(new ECKey()));
            add(link, POST_FORK);
            previous = link.getTxId();
        }

        //The long way first, then middle directly from output 1
        Transaction consolidation = new Transaction();
        consolidation.addInput(previous, 0, new Script(new byte[0]));
        consolidation.addInput(middle.getTxId(), 1, new Script(new byte[0]));
        consolidation.addOutput(40000, p2wpkh(new ECKey()));
        add(consolidation, POST_FORK);

        Assertions.assertEquals(new ChainOnlyCoins.Proven(split.getTxId(), 0), prove(consolidation));
    }

    /**
     * Single on an input with no output at its index signs, under the legacy message, the constant 1, which is valid
     * for any transaction at all. That input can be lifted out just as an Anyone Can Pay one can.
     */
    @Test
    public void singleOnAnInputWithNoOutputOfItsOwnWithholdsTheClaim() {
        Transaction oneForOne = new Transaction();
        oneForOne.addInput(Sha256Hash.twiceOf(new byte[] {6}), 0, new Script(new byte[0]));
        oneForOne.addOutput(90000, p2wpkh(new ECKey()));
        Assertions.assertFalse(ChainOnlyCoins.anInputCanBeLifted(new PSBT(oneForOne), SigHash.SINGLE), "each input has its output");

        Transaction twoForOne = new Transaction();
        twoForOne.addInput(Sha256Hash.twiceOf(new byte[] {7}), 0, new Script(new byte[0]));
        twoForOne.addInput(Sha256Hash.twiceOf(new byte[] {8}), 0, new Script(new byte[0]));
        twoForOne.addOutput(90000, p2wpkh(new ECKey()));
        Assertions.assertTrue(ChainOnlyCoins.anInputCanBeLifted(new PSBT(twoForOne), SigHash.SINGLE), "declared on the control");
        Assertions.assertTrue(ChainOnlyCoins.anInputCanBeLifted(new PSBT(twoForOne), SigHash.UNIFIED_SINGLE), "read broadly, opted in or not");
        Assertions.assertFalse(ChainOnlyCoins.anInputCanBeLifted(new PSBT(twoForOne), SigHash.NONE), "None still commits to every input");

        PSBT declared = new PSBT(twoForOne);
        declared.getPsbtInputs().get(0).setSigHash(SigHash.SINGLE);
        Assertions.assertFalse(ChainOnlyCoins.anInputCanBeLifted(declared, SigHash.ALL), "the input with an output may sign Single");
        declared.getPsbtInputs().get(1).setSigHash(SigHash.SINGLE);
        Assertions.assertTrue(ChainOnlyCoins.anInputCanBeLifted(declared, SigHash.ALL), "the input without one may not");
    }

    /** A final transaction is read off its witnesses: Anyone Can Pay anywhere, or Single without an output of its own. */
    @Test
    public void aFinalTransactionWithALiftableInputWithholdsTheClaim() {
        ECKey key = new ECKey();
        Sha256Hash digest = Sha256Hash.twiceOf(new byte[] {9});

        Transaction ordinary = twoInputsOneOutput(key, digest, SigHash.ALL, SigHash.ALL);
        Assertions.assertFalse(ChainOnlyCoins.anInputCanBeLifted(ordinary));
        Assertions.assertTrue(ChainOnlyCoins.anInputCanBeLifted(twoInputsOneOutput(key, digest, SigHash.ALL, SigHash.ANYONECANPAY_ALL)), "Anyone Can Pay");
        Assertions.assertTrue(ChainOnlyCoins.anInputCanBeLifted(twoInputsOneOutput(key, digest, SigHash.ALL, SigHash.SINGLE)), "Single on the input with no output");
        Assertions.assertFalse(ChainOnlyCoins.anInputCanBeLifted(twoInputsOneOutput(key, digest, SigHash.SINGLE, SigHash.ALL)), "Single on the input that has one");
    }

    private static Transaction twoInputsOneOutput(ECKey key, Sha256Hash digest, SigHash first, SigHash second) {
        Transaction tx = new Transaction();
        tx.addInput(Sha256Hash.twiceOf(new byte[] {10}), 0, new Script(new byte[0]));
        tx.addInput(Sha256Hash.twiceOf(new byte[] {11}), 0, new Script(new byte[0]));
        tx.addOutput(90000, p2wpkh(new ECKey()));
        tx.getInputs().get(0).setWitness(new TransactionWitness(tx, key, key.sign(digest, first, TransactionSignature.Type.ECDSA)));
        tx.getInputs().get(1).setWitness(new TransactionWitness(tx, key, key.sign(digest, second, TransactionSignature.Type.ECDSA)));
        return tx;
    }

    /**
     * A watch only wallet is an xpub, which someone making their own opt-in pass for the user's would hand over to be
     * imported. A seed, its PayNym accounts and a device are keys the user signs with.
     */
    @Test
    public void onlyAWalletThatHoldsItsKeysCountsAsTheUsers() {
        Assertions.assertTrue(ChainOnlyCoins.holdsItsKeys(walletOf(KeystoreSource.SW_SEED)), "a seed");
        Assertions.assertTrue(ChainOnlyCoins.holdsItsKeys(walletOf(KeystoreSource.SW_PAYMENT_CODE)), "a PayNym account of a seed");
        Assertions.assertTrue(ChainOnlyCoins.holdsItsKeys(walletOf(KeystoreSource.HW_USB)), "a connected device");
        Assertions.assertTrue(ChainOnlyCoins.holdsItsKeys(walletOf(KeystoreSource.HW_AIRGAPPED)), "an airgapped device");
        Assertions.assertFalse(ChainOnlyCoins.holdsItsKeys(walletOf(KeystoreSource.SW_WATCH)), "an xpub");
        Assertions.assertFalse(ChainOnlyCoins.holdsItsKeys(walletOf(KeystoreSource.SW_SEED, KeystoreSource.SW_WATCH)), "a quorum with an xpub in it");
        Assertions.assertFalse(ChainOnlyCoins.holdsItsKeys(new Wallet()), "no keystores at all");
    }

    private static Wallet walletOf(KeystoreSource... sources) {
        Wallet wallet = new Wallet();
        for(KeystoreSource source : sources) {
            Keystore keystore = new Keystore();
            keystore.setSource(source);
            wallet.getKeystores().add(keystore);
        }
        return wallet;
    }

    private ChainOnlyCoins.Proof prove(Transaction transaction) {
        return ChainOnlyCoins.prove(transaction.getTxId(), known::get, owned::contains, Network.MAINNET);
    }

    private interface Signer {
        TransactionSignature sign(Signing signing);
    }

    /** The three ways the hot key's input is signed in these cases. */
    private record Signing(Transaction tx, Transaction funding, ECKey key) {
        private byte[] scriptCode() {
            return ScriptType.P2PKH.getOutputScript(key.getPubKeyHash()).getProgram();
        }

        TransactionSignature unified() {
            Sha256Hash hash = tx.hashForUnifiedSignature(List.of(funding.getOutputs().getFirst()), 0, UnifiedScriptType.WITNESS_V0,
                    scriptCode(), SigHash.UNIFIED_ALL.value, null, null, null);
            return key.sign(hash, SigHash.UNIFIED_ALL, TransactionSignature.Type.ECDSA);
        }

        TransactionSignature forgedByte() {
            Sha256Hash hash = tx.hashForWitnessSignature(0, scriptCode(), funding.getOutputs().getFirst().getValue(), SigHash.UNIFIED_ALL.value);
            return key.sign(hash, SigHash.UNIFIED_ALL, TransactionSignature.Type.ECDSA);
        }

        TransactionSignature ordinary() {
            Sha256Hash hash = tx.hashForWitnessSignature(0, scriptCode(), funding.getOutputs().getFirst().getValue(), SigHash.ALL.value);
            return key.sign(hash, SigHash.ALL, TransactionSignature.Type.ECDSA);
        }
    }

    private Transaction spendP2wpkh(Transaction funding, ECKey key, Signer signer, int height) {
        Transaction tx = new Transaction();
        tx.addInput(funding.getTxId(), 0, new Script(new byte[0]));
        tx.addOutput(90000, p2wpkh(new ECKey()));
        TransactionSignature signature = signer.sign(new Signing(tx, funding, key));
        tx.getInputs().getFirst().setWitness(new TransactionWitness(tx, key, signature));
        add(tx, height);
        return tx;
    }

    /**
     * A coin paid to the script, which is taken to be the user's own unless the case says otherwise. Funded before
     * the fork wherever the case is about a verdict, since history from then is where the walk stops; one funded
     * after it, from a parent nobody has, is asked about instead.
     */
    private Transaction fund(Script script, int height) {
        owned.add(script);
        Transaction funding = new Transaction();
        funding.addInput(Sha256Hash.twiceOf(script.getProgram()), 0, new Script(new byte[0]));
        funding.addOutput(100000, script);
        add(funding, height);
        return funding;
    }

    private void add(Transaction transaction, int height) {
        known.put(transaction.getTxId(), new BlockTransaction(transaction.getTxId(), height, null, null, transaction));
    }

    private static Script p2wpkh(ECKey key) {
        return ScriptType.P2WPKH.getOutputScript(key.getPubKeyHash());
    }
}

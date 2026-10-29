package com.sparrowwallet.sparrow;

import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.psbt.PSBTInput;
import com.sparrowwallet.drongo.wallet.BlockTransaction;
import com.sparrowwallet.drongo.wallet.KeystoreSource;
import com.sparrowwallet.drongo.wallet.Wallet;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

import static com.sparrowwallet.drongo.protocol.ScriptType.*;

/**
 * Coins that exist only on this chain, and the proof that they do.
 *
 * <p>Replay protection is a property of the transaction, not of its signatures. Signing with the opt-in is one way to
 * have it. Spending a coin the chain that kept SHA256d does not have is another, and it is the only way open to a
 * wallet whose signer cannot produce the opt-in: that chain rejects the whole transaction for the missing input,
 * and the ordinary signatures on the other inputs commit to every input, so nobody can strip the one that is missing
 * there and replay the rest. Judging protection by the signatures alone labels that transaction "Not replay
 * protected", which is the label that sends someone off to do it again.
 *
 * <p>A coin is proven here when the transaction that created it, or one of its ancestors, carries an opted-in
 * signature that verifies, made by a key in one of the user's own wallets. Verified means checked against the output
 * it spent, fetched by txid, so the bytes are the ones that output commits to. Reading the hash type off a witness
 * push is not enough, for the reason {@link AppServices#signatureOptInCounts} gives.
 *
 * <p>Why the key has to be the user's own. A txid does not commit to the witness, so whoever holds the key behind an
 * opted-in signature can sign the same transaction again under the other chain's rules, for as long as the coin it
 * spent there is unspent. Once they do, every descendant, including a consolidation that was labelled protected, is
 * valid there too. A proof made with someone else's key is therefore a promise from them, not a property of the
 * coin: a payer, a payjoin counterparty, or a server that holds an ancestor's key and serves it with an opted-in
 * witness, could hand one over and withdraw it after the user relied on it. With the user's own key, the only party
 * who can withdraw it is the user, and {@link #CAVEAT} says how to close that for good.
 *
 * <p>What a server can and cannot do. Every transaction is checked against the txid it was asked for, so a server
 * cannot substitute one; it can withhold one, or misreport a height, and either only leaves a coin unproven. It
 * can serve a different witness for the same txid, which is why a witness is only believed when it verifies under
 * a key the user holds: nobody else can produce that signature.
 *
 * <p>Not counted, deliberately:
 * <ul>
 *     <li>A coinbase mined after the fork. Its txid is the hash of bytes a miner chose, and a miner working both
 *     chains could put the same coinbase at the same height on the other one.</li>
 *     <li>Script spends other than a single key: multisig, and taproot script paths. Proving them means running the
 *     script, and a proof that only covers the common shapes is still a proof wherever it says yes.</li>
 *     <li>Legacy P2PKH, whose opted-in script code has no vector to check this against.</li>
 *     <li>A signature by a key that is not in a wallet open in this app, for the reason above.</li>
 *     <li>A signature by a key in a watch only wallet, whose xpub may be anyone's: see {@link #holdsItsKeys}.</li>
 * </ul>
 * Each of these can only make a coin read as unproven, which is the existing label and never a false one.
 */
public final class ChainOnlyCoins {
    /**
     * Generations walked back from a coin before giving up. A wallet's own history is held locally, so the
     * walk is cheap until it leaves the wallet, and the coins this is for are a few generations from the
     * transaction that split them.
     */
    public static final int MAX_DEPTH = 6;

    public static final String CAVEAT = "This holds as long as the transaction that created that coin cannot be "
            + "signed again for the SHA256d chain. Only a signature from a wallet open here is counted, so the one key "
            + "that could do that is yours, and only while the coin it spent is still unspent on the SHA256d chain. "
            + "Spending that coin there once this confirms closes it for good.";

    private static final Map<Sha256Hash, Proven> PROVEN = new ConcurrentHashMap<>();
    private static final Set<Sha256Hash> UNPROVEN = ConcurrentHashMap.newKeySet();

    private ChainOnlyCoins() {
    }

    public sealed interface Proof permits Proven, Unproven, Missing {
    }

    /**
     * @param txid       the transaction carrying the verified opted-in signature
     * @param inputIndex the input it signs
     */
    public record Proven(Sha256Hash txid, int inputIndex) implements Proof {
    }

    public record Unproven() implements Proof {
    }

    /** These transactions are needed before anything can be said. */
    public record Missing(Set<Sha256Hash> txids) implements Proof {
    }

    /** What is known about the coins created by a set of transactions, without fetching anything. */
    public enum Status {
        /** At least one of them is proven. */
        PROVEN,
        /** Some have not been looked at yet. */
        UNKNOWN,
        /** Every one was looked at and none could be proven. */
        UNPROVEN
    }

    public static Status status(Collection<Sha256Hash> creatingTxids) {
        if(creatingTxids.stream().anyMatch(PROVEN::containsKey)) {
            return Status.PROVEN;
        }

        return UNPROVEN.containsAll(creatingTxids) ? Status.UNPROVEN : Status.UNKNOWN;
    }

    public static Optional<Proven> proven(Collection<Sha256Hash> creatingTxids) {
        return creatingTxids.stream().map(PROVEN::get).filter(Objects::nonNull).findFirst();
    }

    /**
     * Records the outcome of a look the caller could not finish, so the same coins are not looked at again on
     * every redraw. Called for coins whose ancestry ran past what the caller was willing to fetch.
     */
    public static void markUnproven(Collection<Sha256Hash> creatingTxids) {
        for(Sha256Hash txid : creatingTxids) {
            if(!PROVEN.containsKey(txid)) {
                UNPROVEN.add(txid);
            }
        }
    }

    /**
     * Whether a wallet's keys can be taken as the user's own, for the purpose of a proof.
     *
     * Nothing can establish that only the user holds a key: whoever does hold it can sign any challenge put to
     * them. What a wallet's keystores record is what the user set it up as. A seed, including the payment code
     * keystores of its PayNym accounts, is a key the app itself holds. A device is a signer the user said they
     * sign with. A watch only keystore is an xpub and nothing more, and an xpub is what someone would hand over to
     * make their own opt-in pass for the user's: so a wallet with one is left out. The cost is a coin left
     * unproven, which opening the wallet that made the signature puts right.
     */
    public static boolean holdsItsKeys(Wallet wallet) {
        return !wallet.getKeystores().isEmpty()
                && wallet.getKeystores().stream().noneMatch(keystore -> keystore.getSource() == KeystoreSource.SW_WATCH);
    }

    /**
     * Forgets which coins were left unproven, so they are looked at again. Called when that answer may have
     * changed: a wallet was opened, whose keys and history can complete a proof, or the server changed, and with it
     * what was withheld. Proofs are kept, since nothing a server or a closed wallet does can make one untrue.
     */
    public static void forgetUnproven() {
        UNPROVEN.clear();
    }

    /**
     * Whether the coins created by this transaction exist only on this chain.
     *
     * @param txid       the transaction that created the coin
     * @param lookup     transactions known so far, by txid, or null where one is not known
     * @param ownsScript whether an output script belongs to one of the user's wallets; only an opted-in signature
     *                   spending such an output is taken as proof
     * @param network    the network, for the fork height below which history is shared
     */
    public static Proof prove(Sha256Hash txid, Function<Sha256Hash, BlockTransaction> lookup, Predicate<Script> ownsScript, Network network) {
        int forkHeight = Blake2bDeployment.activationHeight(network);
        Proof proof = new Walk(lookup, ownsScript, forkHeight).prove(txid, 0);
        if(proof instanceof Proven proven) {
            PROVEN.put(txid, proven);
            UNPROVEN.remove(txid);
        } else if(proof instanceof Unproven) {
            UNPROVEN.add(txid);
        }

        return proof;
    }

    /**
     * One look at a coin's ancestry. Answers are kept for the length of the walk with the depth they were reached at,
     * because an answer cut short by the depth limit is only good for a transaction reached as deep or deeper: the
     * same transaction reached again nearer the coin has more generations left to walk.
     */
    private static final class Walk {
        private final Function<Sha256Hash, BlockTransaction> lookup;
        private final Predicate<Script> ownsScript;
        private final int forkHeight;
        private final Map<Sha256Hash, Proof> seen = new HashMap<>();
        private final Map<Sha256Hash, Integer> seenDepth = new HashMap<>();

        private Walk(Function<Sha256Hash, BlockTransaction> lookup, Predicate<Script> ownsScript, int forkHeight) {
            this.lookup = lookup;
            this.ownsScript = ownsScript;
            this.forkHeight = forkHeight;
        }

        private Proof prove(Sha256Hash txid, int depth) {
            Proven known = PROVEN.get(txid);
            if(known != null) {
                return known;
            }
            Proof earlier = seen.get(txid);
            if(earlier != null && (earlier instanceof Proven || seenDepth.get(txid) <= depth)) {
                return earlier;
            }

            Proof proof = walk(txid, depth);
            seen.put(txid, proof);
            seenDepth.put(txid, depth);
            if(proof instanceof Proven proven) {
                //Kept for every transaction on the way down, since each of them is now known to be one
                PROVEN.put(txid, proven);
            }

            return proof;
        }

        private Proof walk(Sha256Hash txid, int depth) {
            BlockTransaction blockTransaction = lookup.apply(txid);
            if(blockTransaction == null || blockTransaction.getTransaction() == null) {
                return new Missing(Set.of(txid));
            }

            //Confirmed before the fork, so on both chains. Only ever a shortcut, and only one that can stop a proof:
            //a server that reports a false height ends the walk early, which is a coin left unproven. A transaction
            //reported unconfirmed is walked like any other, and is only proven by a signature from the user's own
            //key. A network this build has no fork height for takes no shortcut, rather than reading all of its
            //history as shared.
            int height = blockTransaction.getHeight();
            if(forkHeight != Integer.MAX_VALUE && height > 0 && height < forkHeight) {
                return new Unproven();
            }

            Transaction transaction = blockTransaction.getTransaction();
            if(transaction.isCoinBase()) {
                return new Unproven();
            }

            Set<Sha256Hash> missing = new LinkedHashSet<>();

            //Its own signatures, which need every output it spent: the opted-in message commits to all of them
            List<TransactionOutput> spentOutputs = new ArrayList<>();
            for(TransactionInput input : transaction.getInputs()) {
                BlockTransaction spent = lookup.apply(input.getOutpoint().getHash());
                if(spent == null || spent.getTransaction() == null) {
                    missing.add(input.getOutpoint().getHash());
                    spentOutputs.add(null);
                    continue;
                }

                List<TransactionOutput> outputs = spent.getTransaction().getOutputs();
                long index = input.getOutpoint().getIndex();
                spentOutputs.add(index < outputs.size() ? outputs.get((int)index) : null);
            }

            if(missing.isEmpty() && spentOutputs.stream().allMatch(Objects::nonNull)) {
                for(int i = 0; i < transaction.getInputs().size(); i++) {
                    if(ownsScript.test(spentOutputs.get(i).getScript()) && verifiesOptedIn(transaction, spentOutputs, i)) {
                        return new Proven(txid, i);
                    }
                }
            }

            //Then its ancestors: spending a coin that only this chain has is enough on its own
            if(depth < MAX_DEPTH) {
                //A cycle cannot occur between real txids, but a hostile lookup should not loop
                seen.put(txid, new Missing(Set.of()));
                seenDepth.put(txid, -1);
                for(Sha256Hash parent : new LinkedHashSet<>(transaction.getInputs().stream().map(input -> input.getOutpoint().getHash()).toList())) {
                    if(lookup.apply(parent) == null) {
                        continue;   //Already counted as missing above
                    }

                    Proof parentProof = prove(parent, depth + 1);
                    if(parentProof instanceof Proven proven) {
                        return proven;
                    } else if(parentProof instanceof Missing parentMissing) {
                        missing.addAll(parentMissing.txids());
                    }
                }
            }

            return missing.isEmpty() ? new Unproven() : new Missing(missing);
        }
    }

    /**
     * Whether input i carries an opted-in signature that verifies against the key the output it spends commits to.
     */
    static boolean verifiesOptedIn(Transaction transaction, List<TransactionOutput> spentOutputs, int i) {
        try {
            TransactionInput input = transaction.getInputs().get(i);
            Script spentScript = spentOutputs.get(i).getScript();

            if(P2WPKH.isScriptType(spentScript)) {
                return verifiesKeyHash(transaction, spentOutputs, i, spentScript.getPubKeyHash(), input);
            }

            if(P2SH.isScriptType(spentScript)) {
                //Nested segwit: the script sig is the one push of the redeem script, and that has to be the one
                //the output commits to, or the witness below would be read against a script nobody spent
                List<ScriptChunk> chunks = input.getScriptSig().getChunks();
                if(chunks.size() != 1 || chunks.getFirst().data == null) {
                    return false;
                }

                Script redeemScript = new Script(chunks.getFirst().data);
                if(!P2WPKH.isScriptType(redeemScript)
                        || !Arrays.equals(Utils.sha256hash160(redeemScript.getProgram()), P2SH.getHashFromScript(spentScript))) {
                    return false;
                }

                return verifiesKeyHash(transaction, spentOutputs, i, redeemScript.getPubKeyHash(), input);
            }

            if(P2TR.isScriptType(spentScript)) {
                //Key path only, and without an annex: one push, and 65 bytes because a 64 byte signature
                //carries the default hash type, which does not opt in
                List<byte[]> pushes = input.hasWitness() ? input.getWitness().getPushes() : List.of();
                if(pushes.size() != 1 || pushes.getFirst().length != 65) {
                    return false;
                }

                TransactionSignature signature = TransactionSignature.decodeFromBitcoin(TransactionSignature.Type.SCHNORR, pushes.getFirst(), true);
                if((signature.sighashFlags & SigHash.UNIFIED_FLAG) == 0) {
                    return false;
                }

                Sha256Hash hash = transaction.hashForUnifiedSignature(spentOutputs, i, UnifiedScriptType.TAPROOT, null,
                        signature.sighashFlags, null, null, null);
                return P2TR.getPublicKeyFromScript(spentScript).verify(hash, signature);
            }
        } catch(Exception e) {
            //Anything that does not parse as the shape it claims to be proves nothing
        }

        return false;
    }

    private static boolean verifiesKeyHash(Transaction transaction, List<TransactionOutput> spentOutputs, int i,
                                           byte[] pubKeyHash, TransactionInput input) throws SignatureDecodeException {
        List<byte[]> pushes = input.hasWitness() ? input.getWitness().getPushes() : List.of();
        if(pushes.size() != 2) {
            return false;
        }

        byte[] pubKey = pushes.get(1);
        if(!Arrays.equals(Utils.sha256hash160(pubKey), pubKeyHash)) {
            return false;
        }

        TransactionSignature signature = TransactionSignature.decodeFromBitcoin(TransactionSignature.Type.ECDSA, pushes.getFirst(), true);
        if((signature.sighashFlags & SigHash.UNIFIED_FLAG) == 0) {
            return false;
        }

        //The script code segwit v0 already signs for a key hash spend, which is what the unified message takes for it
        byte[] scriptCode = P2PKH.getOutputScript(pubKeyHash).getProgram();
        Sha256Hash hash = transaction.hashForUnifiedSignature(spentOutputs, i, UnifiedScriptType.WITNESS_V0, scriptCode,
                signature.sighashFlags, null, null, null);
        return ECKey.fromPublicOnly(pubKey).verify(hash, signature);
    }

    /**
     * Whether any input of this PSBT is, or is asked to be, signed in a way that lets it be lifted out on its own.
     *
     * Two ways. Anyone Can Pay, anywhere, commits an input to nothing but itself. And Single on an input with no
     * output at its index: the legacy message for that is the constant 1, which signs any transaction at all.
     * Segwit and the opted-in message do not have that flaw, but which is which is not read here: this only ever
     * withholds a claim, so being broad costs the label in a case nobody chooses, never the user.
     *
     * Read off every signature and every declaration, checked or not, for the same reason: a stray push that happens
     * to read as one of these costs the label, never the user.
     */
    public static boolean anInputCanBeLifted(PSBT psbt, SigHash declared) {
        int outputs = psbt.getTransaction().getOutputs().size();
        List<PSBTInput> psbtInputs = psbt.getPsbtInputs();
        for(int i = 0; i < psbtInputs.size(); i++) {
            PSBTInput psbtInput = psbtInputs.get(i);
            boolean noOutputAtIndex = i >= outputs;
            if(liftable(declared, noOutputAtIndex)) {
                return true;
            }
            if(psbtInput.getSigHash() != null && liftable(psbtInput.getSigHash().value, noOutputAtIndex)) {
                return true;
            }
            for(TransactionSignature signature : psbtInput.getSignatures()) {
                if(liftable(signature.sighashFlags, noOutputAtIndex)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean liftable(SigHash sigHash, boolean noOutputAtIndex) {
        return sigHash != null && liftable(sigHash.value, noOutputAtIndex);
    }

    private static boolean liftable(byte flags, boolean noOutputAtIndex) {
        boolean anyoneCanPay = (flags & SigHash.ANYONECANPAY.value) != 0;
        boolean single = (flags & 0x1f) == SigHash.SINGLE.value;
        return anyoneCanPay || (single && noOutputAtIndex);
    }

    /** For tests, which need each case to start from nothing known. */
    static void clear() {
        PROVEN.clear();
        UNPROVEN.clear();
    }
}

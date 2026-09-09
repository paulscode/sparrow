package com.sparrowwallet.sparrow.net;

import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.protocol.Blake2bDeployment;
import com.sparrowwallet.drongo.protocol.BlockHeader;
import com.sparrowwallet.drongo.protocol.BlockHeaderV2;
import com.sparrowwallet.drongo.protocol.ProtocolException;
import com.sparrowwallet.drongo.protocol.Sha256Hash;

import java.util.ArrayList;
import java.util.List;

/**
 * Block headers whose length is read rather than assumed.
 *
 * <p>A chain that has activated the BLAKE2b proof-of-work change carries
 * 164-byte headers instead of 80, and both lengths appear in one chain: the
 * blocks below the activation height keep the old format. Bit 31 of the version
 * field, in the first four bytes, says which a given header is.
 *
 * <p>Everything in Sparrow that had a literal 80 in it routes through this
 * class, so that removing support later is a file deletion plus reverting the
 * call sites that name it. See {@code docs/electrum-header-v2.md} in the
 * electrs-pruned repo for the protocol proposal this implements the client half
 * of.
 *
 * <p>Two things make this necessary rather than cosmetic. The Electrum protocol
 * returns {@code blockchain.block.headers} as one concatenated hex string below
 * protocol 1.6 and as a list at 1.6 and above, so both forms have to be handled
 * and the concatenated one has to be walked. And the on-disk header store
 * indexes by height times a fixed stride, which a variable record length would
 * break.
 */
public class VariableHeaders {
    /** An 80 byte header as hex, which is the length every header had before this. */
    public static final int V1_HEX_LENGTH = BlockHeaderV2.HEADER_V1_LENGTH * 2;

    /**
     * The on-disk record size for this network.
     *
     * <p>Fixed per network rather than per header, so the store keeps indexing
     * by height times a stride and nothing else in {@link HeaderStore} has to
     * change. A network that can carry v2 headers uses the larger record and
     * pads an 80-byte header out.
     *
     * <p>Mainnet now uses the larger record, which it did not before the fork
     * activated there. A store written at the other stride is discarded rather
     * than misread: its first record cannot descend from the pinned checkpoint,
     * which is the same check that already handled a changed checkpoint set. The
     * cost is one re-download of the headers above the anchor.
     */
    public static int recordLength() {
        return supportsV2(Network.get()) ? BlockHeaderV2.HEADER_V2_LENGTH : BlockHeaderV2.HEADER_V1_LENGTH;
    }

    /**
     * Can this network carry v2 headers?
     *
     * <p>Mainnet, where BLAKE2b activated at height 961640 on 30 August 2026;
     * testnet4, where it activated at 149537; and regtest, where a node can
     * activate it with {@code -testactivationheight} and where this is
     * therefore tested. Not testnet3 or signet, which have no such deployment,
     * so a 164 byte header offered on one of those is refused rather than read.
     *
     * <p>Mainnet was excluded until the fork activated there, on the reasoning
     * that a server setting bit 31 was answering about a chain the client did
     * not ask for. That reasoning expired: there is now a mainnet chain whose
     * headers are 164 bytes, and this build exists to follow it.
     *
     * <p>Reading bit 31 on mainnet is safe for the history below the fork, and
     * that was checked rather than assumed. Every mainnet header from 1 to
     * 961,600 was walked over p2p and none sets bit 31. Consensus makes it
     * impossible from height 227,931 in any case, because BIP34, BIP66 and
     * BIP65 each reject a version below 2, 3 and 4 as a signed comparison, and
     * a version with bit 31 set is negative. Below 227,931 there was no version
     * rule at all, which is why the scan was worth running.
     *
     * <p>Ordinary testnet4 is included along with the forked one, because a
     * wallet cannot tell them apart before connecting. The only visible effect
     * there is the store stride below.
     */
    public static boolean supportsV2(Network network) {
        return network == Network.MAINNET || network == Network.TESTNET4 || network == Network.REGTEST;
    }

    /**
     * The length of a header of the given kind, refusing a v2 one where the
     * network cannot carry it.
     *
     * <p>The refusal keeps every other network exactly as strict as it was
     * before this class existed: mainnet accepted 80 bytes and nothing else,
     * and a server setting bit 31 there is answering about a chain the client
     * did not ask for.
     */
    private static int lengthFor(boolean v2) throws ProtocolException {
        if(!v2) {
            return BlockHeaderV2.HEADER_V1_LENGTH;
        }
        if(!supportsV2(Network.get())) {
            throw new ProtocolException("Block header marked v2 on " + Network.get() + ", which cannot carry one");
        }
        return BlockHeaderV2.HEADER_V2_LENGTH;
    }

    /**
     * The headers a response carries, in whichever form it uses, or -1 if that
     * is not a whole run of headers.
     *
     * <p>Counted without decoding, so this stays the cheap structural check
     * that {@link ElectrumServerRpc#checkBlockHeaders} needs before anything is
     * parsed. Exactly one form must be present: {@code hex} below protocol 1.6
     * and {@code headers} at 1.6 and above, and a response carrying both is as
     * malformed as one carrying neither, since there is no saying which the
     * server meant.
     */
    public static int countCarried(BlockHeaders chunk) {
        if((chunk.hex == null) == (chunk.headers == null)) {
            return -1;
        }
        if(chunk.headers == null) {
            return countHex(chunk.hex);
        }
        for(String header : chunk.headers) {
            if(header == null || countHex(header) != 1) {
                return -1;
            }
        }
        return chunk.headers.size();
    }

    /**
     * The number of headers a concatenated hex run holds, or -1 if the walk
     * does not land exactly on its end.
     */
    public static int countHex(String hex) {
        int offset = 0;
        int count = 0;
        while(offset < hex.length()) {
            if(offset + V1_HEX_LENGTH > hex.length()) {
                return -1;
            }
            int length;
            try {
                //The version is four bytes little endian, so bit 31 is the high bit of the fourth byte, hex characters 6 and 7
                int fourthByte = Integer.parseInt(hex, offset + 6, offset + 8, 16);
                length = lengthFor((fourthByte & 0x80) != 0) * 2;
            } catch(ProtocolException | NumberFormatException e) {
                return -1;
            }
            if(offset + length > hex.length()) {
                return -1;
            }
            offset += length;
            count++;
        }
        return count;
    }

    /**
     * The headers a response carries, decoded, in whichever form it uses.
     *
     * <p>The list form needs no walk, since protocol 1.6 already separated the
     * headers, but each element is still put through {@link #split} so that one
     * rule decides what a header is: an element carrying trailing bytes is a
     * server disagreeing with the client about the format, which is the thing
     * worth failing on.
     *
     * @param count how many headers the caller expects, having already checked
     *              the response reports that many
     */
    public static List<BlockHeader> parse(BlockHeaders chunk, int count) throws ProtocolException {
        if(chunk.headers == null) {
            return split(Utils.hexToBytes(chunk.hex), count);
        }
        if(chunk.headers.size() < count) {
            throw new ProtocolException("Header list holds " + chunk.headers.size() + " of " + count + " headers");
        }
        List<BlockHeader> headers = new ArrayList<>(count);
        for(int i = 0; i < count; i++) {
            String header = chunk.headers.get(i);
            if(header == null) {
                throw new ProtocolException("Header list holds no header at index " + i);
            }
            headers.add(split(Utils.hexToBytes(header), 1).getFirst());
        }
        return headers;
    }

    /**
     * Split a concatenated run of headers, reading each one's length from its
     * own version field.
     *
     * @param bytes the decoded blob
     * @param count how many headers the server said it returned
     * @throws ProtocolException if the walk does not consume exactly that many
     *                           headers, or runs off the end
     */
    public static List<BlockHeader> split(byte[] bytes, int count) throws ProtocolException {
        List<BlockHeader> headers = new ArrayList<>(count);
        int offset = 0;
        for(int i = 0; i < count; i++) {
            if(offset + BlockHeaderV2.HEADER_V1_LENGTH > bytes.length) {
                throw new ProtocolException("Header run ended early: wanted " + count + " headers, got " + i
                        + " before running out at offset " + offset + " of " + bytes.length);
            }
            int length = lengthFor(BlockHeaderV2.isV2(bytes, offset));
            if(offset + length > bytes.length) {
                throw new ProtocolException("Header " + i + " claims " + length + " bytes at offset " + offset
                        + " but only " + (bytes.length - offset) + " remain");
            }
            headers.add(new BlockHeader(bytes, offset));
            offset += length;
        }
        //Trailing bytes mean the walk and the server disagree about the format,
        //which is worth failing on rather than silently ignoring: it is exactly
        //what a client that assumed the wrong length would produce.
        if(offset != bytes.length) {
            throw new ProtocolException("Header run had " + (bytes.length - offset)
                    + " trailing bytes after " + count + " headers");
        }
        return headers;
    }

    /**
     * Serialize a header into a fixed-width record, zero padded.
     *
     * <p>The padding is unambiguous: the version field says how much of the
     * record is the header, so the remainder is never read back.
     */
    public static byte[] toRecord(BlockHeader header) {
        byte[] serialized = header.bitcoinSerialize();
        int size = recordLength();
        if(serialized.length == size) {
            return serialized;
        }
        if(serialized.length > size) {
            throw new IllegalStateException("Header of " + serialized.length
                    + " bytes does not fit a " + size + " byte record on " + Network.get());
        }
        byte[] record = new byte[size];
        System.arraycopy(serialized, 0, record, 0, serialized.length);
        return record;
    }

    /**
     * The height whose header settles which chain a server follows, or -1 where this network has no such height.
     *
     * <p>The first block mined under BLAKE2b, because it is the first one whose format differs. Every header below it is
     * 80 bytes on both chains and tells them apart only by hash; this one is 164 bytes on the chain this build follows
     * and 80 on the chain that kept SHA256d, so the answer is legible in a single header without pinning anything.
     */
    public static int chainIdentityHeight() {
        Network network = Network.get();
        int height = Blake2bDeployment.activationHeight(network);
        return supportsV2(network) && height != Integer.MAX_VALUE ? height : -1;
    }

    /**
     * The one header a single-header response carries, as hex, or null where the response does not hold exactly one.
     *
     * <p>Here rather than at the call site because the two wire forms are this class's problem: below protocol 1.6 the
     * header arrives as {@code hex} and at 1.6 and above as a one element {@code headers} list, and Fulcrum answers in
     * the second form while electrs answers in the first.
     */
    public static String singleHeaderHex(BlockHeaders chunk) {
        if(chunk == null || countCarried(chunk) != 1) {
            return null;
        }
        return chunk.headers == null ? chunk.hex : chunk.headers.getFirst();
    }

    /**
     * Why this server should not be used, or null if it should.
     *
     * <p>This build follows the BLAKE2b chain, and on mainnet that is one of two chains sharing a genesis block, a network
     * name and an address format. {@code genesis_hash} cannot tell them apart, so a server on the other chain is accepted
     * by every check Sparrow already makes, syncs perfectly well, and shows balances and confirmations for a chain the
     * user did not choose. That is silent, and on mainnet it is silent about money.
     *
     * <p>The header at {@link #chainIdentityHeight} is what settles it, and it is served by every Electrum server on
     * either chain because it is ordinary chain data rather than an extension. It says two things: whether that chain
     * changed proof of work at that height, from the format of the header alone, and which such chain it is, from the
     * block's hash against {@link Blake2bDeployment#activationBlockHash}.
     *
     * <p>{@code server.features} is read too, but only as corroboration. It carries {@code blake2b_fork} on a server that
     * implements the proposal this fork's electrs does, and a fork point that disagrees is a contradiction worth refusing
     * whatever the header says. Its absence is not: Fulcrum has no such field and follows this chain perfectly well, and
     * an earlier build of this one refused every Fulcrum server on that basis. Requiring an extension is requiring one
     * implementation.
     *
     * <p>What is still refused is not being able to tell. A server that serves neither the header nor the field is
     * indistinguishable from a server on the other chain, and treating "cannot tell" as "probably fine" is how the silent
     * case comes back.
     *
     * @param features the server's {@code server.features} response, or null if it did not answer
     * @param activationHeader the header the server serves at {@link #chainIdentityHeight} as hex, or null if it served none
     */
    public static String getChainMismatchError(ServerFeatures features, String activationHeader) {
        Network network = Network.get();
        int expectedHeight = chainIdentityHeight();
        if(expectedHeight < 0) {
            return null;    //no BLAKE2b deployment on this network, so there is nothing to disagree about
        }

        ServerFeatures.Blake2bFork reported = features == null ? null : features.blake2b_fork;
        if(reported != null) {
            String contradiction = getReportedForkError(network, expectedHeight, reported);
            if(contradiction != null) {
                return contradiction;
            }
        }

        if(activationHeader != null) {
            return getServedHeaderError(network, expectedHeight, activationHeader);
        }

        if(reported != null) {
            return null;    //the server names this chain's fork point, which is the question that was asked
        }

        return "The chain this server follows could not be established: it did not serve the block header at height "
                + expectedHeight + ", and it does not report a BLAKE2b fork point. This build follows the BLAKE2b chain, "
                + "which on " + network + " forked at that height.";
    }

    /** Whether the fork point a server reports is this chain's. */
    private static String getReportedForkError(Network network, int expectedHeight, ServerFeatures.Blake2bFork reported) {
        if(reported.height == null || reported.height != expectedHeight) {
            return "The server reports a BLAKE2b fork at height " + reported.height + ", but " + network + " forked at height "
                    + expectedHeight + ". These are different chains.";
        }

        Sha256Hash expectedHash = Blake2bDeployment.activationBlockHash(network);
        if(expectedHash != null && reported.hash != null && !expectedHash.toString().equalsIgnoreCase(reported.hash)) {
            return "The server reports the block at height " + expectedHeight + " as " + reported.hash + ", but on this chain it is "
                    + expectedHash + ". These are different chains.";
        }

        return null;
    }

    /** Whether the header a server serves at the activation height is this chain's. */
    private static String getServedHeaderError(Network network, int expectedHeight, String activationHeader) {
        BlockHeader header;
        try {
            header = split(Utils.hexToBytes(activationHeader), 1).getFirst();
        } catch(ProtocolException | IllegalArgumentException e) {
            return "The chain this server follows could not be established: its block header at height " + expectedHeight
                    + " could not be read (" + e.getMessage() + ").";
        }

        if(!header.isV2()) {
            return "The server is following a chain that has not changed its proof of work. This build follows the BLAKE2b "
                    + "chain, which on " + network + " forked at height " + expectedHeight + ".";
        }

        Sha256Hash expectedHash = Blake2bDeployment.activationBlockHash(network);
        if(expectedHash != null && !expectedHash.equals(header.getHash())) {
            return "The server's block at height " + expectedHeight + " is " + header.getHash() + ", but on this chain it is "
                    + expectedHash + ". These are different chains.";
        }

        return null;
    }
}

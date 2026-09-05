package com.sparrowwallet.sparrow.net;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.arteam.simplejsonrpc.client.Transport;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.protocol.BlockHeader;
import com.sparrowwallet.drongo.protocol.VerificationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A blockchain.block.headers response carried through the path a running Sparrow uses: the JSON-RPC call, the deserialization into
 * {@link BlockHeaders}, the checks in {@link ElectrumServerRpc}, and only then the split.
 * <p>
 * Deliberately not the split on its own. {@link ElectrumServerRpc#checkBlockHeaders} runs first and used to require a run of exactly 80 byte
 * headers, so a chain carrying 164 byte ones was refused before anything that could read them was reached. A test that starts at the split
 * cannot see that, and one that starts at the transport can.
 * <p>
 * The transport is a stub rather than a socket because nothing here is about the wire: the response is canned, and what is under test is
 * everything Sparrow does with it.
 */
public class BlockHeadersResponseTest {
    /**
     * Real testnet4 headers spanning the BLAKE2b activation at height 149537, fetched from mempool.guide and frozen here. 149537 is the
     * first v2 header and 149536 the last v1 one, so the pair is the transition itself rather than a constructed likeness of it, and 149537
     * names 149536 as its parent, which is a check the headers are read correctly rather than merely accepted.
     */
    private static final String HEADER_149536 =
            "00c07a2d48a589a1d9402520f5b20e264ce5159b58379f65b8656807d03ed10000000000d8ac297368de27e58b3c40c768bfbc64bd76e1abe63bea"
                    + "50544d0e8cabd1f7e864098a6affff001d6c015cc3";
    private static final String HEADER_149537 =
            "000000a05119dc259b59eaefbccf48ecc15bfd50c499d9d65b500b361b1b60000000000063be46460c5e75edfe6e1fba731e3fc096396af99c299e"
                    + "70625484ffc6f9184101fb896affff001d986cd88b510d792301fb896a00000000b14cf00d0100000000000000000000000f000000000000000000"
                    + "00000000000000000000214802000000000000000000000000000000000000000000000000000000000000000000";

    private static final String HASH_149536 = "0000000000601b1b360b505bd6d999c450fd5bc1ec48cfbcefea599b25dc1951";
    private static final String HASH_149537 = "000000000068f60429c933dc0c8befbcc7edadb1cf8f8d0d7804c608fd736d82";

    @AfterEach
    public void tearDown() {
        Network.set(null);
    }

    @Test
    public void aConcatenatedRunSpanningTheActivationReachesTheCaller() {
        Network.set(Network.TESTNET4);
        BlockHeaders chunk = fetch(149536, 2, "{\"count\":2,\"hex\":\"" + HEADER_149536 + HEADER_149537 + "\",\"max\":2016}");

        assertActivationPair(VariableHeaders.parse(chunk, chunk.count));
    }

    /**
     * Protocol 1.6 returns the run as a list rather than one string, and a client offering a range that spans 1.6 will be given it: Fulcrum
     * 2.1.2 negotiates 1.6 against a max of 1.8 and answers in this form.
     */
    @Test
    public void theProtocol16ListFormReachesTheCaller() {
        Network.set(Network.TESTNET4);
        BlockHeaders chunk = fetch(149536, 2,
                "{\"count\":2,\"headers\":[\"" + HEADER_149536 + "\",\"" + HEADER_149537 + "\"],\"max\":2016}");

        assertActivationPair(VariableHeaders.parse(chunk, chunk.count));
    }

    /** Both forms carry the same headers, so neither may be the only one that works. */
    private static void assertActivationPair(List<BlockHeader> headers) {
        assertEquals(2, headers.size());
        assertFalse(headers.getFirst().isV2(), "149536 is the last v1 header");
        assertTrue(headers.getLast().isV2(), "149537 is the first v2 header");
        assertEquals(HASH_149536, headers.getFirst().getHash().toString());
        assertEquals(HASH_149537, headers.getLast().getHash().toString());
        assertEquals(headers.getFirst().getHash(), headers.getLast().getPrevBlockHash(), "149537 must name 149536 as its parent");
    }

    /**
     * Regtest is where a node can be told to activate BLAKE2b, with -testactivationheight, and so is where the whole path is exercised
     * without the live chain. It has to accept the same run testnet4 does.
     */
    @Test
    public void regtestCarriesV2HeadersToo() {
        Network.set(Network.REGTEST);
        BlockHeaders chunk = fetch(149536, 2, "{\"count\":2,\"hex\":\"" + HEADER_149536 + HEADER_149537 + "\",\"max\":2016}");

        assertActivationPair(VariableHeaders.parse(chunk, chunk.count));
    }

    /**
     * The length rule is what stops a client reading a run as headers it is not. A response claiming more headers than its bytes hold is the
     * shape a fixed stride produces, so it stays a refusal whatever the lengths involved.
     */
    @Test
    public void aRunShorterThanItsReportedCountIsRefused() {
        Network.set(Network.TESTNET4);
        assertThrows(VerificationException.class,
                () -> fetch(149536, 2, "{\"count\":2,\"hex\":\"" + HEADER_149536 + "\",\"max\":2016}"));
        assertThrows(VerificationException.class,
                () -> fetch(149536, 2, "{\"count\":2,\"headers\":[\"" + HEADER_149536 + "\"],\"max\":2016}"));
    }

    /** Trailing bytes on a header mean the server and the client disagree about the format, which is the thing worth failing on. */
    @Test
    public void aPaddedHeaderIsRefused() {
        Network.set(Network.TESTNET4);
        assertThrows(VerificationException.class,
                () -> fetch(149536, 1, "{\"count\":1,\"hex\":\"" + HEADER_149536 + "0000\",\"max\":2016}"));
    }

    /**
     * Only a chain that has activated BLAKE2b carries 164 byte headers, so a server offering one anywhere else is answering about a chain
     * the client did not ask for.
     *
     * <p>Mainnet used to be one of those networks and is not any more, because BLAKE2b activated there at height 961640. testnet3 and
     * signet have no such deployment and stand in for it: they are still exactly as strict as every network was before variable lengths
     * existed. Dropping the case entirely when mainnet moved would have left nothing asserting that the refusal still happens anywhere.
     */
    @Test
    public void aV2HeaderIsRefusedOnANetworkThatCannotCarryOne() {
        Network.set(Network.TESTNET);
        assertThrows(VerificationException.class,
                () -> fetch(149537, 1, "{\"count\":1,\"hex\":\"" + HEADER_149537 + "\",\"max\":2016}"));

        Network.set(Network.SIGNET);
        assertThrows(VerificationException.class,
                () -> fetch(149537, 1, "{\"count\":1,\"hex\":\"" + HEADER_149537 + "\",\"max\":2016}"));
    }

    /**
     * Mainnet carries them now, so the refusal above must not still apply to it.
     *
     * <p>The header used is a real testnet4 one, because what is being asserted is that the length is read and the record parses
     * rather than being refused for its network. Its hash is not checked here; the mainnet hashes are covered by the deployment
     * and live-header tests.
     */
    @Test
    public void aV2HeaderIsAcceptedOnMainnet() {
        Network.set(Network.MAINNET);
        BlockHeaders chunk = fetch(149537, 1, "{\"count\":1,\"hex\":\"" + HEADER_149537 + "\",\"max\":2016}");
        List<BlockHeader> headers = VariableHeaders.parse(chunk, chunk.count);

        assertEquals(1, headers.size());
        assertTrue(headers.getFirst().isV2(), "a 164 byte header must parse as v2 on mainnet");
    }

    /** Neither form, or both at once: in both cases there is no saying what the server meant. */
    @Test
    public void aResponseWithoutExactlyOneFormIsRefused() {
        Network.set(Network.TESTNET4);
        assertThrows(VerificationException.class, () -> fetch(149536, 1, "{\"count\":1,\"max\":2016}"));
        assertThrows(VerificationException.class, () -> fetch(149536, 1,
                "{\"count\":1,\"hex\":\"" + HEADER_149536 + "\",\"headers\":[\"" + HEADER_149536 + "\"],\"max\":2016}"));
    }

    /**
     * A run far wider than the range asked for is refused, and refused before anything is built from it.
     *
     * <p>A header is 80 or 164 bytes, so {@code count} headers cannot occupy more than {@code count * 164} of them. Without that bound a
     * server can make the client allocate an arbitrarily large array by answering a two header request with megabytes of hex. The server is
     * the thing not being trusted here.
     *
     * <p>This is already refused, by {@link ElectrumServerRpc#checkBlockHeaders}, which counts the run against the reported count before the
     * reader is reached. Nothing asserted it, which is the gap this closes: privkeyio/shrike carries the same bound inside its own reader,
     * and comparing the two is what showed that this fork enforces it a layer earlier and had no test saying so.
     */
    @Test
    public void aRunWiderThanTheRangeAskedForIsRefused() {
        Network.set(Network.TESTNET4);
        String oversized = HEADER_149536.repeat(64);

        VerificationException e = assertThrows(VerificationException.class,
                () -> fetch(149536, 1, "{\"count\":1,\"hex\":\"" + oversized + "\",\"max\":2016}"));
        assertTrue(e.getMessage().contains("64 headers for a reported count of 1"),
                "the refusal should say how far the run overshot: " + e.getMessage());
    }

    /** The same holds for the list form, so an oversized entry cannot hide inside a list of the right length. */
    @Test
    public void anOversizedListEntryIsRefused() {
        Network.set(Network.TESTNET4);
        String oversized = HEADER_149536.repeat(64);

        VerificationException e = assertThrows(VerificationException.class,
                () -> fetch(149536, 2, "{\"count\":2,\"headers\":[\"" + HEADER_149536 + "\",\"" + oversized + "\"],\"max\":2016}"));
        assertTrue(e.getMessage().contains("does not hold a whole run of headers"),
                "the refusal should name the malformed run: " + e.getMessage());
    }

    /** And the bound must not refuse a legitimate one: two v2 headers are 328 bytes, exactly what two headers may occupy. */
    @Test
    public void aRunOfTheLargestLegitimateWidthIsAccepted() {
        Network.set(Network.MAINNET);
        BlockHeaders chunk = fetch(149537, 2, "{\"count\":2,\"hex\":\"" + HEADER_149537 + HEADER_149537 + "\",\"max\":2016}");

        assertEquals(2 * 2 * 164, chunk.hex.length(), "two v2 headers should be the widest a two header run can be");

        List<BlockHeader> headers = VariableHeaders.parse(chunk, 2);
        assertEquals(2, headers.size());
        assertTrue(headers.getFirst().isV2() && headers.getLast().isV2(), "both should read as v2 rather than being split at a fixed stride");
    }

    private static BlockHeaders fetch(int startHeight, int count, String result) {
        return new SimpleElectrumServerRpc().getBlockHeadersChunk(answering(result), startHeight, count);
    }

    /** A server that answers every request with the same result, echoing the id the client chose. */
    private static Transport answering(String result) {
        ObjectMapper mapper = new ObjectMapper();
        return request -> "{\"jsonrpc\":\"2.0\",\"id\":" + mapper.readTree(request).get("id") + ",\"result\":" + result + "}";
    }
}

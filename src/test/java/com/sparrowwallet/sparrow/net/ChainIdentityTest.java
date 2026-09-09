package com.sparrowwallet.sparrow.net;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.arteam.simplejsonrpc.client.Transport;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.protocol.Blake2bDeployment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Which chain a server follows, decided from what it serves.
 *
 * <p>Mainnet's two chains share a genesis block, a network name and an address format, so every check Sparrow already
 * made passes against either one. Connecting to the wrong one is silent: it syncs, and then shows another chain's
 * balances and confirmations against addresses this wallet derives.
 *
 * <p>The first version of this asked {@code server.features} for a {@code blake2b_fork} field and refused any server
 * that did not report one. That field is an extension only this fork's electrs implements, so the check refused Fulcrum,
 * which follows this chain and serves it correctly. Requiring an extension is requiring one implementation, and the
 * failure was total: the server was unusable, and the message said it was on the other chain.
 *
 * <p>So the evidence is ordinary chain data now. Every case below uses real headers off the two live chains rather than
 * constructed likenesses, which is what makes the negative case worth anything: the header that must be refused is the
 * actual block the chain that kept SHA256d mined at the same height.
 */
public class ChainIdentityTest {
    /**
     * The first block mined under BLAKE2b, height 961640, taken from a Fulcrum server following the fork. 164 bytes,
     * with bit 31 of its version set, and hashing to the block mempool.guide reports at that height.
     */
    private static final String BLAKE2B_961640 =
            "000000a0657e02138733654183a2c7320d85ca9d743fe139c4bb01000000000000000000c137a8515a0f6b3aaf6049cc7611787c022ad523d51094be"
                    + "0a0363d0dc0bc7684dca936a4f8d001a5671798c84daeb494dca936a00000000b1ccf00d0300000000000000000000001e0300000000000000000000"
                    + "000000000000000068ac0e000000000000000000000000000000000000000000000000000000000000000000";

    /**
     * The block the chain that kept SHA256d mined at that same height, from mempool.space. 80 bytes, and the whole point
     * of the check: a wallet pointed here would sync perfectly and be on the wrong chain.
     */
    private static final String SHA256D_961640 =
            "00c0cd2f5020e5d6a59cf5acc8ab25e86ded4c3528c5216205ca01000000000000000000317c696ea6df187e55b05be2146a5afffa3d3f7d9c23c8"
                    + "cec147d52d9f8b3e0ea6a6776a3d35021742202ecb";

    /** Height 961639, the last block mined under SHA256d on this chain. A v1 header, on this chain, one below the change. */
    private static final String BLAKE2B_961639 =
            "10000a205fca17a6566978303e989d163e1aa9dc6715eef5542e0000000000000000000080fe52c98f1c1f8484213dff5a88315f7c334d0705f7d7"
                    + "9579b289781868c0dff5c1916a3d350217510c87ed";

    private static final String MAINNET_FORK_BLOCK = "0000000000000050c1e5f69672f459293be14f46e5a494e7a8c8541396f18eeb";

    @AfterEach
    public void tearDown() {
        Network.set(null);
    }

    /** No {@code blake2b_fork} field, which is every server that is not this fork's electrs. */
    private static ServerFeatures fulcrum() {
        ServerFeatures features = new ServerFeatures();
        features.server_version = "Fulcrum 2.1.2";
        features.protocol_min = "1.4";
        features.protocol_max = "1.6";
        features.genesis_hash = "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f";
        return features;
    }

    private static ServerFeatures reportingFork(Integer height, String hash) {
        ServerFeatures features = fulcrum();
        features.server_version = "electrs 0.10.11";
        features.blake2b_fork = new ServerFeatures.Blake2bFork();
        features.blake2b_fork.height = height;
        features.blake2b_fork.hash = hash;
        features.blake2b_fork.header_bytes = 164;
        features.blake2b_fork.block_hash = "blake2b";
        return features;
    }

    /**
     * The regression. This is the exact pair the failing server presents: a Fulcrum that follows this chain, reports no
     * fork field, and serves the fork block when asked for it.
     */
    @Test
    public void aServerServingTheForkBlockIsAcceptedWithoutTheExtensionField() {
        Network.set(Network.MAINNET);

        Assertions.assertNull(VariableHeaders.getChainMismatchError(fulcrum(), BLAKE2B_961640),
                "a server serving this chain's fork block follows this chain, whether or not it implements the fork field");
    }

    /** A server that answers no server.features at all is in the same position, and the header still settles it. */
    @Test
    public void theHeaderSettlesItWithNoFeaturesResponseAtAll() {
        Network.set(Network.MAINNET);

        Assertions.assertNull(VariableHeaders.getChainMismatchError(null, BLAKE2B_961640));
    }

    /**
     * The case the whole check exists for, against the real block rather than a likeness of one.
     */
    @Test
    public void aServerOnTheChainThatKeptSha256dIsRefused() {
        Network.set(Network.MAINNET);

        String error = VariableHeaders.getChainMismatchError(fulcrum(), SHA256D_961640);
        Assertions.assertNotNull(error, "this is the other chain's block at that height");
        Assertions.assertTrue(error.contains("has not changed its proof of work"), error);
    }

    /**
     * A v1 header is a v1 header wherever it came from. 961639 is on this chain, one block below the change, and a
     * server serving it as the block at 961640 has not established anything about the chain above.
     */
    @Test
    public void aV1HeaderAtThatHeightIsRefusedEvenFromThisChain() {
        Network.set(Network.MAINNET);

        String error = VariableHeaders.getChainMismatchError(fulcrum(), BLAKE2B_961639);
        Assertions.assertNotNull(error);
        Assertions.assertTrue(error.contains("has not changed its proof of work"), error);
    }

    /**
     * Changing proof of work is not enough on its own where the chain can be named. A header that is structurally a v2
     * one but is not this chain's block must be refused, or the check would accept any fork of this fork.
     *
     * <p>Built by rolling the nonce of the real header, which leaves a well formed 164 byte v2 header that hashes to
     * something else. Nothing here verifies proof of work, so the point stands: identity is the hash, not the shape.
     */
    @Test
    public void aV2HeaderThatIsNotThisChainsBlockIsRefused() {
        Network.set(Network.MAINNET);

        //The nonce is the four bytes at offset 76, hex characters 152 to 160
        String rolled = BLAKE2B_961640.substring(0, 152) + "deadbeef" + BLAKE2B_961640.substring(160);
        Assertions.assertEquals(BLAKE2B_961640.length(), rolled.length(), "still a whole 164 byte header");

        String error = VariableHeaders.getChainMismatchError(fulcrum(), rolled);
        Assertions.assertNotNull(error, "a chain that changed proof of work is not necessarily this one");
        Assertions.assertTrue(error.contains(MAINNET_FORK_BLOCK), error);
    }

    /**
     * The corroborating half, for the server family that does report the field. A fork point that is not this chain's is
     * a contradiction, and it is refused even though the header served alongside it is right.
     */
    @Test
    public void aReportedForkPointOnAnotherChainIsRefusedDespiteACorrectHeader() {
        Network.set(Network.MAINNET);

        String error = VariableHeaders.getChainMismatchError(reportingFork(149537, null), BLAKE2B_961640);
        Assertions.assertNotNull(error, "the server contradicts itself, and one of the two answers is about another chain");
        Assertions.assertTrue(error.contains("149537"), error);
    }

    @Test
    public void aReportedForkBlockOnAnotherChainIsRefusedDespiteACorrectHeight() {
        Network.set(Network.MAINNET);

        String error = VariableHeaders.getChainMismatchError(
                reportingFork(961640, "00000000000000000001d82da6ecccf08e07afa383f9212b0e1b95cc72430c00"), BLAKE2B_961640);
        Assertions.assertNotNull(error);
        Assertions.assertTrue(error.contains(MAINNET_FORK_BLOCK), error);
    }

    /** A fork field with no height in it says nothing, and must not be read as agreement. */
    @Test
    public void aForkFieldWithoutAHeightIsRefused() {
        Network.set(Network.MAINNET);

        Assertions.assertNotNull(VariableHeaders.getChainMismatchError(reportingFork(null, null), null));
    }

    /**
     * A server that reports this chain's fork point but will not serve the header is accepted on the field alone.
     *
     * <p>This is what keeps this fork's own electrs working from behind whatever declines to serve that height, and it
     * is safe in a way absence is not: naming the fork point is a positive answer to the question that was asked.
     */
    @Test
    public void theReportedForkPointStandsAloneWhenNoHeaderIsServed() {
        Network.set(Network.MAINNET);

        Assertions.assertNull(VariableHeaders.getChainMismatchError(reportingFork(961640, MAINNET_FORK_BLOCK), null));
        Assertions.assertNull(VariableHeaders.getChainMismatchError(reportingFork(961640, null), null));
    }

    /**
     * Neither source, which is the one thing still refused. A server that serves no header at that height and reports no
     * fork point is indistinguishable from a server on the other chain, and reading that as probably fine is how the
     * silent case comes back.
     */
    @Test
    public void aServerThatSettlesNothingIsRefused() {
        Network.set(Network.MAINNET);

        String error = VariableHeaders.getChainMismatchError(fulcrum(), null);
        Assertions.assertNotNull(error);
        Assertions.assertTrue(error.contains("could not be established"), error);
        Assertions.assertFalse(error.contains("has not changed its proof of work"),
                "not knowing is not the same as knowing it is the other chain, and the message must not say so");
    }

    /**
     * A header that cannot be read is refused as unestablished rather than as the other chain.
     *
     * <p>Both end in a refusal, so this is about what the user is told. Reporting a truncated or non-hex response as a
     * chain that kept SHA256d would send somebody looking at their node rather than at their server.
     */
    @Test
    public void anUnreadableHeaderIsNotReportedAsTheOtherChain() {
        Network.set(Network.MAINNET);

        for(String malformed : new String[] {
                "",
                "00c0cd2f",
                BLAKE2B_961640.substring(0, BLAKE2B_961640.length() - 8),
                BLAKE2B_961640 + "00",
                SHA256D_961640 + SHA256D_961640,
                "zzzz" + BLAKE2B_961640.substring(4)}) {
            String error = VariableHeaders.getChainMismatchError(fulcrum(), malformed);
            Assertions.assertNotNull(error, "unreadable is not usable: " + malformed);
            Assertions.assertTrue(error.contains("could not be established"), error);
        }
    }

    /**
     * Where nothing is pinned, the format of the header is the whole answer.
     *
     * <p>Testnet4's activation height has moved with each release candidate, so there is no block to pin there. The
     * check still separates the forked testnet4 from the ordinary one, which is what it is for.
     */
    @Test
    public void withoutAPinnedBlockTheFormatStillSeparatesTheChains() {
        Network.set(Network.TESTNET4);
        Assertions.assertNull(Blake2bDeployment.activationBlockHash(Network.TESTNET4), "precondition: nothing pinned there");

        //Height 149537 on the forked testnet4, frozen alongside BlockHeadersResponseTest's copy of it
        String forkedTestnet4 =
                "000000a05119dc259b59eaefbccf48ecc15bfd50c499d9d65b500b361b1b60000000000063be46460c5e75edfe6e1fba731e3fc096396af99c299e70"
                        + "625484ffc6f9184101fb896affff001d986cd88b510d792301fb896a00000000b14cf00d0100000000000000000000000f0000000000000000000000"
                        + "0000000000000000214802000000000000000000000000000000000000000000000000000000000000000000";

        Assertions.assertNull(VariableHeaders.getChainMismatchError(fulcrum(), forkedTestnet4));

        String error = VariableHeaders.getChainMismatchError(fulcrum(), SHA256D_961640);
        Assertions.assertNotNull(error, "an ordinary testnet4 server serves 80 byte headers there");
        Assertions.assertTrue(error.contains("has not changed its proof of work"), error);
    }

    /**
     * A network with no BLAKE2b deployment has nothing to disagree about, and nothing is asked of a server on one.
     */
    @Test
    public void networksWithNoDeploymentAreLeftAlone() {
        for(Network network : new Network[] {Network.TESTNET, Network.SIGNET, Network.REGTEST}) {
            Network.set(network);
            Assertions.assertEquals(-1, VariableHeaders.chainIdentityHeight(), network + " has no height that settles this");
            Assertions.assertNull(VariableHeaders.getChainMismatchError(fulcrum(), null), network.toString());
            Assertions.assertNull(VariableHeaders.getChainMismatchError(null, SHA256D_961640), network.toString());
            Assertions.assertNull(VariableHeaders.getChainMismatchError(reportingFork(1, null), null), network.toString());
        }
    }

    @Test
    public void theHeightAskedForIsTheHeightTheChainChangedProofOfWork() {
        Network.set(Network.MAINNET);
        Assertions.assertEquals(961640, VariableHeaders.chainIdentityHeight());

        Network.set(Network.TESTNET4);
        Assertions.assertEquals(Blake2bDeployment.activationHeight(Network.TESTNET4), VariableHeaders.chainIdentityHeight());
    }

    /**
     * Both wire forms, because the two server families answer in different ones: electrs concatenates into {@code hex}
     * below protocol 1.6 and Fulcrum returns a one element {@code headers} list at 1.6.
     */
    @Test
    public void oneHeaderIsReadFromEitherWireForm() {
        Network.set(Network.MAINNET);

        BlockHeaders asHex = new BlockHeaders();
        asHex.count = 1;
        asHex.max = 2016;
        asHex.hex = BLAKE2B_961640;
        Assertions.assertEquals(BLAKE2B_961640, VariableHeaders.singleHeaderHex(asHex));

        BlockHeaders asList = new BlockHeaders();
        asList.count = 1;
        asList.max = 2016;
        asList.headers = List.of(BLAKE2B_961640);
        Assertions.assertEquals(BLAKE2B_961640, VariableHeaders.singleHeaderHex(asList));
    }

    /**
     * The whole path, from the wire to the verdict, for both server families.
     *
     * <p>Between the response and the code above sits {@link ElectrumServerRpc#checkBlockHeaders}, which runs first and
     * has form opinions of its own: it once required a run of exactly 80 byte headers, and refused a chain carrying 164
     * byte ones before anything that could read them was reached. A one header request is a shape nothing else asks for,
     * so it is checked here against what these two servers actually answer, byte for byte.
     */
    @Test
    public void aOneHeaderRequestSurvivesTheChecksOnTheWayIn() {
        Network.set(Network.MAINNET);

        //Fulcrum 2.1.2 at protocol 1.6, as captured from the server this was reported against
        BlockHeaders fromFulcrum = fetch(961640, "{\"count\":1,\"headers\":[\"" + BLAKE2B_961640 + "\"],\"max\":2016}");
        Assertions.assertNull(VariableHeaders.getChainMismatchError(fulcrum(), VariableHeaders.singleHeaderHex(fromFulcrum)));

        //electrs answers in the concatenated form below protocol 1.6, and reports the same max
        BlockHeaders fromElectrs = fetch(961640, "{\"count\":1,\"hex\":\"" + BLAKE2B_961640 + "\",\"max\":2016}");
        Assertions.assertNull(VariableHeaders.getChainMismatchError(reportingFork(961640, MAINNET_FORK_BLOCK),
                VariableHeaders.singleHeaderHex(fromElectrs)));

        //And the same call against the other chain still reaches the refusal
        BlockHeaders fromOtherChain = fetch(961640, "{\"count\":1,\"hex\":\"" + SHA256D_961640 + "\",\"max\":2016}");
        String error = VariableHeaders.getChainMismatchError(fulcrum(), VariableHeaders.singleHeaderHex(fromOtherChain));
        Assertions.assertNotNull(error);
        Assertions.assertTrue(error.contains("has not changed its proof of work"), error);
    }

    private static BlockHeaders fetch(int startHeight, String result) {
        ObjectMapper mapper = new ObjectMapper();
        Transport transport = request -> "{\"jsonrpc\":\"2.0\",\"id\":" + mapper.readTree(request).get("id") + ",\"result\":" + result + "}";
        return new SimpleElectrumServerRpc().getBlockHeadersChunk(transport, startHeight, 1);
    }

    /**
     * Anything that is not exactly one header yields nothing, which the caller then treats as no answer rather than
     * guessing which of them was meant.
     */
    @Test
    public void anythingOtherThanOneHeaderYieldsNothing() {
        Network.set(Network.MAINNET);

        Assertions.assertNull(VariableHeaders.singleHeaderHex(null));

        BlockHeaders empty = new BlockHeaders();
        empty.count = 0;
        empty.max = 2016;
        empty.hex = "";
        Assertions.assertNull(VariableHeaders.singleHeaderHex(empty));

        BlockHeaders two = new BlockHeaders();
        two.count = 2;
        two.max = 2016;
        two.hex = BLAKE2B_961639 + BLAKE2B_961640;
        Assertions.assertNull(VariableHeaders.singleHeaderHex(two));

        BlockHeaders bothForms = new BlockHeaders();
        bothForms.count = 1;
        bothForms.max = 2016;
        bothForms.hex = BLAKE2B_961640;
        bothForms.headers = List.of(BLAKE2B_961640);
        Assertions.assertNull(VariableHeaders.singleHeaderHex(bothForms),
                "a response carrying both forms is as malformed as one carrying neither");
    }
}

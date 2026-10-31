package com.sparrowwallet.sparrow.net.cormorant.electrum;

import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.sparrow.event.ConnectionFailedEvent;
import com.sparrowwallet.sparrow.net.ChainMismatchException;
import com.sparrowwallet.sparrow.net.ServerConfigException;
import com.sparrowwallet.sparrow.net.ServerFeatures;
import com.sparrowwallet.sparrow.net.VariableHeaders;
import com.sparrowwallet.sparrow.net.cormorant.bitcoind.BitcoindClientService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;

/**
 * The Full Node mode was exempt from the chain check, on the reasoning that the node is the user's own. Its defaults are a
 * stock node's on this computer, which for someone coming from upstream Sparrow is a node on the chain that kept SHA256d,
 * and that connection synced perfectly and showed that chain's balances. Cormorant now reports the fork point its node
 * shows, and serves the header the check reads, so the Full Node mode is checked like any other server.
 */
public class FullNodeChainIdentityTest {
    //Real headers off the two chains, as in ChainIdentityTest
    private static final String BLAKE2B_961640 =
            "000000a0657e02138733654183a2c7320d85ca9d743fe139c4bb01000000000000000000c137a8515a0f6b3aaf6049cc7611787c022ad523d51094be"
                    + "0a0363d0dc0bc7684dca936a4f8d001a5671798c84daeb494dca936a00000000b1ccf00d0300000000000000000000001e0300000000000000000000"
                    + "000000000000000068ac0e000000000000000000000000000000000000000000000000000000000000000000";
    private static final String BLAKE2B_961639 =
            "10000a205fca17a6566978303e989d163e1aa9dc6715eef5542e0000000000000000000080fe52c98f1c1f8484213dff5a88315f7c334d0705f7d7"
                    + "9579b289781868c0dff5c1916a3d350217510c87ed";
    private static final String SHA256D_961640 =
            "00c0cd2f5020e5d6a59cf5acc8ab25e86ded4c3528c5216205ca01000000000000000000317c696ea6df187e55b05be2146a5afffa3d3f7d9c23c8"
                    + "cec147d52d9f8b3e0ea6a6776a3d35021742202ecb";
    private static final String MAINNET_FORK_BLOCK = "0000000000000050c1e5f69672f459293be14f46e5a494e7a8c8541396f18eeb";
    private static final String SHA256D_FORK_HEIGHT_BLOCK = "00000000000000000000d35ab3c2b0b1c1b0a0b0c0d0e0f00112233445566778";

    @AfterEach
    public void tearDown() {
        Network.set(null);
    }

    @Test
    public void aBlake2bNodeReportsItsForkPointAndIsAccepted() {
        Network.set(Network.MAINNET);
        BitcoindClientService node = stubNode(Map.of(961639, "a".repeat(64), 961640, MAINNET_FORK_BLOCK),
                Map.of("a".repeat(64), BLAKE2B_961639, MAINNET_FORK_BLOCK, BLAKE2B_961640));

        ServerFeatures.Blake2bFork fork = ElectrumServerService.getBlake2bFork(node);

        Assertions.assertNotNull(fork);
        Assertions.assertEquals(961640, fork.height);
        Assertions.assertEquals(MAINNET_FORK_BLOCK, fork.hash);
        Assertions.assertNull(VariableHeaders.getChainMismatchError(features(fork), BLAKE2B_961640));
    }

    @Test
    public void aSha256dNodeReportsNoForkPointAndIsRefused() {
        Network.set(Network.MAINNET);
        BitcoindClientService node = stubNode(Map.of(961639, "b".repeat(64), 961640, SHA256D_FORK_HEIGHT_BLOCK),
                Map.of("b".repeat(64), BLAKE2B_961639, SHA256D_FORK_HEIGHT_BLOCK, SHA256D_961640));

        ServerFeatures.Blake2bFork fork = ElectrumServerService.getBlake2bFork(node);

        Assertions.assertNull(fork, "a node whose headers stay 80 bytes must not report a fork point");
        String error = VariableHeaders.getChainMismatchError(features(fork), SHA256D_961640);
        Assertions.assertNotNull(error);
        Assertions.assertTrue(error.contains("has not changed its proof of work"), error);
    }

    @Test
    public void aNodeWithoutThatBlockReportsNothing() {
        Network.set(Network.MAINNET);
        BitcoindClientService node = stubNode(Map.of(), Map.of());

        Assertions.assertNull(ElectrumServerService.getBlake2bFork(node));
    }

    /** Not retried every 15 seconds as though it might fix itself, and short in the status bar. */
    @Test
    public void aChainMismatchIsAConfigurationProblemWithAShortStatus() {
        ChainMismatchException mismatch = new ChainMismatchException("The server is following a chain that has not changed its proof of work.");
        Assertions.assertInstanceOf(ServerConfigException.class, mismatch);

        RuntimeException wrapped = new RuntimeException(new IllegalStateException(mismatch));
        Assertions.assertSame(mismatch, ChainMismatchException.find(wrapped));
        Assertions.assertEquals(ChainMismatchException.SHORT_MESSAGE, new ConnectionFailedEvent(wrapped).getMessage());
        Assertions.assertNull(ChainMismatchException.find(new RuntimeException("other")));
    }

    /**
     * A server that gave no answer may be catching up or have timed out, so it is retried rather than refused for good,
     * and is not called a server on another chain. One that answered for another chain is.
     */
    @Test
    public void noAnswerIsRetriedAndAWrongAnswerIsNot() {
        Network.set(Network.MAINNET);

        String noAnswer = VariableHeaders.getChainMismatchError(features(null), null);
        Assertions.assertTrue(VariableHeaders.isUndetermined(noAnswer), noAnswer);
        Assertions.assertTrue(VariableHeaders.isUndetermined(VariableHeaders.getChainMismatchError(features(null), "00")), "an unreadable header is no answer either");

        String wrongAnswer = VariableHeaders.getChainMismatchError(features(null), SHA256D_961640);
        Assertions.assertFalse(VariableHeaders.isUndetermined(wrongAnswer), wrongAnswer);
    }

    private static ServerFeatures features(ServerFeatures.Blake2bFork fork) {
        ServerFeatures features = new ServerFeatures();
        features.blake2b_fork = fork;
        return features;
    }

    private static BitcoindClientService stubNode(Map<Integer, String> hashes, Map<String, String> headers) {
        return (BitcoindClientService)Proxy.newProxyInstance(BitcoindClientService.class.getClassLoader(), new Class<?>[] {BitcoindClientService.class}, (proxy, method, args) -> {
            if(method.getName().equals("getBlockHash")) {
                String hash = hashes.get((Integer)args[0]);
                if(hash == null) {
                    throw new IllegalStateException("Block height out of range");
                }
                return hash;
            }
            if(method.getName().equals("getBlockHeader") && args.length == 2 && Boolean.FALSE.equals(args[1])) {
                return headers.get((String)args[0]);
            }
            throw new UnsupportedOperationException(method.getName());
        });
    }
}

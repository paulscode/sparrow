package com.sparrowwallet.sparrow.net.cormorant.bitcoind;

import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.protocol.Blake2bDeployment;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.protocol.BlockHeader;
import com.sparrowwallet.sparrow.net.cormorant.electrum.ElectrumBlockHeader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

/**
 * Bitcoin Core mode takes its tip from getblockheader. Rebuilt from the verbose JSON it was an 80 byte header whatever
 * was mined, so a BLAKE2b tip above the fork had the wrong hash and did not read as v2, and the opt-in decision, which
 * reads the tip's format, declined for every wallet. The tip is the node's own bytes now.
 */
public class TipHeaderTest {
    //Block 961640, the first mined under BLAKE2b, as the node serializes it
    private static final String ACTIVATION_HEADER = "000000a0657e02138733654183a2c7320d85ca9d743fe139c4bb01000000000000000000c137a8515a0f6b3aaf6049cc7611787c022ad523d51094be0a0363d0dc0bc7684dca936a4f8d001a5671798c84daeb494dca936a00000000b1ccf00d0300000000000000000000001e0300000000000000000000000000000000000068ac0e000000000000000000000000000000000000000000000000000000000000000000";

    @Test
    public void aBlake2bTipIsTheNodesOwnBytes() {
        Network.set(Network.MAINNET);
        String hash = Blake2bDeployment.activationBlockHash(Network.MAINNET).toString();
        BitcoindClientService node = stubNode(hash, 961640, ACTIVATION_HEADER);

        ElectrumBlockHeader tip = BitcoindClient.tipHeader(node, hash);

        Assertions.assertEquals(961640, tip.height());
        Assertions.assertEquals(ACTIVATION_HEADER, tip.hex());
        BlockHeader header = new BlockHeader(Utils.hexToBytes(tip.hex()));
        Assertions.assertTrue(header.isV2(), "the tip must read as a BLAKE2b header");
        Assertions.assertEquals(Blake2bDeployment.activationBlockHash(Network.MAINNET), header.getHash());
    }

    private static BitcoindClientService stubNode(String hash, int height, String rawHex) {
        return (BitcoindClientService)Proxy.newProxyInstance(BitcoindClientService.class.getClassLoader(), new Class<?>[] {BitcoindClientService.class}, (proxy, method, args) -> {
            if(method.getName().equals("getBlockHeader") && args != null && args.length == 1 && hash.equals(args[0])) {
                return new VerboseBlockHeader(hash, 1, height, 0x20000000, "20000000", "00".repeat(32), 0L, 0L, 0L, "1a008d4f", 1.0, "00", 1, "00".repeat(32));
            }
            if(method.getName().equals("getBlockHeader") && args != null && args.length == 2 && hash.equals(args[0]) && Boolean.FALSE.equals(args[1])) {
                return rawHex;
            }
            throw new UnsupportedOperationException(method.getName());
        });
    }
}

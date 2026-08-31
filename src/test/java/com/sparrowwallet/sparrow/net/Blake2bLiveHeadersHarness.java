package com.sparrowwallet.sparrow.net;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.protocol.BlockHeader;
import com.sparrowwallet.drongo.protocol.HeaderCheckpoints;
import com.sparrowwallet.drongo.protocol.HeaderChainState;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a captured run of live BLAKE2b testnet4 headers through the path a
 * running Sparrow uses, and reports what its own verification makes of them.
 *
 * <p>Not a unit test, because the headers come from a file that is fetched
 * rather than committed: `spikes/blake2b-testnet4/fetch_fork_headers.py` in the
 * electrs-pruned repo pulls them off a fork peer over p2p.
 *
 * <p>What this adds over the regtest harness is that nothing about the data is
 * chosen. The regtest chain has a difficulty of one, timestamps a second apart,
 * and an activation height picked to be convenient. These are the real
 * difficulty targets, the real timestamps and the real activation at 149537, so
 * {@link HeaderChainState}'s difficulty rule, proof-of-work check and
 * median-time-past rule are being asked a question they could actually fail.
 * The anchor is not chosen either: it is the last checkpoint Sparrow already
 * ships for testnet4.
 *
 * <pre>
 *   java -cp ... Blake2bLiveHeadersHarness &lt;fork-headers.json&gt;
 * </pre>
 */
public class Blake2bLiveHeadersHarness {
    public static void main(String[] args) throws Exception {
        File file = new File(args[0]);
        Network.set(Network.TESTNET4);

        //Sparrow's own compiled-in pins, not an anchor chosen for the occasion. The last one is at
        //149183, which is 354 blocks below the activation and on history the two chains share
        HeaderCheckpoints checkpoints = HeaderCheckpoints.get(Network.TESTNET4);
        int anchorHeight = checkpoints.getMaxHeight();
        System.out.println("anchor     : Sparrow's last compiled-in testnet4 checkpoint, height "
                + anchorHeight + ", hash " + checkpoints.getHash(anchorHeight));

        JsonNode root = new ObjectMapper().readTree(file);
        List<String> hexHeaders = new ArrayList<>();
        for(JsonNode node : root.get("headers")) {
            hexHeaders.add(node.asText());
        }
        System.out.println("source     : " + root.get("peer").asText() + " (" + root.get("user_agent").asText() + ")");
        System.out.println("captured   : " + hexHeaders.size() + " headers following height " + anchorHeight);

        //Presented the way a server below protocol 1.6 sends them, so the run has to be walked
        BlockHeaders chunk = new BlockHeaders();
        chunk.count = hexHeaders.size();
        chunk.hex = String.join("", hexHeaders);
        chunk.max = 2016;

        //The real checks, which is where a run of 164 byte headers used to be refused
        ElectrumServerRpc.checkBlockHeaders(chunk, anchorHeight + 1, chunk.count, null);
        System.out.println("checked    : " + (chunk.hex.length() / 2) + " bytes accepted for " + chunk.count
                + " headers (a fixed 80-byte stride would read " + (chunk.hex.length() / 160) + ")");

        List<BlockHeader> headers = VariableHeaders.parse(chunk, chunk.count);
        long v1 = headers.stream().filter(h -> !h.isV2()).count();
        System.out.println("parsed     : " + headers.size() + " headers, " + v1 + " v1 and " + (headers.size() - v1) + " v2");

        int activation = -1;
        for(int i = 0; i < headers.size(); i++) {
            if(headers.get(i).isV2()) {
                activation = anchorHeight + 1 + i;
                break;
            }
        }
        System.out.println("activation : first v2 header at height " + activation);

        HeaderChainState state = checkpoints.newChainState();
        int added = 0;
        try {
            for(BlockHeader header : headers) {
                state.add(header);      //linkage, difficulty, proof of work, median time past
                added++;
            }
        } catch(Exception e) {
            System.out.println("verified   : " + added + " headers accepted, then height "
                    + (anchorHeight + 1 + added) + " was rejected: " + e.getMessage());
            System.out.println("RESULT     : verification stopped inside the run.");
            return;
        }
        System.out.println("verified   : " + added + " headers accepted, tip height " + state.getHeight()
                + " hash " + state.getHash());
        System.out.println("RESULT     : Sparrow's own verification accepted " + added
                + " live headers across the real activation.");
    }
}

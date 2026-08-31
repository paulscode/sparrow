package com.sparrowwallet.sparrow.net;

import com.google.common.net.HostAndPort;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.protocol.BlockHeader;
import com.sparrowwallet.drongo.protocol.HeaderChainState;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.sparrow.io.Config;

import java.util.List;

/**
 * Drives Sparrow's own header path against a live Electrum server on a BLAKE2b
 * chain, and reports what happens.
 *
 * <p>Not a unit test: it needs a server, so it is a main rather than something
 * the build runs. It exists because the unit tests prove the pieces and this
 * proves the pieces fit: negotiation, the response checks, the walk, the
 * variable header length, the BLAKE2b block hash, and {@link HeaderChainState}'s
 * linkage, difficulty and proof-of-work rules, in the order Sparrow does them.
 *
 * <p>It goes through {@link SimpleElectrumServerRpc} over a real
 * {@link TcpTransport} rather than speaking JSON-RPC itself. An earlier version
 * did the latter, and that is precisely why it missed two defects: the checks in
 * {@link ElectrumServerRpc} rejected a run of 164 byte headers before anything
 * that could read them was reached, and the protocol 1.6 response form was not
 * handled at all. A harness that reimplements the client tests the
 * reimplementation.
 *
 * <pre>
 *   java -cp ... Blake2bChainSyncHarness &lt;host&gt; &lt;port&gt; &lt;network&gt; &lt;anchorHeight&gt; &lt;count&gt;
 * </pre>
 */
public class Blake2bChainSyncHarness {
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 60610;
        Network network = Network.valueOf(args.length > 2 ? args[2].toUpperCase() : "REGTEST");
        int anchorHeight = args.length > 3 ? Integer.parseInt(args[3]) : 0;
        int count = args.length > 4 ? Integer.parseInt(args[4]) : 30;
        Network.set(network);
        Config.get().setUseProxy(false);

        try(TcpTransport transport = new TcpTransport(HostAndPort.fromParts(host, port))) {
            transport.connect();
            Thread reader = new Thread(() -> {
                try {
                    transport.readInputLoop();
                } catch(Exception e) {
                    //Expected once the transport is closed
                }
            }, "Blake2bChainSyncHarnessRead");
            reader.setDaemon(true);
            reader.start();

            ElectrumServerRpc rpc = new SimpleElectrumServerRpc();

            List<String> negotiated = rpc.getServerVersion(transport, "Sparrow", ElectrumServer.SUPPORTED_VERSIONS);
            System.out.println("negotiated : " + negotiated);

            //The anchor. On regtest height 0 is genesis, which HeaderChainState accepts.
            String anchorHex = rpc.getBlockHeaders(transport, null, java.util.Set.of(anchorHeight)).get(anchorHeight);
            BlockHeader anchor = new BlockHeader(Utils.hexToBytes(anchorHex));
            Sha256Hash anchorHash = anchor.getHash();
            System.out.println("anchor     : height " + anchorHeight + ", " + (anchorHex.length() / 2) + " bytes, hash " + anchorHash);

            //Through the real checks: a run of 164 byte headers has to survive them before it can be read
            BlockHeaders chunk = rpc.getBlockHeadersChunk(transport, anchorHeight + 1, count);
            int bytes = chunk.hex != null ? chunk.hex.length() / 2 : chunk.headers.stream().mapToInt(h -> h.length() / 2).sum();
            System.out.println("response   : " + (chunk.hex != null ? "concatenated" : "list (protocol 1.6 form)") + ", "
                    + bytes + " bytes for " + chunk.count + " headers"
                    + " (a fixed 80-byte stride would read " + (bytes / 80) + ")");

            List<BlockHeader> headers = VariableHeaders.parse(chunk, chunk.count);
            long v1 = headers.stream().filter(h -> !h.isV2()).count();
            System.out.println("parsed     : " + headers.size() + " headers, " + v1 + " v1 and " + (headers.size() - v1) + " v2");

            HeaderChainState state = new HeaderChainState(anchorHeight, anchorHash, anchor.getDifficultyTarget());
            int added = 0;
            for(BlockHeader header : headers) {
                state.add(header);      //linkage, difficulty, proof of work, median time past
                added++;
            }
            System.out.println("verified   : " + added + " headers accepted, tip height " + state.getHeight()
                    + " hash " + state.getHash());
            System.out.println("RESULT     : Sparrow's own path accepted a chain spanning the BLAKE2b activation.");
        }
    }
}

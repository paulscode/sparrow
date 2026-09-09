package com.sparrowwallet.sparrow.net;

import com.google.common.net.HostAndPort;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.sparrow.io.Config;

import java.util.List;

/**
 * Runs the connect-time chain check against a live Electrum server and reports what it decides.
 *
 * <p>Not a unit test: it needs a server, so it is a main rather than something the build runs. The unit tests prove the
 * decision against frozen responses; this proves it against a server nobody here controls, which is where the defect it
 * was written for came from. A Fulcrum following this chain was refused, and told the user it was following the other
 * one, because the check required a {@code server.features} field that only this fork's electrs implements.
 *
 * <p>It goes through {@link SimpleElectrumServerRpc} over a real transport and asks {@link VariableHeaders} the same
 * questions {@link ElectrumServer#getChainIdentityHeader} and its caller ask, in the same order. Reimplementing the
 * client is what a harness must not do: the two defects this file's neighbour was written for were both in code a
 * hand-rolled JSON-RPC harness never reached.
 *
 * <pre>
 *   java -cp ... ChainIdentityHarness &lt;host&gt; &lt;port&gt; [network] [tls]
 * </pre>
 */
public class ChainIdentityHarness {
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 50002;
        Network network = Network.valueOf(args.length > 2 ? args[2].toUpperCase() : "MAINNET");
        boolean tls = args.length <= 3 || Boolean.parseBoolean(args[3]);
        Network.set(network);
        Config.get().setUseProxy(false);

        HostAndPort server = HostAndPort.fromParts(host, port);
        try(TcpTransport transport = tls ? new TcpOverTlsTransport(server) : new TcpTransport(server)) {
            transport.connect();
            Thread reader = new Thread(() -> {
                try {
                    transport.readInputLoop();
                } catch(Exception e) {
                    //Expected once the transport is closed
                }
            }, "ChainIdentityHarnessRead");
            reader.setDaemon(true);
            reader.start();

            ElectrumServerRpc rpc = new SimpleElectrumServerRpc();

            List<String> negotiated = rpc.getServerVersion(transport, "Sparrow", ElectrumServer.SUPPORTED_VERSIONS);
            System.out.println("negotiated : " + negotiated);

            ServerFeatures features = null;
            try {
                features = rpc.getServerFeatures(transport);
                System.out.println("features   : " + features);
            } catch(Exception e) {
                System.out.println("features   : not answered (" + e.getMessage() + ")");
            }
            System.out.println("fork field : " + (features != null && features.blake2b_fork != null ? features.blake2b_fork : "absent"));

            //The body of ElectrumServer.getChainIdentityHeader, against this server
            int height = VariableHeaders.chainIdentityHeight();
            System.out.println("asking for : header at height " + height + " on " + network);

            String headerHex = null;
            try {
                BlockHeaders chunk = rpc.getBlockHeadersChunk(transport, height, 1);
                System.out.println("response   : " + (chunk.hex != null ? "concatenated" : "list (protocol 1.6 form)")
                        + ", count " + chunk.count + ", max " + chunk.max);
                headerHex = VariableHeaders.singleHeaderHex(chunk);
            } catch(Exception e) {
                System.out.println("response   : not served (" + e.getMessage() + ")");
            }
            System.out.println("header     : " + (headerHex == null ? "none" : (headerHex.length() / 2) + " bytes, "
                    + (headerHex.length() / 2 == 164 ? "v2, so that chain changed proof of work here" : "v1")));

            String error = VariableHeaders.getChainMismatchError(features, headerHex);
            System.out.println("RESULT     : " + (error == null ? "accepted, this server follows the BLAKE2b chain" : "REFUSED: " + error));
            System.exit(error == null ? 0 : 1);
        }
    }
}

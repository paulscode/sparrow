package com.sparrowwallet.sparrow.net;

/**
 * A server that answered, but not in a way this client can use: part of a batch, or a reply that is not JSON-RPC 2.0.
 *
 * Unchecked on purpose. The JSON-RPC client wraps an IOException from the transport as an IllegalStateException, which
 * the RPC layer retries as though it were transient; a server that does this does it every time, so retrying only delays
 * the answer and buries its reason under "Retries exhausted". An unchecked exception passes through both unchanged.
 */
public class ServerProtocolException extends RuntimeException {
    public ServerProtocolException(String message) {
        super(message);
    }
}

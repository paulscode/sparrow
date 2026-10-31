package com.sparrowwallet.sparrow.net;

import com.sparrowwallet.sparrow.ForkLinks;

/**
 * The server follows a chain other than the one this build follows.
 *
 * <p>A configuration problem rather than a transient one: the same server gives the same answer every time, so it is
 * not retried, and the user is told once what to change.
 */
public class ChainMismatchException extends ServerConfigException {
    public static final String SHORT_MESSAGE = "The server follows a different chain";

    public static final String REMEDY = "Sparrow (BLAKE2b) needs a server that follows the BLAKE2b chain. A node or Electrum server "
            + "set up for upstream Sparrow follows the SHA256d chain. Point the server settings at one backed by a BLAKE2b node.";

    public static final String CONNECTING_URL = ForkLinks.CONNECTING;

    public ChainMismatchException(String message) {
        super(message);
    }

    /** The chain mismatch behind a connection failure, wherever in the causes it is, or null if there is none. */
    public static ChainMismatchException find(Throwable exception) {
        for(Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if(cause instanceof ChainMismatchException chainMismatchException) {
                return chainMismatchException;
            }
            if(cause.getCause() == cause) {
                break;
            }
        }
        return null;
    }
}

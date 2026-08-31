package com.sparrowwallet.sparrow.net;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * The blockchain.block.headers response: a run of consecutive block headers, with the maximum number of headers the server will return.
 * <p>
 * The run comes in one of two forms and never both. Below protocol 1.6 it is one concatenated hex string in {@code hex}; at 1.6 and above
 * it is a list of hex strings in {@code headers}. Which arrives depends on what was negotiated, so a client offering a range spanning 1.6
 * has to accept either. See {@link VariableHeaders}, which reads both.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BlockHeaders {
    public static final int HEADER_HEX_LENGTH = 160;

    /**
     * Substituted for a range the server returned an error for, and filtered out before a batched result is returned.
     */
    public static final BlockHeaders ERROR_HEADERS = new BlockHeaders();

    public int count;
    public String hex;          //protocol 1.5 and below
    public List<String> headers;    //protocol 1.6 and above
    public int max;

    @Override
    public String toString() {
        return "BlockHeaders{count=" + count + ", max=" + max + '}';
    }
}

package com.sparrowwallet.sparrow.net;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ServerFeatures {
    //hosts is typed as Map<String, Object> rather than Map<String, HostInfo> because the wire shape
    //varies in practice: most servers return {host: {tcp_port: N, ssl_port: N}} per the Electrum spec,
    //but some (electrs) return {host: N} (a bare port number) or omit fields. Sparrow doesn't read this
    //(it's reserved for inbound deserialization compatibility); cormorant writes the spec-conformant
    //nested-map shape via a manually-constructed Map.
    public Map<String, Object> hosts;
    public String genesis_hash;
    public String hash_function;
    public String server_version;
    public String protocol_min;
    public String protocol_max;
    public Integer pruning;
    public List<Integer> silent_payments;

    //Chain identity, which genesis_hash cannot carry here: a forked chain and the chain it forked from share a genesis block
    //and diverge only at the activation height, so two servers can report the same genesis and serve chains that disagree.
    //Absent on a chain that has not forked, which is itself the answer for it. See Blake2bFork below.
    public Blake2bFork blake2b_fork;

    public ServerFeatures() {}

    /**
     * Where a server's chain changed proof of work, as reported in {@code server.features}.
     *
     * <p>The fork point is reported rather than an opaque chain identifier because it needs no registry and the client can
     * check it against what the server then serves.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Blake2bFork {
        /** The height of the first block mined under the new proof of work. */
        public Integer height;

        /** The hash of that block. */
        public String hash;

        /** The header length above the fork, 164 where the first 80 bytes remain a v1 header. */
        public Integer header_bytes;

        /** The algorithm block hashes use above the fork, "blake2b". */
        public String block_hash;

        @Override
        public String toString() {
            return "Blake2bFork{height=" + height + ", hash='" + hash + "', header_bytes=" + header_bytes + ", block_hash='" + block_hash + "'}";
        }
    }

    @Override
    public String toString() {
        return "ServerFeatures{server_version='" + server_version + "', protocol_min='" + protocol_min + "', protocol_max='" + protocol_max + "', silent_payments=" + silent_payments + ", blake2b_fork=" + blake2b_fork + '}';
    }
}

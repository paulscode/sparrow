package com.sparrowwallet.sparrow.net;

import com.google.common.net.HostAndPort;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.sparrow.io.Server;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public enum PublicElectrumServer {
    BLOCKSTREAM_INFO("blockstream.info", "ssl://blockstream.info:700", Network.MAINNET),
    ELECTRUM_BLOCKSTREAM_INFO("electrum.blockstream.info", "ssl://electrum.blockstream.info:50002", Network.MAINNET),
    LUKECHILDS_CO("bitcoin.lu.ke", "ssl://bitcoin.lu.ke:50002", Network.MAINNET),
    EMZY_DE("electrum.emzy.de", "ssl://electrum.emzy.de:50002", Network.MAINNET),
    BITAROO_NET("electrum.bitaroo.net", "ssl://electrum.bitaroo.net:50002", Network.MAINNET),
    DIYNODES_COM("electrum.diynodes.com", "ssl://electrum.diynodes.com:50022", Network.MAINNET),
    SETHFORPRIVACY_COM("fulcrum.sethforprivacy.com", "ssl://fulcrum.sethforprivacy.com:50002", Network.MAINNET),
    TESTNET_ARANGUREN_ORG("testnet.aranguren.org", "ssl://testnet.aranguren.org:51002", Network.TESTNET),
    TESTNET_QTORNADO_COM("testnet.qtornado.com", "ssl://testnet.qtornado.com:51002", Network.TESTNET),
    SIGNET_MEMPOOL_SPACE("mempool.space", "ssl://mempool.space:60602", Network.SIGNET),
    TESTNET4_MEMPOOL_SPACE("mempool.space", "ssl://mempool.space:40002", Network.TESTNET4),
    TESTNET4_C3_SOFT("blackie.c3-soft.com", "ssl://blackie.c3-soft.com:57010", Network.TESTNET4),
    FRIGATE_2140_DEV("frigate.2140.dev", "ssl://frigate.2140.dev:50002", Network.MAINNET, List.of(PolicyType.SINGLE_HD, PolicyType.MULTI_HD, PolicyType.SINGLE_SP));

    PublicElectrumServer(String name, String url, Network network) {
        this(name, url, network, List.of(PolicyType.SINGLE_HD, PolicyType.MULTI_HD));
    }

    PublicElectrumServer(String name, String url, Network network, List<PolicyType> supportedPolicyTypes) {
        this.server = new Server(url, name);
        this.network = network;
        this.supportedPolicyTypes = supportedPolicyTypes;
    }

    /**
     * No network, because no public server indexes this chain.
     *
     * <p>Every server listed above follows the chain that kept SHA256d. Connecting to one would not
     * fail in any way a user could see: it would sync, and then show that chain's blocks, that
     * chain's history and that chain's balances, against addresses this wallet derives. The entries
     * are left in place so upstream keeps merging cleanly, and this is the gate that withdraws them.
     */
    public static final List<Network> SUPPORTED_NETWORKS = List.of();

    private final Server server;
    private final Network network;
    private final List<PolicyType> supportedPolicyTypes;

    public Server getServer() {
        return server;
    }

    public String getUrl() {
        return server.getUrl();
    }

    public Network getNetwork() {
        return network;
    }

    public boolean isSupportedPolicyType(PolicyType policyType) {
        return supportedPolicyTypes.contains(policyType);
    }

    public boolean supportsAllPolicyTypes(List<PolicyType> policyTypes) {
        return policyTypes.stream().allMatch(this::isSupportedPolicyType);
    }

    /**
     * Empty, and honouring the same gate the settings screens divide on, so a caller that indexes
     * into this list cannot be handed a server on the other chain.
     */
    public static List<PublicElectrumServer> getServers() {
        if(!supportedNetwork()) {
            return List.of();
        }

        return Arrays.stream(values()).filter(server -> server.network == Network.get()).collect(Collectors.toList());
    }

    public static boolean supportedNetwork() {
        return SUPPORTED_NETWORKS.contains(Network.get());
    }

    public static PublicElectrumServer fromServer(Server server) {
        for(PublicElectrumServer publicServer : values()) {
            if(publicServer.getServer().equals(server)) {
                return publicServer;
            }
        }

        return null;
    }

    public static boolean isPublicServer(HostAndPort hostAndPort) {
        for(PublicElectrumServer publicServer : values()) {
            if(publicServer.getServer().getHostAndPort().equals(hostAndPort)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public String toString() {
        return server.getAlias();
    }
}

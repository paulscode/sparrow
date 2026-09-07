package com.sparrowwallet.sparrow.net;

import com.google.common.net.HostAndPort;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.tern.http.client.HttpResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;

public enum BroadcastSource {
    BLOCKSTREAM_INFO("blockstream.info", "https://blockstream.info", "http://explorerzydxu5ecjrkwceayqybizmpjjznk5izmitf2modhcusuqlid.onion") {
        @Override
        public Sha256Hash broadcastTransaction(Transaction transaction) throws BroadcastException {
            String data = Utils.bytesToHex(transaction.bitcoinSerialize());
            return postTransactionData(data);
        }

        @Override
        public List<Network> getSupportedNetworks() {
            return NO_NETWORKS;
        }

        protected URL getURL(HostAndPort proxy) throws MalformedURLException, URISyntaxException {
            if(Network.get() == Network.MAINNET) {
                return new URI(getBaseUrl(proxy) + "/api/tx").toURL();
            } else if(Network.get() == Network.TESTNET) {
                return new URI(getBaseUrl(proxy) + "/testnet/api/tx").toURL();
            } else {
                throw new IllegalStateException("Cannot broadcast transaction to " + getName() + " on network " + Network.get());
            }
        }
    },
    MEMPOOL_SPACE("mempool.space", "https://mempool.space", "http://mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion") {
        public Sha256Hash broadcastTransaction(Transaction transaction) throws BroadcastException {
            String data = Utils.bytesToHex(transaction.bitcoinSerialize());
            return postTransactionData(data);
        }

        @Override
        public List<Network> getSupportedNetworks() {
            return NO_NETWORKS;
        }

        protected URL getURL(HostAndPort proxy) throws MalformedURLException, URISyntaxException {
            if(Network.get() == Network.MAINNET) {
                return new URI(getBaseUrl(proxy) + "/api/tx").toURL();
            } else if(Network.get() == Network.TESTNET) {
                return new URI(getBaseUrl(proxy) + "/testnet/api/tx").toURL();
            } else if(Network.get() == Network.SIGNET) {
                return new URI(getBaseUrl(proxy) + "/signet/api/tx").toURL();
            } else if(Network.get() == Network.TESTNET4) {
                return new URI(getBaseUrl(proxy) + "/testnet4/api/tx").toURL();
            } else {
                throw new IllegalStateException("Cannot broadcast transaction to " + getName() + " on network " + Network.get());
            }
        }
    },
    MEMPOOL_EMZY_DE("mempool.emzy.de", "https://mempool.emzy.de", "http://mempool4t6mypeemozyterviq3i5de4kpoua65r3qkn5i3kknu5l2cad.onion") {
        public Sha256Hash broadcastTransaction(Transaction transaction) throws BroadcastException {
            String data = Utils.bytesToHex(transaction.bitcoinSerialize());
            return postTransactionData(data);
        }

        @Override
        public List<Network> getSupportedNetworks() {
            return NO_NETWORKS;
        }

        protected URL getURL(HostAndPort proxy) throws MalformedURLException, URISyntaxException {
            if(Network.get() == Network.MAINNET) {
                return new URI(getBaseUrl(proxy) + "/api/tx").toURL();
            } else if(Network.get() == Network.TESTNET) {
                return new URI(getBaseUrl(proxy) + "/testnet/api/tx").toURL();
            } else if(Network.get() == Network.SIGNET) {
                return new URI(getBaseUrl(proxy) + "/signet/api/tx").toURL();
            } else {
                throw new IllegalStateException("Cannot broadcast transaction to " + getName() + " on network " + Network.get());
            }
        }
    };

    private final String name;
    private final String tlsUrl;
    private final String onionUrl;

    private static final Logger log = LoggerFactory.getLogger(BroadcastSource.class);

    BroadcastSource(String name, String tlsUrl, String onionUrl) {
        this.name = name;
        this.tlsUrl = tlsUrl;
        this.onionUrl = onionUrl;
    }

    public String getName() {
        return name;
    }

    public String getTlsUrl() {
        return tlsUrl;
    }

    public String getOnionUrl() {
        return onionUrl;
    }

    public String getBaseUrl(HostAndPort proxy) {
        return (proxy == null ? getTlsUrl() : getOnionUrl());
    }

    public abstract Sha256Hash broadcastTransaction(Transaction transaction) throws BroadcastException;

    /**
     * No network, for every source here.
     *
     * <p>All of them follow the chain that kept SHA256d, and posting a transaction to one relays it
     * there. Replay protection on this chain is opt in, so an ordinary transaction is valid on both
     * and can confirm on the other, spending inputs that predate the fork.
     *
     * <p>The call site that used these is gone; this is the second lock. A caller reintroduced by a
     * merge selects from an empty list and falls through to the connected server rather than
     * quietly resuming the old behaviour.
     */
    static final List<Network> NO_NETWORKS = List.of();

    public abstract List<Network> getSupportedNetworks();

    protected abstract URL getURL(HostAndPort proxy) throws MalformedURLException, URISyntaxException;

    public Sha256Hash postTransactionData(String data) throws BroadcastException {
        //If a Tor proxy is configured, ensure we use a new circuit by configuring a random proxy password
        HttpClientService httpClientService = AppServices.getHttpClientService();
        httpClientService.changeIdentity();

        try {
            URL url = getURL(httpClientService.getTorProxy());

            if(log.isInfoEnabled()) {
                log.info("Broadcasting transaction to " + url);
            }

            String response = httpClientService.postString(url.toString(), null, "text/plain", data);

            try {
                return Sha256Hash.wrap(response.trim());
            } catch(Exception e) {
                throw new BroadcastException("Could not retrieve txid from broadcast, server returned: " + response);
            }
        } catch(HttpResponseException e) {
            throw new BroadcastException("Could not broadcast transaction, server returned " + e.getStatusCode() + ": " + e.getResponseBody());
        } catch(Exception e) {
            log.error("Could not post transaction via " + getName(), e);
            throw new BroadcastException("Could not broadcast transaction via " + getName(), e);
        }
    }

    public static final class BroadcastException extends Exception {
        public BroadcastException(String message) {
            super(message);
        }

        public BroadcastException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

package com.sparrowwallet.sparrow.io;

import com.sparrowwallet.sparrow.net.FeeRatesSource;
import com.sparrowwallet.sparrow.net.PublicElectrumServer;
import com.sparrowwallet.sparrow.net.ServerType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Withdrawing a source from the pickers does nothing for an install that already chose one.
 *
 * <p>That is the half of the removal that is easy to miss, and it is the half that matters: the people
 * exposed to a source on the chain that kept SHA256d are exactly the ones who went and selected it. A
 * stored value outlives the entry it came from, so each is refused on read.
 */
public class WithdrawnSourceConfigTest {
    private static Config config() {
        //Not Config.get(), which loads and would write the user's real file
        return new Config();
    }

    @Test
    public void aStoredBlockExplorerOnTheOtherChainIsRefused() {
        for(String url : new String[] {
                "https://mempool.space",
                "https://blockstream.info",
                "https://mempool.space/tx/{0}",
                "https://MEMPOOL.SPACE",
                "http://mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion"}) {
            Config config = config();
            config.setBlockExplorer(new Server(url));
            Assertions.assertNull(config.getBlockExplorer(),
                    url + " indexes the chain that kept SHA256d and must not be opened for a txid from this one");
        }
    }

    /**
     * The point of keeping the custom URL option. Somebody running an explorer that follows this chain
     * must still be able to point at it, so the refusal has to be by host rather than by "not one of
     * ours".
     */
    @Test
    public void aStoredExplorerThatFollowsThisChainIsKept() {
        Config config = config();
        Server ours = new Server("https://explorer.example.test/tx/{0}");
        config.setBlockExplorer(ours);
        Assertions.assertEquals(ours, config.getBlockExplorer());
    }

    @Test
    public void aStoredFeeRatesSourceThatWasWithdrawnReadsAsUnset() {
        Config config = config();
        config.setFeeRatesSource(FeeRatesSource.MEMPOOL_SPACE);
        Assertions.assertNull(config.getFeeRatesSource(),
                "a withdrawn source must read as unset so the default applies, rather than showing as selected while nothing uses it");
    }

    @Test
    public void aStoredFeeRatesSourceThatStillWorksIsKept() {
        for(FeeRatesSource source : new FeeRatesSource[] {FeeRatesSource.ELECTRUM_SERVER, FeeRatesSource.MINIMUM}) {
            Config config = config();
            config.setFeeRatesSource(source);
            Assertions.assertEquals(source, config.getFeeRatesSource());
        }
    }

    /**
     * The one that decides what the wallet connects to.
     *
     * <p>The settings screen already substituted Bitcoin Core for this, but only once that screen was
     * opened, which is after a connection has been made. An install that was on a public server would
     * have synced against another chain first and been corrected afterwards.
     */
    @Test
    public void aStoredPublicServerTypeDoesNotSurviveIntoAConnection() {
        Assertions.assertFalse(PublicElectrumServer.supportedNetwork(), "precondition: public servers are withdrawn");

        Config config = config();
        config.setServerType(ServerType.PUBLIC_ELECTRUM_SERVER);
        config.setPublicElectrumServer(new Server("ssl://electrum.blockstream.info:50002"));

        Assertions.assertNull(config.getPublicElectrumServer(),
                "there is nothing to connect to, so nothing is handed back");
        Assertions.assertNull(config.getServer(),
                "and the connection path gets nothing rather than a server on the other chain");
    }

    /**
     * The server type itself is left alone. It is read by things that have nothing to do with
     * connecting, such as whether transaction proofs are verified, and rewriting it broke those.
     */
    @Test
    public void theServerTypeItselfIsNotRewritten() {
        for(ServerType type : ServerType.values()) {
            Config config = config();
            config.setServerType(type);
            Assertions.assertEquals(type, config.getServerType());
        }
    }

    /**
     * Nothing to open must mean nothing is opened.
     *
     * <p>With the explorers that follow the other chain withdrawn, the default is None, and None's URL
     * is the placeholder "http://none". Whether a txid link is dead is decided separately from which
     * explorer is configured, and that decision used to read the stored field: it saw a value, called
     * the link live, and the caller then built "http://none/tx/..." and handed it to the browser.
     */
    @Test
    public void aTxidLinkIsDeadWhereThereIsNoExplorerToOpen() {
        Config unset = config();
        Assertions.assertTrue(unset.isBlockExplorerDisabled(), "nothing configured means nothing to open");

        Config none = config();
        none.setBlockExplorer(new Server("http://none"));
        Assertions.assertTrue(none.isBlockExplorerDisabled(), "None means nothing to open");

        Config withdrawn = config();
        withdrawn.setBlockExplorer(new Server("https://mempool.space"));
        Assertions.assertTrue(withdrawn.isBlockExplorerDisabled(),
                "a refused explorer must disable the link rather than fall through to the placeholder URL");

        Config ours = config();
        ours.setBlockExplorer(new Server("https://explorer.example.test/tx/{0}"));
        Assertions.assertFalse(ours.isBlockExplorerDisabled(), "an explorer that follows this chain still works");
    }
}

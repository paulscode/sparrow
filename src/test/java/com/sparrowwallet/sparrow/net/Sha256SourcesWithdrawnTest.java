package com.sparrowwallet.sparrow.net;

import com.sparrowwallet.drongo.Network;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Nothing in this build may send a user, or a user's transaction, to the chain that kept SHA256d.
 *
 * <p>Every source Sparrow ships follows that chain. On a wallet that holds coins on this one they
 * range from useless to dangerous, and the dangerous end is not hypothetical: replay protection here
 * is opt in, so an ordinary transaction is valid on both chains and can confirm on the other,
 * spending inputs that predate the fork.
 *
 * <p>These are here because the entries themselves are deliberately left in place, so that upstream
 * keeps merging cleanly. That makes the withdrawal a property of a gate rather than of the code
 * being absent, and a gate is something a merge can quietly reopen.
 */
public class Sha256SourcesWithdrawnTest {
    /**
     * The one that moves value.
     *
     * <p>With a Tor proxy configured, upstream posts the raw transaction to these before it ever
     * reaches the connected server, and on mainnet parameters it returned as soon as two of them
     * accepted it, so the transaction reached the other chain and never reached this one.
     */
    @Test
    public void noBroadcastSourceClaimsAnyNetwork() {
        for(BroadcastSource source : BroadcastSource.values()) {
            Assertions.assertEquals(List.of(), source.getSupportedNetworks(),
                    source.getName() + " follows the chain that kept SHA256d and must never be selectable to broadcast to");
        }
    }

    @Test
    public void broadcastSourcesAreNotSelectableOnThisChain() {
        List<BroadcastSource> selectable = Arrays.stream(BroadcastSource.values())
                .filter(src -> src.getSupportedNetworks().contains(Network.get()))
                .toList();
        Assertions.assertEquals(List.of(), selectable,
                "the list a broadcast would choose from must be empty, so it falls through to the connected server");
    }

    /**
     * None of these index this chain. Connecting would not visibly fail: it would sync, and then
     * show another chain's blocks, history and balances against addresses this wallet derives.
     */
    @Test
    public void noPublicElectrumServerIsOffered() {
        Assertions.assertFalse(PublicElectrumServer.supportedNetwork(),
                "no public server indexes this chain");
        Assertions.assertEquals(List.of(), PublicElectrumServer.getServers(),
                "getServers must honour the gate, since callers index into it");
    }

    /**
     * Fee estimates describe a mempool. The external sources describe the wrong one.
     */
    @Test
    public void onlyTheConnectedServerAndTheMinimumQuoteFees() {
        for(FeeRatesSource source : FeeRatesSource.values()) {
            boolean expected = source == FeeRatesSource.ELECTRUM_SERVER || source == FeeRatesSource.MINIMUM;
            Assertions.assertEquals(expected, source.supportsNetwork(Network.get()),
                    source.getName() + " should " + (expected ? "" : "not ") + "quote fees on this chain");
        }
    }

    /**
     * The external fee sources are exactly the ones that reach out over the network, so this says the
     * same thing a second way and would catch a new source added without a gate.
     */
    @Test
    public void nothingExternalQuotesFees() {
        for(FeeRatesSource source : FeeRatesSource.values()) {
            if(source.isExternal()) {
                Assertions.assertFalse(source.supportsNetwork(Network.get()),
                        source.getName() + " reaches the network for fees and follows the other chain");
            }
        }
    }

    /**
     * A txid from this chain is either absent from those explorers, which is useless, or present
     * because the transaction was replayed, which would show a confirmation on the other chain as
     * though it were this one's.
     */
    @Test
    public void noBlockExplorerFollowsTheOtherChain() {
        Assertions.assertEquals(List.of(BlockExplorer.NONE), Arrays.asList(BlockExplorer.values()),
                "only None is offered; a custom URL remains available for an explorer that follows this chain");
    }
}

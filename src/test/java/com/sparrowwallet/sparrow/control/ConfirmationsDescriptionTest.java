package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.wallet.BlockTransactionHash;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a coin's tooltip says, and in particular what it says about one the long maturity window is holding.
 *
 * <p>The case this exists for cannot be produced by hand: it needs a wallet holding mined coins on a chain
 * past a specific height. The wording is therefore a function of its inputs rather than something read out of
 * a tooltip, and this asks it directly.
 *
 * <p>What is still untested is that the height reaches it. That runs through JavaFX cell recycling and needs
 * a display, so it is checked by eye.
 */
public class ConfirmationsDescriptionTest {
    private static final int START = 973440;
    private static final int RELEASE = 979920;

    @AfterEach
    public void tearDown() {
        Network.set(null);
    }

    /** Nothing about ordinary coins changes, which is most of what this function is asked about. */
    @Test
    public void ordinaryCoinsReadAsTheyAlwaysHave() {
        assertEquals("Unconfirmed in mempool", ConfirmationsDescription.get(0, false, 0, 900000));
        assertEquals("1 confirmation", ConfirmationsDescription.get(1, false, 0, 900000));
        assertEquals("3 confirmations", ConfirmationsDescription.get(3, false, 0, 900000));
        assertEquals(BlockTransactionHash.BLOCKS_TO_FULLY_CONFIRM + "+ confirmations",
                ConfirmationsDescription.get(150, false, 0, 900000));
    }

    /** An unconfirmed coin says so first, whatever else is true of it. */
    @Test
    public void unconfirmedWinsOverEverything() {
        Network.set(Network.MAINNET);
        assertEquals("Unconfirmed in mempool", ConfirmationsDescription.get(0, true, START, START + 1));
    }

    /**
     * The case the change is for. Without it this reads "6+ confirmations" for forty-five days while the coin
     * cannot be moved at all.
     */
    @Test
    public void aCoinbaseHeldByTheWindowSaysWhenItUnlocks() {
        Network.set(Network.MAINNET);
        String description = ConfirmationsDescription.get(500, true, START, START + 500);
        assertTrue(description.contains("immature coinbase"), description);
        assertTrue(description.contains("spendable from block " + RELEASE), description);
        assertTrue(description.startsWith("500 confirmations"), description);
        assertFalse(description.contains(BlockTransactionHash.BLOCKS_TO_FULLY_CONFIRM + "+ confirmations"), description);
    }

    /** And stops saying it the moment the coin is actually spendable. */
    @Test
    public void onceSpendableItReadsAsAnyOtherCoin() {
        Network.set(Network.MAINNET);
        assertEquals(BlockTransactionHash.BLOCKS_TO_FULLY_CONFIRM + "+ confirmations",
                ConfirmationsDescription.get(RELEASE - START, true, START, RELEASE));
    }

    /**
     * A coinbase mined before the window keeps the short wording it had. Quoting a height for a coin that
     * matures in sixteen hours would be worse than the count it replaces.
     */
    @Test
    public void aCoinbaseBelowTheWindowKeepsTheOrdinaryWording() {
        Network.set(Network.MAINNET);
        int coinbaseHeight = START - 5000;
        String description = ConfirmationsDescription.get(3, true, coinbaseHeight, coinbaseHeight + 2);
        assertEquals("3 confirmations, immature coinbase", description);
    }

    /** Missing inputs fall back rather than inventing a height. Both are reachable: the tip can be unknown. */
    @Test
    public void unknownInputsFallBackToTheCountingWording() {
        Network.set(Network.MAINNET);
        assertEquals(BlockTransactionHash.BLOCKS_TO_FULLY_CONFIRM + "+ confirmations",
                ConfirmationsDescription.get(500, true, START, null));
        assertEquals(BlockTransactionHash.BLOCKS_TO_FULLY_CONFIRM + "+ confirmations",
                ConfirmationsDescription.get(500, true, 0, START + 500));
    }

    /**
     * A network with no deployment must not borrow mainnet's heights and tell a regtest miner their coin is
     * frozen until a block that network will not see for years.
     */
    @Test
    public void undeployedNetworksAreNotToldAboutAWindow() {
        Network.set(Network.REGTEST);
        String description = ConfirmationsDescription.get(500, true, START, START + 500);
        assertEquals(BlockTransactionHash.BLOCKS_TO_FULLY_CONFIRM + "+ confirmations", description);
    }
}

package com.sparrowwallet.sparrow.event;

import com.sparrowwallet.sparrow.io.Server;

/**
 * The configured block explorer changed, or was cleared.
 *
 * <p>Whether a transaction row offers "Open in Block Explorer" is decided when its cell is drawn, and
 * whether the button in the transaction view is enabled is decided when that view is built. Neither is
 * asked again, so changing the setting used to do nothing at all until the wallet was restarted:
 * switching tabs does not redraw a cell that is already valid.
 *
 * <p>That mattered little while the default was an explorer that worked, because few people changed it.
 * It matters now that the default is None, since everyone who wants one has to go and set it, and what
 * they see afterwards is a setting that appears to have been ignored.
 */
public class BlockExplorerChangedEvent {
    private final Server blockExplorer;

    public BlockExplorerChangedEvent(Server blockExplorer) {
        this.blockExplorer = blockExplorer;
    }

    /** The newly configured explorer, or null where there is none to open. */
    public Server getBlockExplorer() {
        return blockExplorer;
    }
}

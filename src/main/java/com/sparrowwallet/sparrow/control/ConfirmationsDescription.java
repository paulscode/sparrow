package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.protocol.LongCoinbaseMaturity;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.wallet.BlockTransactionHash;

/**
 * What to say about a coin's confirmations, given everything needed to decide it.
 *
 * <p>A class of its own, and deliberately not a method on {@link CoinCell}, because anything nested in a
 * JavaFX control cannot be tested without a display: loading the cell runs {@code Control}'s static
 * initialiser, which wants a toolkit. The wording is a function of a height, a tip and a count, so it does
 * not need to be inside a cell to be correct, and outside one it can be asked directly.
 *
 * <p>The case this exists for cannot be produced by hand either way: a coinbase held by the long maturity
 * window needs a wallet holding mined coins on a chain past a specific height.
 */
public final class ConfirmationsDescription {
    private ConfirmationsDescription() {}

    /**
     * Answers the coinbase case from the block height rather than from the confirmation count.
     *
     * <p>The count is bound only until {@code BLOCKS_TO_FULLY_CONFIRM} and is deliberately frozen after
     * that, so it cannot say how much longer a coin has to wait. A height and a tip can, and for a coin held
     * for forty-five days the difference is the whole point: without this it reads "6+ confirmations"
     * throughout, which is true and useless.
     *
     * @param coinbaseHeight the block the coinbase was mined in, or 0 if not known or not a coinbase
     * @param currentBlockHeight the chain tip, or null if not known
     */
    public static String get(int confirmations, boolean isCoinbase, int coinbaseHeight, Integer currentBlockHeight) {
        if(confirmations == 0) {
            return "Unconfirmed in mempool";
        }

        if(isCoinbase && coinbaseHeight > 0 && currentBlockHeight != null
                && !LongCoinbaseMaturity.isSpendable(Network.get(), coinbaseHeight, currentBlockHeight)) {
            int spendableFrom = LongCoinbaseMaturity.spendableFromHeight(Network.get(), coinbaseHeight);
            //Only worth saying for a coin the window is holding. Quoting a height for one that matures in
            //sixteen hours is worse than the count it would replace, so this is left to fall through below
            if(spendableFrom > coinbaseHeight + Transaction.COINBASE_MATURITY_THRESHOLD) {
                //Said as a height rather than as a wait, because a wait is only as good as an assumed block
                //interval and this chain's has not been near ten minutes
                return confirmations + " confirmation" + (confirmations == 1 ? "" : "s")
                        + ", immature coinbase, spendable from block " + spendableFrom;
            }
        }

        if(confirmations < BlockTransactionHash.BLOCKS_TO_FULLY_CONFIRM) {
            return confirmations + " confirmation" + (confirmations == 1 ? "" : "s") + (isCoinbase ? ", immature coinbase" : "");
        }

        return BlockTransactionHash.BLOCKS_TO_FULLY_CONFIRM + "+ confirmations";
    }
}

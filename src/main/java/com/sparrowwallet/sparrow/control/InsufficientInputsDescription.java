package com.sparrowwallet.sparrow.control;

/**
 * What the Send screen says when a transaction cannot be funded.
 *
 * <p>"Insufficient Inputs" is true and is not the answer. A miner whose coins are all immature reads it as
 * the money not being there, which is the support message this exists to stop.
 *
 * <p>No unlock height goes in this message. It summarises possibly several coins, and each unlocks a fixed
 * depth after its own block, so several coins means several heights and any single one would usually be
 * wrong. Name the amount and send the reader to the coins.
 *
 * <p>A pure function of a formatted amount, so it can be tested without a display. The caller formats,
 * because the amount has to follow the unit and format the rest of the interface is using.
 */
public final class InsufficientInputsDescription {
    private InsufficientInputsDescription() {}

    /**
     * @param immatureAmount the wallet's immature balance, already formatted with its unit, or null if there
     *                       is none
     */
    public static String get(String immatureAmount) {
        if(immatureAmount == null) {
            return "Insufficient Inputs";
        }

        //Said whenever any of the balance is immature, rather than only when the immature part would have
        //covered the shortfall. Working out the shortfall exactly at this point means unpicking the fee
        //iteration, and the sentence is worth saying either way: it is the difference between "your money is
        //gone" and "your money is waiting".
        return "Insufficient spendable funds, " + immatureAmount + " immature. See the UTXOs tab.";
    }
}

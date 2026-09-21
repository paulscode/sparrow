package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.sparrow.terminal.wallet.table.DateTableCell;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The duration buckets, walked across every boundary.
 *
 * <p>Worth testing in its own right rather than through a cell, because the failure mode is a bucket that
 * reads fine and is wrong by one, which no amount of looking at the screen would catch.
 */
public class MaturityEstimateTest {
    private static final int HOUR = 60 / MaturityEstimate.MINUTES_PER_BLOCK;
    private static final int DAY = HOUR * 24;
    private static final int WEEK = DAY * 7;

    /** Every threshold, from just below to exactly on, so an off-by-one moves one of these. */
    @Test
    public void theUnitChangesExactlyAtEachThreshold() {
        assertEquals("less than an hour", MaturityEstimate.describe(2));
        assertEquals("about 1 hour", MaturityEstimate.describe(HOUR));

        assertEquals("about 71 hours", MaturityEstimate.describe(71 * HOUR));
        assertEquals("about 3 days", MaturityEstimate.describe(3 * DAY));

        assertEquals("about 13 days", MaturityEstimate.describe(13 * DAY));
        assertEquals("about 2 weeks", MaturityEstimate.describe(2 * WEEK));

        assertEquals("about 9 weeks", MaturityEstimate.describe(9 * WEEK));
        assertEquals("about 2 months", MaturityEstimate.describe(10 * WEEK));
    }

    /**
     * The number never reads as a quantity of the unit below its own threshold, and no wait is describable
     * two ways one block apart. Choosing the unit from the raw block figure let 431 blocks read "about 72
     * hours" while 432 read "about 3 days".
     */
    @Test
    public void aThresholdNeverReadsAsTheUnitBelowIt() {
        assertEquals("about 3 days", MaturityEstimate.describe(3 * DAY));
        assertEquals("about 3 days", MaturityEstimate.describe(3 * DAY - 1));
        assertEquals("about 2 weeks", MaturityEstimate.describe(2 * WEEK));
        assertEquals("about 2 weeks", MaturityEstimate.describe(2 * WEEK - 1));

        //No description anywhere in the range names a count that belongs to the unit above it
        for(int blocks = 1; blocks <= 20 * WEEK; blocks++) {
            String description = MaturityEstimate.describe(blocks);
            assertFalse(description.equals("about 72 hours"), "at " + blocks + " blocks");
            assertFalse(description.equals("about 14 days"), "at " + blocks + " blocks");
            assertFalse(description.equals("about 10 weeks"), "at " + blocks + " blocks");
        }
    }

    /** Singular and plural, because "about 1 weeks" is the kind of thing that ships. */
    @Test
    public void unitsArePluralisedCorrectly() {
        assertEquals("about 1 hour", MaturityEstimate.describe(HOUR));
        assertEquals("about 2 hours", MaturityEstimate.describe(2 * HOUR));
        assertEquals("about 3 days", MaturityEstimate.describe(3 * DAY));
        assertEquals("about 2 weeks", MaturityEstimate.describe(2 * WEEK));
    }

    /** The wait this was written for: the long maturity depth on mainnet. */
    @Test
    public void theLongMaturityDepthReadsAsWeeks() {
        //6480 blocks at ten minutes is 45 days, which is six and a half weeks
        assertEquals("about 6 weeks", MaturityEstimate.describe(6480));
    }

    /** Never a decimal, never a date, and always hedged. */
    @Test
    public void theWordingIsAlwaysHedgedAndNeverPrecise() {
        for(int blocks : new int[]{HOUR, 5 * HOUR, 3 * DAY, 10 * DAY, 2 * WEEK, 6480, 20 * WEEK}) {
            String description = MaturityEstimate.describe(blocks);
            assertTrue(description.startsWith("about "), description);
            assertFalse(description.contains("."), description);
        }
    }

    /**
     * A wait that has already ended is answered rather than thrown on. Callers ask only about immature
     * coins, but a tip can arrive between the two questions and that should not take down a table cell.
     */
    @Test
    public void aFinishedWaitIsAnsweredRatherThanThrowing() {
        assertEquals("less than an hour", MaturityEstimate.describe(0));
        assertEquals("less than an hour", MaturityEstimate.describe(-1));
    }

    /** Rounding never reaches zero, because the wait being described has not ended. */
    @Test
    public void roundingNeverProducesZero() {
        //Just over three days rounds to 3, not down into nothing
        assertEquals("about 3 days", MaturityEstimate.describe(3 * DAY + 1));
        //Just over ten weeks is 2.3 months, which must not round to "about 0 months"
        assertTrue(MaturityEstimate.describe(10 * WEEK + 1).startsWith("about 2 month"));
    }

    /** The short form keeps the hedge, because an unhedged duration reads as a promise anywhere. */
    @Test
    public void theShortFormKeepsTheHedge() {
        assertEquals("<1 hour", MaturityEstimate.describeShort(2));
        assertEquals("~1 hour", MaturityEstimate.describeShort(HOUR));
        assertEquals("~3 days", MaturityEstimate.describeShort(3 * DAY));
        assertEquals("~6 weeks", MaturityEstimate.describeShort(6480));
        assertEquals("~2 months", MaturityEstimate.describeShort(10 * WEEK));
    }

    /** Both forms describe the same wait in the same unit, so the two interfaces cannot disagree. */
    @Test
    public void bothFormsAgreeOnTheUnit() {
        for(int blocks = 1; blocks <= 60 * WEEK; blocks += 7) {
            String full = MaturityEstimate.describe(blocks);
            String brief = MaturityEstimate.describeShort(blocks);
            if(full.equals("less than an hour")) {
                assertEquals("<1 hour", brief, "at " + blocks + " blocks");
            } else {
                assertEquals(full.replace("about ", "~"), brief, "at " + blocks + " blocks");
            }
        }
    }

    /**
     * The terminal's date column is a fixed width, so the string it builds has to fit it or fall back. This
     * tracks the real constant rather than a copy of it, because widening that column is exactly the change
     * that would silently break this.
     */
    @Test
    public void theTerminalStringFitsItsColumnOrFallsBack() {
        for(int blocks = 1; blocks <= 60 * WEEK; blocks += 13) {
            String text = "Immature " + MaturityEstimate.describeShort(blocks);
            if(text.length() > DateTableCell.UTXO_WIDTH) {
                //The cell falls back to the bare fact, which must itself fit
                assertTrue("Immature".length() <= DateTableCell.UTXO_WIDTH);
            }
        }

        //A year, which is what part two of the deployment would mean, is the case that overflows
        assertTrue(("Immature " + MaturityEstimate.describeShort(52560)).length() > DateTableCell.UTXO_WIDTH);
        //And six weeks, which is what it means today, is the case that fits
        assertTrue(("Immature " + MaturityEstimate.describeShort(6480)).length() <= DateTableCell.UTXO_WIDTH);
    }

}

package com.sparrowwallet.sparrow.control;

/**
 * How long a wallet has to wait, said coarsely enough that nobody mistakes it for a promise.
 *
 * <p>Blocks remaining is exact. Turning it into a duration needs an assumed block interval, and the choice
 * matters less than being honest about it, so this uses the ten minute target rather than a measured rate.
 * The target is stable, documented, and the number the rest of Bitcoin quotes; a measured rate is more
 * accurate, much more code, and makes the figure move for reasons the user cannot see. It also errs in the
 * safer direction, because this chain has been running faster than ten minutes, so an estimate built on the
 * target reads long, and a lock that opens earlier than promised is the failure nobody complains about.
 *
 * <p>Rounded hard, always prefixed "about", never a date and never a decimal. "about 4 weeks" is useful and
 * honest; "44.7 days" and "2 November" are neither, because both imply we know when a block will be found.
 *
 * <p>A pure function of one integer so the buckets can be tested, which they should be: a bucketing function
 * with an off-by-one at a boundary is exactly the sort of thing that reads fine and is wrong.
 */
public final class MaturityEstimate {
    private MaturityEstimate() {}

    /** The target, not a measured rate. See the class comment for why. */
    static final int MINUTES_PER_BLOCK = 10;

    private static final int BLOCKS_PER_HOUR = 60 / MINUTES_PER_BLOCK;
    private static final int BLOCKS_PER_DAY = BLOCKS_PER_HOUR * 24;
    private static final int BLOCKS_PER_WEEK = BLOCKS_PER_DAY * 7;
    private static final int BLOCKS_PER_MONTH = BLOCKS_PER_DAY * 30;

    /**
     * Describes a wait of {@code blocksRemaining} blocks, as "about N <unit>".
     *
     * <p>The unit changes at ten weeks, two weeks, three days and one hour. Each threshold is inclusive of
     * the larger unit, so the number never reads as a quantity of the unit below its own threshold: at
     * exactly three days this says "about 3 days" rather than "about 72 hours".
     *
     * <p>A non-positive wait is not expected, since callers ask only about coins that are still immature, but
     * it is answered rather than thrown on, because a tip arriving between the two questions should not take
     * down a table cell.
     */
    public static String describe(int blocksRemaining) {
        return format(blocksRemaining, "about ", "less than an hour");
    }

    /**
     * The same estimate in a form that fits a fixed width column, as the terminal interface has. Keeps the
     * hedge, because an unhedged duration would read as a promise there just as much as anywhere else.
     */
    public static String describeShort(int blocksRemaining) {
        return format(blocksRemaining, "~", "<1 hour");
    }

    private static String format(int blocksRemaining, String prefix, String underAnHour) {
        //The unit is chosen from the rounded count rather than from the raw block figure, so a count can
        //never reach the threshold of the unit above it. Choosing from the raw figure let 431 blocks round
        //up to "about 72 hours" while 432 read "about 3 days": the same wait, said two ways, one block apart.
        int hours = round(blocksRemaining, BLOCKS_PER_HOUR);
        if(hours < 1) {
            return underAnHour;
        }
        if(hours < 72) {
            return unit(prefix, hours, "hour");
        }

        int days = round(blocksRemaining, BLOCKS_PER_DAY);
        if(days < 14) {
            return unit(prefix, days, "day");
        }

        int weeks = round(blocksRemaining, BLOCKS_PER_WEEK);
        if(weeks < 10) {
            return unit(prefix, weeks, "week");
        }

        return unit(prefix, round(blocksRemaining, BLOCKS_PER_MONTH), "month");
    }

    private static int round(int blocksRemaining, int blocksPerUnit) {
        return Math.round((float)blocksRemaining / blocksPerUnit);
    }

    /** Never zero: the caller is asking about a wait that has not ended. */
    private static String unit(String prefix, int units, String unitName) {
        int count = Math.max(1, units);
        return prefix + count + " " + unitName + (count == 1 ? "" : "s");
    }
}

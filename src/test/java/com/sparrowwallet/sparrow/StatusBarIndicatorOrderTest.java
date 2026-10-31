package com.sparrowwallet.sparrow;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The BLAKE2b badge stays first among the status bar's right items, whatever indicators are added after it. The Tor and
 * USB indicators count from the end of the list, which put Tor before the badge when the server toggle was all that
 * followed it.
 */
public class StatusBarIndicatorOrderTest {
    private static final int BADGE = 0;

    @Test
    public void anIndicatorCountedFromTheEndStaysAfterTheBadge() {
        //[badge, toggle]: Tor (2 from the end) and USB (1 from the end) both go between them
        Assertions.assertEquals(1, AppController.indicatorIndex(2, BADGE, 2));
        Assertions.assertEquals(1, AppController.indicatorIndex(2, BADGE, 1));
        //[badge, usb, toggle]: Tor goes before the USB indicator, as it did before the badge
        Assertions.assertEquals(1, AppController.indicatorIndex(3, BADGE, 2));
        //[badge, tor, usb, toggle]: a fourth item two from the end lands after Tor
        Assertions.assertEquals(2, AppController.indicatorIndex(4, BADGE, 2));
    }

    @Test
    public void anIndicatorAddedAtTheFrontGoesJustAfterTheBadge() {
        Assertions.assertEquals(1, AppController.indicatorIndex(2, BADGE, Integer.MAX_VALUE));
        Assertions.assertEquals(1, AppController.indicatorIndex(5, BADGE, Integer.MAX_VALUE));
    }
}

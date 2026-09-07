package com.sparrowwallet.sparrow;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Three states, because two of them are claims and the third is the absence of one.
 *
 * <p>An opt-in is counted only from a signature that verified against a key this wallet derives. That
 * fixed the screen saying "protected" over unverified bytes, and immediately created the mirror of it:
 * saying "not protected" over the same bytes. Both are assertions about something nobody checked, and
 * the second is the one that would send someone off to re-sign a transaction that was already fine.
 */
public class OptInCountsTest {
    private static AppServices.OptInCounts counts(int optedIn, int verified, int total) {
        return new AppServices.OptInCounts(optedIn, verified, total, 0);
    }

    @Test
    public void oneVerifiedOptInProtectsTheWholeTransaction() {
        AppServices.OptInCounts c = counts(1, 3, 3);
        Assertions.assertTrue(c.isProtected());
        Assertions.assertFalse(c.isKnownUnprotected());
        Assertions.assertFalse(c.isUncertain());
    }

    /**
     * The only shape that lets the absence of protection be stated: everything present was checked, and
     * none of it opted in.
     */
    @Test
    public void absenceIsOnlyClaimedWhenEverythingWasChecked() {
        AppServices.OptInCounts c = counts(0, 2, 2);
        Assertions.assertTrue(c.isKnownUnprotected());
        Assertions.assertFalse(c.isProtected());
        Assertions.assertFalse(c.isUncertain());
    }

    /**
     * Nothing could be checked. This is the ordinary case of a PSBT opened without the wallet that owns
     * its inputs, and it must not read as either answer.
     */
    @Test
    public void nothingCheckedIsNeitherProtectedNorUnprotected() {
        AppServices.OptInCounts c = counts(0, 0, 2);
        Assertions.assertTrue(c.isUncertain());
        Assertions.assertFalse(c.isProtected());
        Assertions.assertFalse(c.isKnownUnprotected(),
                "unchecked signatures must never be reported as the absence of protection");
    }

    /**
     * Partly checked, and what was checked did not opt in. The rest could still be anything, so the
     * transaction cannot be called unprotected.
     */
    @Test
    public void partlyCheckedIsStillUncertain() {
        AppServices.OptInCounts c = counts(0, 1, 3);
        Assertions.assertTrue(c.isUncertain());
        Assertions.assertFalse(c.isKnownUnprotected());
    }

    /**
     * A verified opt-in settles it even where other signatures could not be checked: one is enough to
     * make the transaction invalid under the pre-fork rules, and that is a property of the transaction
     * rather than of each signature.
     */
    @Test
    public void aVerifiedOptInSettlesItEvenWithUncheckedSignaturesPresent() {
        AppServices.OptInCounts c = counts(1, 1, 4);
        Assertions.assertTrue(c.isProtected());
        Assertions.assertFalse(c.isUncertain(), "the answer is known, so nothing is uncertain about it");
    }

    /**
     * Nothing signed yet. The caller falls back to what the transaction declares it will be, so these
     * must not read as any of the three.
     */
    @Test
    public void anUnsignedTransactionCarriesNoClaimEitherWay() {
        AppServices.OptInCounts c = counts(0, 0, 0);
        Assertions.assertFalse(c.isProtected());
        Assertions.assertFalse(c.isKnownUnprotected());
        Assertions.assertFalse(c.isUncertain());
    }

    @Test
    public void aNullPsbtCountsNothing() {
        AppServices.OptInCounts c = AppServices.signatureOptInCounts(null, null);
        Assertions.assertEquals(0, c.total());
        Assertions.assertEquals(0, c.verified());
        Assertions.assertEquals(0, c.optedIn());
        Assertions.assertEquals(0, c.liftable());
    }
}

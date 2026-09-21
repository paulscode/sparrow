package com.sparrowwallet.sparrow.control;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class InsufficientInputsDescriptionTest {
    /** With nothing immature, the wording is exactly what it has always been. */
    @Test
    public void withNothingImmatureTheWordingIsUnchanged() {
        assertEquals("Insufficient Inputs", InsufficientInputsDescription.get(null));
    }

    /** With something immature, the amount is named and the reader is sent to the coins. */
    @Test
    public void withSomethingImmatureTheAmountIsNamed() {
        String message = InsufficientInputsDescription.get("6.25 BTC");
        assertTrue(message.contains("6.25 BTC"), message);
        assertTrue(message.contains("immature"), message);
        assertTrue(message.contains("UTXOs"), message);
        assertFalse(message.contains("Insufficient Inputs"), message);
    }

    /**
     * No unlock height, ever. The message summarises possibly several coins and each unlocks a fixed depth
     * after its own block, so any single height would usually be wrong.
     */
    @Test
    public void noUnlockHeightAppearsInTheMessage() {
        String message = InsufficientInputsDescription.get("6.25 BTC");
        assertFalse(message.contains("block"), message);
        assertFalse(message.matches(".*\\b9\\d{5}\\b.*"), message);
    }
}

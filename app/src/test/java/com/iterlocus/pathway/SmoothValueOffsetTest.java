package com.iterlocus.pathway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

public class SmoothValueOffsetTest {

    @Test
    public void startsAtZeroWithoutInitialJump() {
        SmoothValueOffset offset = new SmoothValueOffset(2d, 10d, 5d, 0.2d,
                new Random(1L));

        assertEquals(0d, offset.getValue(), 0d);
    }

    @Test
    public void remainsInsideConfiguredBounds() {
        SmoothValueOffset offset = new SmoothValueOffset(2d, 10d, 5d, 0.2d,
                new Random(2L));

        for (int index = 0; index < 100_000; index++) {
            offset.advance(0.1d);
            assertTrue(Math.abs(offset.getValue()) <= 2d + 1e-12d);
        }
    }

    @Test
    public void changeRateIsLimited() {
        SmoothValueOffset offset = new SmoothValueOffset(5d, 1d, 0d, 0.2d,
                new Random(3L));
        double previous = offset.getValue();

        for (int index = 0; index < 50_000; index++) {
            offset.advance(0.1d);
            assertTrue(Math.abs(offset.getValue() - previous) <= 0.021d);
            previous = offset.getValue();
        }
    }
}

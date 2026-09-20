package com.iterlocus.pathway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

public class SmoothRouteOffsetTest {

    @Test
    public void startsAtRouteCenterWithoutInitialJump() {
        SmoothRouteOffset offset = new SmoothRouteOffset(10d, 6d, new Random(1L));

        assertEquals(0d, offset.getEastMeters(), 0d);
        assertEquals(0d, offset.getNorthMeters(), 0d);
    }

    @Test
    public void remainsInsideConfiguredEllipseOverLongPlayback() {
        SmoothRouteOffset offset = new SmoothRouteOffset(10d, 6d, new Random(2L));

        for (int index = 0; index < 100_000; index++) {
            offset.advance(0.1d);
            double normalized = square(offset.getEastMeters() / 10d)
                    + square(offset.getNorthMeters() / 6d);
            assertTrue(normalized <= 1d + 1e-9d);
        }
    }

    @Test
    public void driftIsContinuousAndSpeedLimited() {
        SmoothRouteOffset offset = new SmoothRouteOffset(20d, 20d, new Random(3L));
        double previousEast = offset.getEastMeters();
        double previousNorth = offset.getNorthMeters();

        for (int index = 0; index < 50_000; index++) {
            offset.advance(0.1d);
            double moved = Math.hypot(offset.getEastMeters() - previousEast,
                    offset.getNorthMeters() - previousNorth);
            assertTrue(moved <= 0.036d);
            previousEast = offset.getEastMeters();
            previousNorth = offset.getNorthMeters();
        }
    }

    private static double square(double value) {
        return value * value;
    }
}

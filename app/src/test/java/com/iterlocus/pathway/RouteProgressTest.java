package com.iterlocus.pathway;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RouteProgressTest {

    @Test
    public void explicitPausedStateIsReported() {
        RouteProgress progress = new RouteProgress("route", false, true, 0d, 10d, 100d, 0);

        assertTrue(progress.isPaused());
    }

    @Test
    public void zeroSpeedDoesNotImplicitlyPauseRoute() {
        RouteProgress progress = new RouteProgress("route", false, false, 0d, 10d, 100d, 0);

        assertFalse(progress.isPaused());
    }
}

package com.iterlocus.pathway;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LocationOffsetTest {

    @Test
    public void longitudeMetersAccountForLatitude() {
        double[] result = LocationOffset.applyMeters(0d, 60d, 556.6d, 0d);

        assertEquals(0.01d, result[0], 1e-6d);
        assertEquals(60d, result[1], 1e-12d);
    }

    @Test
    public void latitudeMetersUseLatitudeScale() {
        double[] result = LocationOffset.applyMeters(120d, 30d, 0d, 1105.74d);

        assertEquals(120d, result[0], 1e-12d);
        assertEquals(30.01d, result[1], 1e-6d);
    }
}

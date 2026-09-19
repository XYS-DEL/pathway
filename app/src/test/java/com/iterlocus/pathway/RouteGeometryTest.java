package com.iterlocus.pathway;

import static org.junit.Assert.assertEquals;

import com.baidu.mapapi.model.LatLng;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class RouteGeometryTest {

    /** 1 度纬度约 111195 米，容差给足以免受地球半径取值差异影响。 */
    private static final double ONE_DEGREE_TOLERANCE = 200d;

    @Test
    public void distanceBetweenIdenticalPointsIsZero() {
        LatLng p = new LatLng(39.915, 116.404);
        assertEquals(0d, RouteGeometry.distanceMeters(p, p), 1e-6);
    }

    @Test
    public void oneDegreeOfLatitudeIsAbout111km() {
        assertEquals(111195d,
                RouteGeometry.distanceMeters(new LatLng(0, 0), new LatLng(1, 0)),
                ONE_DEGREE_TOLERANCE);
    }

    @Test
    public void oneDegreeOfLongitudeAtEquatorIsAbout111km() {
        assertEquals(111195d,
                RouteGeometry.distanceMeters(new LatLng(0, 0), new LatLng(0, 1)),
                ONE_DEGREE_TOLERANCE);
    }

    @Test
    public void longitudeDegreeShrinksAwayFromEquator() {
        double atEquator = RouteGeometry.distanceMeters(new LatLng(0, 0), new LatLng(0, 1));
        double at60 = RouteGeometry.distanceMeters(new LatLng(60, 0), new LatLng(60, 1));
        // cos(60°) = 0.5，约为赤道的一半
        assertEquals(atEquator / 2, at60, ONE_DEGREE_TOLERANCE);
    }

    @Test
    public void totalLengthSumsAllSegments() {
        List<LatLng> points = Arrays.asList(new LatLng(0, 0), new LatLng(0.5, 0), new LatLng(1, 0));
        assertEquals(111195d, RouteGeometry.totalLengthMeters(points, false), ONE_DEGREE_TOLERANCE);
    }

    @Test
    public void closedAddsTheReturnSegment() {
        List<LatLng> triangle = Arrays.asList(new LatLng(0, 0), new LatLng(0, 1), new LatLng(1, 1));
        double open = RouteGeometry.totalLengthMeters(triangle, false);
        double closed = RouteGeometry.totalLengthMeters(triangle, true);
        assertEquals(RouteGeometry.distanceMeters(new LatLng(1, 1), new LatLng(0, 0)),
                closed - open, 1e-6);
    }

    @Test
    public void totalLengthIsZeroWhenFewerThanTwoPoints() {
        assertEquals(0d, RouteGeometry.totalLengthMeters(null, false), 1e-9);
        assertEquals(0d, RouteGeometry.totalLengthMeters(new ArrayList<>(), false), 1e-9);
        assertEquals(0d, RouteGeometry.totalLengthMeters(
                Collections.singletonList(new LatLng(1, 1)), true), 1e-9);
    }
}

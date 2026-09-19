package com.iterlocus.pathway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void densifyAddsExactlyRequestedCount() {
        List<LatLng> line = Arrays.asList(new LatLng(0, 0), new LatLng(1, 0));
        assertEquals(5, RouteGeometry.densify(line, false, 3).size());
    }

    @Test
    public void densifyPlacesNewPointsEvenlyByTotalLength() {
        List<LatLng> line = Arrays.asList(new LatLng(0, 0), new LatLng(1, 0));
        List<LatLng> result = RouteGeometry.densify(line, false, 3);
        assertEquals(0.25, result.get(1).latitude, 1e-6);
        assertEquals(0.50, result.get(2).latitude, 1e-6);
        assertEquals(0.75, result.get(3).latitude, 1e-6);
    }

    @Test
    public void densifyKeepsOriginalVertices() {
        List<LatLng> corner = Arrays.asList(new LatLng(0, 0), new LatLng(0, 1), new LatLng(1, 1));
        List<LatLng> result = RouteGeometry.densify(corner, false, 4);
        assertTrue(contains(result, new LatLng(0, 0)));
        assertTrue(contains(result, new LatLng(0, 1)));
        assertTrue(contains(result, new LatLng(1, 1)));
        assertEquals(3 + 4, result.size());
    }

    @Test
    public void densifyResultLiesOnTheOriginalRoute() {
        List<LatLng> corner = Arrays.asList(new LatLng(0, 0), new LatLng(0, 1), new LatLng(1, 1));
        List<LatLng> result = RouteGeometry.densify(corner, false, 5);
        double walked = 0d;
        for (int i = 1; i < result.size(); i++) {
            walked += RouteGeometry.distanceMeters(result.get(i - 1), result.get(i));
        }
        assertEquals(RouteGeometry.totalLengthMeters(corner, false), walked, 1d);
    }

    @Test
    public void densifyIsNoOpForNonPositiveCountOrTooFewPoints() {
        List<LatLng> line = Arrays.asList(new LatLng(0, 0), new LatLng(1, 0));
        assertEquals(2, RouteGeometry.densify(line, false, 0).size());
        assertEquals(2, RouteGeometry.densify(line, false, -5).size());
        assertEquals(1, RouteGeometry.densify(
                Collections.singletonList(new LatLng(0, 0)), false, 3).size());
        assertEquals(0, RouteGeometry.densify(null, false, 3).size());
    }

    @Test
    public void densifyIsNoOpWhenTotalLengthIsZero() {
        List<LatLng> samePoint = Arrays.asList(new LatLng(1, 1), new LatLng(1, 1));
        assertEquals(2, RouteGeometry.densify(samePoint, false, 5).size());
    }

    @Test
    public void densifyWrapsAroundWhenClosed() {
        List<LatLng> square = Arrays.asList(
                new LatLng(0, 0), new LatLng(0, 1), new LatLng(1, 1), new LatLng(1, 0));
        List<LatLng> result = RouteGeometry.densify(square, true, 8);
        assertEquals(4 + 8, result.size());
        double walked = 0d;
        for (int i = 1; i < result.size(); i++) {
            walked += RouteGeometry.distanceMeters(result.get(i - 1), result.get(i));
        }
        assertTrue("闭合密化后应走满一圈", walked > RouteGeometry.totalLengthMeters(square, false));
    }

    private static boolean contains(List<LatLng> points, LatLng target) {
        for (LatLng p : points) {
            if (Math.abs(p.latitude - target.latitude) < 1e-9
                    && Math.abs(p.longitude - target.longitude) < 1e-9) {
                return true;
            }
        }
        return false;
    }
}

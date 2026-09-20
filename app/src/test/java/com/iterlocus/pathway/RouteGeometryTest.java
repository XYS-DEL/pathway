package com.iterlocus.pathway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    @Test
    public void cannotCloseWithFewerThanTwoPoints() {
        assertFalse(RouteGeometry.canClose(0));
        assertFalse(RouteGeometry.canClose(1));
        assertTrue(RouteGeometry.canClose(2));
        assertTrue(RouteGeometry.canClose(5));
    }

    @Test
    public void hitRadiusAcceptsPointsInsideAndOnBoundary() {
        assertTrue(RouteGeometry.isWithinHitRadius(100, 100, 100, 100, 24f));
        assertTrue(RouteGeometry.isWithinHitRadius(110, 100, 100, 100, 24f));
        assertTrue(RouteGeometry.isWithinHitRadius(124, 100, 100, 100, 24f));
    }

    @Test
    public void hitRadiusRejectsPointsOutside() {
        assertFalse(RouteGeometry.isWithinHitRadius(125, 100, 100, 100, 24f));
        assertFalse(RouteGeometry.isWithinHitRadius(100, 200, 100, 100, 24f));

        // 对角线方向的 (16,16)：L2 距离 √512 ≈ 22.63 ≤ 24（应命中），
        // 而曼哈顿距离 32 > 24（会误判）。上一条只排除了切比雪夫。
        assertTrue(RouteGeometry.isWithinHitRadius(116, 116, 100, 100, 24f));
    }

    @Test
    public void hitRadiusUsesEuclideanDistanceNotAxisDistance() {
        // (117,117) 的对角距离是 24.04 > 24，虽然轴距各只有 17
        assertFalse(RouteGeometry.isWithinHitRadius(117, 117, 100, 100, 24f));
    }

    @Test
    public void firstSampleIsAlwaysAccepted() {
        assertTrue(RouteGeometry.shouldSample(null, new LatLng(0, 0),
                RouteGeometry.LINE_SAMPLE_MIN_DISTANCE_METERS));
    }

    @Test
    public void samplingRejectsPointsTooCloseToLast() {
        assertFalse(RouteGeometry.shouldSample(new LatLng(0, 0), new LatLng(0, 0.000001),
                RouteGeometry.LINE_SAMPLE_MIN_DISTANCE_METERS));
    }

    @Test
    public void samplingAcceptsPointsFarEnoughFromLast() {
        assertTrue(RouteGeometry.shouldSample(new LatLng(0, 0), new LatLng(0, 0.001),
                RouteGeometry.LINE_SAMPLE_MIN_DISTANCE_METERS));
    }

    @Test
    public void samplingThresholdValueAndOperatorArePinned() {
        LatLng origin = new LatLng(0, 0);

        // 恰好等于阈值：钉住 >= 而不是 >。阈值由 distanceMeters 现算，避免浮点字面量；
        // 期望值 true 是独立可知的（">=" 在等号处为真），不是拿被测代码当期望。
        LatLng atThreshold = new LatLng(0, 2.0 / 111195.0);
        assertTrue(RouteGeometry.shouldSample(origin, atThreshold,
                RouteGeometry.distanceMeters(origin, atThreshold)));

        // 收紧夹逼：赤道上 1.5e-5 度 ≈ 1.67 米（应丢弃）、2.5e-5 度 ≈ 2.78 米（应保留）。
        // 这两条把 LINE_SAMPLE_MIN_DISTANCE_METERS 锁进 (1.67, 2.78]，取 1.0 或 3.0 都会失败——
        // 上面两条只夹到 (0.111, 111.195]，50 和 100 都能蒙混过关。
        assertFalse(RouteGeometry.shouldSample(origin, new LatLng(0, 1.5e-5),
                RouteGeometry.LINE_SAMPLE_MIN_DISTANCE_METERS));
        assertTrue(RouteGeometry.shouldSample(origin, new LatLng(0, 2.5e-5),
                RouteGeometry.LINE_SAMPLE_MIN_DISTANCE_METERS));
    }

    @Test
    public void doubleDistanceMatchesLatLngVersion() {
        LatLng a = new LatLng(39.915, 116.404);
        LatLng b = new LatLng(39.925, 116.414);
        assertEquals(RouteGeometry.distanceMeters(a, b),
                RouteGeometry.distanceMeters(a.longitude, a.latitude, b.longitude, b.latitude),
                1e-9);
    }

    @Test
    public void doubleDistanceTakesLongitudeFirst() {
        // 原点不能取 (0,0)：把 (纬度, 经度) 互换是球面关于 x=y 平面的镜射，是等距变换，
        // 而 (0,0) 正好在这个镜面上——实测两个距离差 0.0，钉不住参数顺序。
        // 改取赤道外的原点 (0, 60)：那里 1 经度约 55.6 公里，换成 (0,60) → (60,1) 则跨 59 个纬度。
        double correct = RouteGeometry.distanceMeters(0, 60, 1, 60);
        double swapped = RouteGeometry.distanceMeters(0, 60, 60, 1);
        assertTrue("参数顺序写反应得到完全不同的距离", Math.abs(correct - swapped) > 1000d);

        // 只断言"两者不同"不够：把纬度当经度用同样会得到不同的值。还得钉住 correct
        // 就是地理上对的那个——60°N 上 1 经度 ≈ 111195 * cos(60°) 米。
        assertEquals(111195d * Math.cos(Math.toRadians(60d)), correct, ONE_DEGREE_TOLERANCE);
    }

    @Test
    public void bearingIsZeroDueNorth() {
        assertEquals(0d, RouteGeometry.initialBearingDegrees(0, 0, 0, 1), 0.5);
    }

    @Test
    public void bearingIsNinetyDueEast() {
        assertEquals(90d, RouteGeometry.initialBearingDegrees(0, 0, 1, 0), 0.5);
    }

    @Test
    public void bearingIsOneEightyDueSouth() {
        assertEquals(180d, RouteGeometry.initialBearingDegrees(0, 1, 0, 0), 0.5);
    }

    @Test
    public void bearingIsTwoSeventyDueWest() {
        assertEquals(270d, RouteGeometry.initialBearingDegrees(1, 0, 0, 0), 0.5);
    }

    @Test
    public void bearingIsZeroForIdenticalPoints() {
        assertEquals(0d, RouteGeometry.initialBearingDegrees(1, 2, 1, 2), 1e-9);
    }

    @Test
    public void bearingCrossesAntimeridianGoingEast() {
        // 179.9°E → 179.9°W 是向东跨过 180° 的一小步（约 22 公里），
        // 不是向西绕地球一圈。平面 atan2 会给出 ~270°，正确结果是 ~90°。
        assertEquals(90d, RouteGeometry.initialBearingDegrees(179.9, 0, -179.9, 0), 0.5);
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

package com.iterlocus.pathway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RoutePlayerTest {

    /** 经纬度容差：1e-6 度约 0.11 米。 */
    private static final double DEGREE_TOLERANCE = 1e-6;

    /** 赤道上 (0,0) → (0.01, 0)：正东、长约 1112 米的一条直线。 */
    private static double[][] eastLine() {
        return new double[][]{{0d, 0d}, {0.01d, 0d}};
    }

    /** 先正东、再正北的直角。 */
    private static double[][] corner() {
        return new double[][]{{0d, 0d}, {0.01d, 0d}, {0.01d, 0.01d}};
    }

    private static double[][] triangle() {
        return new double[][]{{0d, 0d}, {0.01d, 0d}, {0.01d, 0.01d}};
    }

    private static double firstSegmentLength() {
        return new RoutePlayer(new double[][]{{0d, 0d}, {0.01d, 0d}}, false, 0d)
                .getTotalDistance();
    }

    @Test
    public void zeroAdvanceKeepsPositionAtStart() {
        RoutePlayer player = new RoutePlayer(eastLine(), false, 100d);
        player.advance(0d);
        assertEquals(0d, player.getPosition()[0], DEGREE_TOLERANCE);
        assertEquals(0d, player.getPosition()[1], DEGREE_TOLERANCE);
    }

    @Test
    public void distanceCoveredEqualsSpeedTimesTime() {
        RoutePlayer player = new RoutePlayer(eastLine(), false, 0d);
        player.setSpeed(50d);
        player.advance(2d);
        assertEquals(100d, player.getDistanceCovered(), 1e-6);
    }

    @Test
    public void halfwayAlongStraightLineIsTheMidpoint() {
        RoutePlayer player = new RoutePlayer(eastLine(), false, 0d);
        player.setSpeed(player.getTotalDistance());   // 1 秒走完全程
        player.advance(0.5d);
        assertEquals(0.005d, player.getPosition()[0], DEGREE_TOLERANCE);
        assertEquals(0d, player.getPosition()[1], DEGREE_TOLERANCE);
    }

    @Test
    public void openRouteStopsAtLastPointAndFinishes() {
        RoutePlayer player = new RoutePlayer(eastLine(), false, 100000d);
        player.advance(10d);   // 远远超过总长
        assertTrue(player.isFinished());
        assertEquals(0.01d, player.getPosition()[0], DEGREE_TOLERANCE);
        assertEquals(player.getTotalDistance(), player.getDistanceCovered(), 1e-6);
    }

    @Test
    public void advancingPastTheEndOfAnOpenRouteDoesNotMoveIt() {
        RoutePlayer player = new RoutePlayer(eastLine(), false, 100000d);
        player.advance(10d);
        double lng = player.getPosition()[0];
        player.advance(10d);
        assertEquals(lng, player.getPosition()[0], 1e-12);
    }

    @Test
    public void positionCrossesIntoTheNextSegment() {
        RoutePlayer player = new RoutePlayer(corner(), false, 0d);
        double first = firstSegmentLength();
        player.setSpeed(first * 2d);
        player.advance(0.75d);   // 1.5 倍第一段长 → 落在第二段一半处
        double[] position = player.getPosition();
        assertEquals(0.01d, position[0], DEGREE_TOLERANCE);    // 经度已是拐角值
        assertEquals(0.005d, position[1], DEGREE_TOLERANCE);   // 纬度走了一半
    }

    @Test
    public void bearingFollowsTheCurrentSegment() {
        RoutePlayer player = new RoutePlayer(corner(), false, 0d);
        player.setSpeed(firstSegmentLength() * 2d);
        player.advance(0.25d);   // 第一段中点
        assertEquals(90d, player.getBearing(), 0.5);
        player.advance(0.4d);    // 越过拐角，进入第二段
        assertEquals(0d, player.getBearing(), 0.5);
    }

    @Test
    public void closedRouteWrapsBackToStartAfterOneLap() {
        RoutePlayer player = new RoutePlayer(triangle(), true, 0d);
        double total = player.getTotalDistance();
        player.setSpeed(total / 2d);
        player.advance(2d);      // 正好一圈
        assertEquals(0d, player.getDistanceCovered(), 1e-6);
        assertEquals(0d, player.getPosition()[0], DEGREE_TOLERANCE);
        assertEquals(0d, player.getPosition()[1], DEGREE_TOLERANCE);
        assertEquals(1, player.getLapCount());
        assertFalse(player.isFinished());
    }

    @Test
    public void closedRouteNeverFinishesAndCountsEveryLap() {
        RoutePlayer player = new RoutePlayer(triangle(), true, 0d);
        player.setSpeed(player.getTotalDistance());   // 每秒一圈
        for (int lap = 1; lap <= 20; lap++) {
            player.advance(1d);
            assertEquals(lap, player.getLapCount());
            assertFalse(player.isFinished());
        }
    }

    @Test
    public void singleAdvanceCrossingSeveralLapsCountsThemAll() {
        RoutePlayer player = new RoutePlayer(triangle(), true, 0d);
        double total = player.getTotalDistance();
        player.setSpeed(total * 3.5d);
        player.advance(1d);      // 一步跨 3.5 圈
        assertEquals(3, player.getLapCount());
        assertEquals(total * 0.5d, player.getDistanceCovered(), 1e-6);
    }

    @Test
    public void openRouteLapCountStaysZero() {
        RoutePlayer player = new RoutePlayer(eastLine(), false, 100000d);
        player.advance(100d);
        assertEquals(0, player.getLapCount());
    }

    @Test
    public void closedRouteWithTwoPointsWalksThereAndBack() {
        double[][] pair = {{0d, 0d}, {0.01d, 0d}};
        RoutePlayer open = new RoutePlayer(pair, false, 0d);
        RoutePlayer closed = new RoutePlayer(pair, true, 0d);
        assertEquals(open.getTotalDistance() * 2d, closed.getTotalDistance(), 1e-6);
        assertFalse(closed.isFinished());
    }

    @Test
    public void aSinglePointCannotBeClosed() {
        RoutePlayer player = new RoutePlayer(new double[][]{{0d, 0d}}, true, 100d);
        player.advance(10d);
        assertEquals(0d, player.getDistanceCovered(), 1e-9);
    }

    @Test
    public void zeroSpeedDoesNotMove() {
        RoutePlayer player = new RoutePlayer(eastLine(), false, 0d);
        player.advance(100d);
        assertEquals(0d, player.getDistanceCovered(), 1e-9);
        assertFalse(player.isFinished());
    }

    @Test
    public void negativeDtDoesNotMove() {
        RoutePlayer player = new RoutePlayer(eastLine(), false, 100d);
        player.advance(-5d);
        assertEquals(0d, player.getDistanceCovered(), 1e-9);
    }

    @Test
    public void negativeSpeedIsTreatedAsZero() {
        RoutePlayer player = new RoutePlayer(eastLine(), false, 100d);
        player.setSpeed(-3d);
        assertEquals(0d, player.getSpeed(), 1e-9);
        player.advance(10d);
        assertEquals(0d, player.getDistanceCovered(), 1e-9);
    }

    @Test
    public void setSpeedChangesThePace() {
        RoutePlayer slow = new RoutePlayer(eastLine(), false, 10d);
        RoutePlayer fast = new RoutePlayer(eastLine(), false, 10d);
        fast.setSpeed(30d);
        slow.advance(1d);
        fast.advance(1d);
        assertEquals(slow.getDistanceCovered() * 3d, fast.getDistanceCovered(), 1e-6);
    }

    @Test
    public void mutatingTheReturnedPositionDoesNotAffectThePlayer() {
        RoutePlayer player = new RoutePlayer(eastLine(), false, 100d);
        player.advance(1d);
        double[] snapshot = player.getPosition();
        snapshot[0] = 999d;
        assertTrue("getPosition 必须返回副本，否则调用方能改坏引擎内部状态",
                player.getPosition()[0] < 1d);
    }

    @Test
    public void emptyPointListIsSafe() {
        RoutePlayer player = new RoutePlayer(new double[0][], false, 10d);
        assertNull(player.getPosition());
        assertEquals(0d, player.getTotalDistance(), 1e-9);
        assertEquals(0d, player.getBearing(), 1e-9);
        assertEquals(0, player.getLapCount());
        player.advance(10d);          // 不应抛
        assertNull(player.getPosition());
    }

    @Test
    public void nullPointListIsSafe() {
        RoutePlayer player = new RoutePlayer(null, false, 10d);
        assertNull(player.getPosition());
        assertEquals(0d, player.getTotalDistance(), 1e-9);
        player.advance(10d);
    }

    @Test
    public void singlePointListStaysAtThatPoint() {
        RoutePlayer player = new RoutePlayer(new double[][]{{116.4d, 39.9d}}, false, 10d);
        assertNotNull(player.getPosition());
        assertEquals(116.4d, player.getPosition()[0], 1e-9);
        assertEquals(39.9d, player.getPosition()[1], 1e-9);
        assertEquals(0d, player.getTotalDistance(), 1e-9);
        player.advance(10d);
        assertEquals(116.4d, player.getPosition()[0], 1e-9);
    }
}

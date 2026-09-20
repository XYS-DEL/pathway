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

    /**
     * 经度是第一个参数——顺序传反必须被抓住。
     *
     * <p><b>本测试刻意放在北纬 60°，不要挪回赤道附近。</b>赤道上 1° 经度与 1° 纬度都是
     * ≈1111.95 米，两条轴等距，「沿纬线量」与「沿经线量」得到同一个长度，
     * 顺序传反在赤道附近完全看不出来（已用变异测试证实：把 {@code RoutePlayer} 里
     * {@code distanceMeters} 的实参调换，其余测试全绿）。
     * 北纬 60° 处 1° 经度只剩 ≈556 米，两轴不再等距，调换后 556 会变成 1112。
     */
    @Test
    public void distanceUsesLongitudeFirst() {
        // {经度, 纬度}：沿北纬 60° 向东 0.01°，正确 ≈555.98 米，顺序传反则 ≈1111.95 米。
        RoutePlayer player = new RoutePlayer(new double[][]{{0d, 60d}, {0.01d, 60d}}, false, 0d);
        assertEquals(556d, player.getTotalDistance(), 2d);
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
        double[][] single = {{0d, 0d}};

        RoutePlayer closed = new RoutePlayer(single, true, 100d);
        closed.advance(10d);
        assertEquals(0d, closed.getDistanceCovered(), 1e-9);

        // 闭合需要至少 2 个点，单点闭合必须退化成一个零长度的开环路线。
        // 这里断言 isFinished() 而不是再断言一次距离：零长度路线的距离恒为 0，
        // 断言它由构造成立——删掉构造器里的 count >= 2 守卫也照样绿（已用变异测试证实）。
        // isFinished() 才是那个守卫的可观测量：零长度开环「已走完」（true），
        // 而闭合路线永远不会 finished，守卫一旦缺失这里会变成 false。
        assertTrue(closed.isFinished());
        assertEquals(0, closed.getLapCount());
    }

    @Test
    public void closedRouteWithCoincidentPointsDoesNotProduceNaN() {
        // 两点重合的闭合路线：能过「点数 >= 2」，却总长为 0。
        // 这是「点数」与「总长」两道拒绝不可互相替代的实证，也是 advance 里
        // mTotalDistance <= 0d 那道守卫唯一能拦住的东西——去掉它，
        // mDistance %= mTotalDistance 会得到 NaN，圈数会变成 Integer.MAX_VALUE。
        RoutePlayer player = new RoutePlayer(new double[][]{{0d, 0d}, {0d, 0d}}, true, 100d);
        // 先把前提钉住：本用例的全部意义都建立在「总长恰为 0」上。
        // 若日后距离算法变动使两点重合不再给出 0，这条断言会先红，
        // 而不是让下面的断言因为前提消失而悄悄变成空过。
        assertEquals(0d, player.getTotalDistance(), 1e-9);
        player.advance(10d);

        assertEquals(0d, player.getDistanceCovered(), 1e-9);
        assertEquals(0, player.getLapCount());
        assertEquals(0d, player.getPosition()[0], 1e-9);
        assertEquals(0d, player.getPosition()[1], 1e-9);
        assertFalse(player.isFinished());
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

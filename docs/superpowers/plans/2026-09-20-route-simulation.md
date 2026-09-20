# 路线模拟 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `RouteSimulationActivity` 从占位界面做成真功能——选一条已存路线、选速度档位、开始模拟，位置在 `ServiceGo` 里沿折线连续推进，用户切到别的 App 后仍持续移动。

**Architecture:** 运动引擎（`RoutePlayer`，纯逻辑）跑在 `ServiceGo` 的定位线程里，每个 tick 按真实经过的时间把「当前位置」单元格沿折线推进；现有的双 provider 推送代码一行不改。界面通过 binder 轮询状态快照，不注册回调。播放期间摇杆禁用并变灰。

**Tech Stack:** Java（无 Kotlin）、Android SDK 32 / minSdk 27、百度 LBS SDK、JUnit 4、Gradle 8.13 + AGP 8.12.1。

**Spec:** `docs/superpowers/specs/2026-09-20-route-simulation-design.md`

## Global Constraints

- **注释与提交信息一律中文**，与仓库现状一致。
- **运行期错误记日志后降级，不抛不崩**：`XLog.e("COMPONENT: ERROR - method")`。唯一例外是 `onCreate`/`onUpgrade` 里的 DDL 错误（记日志并重抛）——本次不涉及。
- **用户可见文案进 `res/values/strings.xml`**，消息类用 `app_*` 前缀。
- **坐标系**：界面与地图层用 BD09；喂给 `ServiceGo` 的必须是 **WGS84**。`MapUtils.bd2wgs(经度, 纬度)` 返回 `{经度, 纬度}`；百度 `LatLng` 构造是 `(纬度, 经度)`。写反就是 ~500m 偏移。
- **纯逻辑类不得碰 `android.*`**（普通 JVM 单测会抛 "not mocked"）。`com.baidu.mapapi.*` 是普通 jar，可以用。
- **`RoutePlayer` 与 `RouteGeometry` 的新增函数必须先写测试再写实现。**
- JDK 17–21。若默认 `JAVA_HOME` 是 25，Gradle 8.13 起不来，需 pin：
  ```bash
  JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" ./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
  ```
  （本机已在 `~/.gradle/gradle.properties` 里写了 `org.gradle.java.home`，直接 `./gradlew` 通常也行。）
- **每个任务的验证三件套**：`assembleDebug` + `lintDebug` + `testDebugUnitTest`，全绿才算完成。基线是 **33 个单元测试**（`RouteGeometryTest` 22 + 一个 `ExampleUnitTest` + androidTest 的一个不计入）。
- **不要跑 `assembleRelease`**：它需要 `local.properties` 里的签名配置，缺失时按设计直接失败。
- **界面改动必须真机截图验收**——编译、lint、单测都覆盖不到布局与配色。`RouteSimulationActivity` 若未 exported，需人工打开后 `adb exec-out screencap -p` 截图。
- 提交信息结尾加 `Co-Authored-By: Claude Code <noreply@anthropic.com>`。

---

### Task 1: `RouteGeometry` 扩展（double 版距离 + 航向）

`RoutePlayer` 需要在裸经纬度上算距离与航向。现有 `RouteGeometry.distanceMeters(LatLng, LatLng)` 收 `LatLng`，而引擎刻意不收 `LatLng`（它不携带坐标系信息，见 spec）。本任务把 haversine 抽成标量版本，并把航向也放进来——两者都是纯几何，与路线引擎的状态机无关。

**Files:**
- Modify: `app/src/main/java/com/iterlocus/pathway/RouteGeometry.java:30-40`
- Test: `app/src/test/java/com/iterlocus/pathway/RouteGeometryTest.java`

**Interfaces:**
- Consumes: 现有 `EARTH_RADIUS_M`（`private static final double = 6371008.8`）
- Produces:
  - `public static double distanceMeters(double lng1, double lat1, double lng2, double lat2)`
  - `public static double initialBearingDegrees(double lng1, double lat1, double lng2, double lat2)`
  - `distanceMeters(LatLng, LatLng)` 保留原签名，改为委托

- [ ] **Step 1: 写失败的测试**

在 `RouteGeometryTest.java` 末尾（`contains` 辅助方法之前）加入：

```java
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
        // 在 (0,0) 附近经纬度对称，写反了也看不出来，所以必须用不对称的点钉住。
        // (0,0) → (1, 60)：经度差 1 度、纬度差 60 度。
        double correct = RouteGeometry.distanceMeters(0, 0, 1, 60);
        double swapped = RouteGeometry.distanceMeters(0, 0, 60, 1);
        assertTrue("参数顺序写反应得到完全不同的距离", Math.abs(correct - swapped) > 1000d);
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
```

- [ ] **Step 2: 跑测试确认失败**

```bash
JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" ./gradlew :app:testDebugUnitTest --tests "com.iterlocus.pathway.RouteGeometryTest"
```

预期：编译失败，`cannot find symbol: method distanceMeters(double,double,double,double)` 与 `initialBearingDegrees`。

- [ ] **Step 3: 实现**

把 `RouteGeometry.java` 现有的 `distanceMeters(LatLng, LatLng)`（第 30–40 行）整体替换为下面三块：

```java
    /** 两点间测地距离（haversine），单位米。 */
    public static double distanceMeters(LatLng a, LatLng b) {
        return distanceMeters(a.longitude, a.latitude, b.longitude, b.latitude);
    }

    /**
     * 两点间测地距离（haversine），单位米。
     *
     * <p>入参顺序是 <b>(经度, 纬度)</b>，与 {@link MapUtils#bd2wgs(double, double)} 一致；
     * 而百度 {@code LatLng} 的构造是 (纬度, 经度)。本项目已因坐标系踩过坑，别写反。
     */
    public static double distanceMeters(double lng1, double lat1, double lng2, double lat2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = phi2 - phi1;
        double dLambda = Math.toRadians(lng2 - lng1);
        double sinLat = Math.sin(dPhi / 2);
        double sinLng = Math.sin(dLambda / 2);
        double h = sinLat * sinLat + Math.cos(phi1) * Math.cos(phi2) * sinLng * sinLng;
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }

    /**
     * 起点指向终点的初始方位角，度，取值 [0, 360)。两点重合时返回 0。
     *
     * <p>用标准 initial bearing 公式而不是平面 atan2：{@code Location.setBearing()} 会被
     * 目标 App 读取，值得算对；而且平面算法在经度跨 ±180° 时会给出反向航向。
     */
    public static double initialBearingDegrees(double lng1, double lat1, double lng2, double lat2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dLambda = Math.toRadians(lng2 - lng1);
        double y = Math.sin(dLambda) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2)
                - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLambda);
        return (Math.toDegrees(Math.atan2(y, x)) + 360d) % 360d;
    }
```

`totalLengthMeters(List<LatLng>, boolean)` **不动**——它已经调用 `distanceMeters(LatLng, LatLng)`，委托链自动生效。

- [ ] **Step 4: 跑测试确认通过**

```bash
JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" ./gradlew :app:testDebugUnitTest --tests "com.iterlocus.pathway.RouteGeometryTest"
```

预期：PASS，30 个（原有 22 + 新增 8）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/iterlocus/pathway/RouteGeometry.java app/src/test/java/com/iterlocus/pathway/RouteGeometryTest.java
git commit -m "feat: RouteGeometry 补 double 版距离与初始航向"
```

---

### Task 2: `RoutePlayer`（运动引擎）

本次的核心。一个不碰 Android 的纯逻辑类，把「沿折线走了多远、现在在哪、朝哪」全部算出来。服务侧只负责按时间调 `advance`。

**Files:**
- Create: `app/src/main/java/com/iterlocus/pathway/RoutePlayer.java`
- Test: `app/src/test/java/com/iterlocus/pathway/RoutePlayerTest.java`

**Interfaces:**
- Consumes: `RouteGeometry.distanceMeters(double,double,double,double)`、`RouteGeometry.initialBearingDegrees(double,double,double,double)`（Task 1）
- Produces:
  - `RoutePlayer(double[][] wgsPoints, boolean closed, double speedMetersPerSecond)`
  - `void advance(double dtSeconds)`
  - `double[] getPosition()` — `{经度, 纬度}`，无点时 `null`
  - `double getBearing()`、`double getDistanceCovered()`、`double getTotalDistance()`
  - `int getLapCount()`、`double getSpeed()`、`void setSpeed(double)`
  - `boolean isFinished()`

- [ ] **Step 1: 写失败的测试**

创建 `app/src/test/java/com/iterlocus/pathway/RoutePlayerTest.java`：

```java
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
```

- [ ] **Step 2: 跑测试确认失败**

```bash
JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" ./gradlew :app:testDebugUnitTest --tests "com.iterlocus.pathway.RoutePlayerTest"
```

预期：编译失败，`cannot find symbol: class RoutePlayer`。

- [ ] **Step 3: 实现**

创建 `app/src/main/java/com/iterlocus/pathway/RoutePlayer.java`：

```java
package com.iterlocus.pathway;

/**
 * 路线模拟的运动引擎：纯逻辑，与 Android 无关，可在普通 JVM 单元测试里跑。
 *
 * <p><b>坐标一律是 WGS84。</b>入参 {@code double[][]} 每个元素是 {@code {经度, 纬度}}，
 * 正是 {@link MapUtils#bd2wgs(double, double)} 的返回形状。
 *
 * <p>刻意不收 {@code LatLng}：那个类型在本项目里既装 BD09（地图层）也装别的，
 * 不携带坐标系信息；而坐标系混用是本项目最大的坑（见 CLAUDE.md）。
 * 用裸数组至少让「这是服务边界、是 WGS84」在签名上看得见。
 *
 * <p>位置沿折线按弧长推进，段内经纬度线性插值——几百米尺度下误差远小于 1cm，
 * 而用户画出来的本来就是折线，不做曲线平滑。
 */
public final class RoutePlayer {

    private final double[] mLngs;
    private final double[] mLats;
    private final boolean mClosed;
    /** 累积弧长，长度 = 段数 + 1。{@code mCumulative[段数]} 即总长。 */
    private final double[] mCumulative;
    private final double mTotalDistance;

    private double mSpeed;
    private double mDistance;
    private int mLapCount;
    /** 单调前进的游标：当前位置落在第几段。弧长只增不减，不需要二分查找。 */
    private int mSegment;

    /**
     * @param wgsPoints 每个元素 {@code {经度, 纬度}}，<b>WGS84</b>
     * @param closed    闭合路线走完一圈回到起点继续；开环走到末点即结束
     * @param speedMetersPerSecond 初速度，m/s；0 表示暂停
     */
    public RoutePlayer(double[][] wgsPoints, boolean closed, double speedMetersPerSecond) {
        int count = wgsPoints == null ? 0 : wgsPoints.length;
        mLngs = new double[count];
        mLats = new double[count];
        for (int index = 0; index < count; index++) {
            mLngs[index] = wgsPoints[index][0];
            mLats[index] = wgsPoints[index][1];
        }

        // 少于 2 个点无从成段，闭合也无从谈起
        mClosed = closed && count >= 2;
        int segmentCount = mClosed ? count : Math.max(0, count - 1);

        mCumulative = new double[segmentCount + 1];
        for (int segment = 0; segment < segmentCount; segment++) {
            mCumulative[segment + 1] = mCumulative[segment] + segmentLength(segment);
        }
        mTotalDistance = mCumulative[segmentCount];

        setSpeed(speedMetersPerSecond);
    }

    /**
     * 按时间推进。
     *
     * <p>开环：走满总长后夹在末点不再前进。闭合：回绕，永不结束。
     * {@code dtSeconds <= 0} 或速度为 0 时不动。
     */
    public void advance(double dtSeconds) {
        if (dtSeconds <= 0d || mSpeed <= 0d || mTotalDistance <= 0d) {
            return;
        }

        mDistance += mSpeed * dtSeconds;

        if (mClosed) {
            if (mDistance >= mTotalDistance) {
                // 用除法而不是单次取模：单步跨过多圈时圈数才不会丢。
                // 服务侧 dt 有 1 秒上限，正常跑不到这里，但短到几米的闭合路线能。
                mLapCount += (int) (mDistance / mTotalDistance);
                mDistance %= mTotalDistance;
                mSegment = 0;
            }
        } else if (mDistance > mTotalDistance) {
            mDistance = mTotalDistance;
        }

        int segmentCount = segmentCount();
        while (mSegment < segmentCount - 1 && mDistance >= mCumulative[mSegment + 1]) {
            mSegment++;
        }
    }

    /** 当前位置 {@code {经度, 纬度}}（WGS84）。无点时返回 {@code null}。 */
    public double[] getPosition() {
        if (mLngs.length == 0) {
            return null;
        }
        if (segmentCount() == 0) {
            return new double[]{mLngs[0], mLats[0]};
        }

        double start = mCumulative[mSegment];
        double length = mCumulative[mSegment + 1] - start;
        double ratio = length <= 0d ? 0d : (mDistance - start) / length;
        ratio = Math.max(0d, Math.min(1d, ratio));

        int to = (mSegment + 1) % mLngs.length;
        return new double[]{
                mLngs[mSegment] + (mLngs[to] - mLngs[mSegment]) * ratio,
                mLats[mSegment] + (mLats[to] - mLats[mSegment]) * ratio};
    }

    /** 当前线段的航向，度，[0, 360)。点数不足以成段时为 0。 */
    public double getBearing() {
        if (segmentCount() == 0) {
            return 0d;
        }
        int to = (mSegment + 1) % mLngs.length;
        return RouteGeometry.initialBearingDegrees(
                mLngs[mSegment], mLats[mSegment], mLngs[to], mLats[to]);
    }

    /** 本圈已走距离，米。闭合路线每绕完一圈归零。 */
    public double getDistanceCovered() {
        return mDistance;
    }

    public double getTotalDistance() {
        return mTotalDistance;
    }

    /** 闭合路线已完成的圈数；开环恒为 0。 */
    public int getLapCount() {
        return mLapCount;
    }

    public double getSpeed() {
        return mSpeed;
    }

    /** 播放中可改。负值按 0 处理——0 表示暂停。 */
    public void setSpeed(double speedMetersPerSecond) {
        mSpeed = Math.max(0d, speedMetersPerSecond);
    }

    /** 开环路线走满总长即为 true；闭合路线永远为 false。 */
    public boolean isFinished() {
        return !mClosed && mDistance >= mTotalDistance;
    }

    private int segmentCount() {
        return mCumulative.length - 1;
    }

    /** 第 segment 段的长度。最后一段闭合时回绕到首点。 */
    private double segmentLength(int segment) {
        int to = (segment + 1) % mLngs.length;
        return RouteGeometry.distanceMeters(
                mLngs[segment], mLats[segment], mLngs[to], mLats[to]);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" ./gradlew :app:testDebugUnitTest --tests "com.iterlocus.pathway.RoutePlayerTest"
```

预期：PASS，20 个。

- [ ] **Step 5: 跑全量验证三件套**

```bash
JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" ./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

预期：`BUILD SUCCESSFUL`，单元测试 33 + 8 + 20 = **61** 个，0 失败。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/iterlocus/pathway/RoutePlayer.java app/src/test/java/com/iterlocus/pathway/RoutePlayerTest.java
git commit -m "feat: RoutePlayer 路线运动引擎（纯逻辑，可 JVM 单测）"
```

---

### Task 3: 服务侧路线引擎

把引擎接到 `ServiceGo` 的定位线程上。本任务只做**引擎接线**——位置真的会沿路线动起来、能开始能结束、手动瞬移会终止播放。播放期间的摇杆禁用与通知改造在 Task 4。

**Files:**
- Create: `app/src/main/java/com/iterlocus/pathway/RouteProgress.java`
- Modify: `app/src/main/java/com/iterlocus/pathway/service/ServiceGo.java`

**Interfaces:**
- Consumes: `RoutePlayer`（Task 2）、`RouteGeometry.MIN_POINTS_FOR_CLOSE`
- Produces:
  - `RouteProgress`：`getRouteName()`、`isFinished()`、`getSpeed()`、`getDistanceCovered()`、`getTotalDistance()`、`getLapCount()`
  - `ServiceGo.ServiceGoBinder.startRoute(String, double[][], boolean, double) -> boolean`
  - `ServiceGo.ServiceGoBinder.stopRoute()`
  - `ServiceGo.ServiceGoBinder.setRouteSpeed(double)`
  - `ServiceGo.ServiceGoBinder.getRouteProgress() -> RouteProgress | null`
  - `ServiceGo.isAlive() -> boolean`

- [ ] **Step 1: 创建 `RouteProgress`**

```java
package com.iterlocus.pathway;

/**
 * 路线模拟的状态快照。不可变；同一进程内经由 binder 传递，不需要 {@code Parcelable}。
 *
 * <p>三种状态由「是否为 null」加 {@link #isFinished()} 表达：{@code null} = 没有路线在跑；
 * 非 null 且未 finished = 模拟中；非 null 且 finished = 已到达终点，等待用户结束。
 *
 * <p>{@link #getDistanceCovered()} 是<b>本圈</b>已走距离，闭合路线每绕完一圈归零。
 * 界面靠 {@link #getLapCount()} 把这次归零解释清楚，否则用户会当成进度跳回 0 的 bug。
 */
public final class RouteProgress {

    private final String mRouteName;
    private final boolean mFinished;
    private final double mSpeed;
    private final double mDistanceCovered;
    private final double mTotalDistance;
    private final int mLapCount;

    public RouteProgress(String routeName, boolean finished, double speed,
                         double distanceCovered, double totalDistance, int lapCount) {
        mRouteName = routeName == null ? "" : routeName;
        mFinished = finished;
        mSpeed = speed;
        mDistanceCovered = distanceCovered;
        mTotalDistance = totalDistance;
        mLapCount = lapCount;
    }

    /** 正在跑的路线名。界面靠它把选中行对回去（系统重建后唯一的记忆来源）。 */
    public String getRouteName() {
        return mRouteName;
    }

    /** true = 已到达终点。 */
    public boolean isFinished() {
        return mFinished;
    }

    /** m/s */
    public double getSpeed() {
        return mSpeed;
    }

    /** 本圈已走，米。 */
    public double getDistanceCovered() {
        return mDistanceCovered;
    }

    /** 米 */
    public double getTotalDistance() {
        return mTotalDistance;
    }

    /** 闭合路线已完成的圈数；开环恒为 0。 */
    public int getLapCount() {
        return mLapCount;
    }
}
```

- [ ] **Step 2: 给 `ServiceGo` 加字段与常量**

在 `ServiceGo` 的常量区（`HANDLER_MSG_ID` 附近）加：

```java
    /** 单次推进的时间上限，秒。doze / GC 长暂停之后不夹住会一次跳出几百米。 */
    private static final double MAX_TICK_SECONDS = 1.0;

    /** 服务存活标志，供 MainActivity 对账 isMockServStart。 */
    private static volatile boolean sAlive = false;
```

在摇杆相关字段（`private JoyStick mJoyStick;`）后面加：

```java
    /** 当前路线。null 表示没有路线在跑。定位线程读、UI 线程写，故 volatile。 */
    private volatile RoutePlayer mRoutePlayer;
    private volatile String mRouteName;
    /** 上一 tick 的时刻，用于算真实的 dt。 */
    private long mLastTickMs;
    /** 主线程 Handler：摇杆是 View，只能在主线程碰。 */
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
```

新增 import：`android.os.Looper`、`com.iterlocus.pathway.RouteGeometry`、`com.iterlocus.pathway.RoutePlayer`、`com.iterlocus.pathway.RouteProgress`。

- [ ] **Step 3: `onCreate` 置存活标志、`onDestroy` 清掉**

`onCreate` 的 `super.onCreate();` 之后紧接着加：

```java
        sAlive = true;
        // 必须在 initGoLocation() 之前：循环的第一条消息马上就会读它
        mLastTickMs = SystemClock.elapsedRealtime();
```

`onDestroy` 的 `isStop = true;` 之后加：

```java
        sAlive = false;
```

在类末尾（`ServiceGoBinder` 之后）加静态访问器：

```java
    /**
     * 服务是否存活。
     *
     * <p>供 {@code MainActivity} 对账 {@code isMockServStart}——模拟界面也能独立启动本服务，
     * 那个 Activity 私有字段会因此陈旧。顺带修掉「服务被系统杀死后字段仍是 true」的既有隐患。
     */
    public static boolean isAlive() {
        return sAlive;
    }
```

- [ ] **Step 4: 在 tick 里推进路线**

把 `initGoLocation()` 里 `handleMessage` 的 `if (!isStop)` 块改为：

```java
                    if (!isStop) {
                        advanceRoute();
                        setLocationNetwork();
                        setLocationGPS();

                        sendEmptyMessage(HANDLER_MSG_ID);
                    }
```

在 `initGoLocation()` 之后新增：

```java
    /**
     * 按真实经过的时间推进路线。
     *
     * <p>dt 必须用时钟差而不是写死的 0.1：{@code Thread.sleep(100)} 会漂，
     * 几公里的路线上累积误差肉眼可见。上限 {@link #MAX_TICK_SECONDS} 是防
     * doze / GC 长暂停之后一次跳出几百米——那是一条不真实的瞬移轨迹。
     *
     * <p>写回的是摇杆用的同一个「当前位置」单元格，所以 {@code setLocationGPS()} /
     * {@code setLocationNetwork()} 一行都不用改。
     *
     * <p>到达终点后本方法只是不再推进；交还摇杆、改通知文案属于表现层，在
     * onRouteFinished() 里做。
     */
    private void advanceRoute() {
        long now = SystemClock.elapsedRealtime();
        double dt = Math.min((now - mLastTickMs) / 1000.0, MAX_TICK_SECONDS);
        mLastTickMs = now;

        RoutePlayer player = mRoutePlayer;
        if (player == null || player.isFinished()) {
            return;
        }

        player.advance(dt);

        double[] position = player.getPosition();
        if (position == null) {
            return;
        }
        mCurLng = position[0];
        mCurLat = position[1];
        mCurBea = (float) player.getBearing();
        mSpeed = player.getSpeed();
    }

    /** 把摇杆内置地图同步到当前位置。只能在主线程碰这些 View。 */
    private void syncJoyStickToCurrentPosition() {
        final double lng = mCurLng;
        final double lat = mCurLat;
        final double alt = mCurAlt;
        postToMain(() -> {
            if (mJoyStick != null) {
                mJoyStick.setCurrentPosition(lng, lat, alt);
            }
        });
    }

    /** 已在主线程就直接跑，否则投递过去。服务里既有 UI 线程调用也有定位线程调用。 */
    private void postToMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            mMainHandler.post(action);
        }
    }
```

- [ ] **Step 5: 手动干预终止播放**

`ServiceGoBinder.setPosition` 开头加 `stopRoute();`：

```java
        public void setPosition(double lng, double lat, double alt) {
            // 播放中瞬移是自相矛盾的状态：两个写入者抢同一个位置。先结束路线，
            // 让「谁在控制位置」始终只有一个答案。
            stopRoute();

            mLocHandler.removeMessages(HANDLER_MSG_ID);
            mCurLng = lng;
            mCurLat = lat;
            mCurAlt = alt;
            mLocHandler.sendEmptyMessage(HANDLER_MSG_ID);
            mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);
        }
```

- [ ] **Step 6: 加 binder 的四个路线方法**

在 `ServiceGoBinder` 内、`setPosition` 之后加：

```java
        /**
         * 开始沿路线模拟。
         *
         * @param wgsPoints 每个元素 {@code {经度, 纬度}}，<b>WGS84</b>（调用方负责从 BD09 转换）
         * @return 是否成功开始；点数不足 2 或总长为 0 时返回 false，不抛
         */
        public boolean startRoute(String routeName, double[][] wgsPoints,
                                  boolean closed, double speedMps) {
            if (wgsPoints == null || wgsPoints.length < RouteGeometry.MIN_POINTS_FOR_CLOSE) {
                return false;
            }
            RoutePlayer player = new RoutePlayer(wgsPoints, closed, speedMps);
            if (player.getTotalDistance() <= 0d) {
                return false;
            }

            mRoutePlayer = player;
            mRouteName = routeName == null ? "" : routeName;
            // 归零基准时刻，否则第一帧会带上「上次 tick 到现在」的整段间隔
            mLastTickMs = SystemClock.elapsedRealtime();
            return true;
        }

        /** 结束路线模拟。没有路线在跑时什么也不做。 */
        public void stopRoute() {
            if (mRoutePlayer == null) {
                return;
            }
            mRoutePlayer = null;
            mRouteName = null;
            syncJoyStickToCurrentPosition();
        }

        /** 播放中改速度。没有路线在跑时什么也不做。 */
        public void setRouteSpeed(double speedMps) {
            RoutePlayer player = mRoutePlayer;
            if (player != null) {
                player.setSpeed(speedMps);
            }
        }

        /** 当前路线状态；没有路线在跑时返回 null。 */
        public RouteProgress getRouteProgress() {
            RoutePlayer player = mRoutePlayer;
            if (player == null) {
                return null;
            }
            return new RouteProgress(mRouteName, player.isFinished(), player.getSpeed(),
                    player.getDistanceCovered(), player.getTotalDistance(), player.getLapCount());
        }
```

- [ ] **Step 7: 验证三件套**

```bash
JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" ./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

预期：`BUILD SUCCESSFUL`，61 个单元测试 0 失败。

本任务没有新增单测——`ServiceGo` 是 Android `Service`，JVM 单测覆盖不到；引擎逻辑已在 Task 2 单测里钉死。服务侧的验证靠编译 + 审查 + Task 5 之后的真机。

- [ ] **Step 8: 提交**

```bash
git add app/src/main/java/com/iterlocus/pathway/RouteProgress.java app/src/main/java/com/iterlocus/pathway/service/ServiceGo.java
git commit -m "feat: ServiceGo 接入路线模拟（真实 dt、到达即停、瞬移终止播放）"
```

---

### Task 4: 播放期间的表现（摇杆禁用 + 通知）

引擎能跑了，但此刻播放中的摇杆仍能拖、通知里也没有停止入口——而用户整场模拟都在别的 App 里。本任务补上这两块表现。

**Files:**
- Modify: `app/src/main/java/com/iterlocus/pathway/joystick/JoyStick.java`
- Modify: `app/src/main/java/com/iterlocus/pathway/service/ServiceGo.java`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: Task 3 的 `mRoutePlayer`、`startRoute`、`stopRoute`
- Produces:
  - `JoyStick.setInputEnabled(boolean)`、`JoyStick.isInputEnabled() -> boolean`
  - `ServiceGo` 私有 `onRouteFinished()`、`setJoyStickInputEnabled(boolean)`、`buildNotification()`、`updateNotification()`
  - 通知动作字符串 `"StopRoute"`

- [ ] **Step 1: `JoyStick` 加输入开关**

在字段区（`private JoyStickClickListener mListener;` 附近）加：

```java
    /** 输入开关。播放路线期间置 false：位置归路线，摇杆不该再能改它。 */
    private boolean mInputEnabled = true;
```

在 `setListener` 之后加：

```java
    /**
     * 开关摇杆输入。
     *
     * <p>刻意不叫 {@code setEnabled}：{@code JoyStick extends View}，而
     * {@code View.setEnabled(boolean)} 已存在且语义不同（它还牵动框架的 clickable
     * 与 drawable 状态刷新）。覆写框架方法会让人以为调的是 View 那一个。
     *
     * <p>禁用时必须<b>同时变灰</b>：外观不变的禁用态在本项目已经栽过一次
     * （终审 Ruling UI-4，一个 setEnabled(false) 却看不出变化的按钮），
     * 用户看到能拖却没反应，比明确灰掉更糟。
     */
    public void setInputEnabled(boolean enabled) {
        mInputEnabled = enabled;
        if (!enabled) {
            if (mTimer != null) {
                mTimer.cancel();
            }
            isMove = false;
        }
        applyInputEnabledVisual();
    }

    public boolean isInputEnabled() {
        return mInputEnabled;
    }

    private void applyInputEnabledVisual() {
        float alpha = mInputEnabled ? 1f : 0.4f;
        if (mJoystickLayout != null) {
            mJoystickLayout.setAlpha(alpha);
        }
        if (mMapLayout != null) {
            mMapLayout.setAlpha(alpha);
        }
        if (mHistoryLayout != null) {
            mHistoryLayout.setAlpha(alpha);
        }
    }
```

在 `show()` 的 `switch (mCurWin)` **之前**加一行，保证切窗口后灰态仍在：

```java
        applyInputEnabledVisual();
```

- [ ] **Step 2: `JoyStick` 的三处输入守卫**

`processDirection` 是方向输入的**唯一**收口（`RockerView` 与 `ButtonView` 都指向它）。在方法第一行加守卫：

```java
    private void processDirection(boolean auto, double angle, double r) {
        if (!mInputEnabled) {
            return;
        }
        if (r <= 0) {
```

`JoyStickOnTouchListener.onTouch` 开头加守卫（禁用时吃掉事件，窗口不再能被拖走）：

```java
        public boolean onTouch(View view, MotionEvent event) {
            if (!mInputEnabled) {
                return true;
            }
            switch (event.getAction()) {
```

`onPositionInfo` 有两个调用点，各加一道守卫：

**其一**，摇杆地图窗的定位回调（`mListener.onPositionInfo(lngLat[0], lngLat[1], mAltitude);`），在调用前加：

```java
                    if (!mInputEnabled) {
                        return;
                    }
```

**其二**，历史列表项点击处（`mListener.onPositionInfo(Double.parseDouble(wgs84Longitude), Double.parseDouble(wgs84Latitude), mAltitude);`）。

这一处**不能用 `return`**——它后面还有刷新地图的语句（读 `BDLatLngText`、重设 `mCurMapLngLat`），提前返回会把那一段一起跳过，禁用期间历史窗的地图就停在旧位置。改用 `if` 包住那一行：

```java
            if (mInputEnabled) {
                mListener.onPositionInfo(Double.parseDouble(wgs84Longitude), Double.parseDouble(wgs84Latitude), mAltitude);
            }
```

- [ ] **Step 3: `ServiceGo` 侧加权威守卫**

**这一步不能省。** 摇杆有三个子窗口（摇杆 / 地图 / 历史），输入入口分散，界面层的守卫是「让禁用看起来成立」；真正保证位置不被改的是服务这一层——无论输入从哪个子视图来都拦得住。

在 `initJoyStick()` 的监听器里，两个回调开头各加一道：

```java
            @Override
            public void onMoveInfo(double speed, double disLng, double disLat, double angle) {
                // 播放期间位置归路线，摇杆输入一律忽略。权威层：JoyStick 自己也拦，
                // 但那只是让「禁用」在观感上成立，入口分散，靠界面层拦不干净。
                if (mRoutePlayer != null) {
                    return;
                }
                mSpeed = speed;
                // ... 原有内容不变
            }

            @Override
            public void onPositionInfo(double lng, double lat, double alt) {
                if (mRoutePlayer != null) {
                    return;
                }
                mCurLng = lng;
                // ... 原有内容不变
            }
```

- [ ] **Step 4: `ServiceGo` 加摇杆开关与到达处理**

在 `initJoyStick()` 之后加：

```java
    private void setJoyStickInputEnabled(boolean enabled) {
        final boolean value = enabled;
        postToMain(() -> {
            if (mJoyStick != null) {
                mJoyStick.setInputEnabled(value);
            }
        });
    }

    /**
     * 到达终点：位置停在末点，摇杆交还用户，通知改文案。
     *
     * <p>运行在定位线程上，摇杆与通知的落地都走 {@link #postToMain}。
     */
    private void onRouteFinished() {
        syncJoyStickToCurrentPosition();
        setJoyStickInputEnabled(true);
        updateNotification();
    }
```

在 `advanceRoute()` 的末尾（写回 `mSpeed` 之后）补上到达检测：

```java
        if (player.isFinished()) {
            onRouteFinished();
        }
```

并把 `startRoute` / `stopRoute` 的表现调用补上（Task 3 里这两个方法还只有引擎逻辑，此处才接上表现）。

`startRoute` 在 `mLastTickMs = SystemClock.elapsedRealtime();` 之后加：

```java
            setJoyStickInputEnabled(false);
            syncJoyStickToCurrentPosition();
            updateNotification();
```

`stopRoute` 在 `mRouteName = null;` 之后加：

```java
            setJoyStickInputEnabled(true);
            updateNotification();
```

（`stopRoute` 里的 `syncJoyStickToCurrentPosition()` 在 Task 3 已有，此行不必重复添加。）

- [ ] **Step 5: `ServiceGo` 通知改造**

加常量（与既有两个动作常量并列）：

```java
    private static final String SERVICE_GO_NOTE_ACTION_ROUTE_STOP = "StopRoute";
```

把 `initNotification()` 改成注册第三个动作并复用 `buildNotification()`：

```java
    private void initNotification() {
        mActReceiver = new NoteActionReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW);
        filter.addAction(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE);
        filter.addAction(SERVICE_GO_NOTE_ACTION_ROUTE_STOP);
        registerReceiver(mActReceiver, filter);

        NotificationChannel mChannel = new NotificationChannel(SERVICE_GO_NOTE_CHANNEL_ID, SERVICE_GO_NOTE_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (notificationManager != null) {
            notificationManager.createNotificationChannel(mChannel);
        }

        startForeground(SERVICE_GO_NOTE_ID, buildNotification());
    }

    /**
     * 构造前台通知。
     *
     * <p>播放路线时正文换成模拟状态，并多一个「结束路线」动作——用户整场模拟都在别的
     * App 里，没有这个动作就只能切回行屿才能停。
     *
     * <p>措辞刻意不叫「停止模拟」：那会被读成停掉整个 mock 服务。结束路线之后服务继续
     * 在终点维持位置模拟，这是对的——用户此刻正「站」在终点。
     */
    private Notification buildNotification() {
        Intent clickIntent = new Intent(this, MainActivity.class);
        PendingIntent clickPI = PendingIntent.getActivity(this, 1, clickIntent, PendingIntent.FLAG_IMMUTABLE);
        Intent showIntent = new Intent(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW);
        PendingIntent showPendingPI = PendingIntent.getBroadcast(this, 0, showIntent, PendingIntent.FLAG_IMMUTABLE);
        Intent hideIntent = new Intent(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE);
        PendingIntent hidePendingPI = PendingIntent.getBroadcast(this, 0, hideIntent, PendingIntent.FLAG_IMMUTABLE);

        RoutePlayer player = mRoutePlayer;
        String text;
        if (player == null) {
            text = getResources().getString(R.string.app_service_tips);
        } else {
            text = getResources().getString(player.isFinished()
                    ? R.string.app_route_arrived
                    : R.string.app_route_playing);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, SERVICE_GO_NOTE_CHANNEL_ID)
                .setChannelId(SERVICE_GO_NOTE_CHANNEL_ID)
                .setContentTitle(getResources().getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(clickPI)
                .addAction(new NotificationCompat.Action(null, getResources().getString(R.string.note_show), showPendingPI))
                .addAction(new NotificationCompat.Action(null, getResources().getString(R.string.note_hide), hidePendingPI))
                .setSmallIcon(R.mipmap.ic_launcher);

        if (player != null) {
            Intent stopIntent = new Intent(SERVICE_GO_NOTE_ACTION_ROUTE_STOP);
            // 请求码与上面两个错开；即便 action 已经不同，也别复用同一个码
            PendingIntent stopPendingPI = PendingIntent.getBroadcast(this, 3, stopIntent, PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new NotificationCompat.Action(null,
                    getResources().getString(R.string.note_route_stop), stopPendingPI));
        }

        return builder.build();
    }

    /** 刷新前台通知。播放状态一变就调。 */
    private void updateNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(SERVICE_GO_NOTE_ID, buildNotification());
        }
    }
```

`NoteActionReceiver.onReceive` 里加一个分支：

```java
                if (action.equals(SERVICE_GO_NOTE_ACTION_ROUTE_STOP)) {
                    mBinder.stopRoute();
                }
```

- [ ] **Step 6: 加三条字符串**

在 `app/src/main/res/values/strings.xml` 的 `app_route_simulation` 一行附近加：

```xml
    <string name="app_route_playing">正在模拟路线</string>
    <string name="app_route_arrived">已到达终点</string>
    <string name="note_route_stop">结束路线</string>
```

- [ ] **Step 7: 验证三件套**

```bash
JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" ./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

预期：`BUILD SUCCESSFUL`，61 个单元测试 0 失败，lint 0 error。

- [ ] **Step 8: 提交**

```bash
git add app/src/main/java/com/iterlocus/pathway/joystick/JoyStick.java app/src/main/java/com/iterlocus/pathway/service/ServiceGo.java app/src/main/res/values/strings.xml
git commit -m "feat: 播放路线时禁用摇杆并变灰，通知栏加「结束路线」"
```

---

### Task 5: 模拟界面

把 68 行的占位界面换成真正的中控台：选路线、选档位、开始、看进度。

**Files:**
- Modify: `app/src/main/java/com/iterlocus/pathway/RouteSimulationActivity.java`（整体重写）
- Modify: `app/src/main/res/layout/activity_route_simulation.xml`（整体重写）
- Create: `app/src/main/res/layout/route_sim_route_item.xml`
- Create: `app/src/main/res/drawable/bg_route_row.xml`
- Create: `app/src/main/res/color/route_row_text.xml`
- Modify: `app/src/main/res/drawable/bg_tool_chip_accent.xml`（`<shape>` → `<selector>`，补禁用态）
- Modify: `app/src/main/res/menu/menu_nav.xml`
- Modify: `app/src/main/java/com/iterlocus/pathway/MainActivity.java`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: Task 3/4 的 binder 四个路线方法、`ServiceGo.isAlive()`、`DataBaseRoute.queryAll`、`MapUtils.bd2wgs`、`RoutePlayer.getTotalDistance()`、`MainActivity.LNG_MSG_ID/LAT_MSG_ID/ALT_MSG_ID`
- Produces: 无（终端界面）

- [ ] **Step 1: 新增两个选中态资源**

`app/src/main/res/drawable/bg_route_row.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 路线列表行的选中态。
     不能复用 bg_tool_chip_toggle：那个的键是 state_checked，只有 Checkable 会置上；
     而 ListView 的行是普通 LinearLayout，setActivated 置的是 state_activated，键对不上，
     选中会毫无视觉变化。配色与悬浮片保持一致：选中 = 主题色实底 + 白字。 -->
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_activated="true">
        <shape android:shape="rectangle">
            <solid android:color="@color/colorPrimary" />
            <corners android:radius="12dp" />
            <stroke
                android:width="1dp"
                android:color="#59FFFFFF" />
        </shape>
    </item>
    <item>
        <shape android:shape="rectangle">
            <solid android:color="#E0FFFFFF" />
            <corners android:radius="12dp" />
            <stroke
                android:width="1dp"
                android:color="#33000000" />
        </shape>
    </item>
</selector>
```

`app/src/main/res/color/route_row_text.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 路线列表行的文字色。View.setActivated 会经 ViewGroup.dispatchSetActivated
     传播到子视图，所以行内两个 TextView 共用这一个文件就能跟着变。 -->
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_activated="true" android:color="#FFFFFF" />
    <item android:color="#212121" />
</selector>
```

- [ ] **Step 2: 给主操作片补禁用态**

`bg_tool_chip_accent.xml` 现在是纯 `<shape>`，`setEnabled(false)` 外观不变。模拟界面的开始按钮在「库里没有路线」时要置灰——**外观不变的禁用态正是本项目栽过的那个坑**（Ruling UI-4）。整体替换为：

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 主操作片：主题色实底，与其余片区分。
     必须带禁用态：模拟界面的「开始模拟」在没有路线可选时置灰，
     若外观不变，用户看到的就是一个能点却没反应的按钮——本项目已栽过一次。 -->
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_enabled="false">
        <shape android:shape="rectangle">
            <solid android:color="#99FFFFFF" />
            <corners android:radius="22dp" />
            <stroke
                android:width="1dp"
                android:color="#4DFFFFFF" />
        </shape>
    </item>
    <item android:state_pressed="true">
        <shape android:shape="rectangle">
            <solid android:color="#CC00695C" />
            <corners android:radius="22dp" />
        </shape>
    </item>
    <item>
        <shape android:shape="rectangle">
            <solid android:color="@color/colorPrimary" />
            <corners android:radius="22dp" />
        </shape>
    </item>
</selector>
```

同时把使用它的 `activity_route_simulation.xml` 主按钮的文字色改为 `@color/chip_text`（它带禁用态变淡），而绘制界面的「完成绘制」按钮用的是写死的 `#FFFFFF`，保持不变、不受影响。

- [ ] **Step 3: 列表行布局**

`app/src/main/res/layout/route_sim_route_item.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:background="@drawable/bg_route_row"
    android:orientation="vertical"
    android:paddingHorizontal="16dp"
    android:paddingVertical="12dp">

    <TextView
        android:id="@+id/route_sim_item_name"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:ellipsize="end"
        android:maxLines="1"
        android:textColor="@color/route_row_text"
        android:textSize="16sp"
        android:textStyle="bold" />

    <TextView
        android:id="@+id/route_sim_item_meta"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="2dp"
        android:textColor="@color/route_row_text"
        android:textSize="13sp" />

</LinearLayout>
```

- [ ] **Step 4: 主布局**

`app/src/main/res/layout/activity_route_simulation.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp">

    <TextView
        android:id="@+id/route_sim_status"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="@string/route_sim_status_idle"
        android:textSize="16sp"
        android:textStyle="bold" />

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:text="@string/route_sim_list_title"
        android:textSize="14sp"
        android:textStyle="bold" />

    <TextView
        android:id="@+id/route_sim_empty"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:text="@string/route_sim_empty"
        android:textSize="14sp"
        android:visibility="gone" />

    <ListView
        android:id="@+id/route_sim_list"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_marginTop="8dp"
        android:layout_weight="1"
        android:divider="@null"
        android:dividerHeight="8dp" />

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="12dp"
        android:gravity="center_vertical"
        android:orientation="horizontal">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/route_sim_speed_label"
            android:textSize="14sp" />

        <RadioGroup
            android:id="@+id/route_sim_speed_group"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="8dp"
            android:orientation="horizontal">

            <RadioButton
                android:id="@+id/route_sim_speed_walk"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:background="@drawable/bg_tool_chip_toggle"
                android:button="@null"
                android:checked="true"
                android:elevation="4dp"
                android:paddingHorizontal="14dp"
                android:paddingVertical="8dp"
                android:text="@string/route_sim_speed_walk"
                android:textColor="@color/chip_text_toggle" />

            <RadioButton
                android:id="@+id/route_sim_speed_run"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginStart="8dp"
                android:background="@drawable/bg_tool_chip_toggle"
                android:button="@null"
                android:elevation="4dp"
                android:paddingHorizontal="14dp"
                android:paddingVertical="8dp"
                android:text="@string/route_sim_speed_run"
                android:textColor="@color/chip_text_toggle" />

            <RadioButton
                android:id="@+id/route_sim_speed_bike"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginStart="8dp"
                android:background="@drawable/bg_tool_chip_toggle"
                android:button="@null"
                android:elevation="4dp"
                android:paddingHorizontal="14dp"
                android:paddingVertical="8dp"
                android:text="@string/route_sim_speed_bike"
                android:textColor="@color/chip_text_toggle" />
        </RadioGroup>
    </LinearLayout>

    <TextView
        android:id="@+id/route_sim_primary"
        android:layout_width="match_parent"
        android:layout_height="48dp"
        android:layout_marginTop="12dp"
        android:background="@drawable/bg_tool_chip_accent"
        android:clickable="true"
        android:elevation="4dp"
        android:focusable="true"
        android:gravity="center"
        android:text="@string/route_sim_start"
        android:textColor="@color/chip_text"
        android:textSize="16sp"
        android:textStyle="bold" />

    <TextView
        android:id="@+id/route_sim_content"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="12dp"
        android:maxLines="6"
        android:textIsSelectable="true"
        android:textSize="12sp" />

</LinearLayout>
```

- [ ] **Step 5: 加字符串**

在 `strings.xml` 里（`route_sim_*` 那一段）加：

```xml
    <string name="route_sim_list_title">选择路线</string>
    <string name="route_sim_empty">还没有路线，先去侧滑菜单的「绘制路线」画一条</string>
    <string name="route_sim_item_meta">%1$d 点 · %2$s · %3$s</string>
    <string name="route_sim_closed">闭合</string>
    <string name="route_sim_open">开环</string>
    <string name="route_sim_speed_label">速度</string>
    <string name="route_sim_speed_walk">步行</string>
    <string name="route_sim_speed_run">跑步</string>
    <string name="route_sim_speed_bike">骑行</string>
    <string name="route_sim_start">开始模拟</string>
    <string name="route_sim_stop">结束路线</string>
    <string name="route_sim_need_selection">请先选一条路线</string>
    <string name="route_sim_start_failed">这条路线无法模拟（点数不足或长度为零）</string>
    <string name="route_sim_status_idle">未开始</string>
    <string name="route_sim_status_arrived">已到达终点</string>
    <string name="route_sim_status_playing">模拟中 · 已走 %1$s / %2$s</string>
    <string name="route_sim_status_playing_lap">模拟中 · 第 %1$d 圈 · 本圈 %2$s / %3$s</string>
    <string name="route_sim_meters">%1$d 米</string>
    <string name="route_sim_kilometers">%1$.1f 公里</string>
```

原有的 `route_sim_placeholder`、`route_sim_received` 保留——重写后的界面仍在用 `route_sim_received`。`route_sim_placeholder` 重写后不再被引用，**留着**（一条无害的 lint `UnusedResources` 警告，与绘制界面那版对同类字符串的处理一致）。

- [ ] **Step 6: 重写 `RouteSimulationActivity`**

整体替换文件内容：

```java
package com.iterlocus.pathway;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.preference.PreferenceManager;

import com.baidu.mapapi.model.LatLng;
import com.elvishew.xlog.XLog;
import com.iterlocus.pathway.database.DataBaseRoute;
import com.iterlocus.pathway.service.ServiceGo;
import com.iterlocus.pathway.utils.GoUtils;
import com.iterlocus.pathway.utils.MapUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 模拟路线。
 *
 * <p>选一条已保存的路线、选速度档位、开始模拟。真正的移动在 {@link ServiceGo} 里跑——
 * 用户按下开始之后一定会切走到目标 App，任何建立在 Activity 上的定时器都会在后台被冻结。
 *
 * <p>进度靠轮询 {@link ServiceGo.ServiceGoBinder#getRouteProgress()}，不注册回调：
 * 轮询不会在 Activity 销毁时泄漏监听器，系统重建后也自然接上，服务始终是唯一事实源。
 * 重建后靠快照里的路线名把选中行、档位、按钮文案全部对回去。
 *
 * <p>本界面<b>不解析 NFC 卡片</b>，只把收到的东西原样列出来供核对。
 * 那三个字段的解析是后续版本的事。
 */
public class RouteSimulationActivity extends BaseActivity {

    /** NFC 卡片上的原始 URL，未做任何加工。 */
    public static final String EXTRA_CARD_URL = "EXTRA_CARD_URL";
    /** 卡片携带的包名，可能为空。 */
    public static final String EXTRA_CARD_PACKAGE = "EXTRA_CARD_PACKAGE";
    /** 来源标记，由 NFC 模块填入，是自由字符串，不要按穷举写判断。 */
    public static final String EXTRA_SOURCE = "EXTRA_SOURCE";

    private static final long PROGRESS_POLL_MS = 500L;

    /** 列表的一行：原始配置 + 换算后的 WGS84 点集 + WGS84 总长。 */
    private static final class RouteRow {
        final RouteConfig config;
        final double[][] wgsPoints;
        final double totalMeters;

        RouteRow(RouteConfig config, double[][] wgsPoints, double totalMeters) {
            this.config = config;
            this.wgsPoints = wgsPoints;
            this.totalMeters = totalMeters;
        }
    }

    private SharedPreferences mPreferences;
    private SQLiteDatabase mRouteDb;

    private TextView mStatusText;
    private TextView mPrimaryButton;
    private TextView mEmptyText;
    private ListView mRouteList;
    private RadioGroup mSpeedGroup;

    private final List<RouteRow> mRows = new ArrayList<>();
    private RouteListAdapter mAdapter;
    private int mSelectedIndex = -1;
    private double mSelectedSpeed = 1.2d;
    /** applySpeedSelection() 里的 check() 会回调监听器，用它挡住自触发。 */
    private boolean mApplyingSpeed;

    private ServiceGo.ServiceGoBinder mServiceBinder;
    private boolean mBound;
    /** 已发起 startRoute 的意图但绑定还没完成，onServiceConnected 里补发。 */
    private boolean mPendingStart;

    private final Handler mPollHandler = new Handler(Looper.getMainLooper());
    private final Runnable mPollTask = new Runnable() {
        @Override
        public void run() {
            refreshProgress();
            mPollHandler.postDelayed(this, PROGRESS_POLL_MS);
        }
    };

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mServiceBinder = (ServiceGo.ServiceGoBinder) service;
            mBound = true;
            if (mPendingStart) {
                mPendingStart = false;
                if (mSelectedIndex >= 0 && mSelectedIndex < mRows.size()) {
                    beginRoute(mRows.get(mSelectedIndex));
                }
            }
            refreshProgress();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mServiceBinder = null;
            mBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(getResources().getColor(R.color.colorPrimary, this.getTheme()));

        setContentView(R.layout.activity_route_simulation);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        mPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        mRouteDb = new DataBaseRoute(getApplicationContext()).getWritableDatabase();

        mStatusText = findViewById(R.id.route_sim_status);
        mPrimaryButton = findViewById(R.id.route_sim_primary);
        mEmptyText = findViewById(R.id.route_sim_empty);
        mRouteList = findViewById(R.id.route_sim_list);
        mSpeedGroup = findViewById(R.id.route_sim_speed_group);

        showReceivedCardFields();

        mAdapter = new RouteListAdapter();
        mRouteList.setAdapter(mAdapter);
        mRouteList.setOnItemClickListener((parent, view, position, id) -> selectRow(position));

        mSelectedSpeed = speedWalk();
        mSpeedGroup.setOnCheckedChangeListener((group, checkedId) -> onSpeedChipChanged(checkedId));

        mPrimaryButton.setOnClickListener(v -> onPrimaryClicked());

        loadRoutes();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 只在服务已经活着时才绑定：bindService 配 BIND_AUTO_CREATE 会**创建**服务，
        // 而 ServiceGo.onCreate 会装 test provider 并起前台通知——光打开这个界面就把
        // 模拟位置服务启动了，显然不是用户要的。
        // 服务没活着就不可能有路线在跑，直接按「未开始」显示即可。
        if (!mBound && ServiceGo.isAlive()) {
            bindService(new Intent(this, ServiceGo.class), mConnection, Context.BIND_AUTO_CREATE);
        }
        mPollHandler.post(mPollTask);
    }

    @Override
    protected void onPause() {
        mPollHandler.removeCallbacks(mPollTask);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mPollHandler.removeCallbacks(mPollTask);

        if (mBound) {
            unbindService(mConnection);
            mBound = false;
        }
        if (mRouteDb != null) {
            mRouteDb.close();
        }

        // 刻意不停服务：用户切走之后位置要继续移动，那正是本功能的全部意义
        super.onDestroy();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /*===== NFC 交接字段 =====*/

    private void showReceivedCardFields() {
        Intent intent = getIntent();
        StringBuilder builder = new StringBuilder();
        builder.append(getResources().getString(R.string.route_sim_received)).append("\n\n");
        builder.append(EXTRA_CARD_URL).append(":\n").append(orDash(intent.getStringExtra(EXTRA_CARD_URL))).append("\n\n");
        builder.append(EXTRA_CARD_PACKAGE).append(":\n").append(orDash(intent.getStringExtra(EXTRA_CARD_PACKAGE))).append("\n\n");
        builder.append(EXTRA_SOURCE).append(":\n").append(orDash(intent.getStringExtra(EXTRA_SOURCE)));

        TextView content = findViewById(R.id.route_sim_content);
        content.setText(builder.toString());
    }

    private String orDash(String value) {
        return value == null || value.isEmpty() ? "—" : value;
    }

    /*===== 路线列表 =====*/

    private void loadRoutes() {
        mRows.clear();

        if (mRouteDb != null) {
            List<RouteConfig> configs = DataBaseRoute.queryAll(mRouteDb);
            for (RouteConfig config : configs) {
                mRows.add(buildRow(config));
            }
        } else {
            XLog.e("ROUTE_SIM: ERROR - 路线库打不开");
        }

        if (mSelectedIndex >= mRows.size()) {
            mSelectedIndex = -1;
        }

        mEmptyText.setVisibility(mRows.isEmpty() ? View.VISIBLE : View.GONE);
        mPrimaryButton.setEnabled(!mRows.isEmpty());
        mAdapter.notifyDataSetChanged();
        refreshProgress();
    }

    /**
     * 建一行：把 BD09 点集一次性换成 WGS84，并用引擎自己算出总长。
     *
     * <p>总长交给 {@link RoutePlayer} 而不是另写一份求和，是为了让列表显示的数字
     * 与真正要走的那条线严格一致——两份算法迟早会漂。
     */
    private RouteRow buildRow(RouteConfig config) {
        List<LatLng> points = config.getPoints();
        double[][] wgs = new double[points.size()][];
        for (int index = 0; index < points.size(); index++) {
            LatLng point = points.get(index);
            // MapUtils 入参是 (经度, 纬度)，返回 {经度, 纬度}；LatLng 构造是 (纬度, 经度)
            wgs[index] = MapUtils.bd2wgs(point.longitude, point.latitude);
        }
        double total = new RoutePlayer(wgs, config.isClosed(), 0d).getTotalDistance();
        return new RouteRow(config, wgs, total);
    }

    private void selectRow(int index) {
        if (index == mSelectedIndex) {
            return;
        }
        mSelectedIndex = index;
        mAdapter.notifyDataSetChanged();
    }

    /**
     * 按名字把选中行对回去。系统重建后界面没有任何「我刚跑了哪条」的记忆，
     * 只有轮询回来的快照，这是唯一的恢复途径。
     */
    private void selectRowByName(String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        for (int index = 0; index < mRows.size(); index++) {
            if (name.equals(mRows.get(index).config.getName())) {
                selectRow(index);
                return;
            }
        }
    }

    /** 行的选中态靠 setActivated，由 bg_route_row / route_row_text 的 state_activated 键接住。 */
    private final class RouteListAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mRows.size();
        }

        @Override
        public Object getItem(int position) {
            return mRows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(RouteSimulationActivity.this)
                        .inflate(R.layout.route_sim_route_item, parent, false);
            }

            RouteRow item = mRows.get(position);
            TextView name = row.findViewById(R.id.route_sim_item_name);
            TextView meta = row.findViewById(R.id.route_sim_item_meta);

            name.setText(item.config.getName());
            meta.setText(getResources().getString(R.string.route_sim_item_meta,
                    item.config.getPointCount(),
                    getResources().getString(item.config.isClosed()
                            ? R.string.route_sim_closed
                            : R.string.route_sim_open),
                    formatDistance(item.totalMeters)));

            // 回收复用，所以两个方向都要显式设
            row.setActivated(position == mSelectedIndex);
            return row;
        }
    }

    /*===== 档位 =====*/

    private void onSpeedChipChanged(int checkedId) {
        if (mApplyingSpeed) {
            return;
        }
        mSelectedSpeed = speedForChip(checkedId);
        // 播放中改档位：改的是同一个字段，引擎下一帧生效
        if (mServiceBinder != null) {
            mServiceBinder.setRouteSpeed(mSelectedSpeed);
        }
    }

    private void applySpeedSelection() {
        int checkedId = R.id.route_sim_speed_walk;
        if (Math.abs(mSelectedSpeed - speedRun()) < 0.01d) {
            checkedId = R.id.route_sim_speed_run;
        } else if (Math.abs(mSelectedSpeed - speedBike()) < 0.01d) {
            checkedId = R.id.route_sim_speed_bike;
        }
        mApplyingSpeed = true;
        mSpeedGroup.check(checkedId);
        mApplyingSpeed = false;
    }

    private double speedForChip(int checkedId) {
        if (checkedId == R.id.route_sim_speed_run) {
            return speedRun();
        }
        if (checkedId == R.id.route_sim_speed_bike) {
            return speedBike();
        }
        return speedWalk();
    }

    private double speedWalk() {
        return readSpeedPreference("setting_walk", R.string.setting_walk_default, 1.2d);
    }

    private double speedRun() {
        return readSpeedPreference("setting_run", R.string.setting_run_default, 3.6d);
    }

    private double speedBike() {
        return readSpeedPreference("setting_bike", R.string.setting_bike_default, 10.0d);
    }

    /** 读档位速度：与摇杆同一套偏好键与缺省值，读不到就用兜底值。 */
    private double readSpeedPreference(String key, int defaultResId, double fallback) {
        try {
            return Double.parseDouble(
                    mPreferences.getString(key, getResources().getString(defaultResId)));
        } catch (NumberFormatException e) {
            XLog.e("ROUTE_SIM: ERROR - readSpeedPreference");
            return fallback;
        }
    }

    /*===== 开始 / 结束 =====*/

    private void onPrimaryClicked() {
        if (currentProgress() != null) {
            stopSimulation();
        } else {
            startSimulation();
        }
    }

    private RouteProgress currentProgress() {
        return mServiceBinder == null ? null : mServiceBinder.getRouteProgress();
    }

    private void startSimulation() {
        if (mSelectedIndex < 0 || mSelectedIndex >= mRows.size()) {
            GoUtils.DisplayToast(this, getResources().getString(R.string.route_sim_need_selection));
            return;
        }

        // 与 MainActivity.doGoLocation() 同一套预检，顺序也一致
        if (!GoUtils.isNetworkAvailable(this)) {
            GoUtils.DisplayToast(this, getResources().getString(R.string.app_error_network));
            return;
        }
        if (!GoUtils.isGpsOpened(this)) {
            GoUtils.showEnableGpsDialog(this);
            return;
        }
        if (!Settings.canDrawOverlays(getApplicationContext())) {
            GoUtils.showEnableFloatWindowDialog(this);
            XLog.e("ROUTE_SIM: ERROR - 无悬浮窗权限");
            return;
        }
        if (!GoUtils.isAllowMockLocation(this)) {
            GoUtils.showEnableMockLocationDialog(this);
            XLog.e("ROUTE_SIM: ERROR - 无模拟位置权限");
            return;
        }

        RouteRow row = mRows.get(mSelectedIndex);

        // 服务可能没在跑，自己起。把路线首点作为 extras 传进去——ServiceGo.onStartCommand
        // 会用它们初始化位置单元格，不传就落到 DEFAULT_LAT/DEFAULT_LNG（36.66, 117.03），
        // 界面会先闪一下那个坐标才被路线第一帧覆盖。
        Intent intent = new Intent(this, ServiceGo.class);
        if (row.wgsPoints.length > 0) {
            intent.putExtra(MainActivity.LNG_MSG_ID, row.wgsPoints[0][0]);
            intent.putExtra(MainActivity.LAT_MSG_ID, row.wgsPoints[0][1]);
        }
        intent.putExtra(MainActivity.ALT_MSG_ID, currentAltitude());
        startForegroundService(intent);

        if (mServiceBinder != null) {
            beginRoute(row);
        } else {
            // 绑定是异步的，真正的 startRoute 在 onServiceConnected 里补发
            mPendingStart = true;
            bindService(new Intent(this, ServiceGo.class), mConnection, Context.BIND_AUTO_CREATE);
        }
    }

    private void beginRoute(RouteRow row) {
        if (mServiceBinder == null) {
            return;
        }
        boolean started = mServiceBinder.startRoute(
                row.config.getName(), row.wgsPoints, row.config.isClosed(), mSelectedSpeed);
        if (!started) {
            GoUtils.DisplayToast(this, getResources().getString(R.string.route_sim_start_failed));
            return;
        }

        // WiFi 开着会让被 mock 的位置弹回真实位置，是本项目的已知限制。
        // 每次瞬移后都会警告，开始模拟同理。
        if (GoUtils.isWifiEnabled(this)) {
            GoUtils.showDisableWifiDialog(this);
        }
        refreshProgress();
    }

    private void stopSimulation() {
        if (mServiceBinder != null) {
            mServiceBinder.stopRoute();
        }
        refreshProgress();
    }

    private double currentAltitude() {
        try {
            return Double.parseDouble(mPreferences.getString("setting_altitude", "55.0"));
        } catch (NumberFormatException e) {
            XLog.e("ROUTE_SIM: ERROR - currentAltitude");
            return 55.0d;
        }
    }

    /*===== 进度 =====*/

    private void refreshProgress() {
        RouteProgress progress = currentProgress();

        if (progress == null) {
            mStatusText.setText(R.string.route_sim_status_idle);
            mPrimaryButton.setText(R.string.route_sim_start);
            return;
        }

        mPrimaryButton.setText(R.string.route_sim_stop);

        // 系统重建后靠这里把选中行与档位对回去
        selectRowByName(progress.getRouteName());
        if (Math.abs(mSelectedSpeed - progress.getSpeed()) > 0.01d) {
            mSelectedSpeed = progress.getSpeed();
            applySpeedSelection();
        }

        if (progress.isFinished()) {
            mStatusText.setText(R.string.route_sim_status_arrived);
            return;
        }

        String covered = formatDistance(progress.getDistanceCovered());
        String total = formatDistance(progress.getTotalDistance());
        if (progress.getLapCount() > 0) {
            // 闭合路线每绕完一圈「已走」会归零，圈数把那件事解释清楚；
            // 不写出来用户会当成进度跳回 0 的 bug
            mStatusText.setText(getResources().getString(
                    R.string.route_sim_status_playing_lap,
                    progress.getLapCount() + 1, covered, total));
        } else {
            mStatusText.setText(getResources().getString(
                    R.string.route_sim_status_playing, covered, total));
        }
    }

    /** 米或公里。1 公里以下用米，避免「0.1 公里」这种读不出量级的显示。 */
    private String formatDistance(double meters) {
        if (meters < 1000d) {
            return getResources().getString(R.string.route_sim_meters,
                    (int) Math.round(meters));
        }
        return getResources().getString(R.string.route_sim_kilometers, meters / 1000d);
    }
}
```

- [ ] **Step 7: 加入口与主界面状态对账**

`app/src/main/res/menu/menu_nav.xml` 的 `nav_route_draw` 一项之后加：

```xml
        <item
            android:id="@+id/nav_route_sim"
            android:icon="@drawable/ic_menu_nfc"
            android:title="@string/nav_menu_route_sim" />
```

`strings.xml` 加：

```xml
    <string name="nav_menu_route_sim">模拟路线</string>
```

`MainActivity` 的导航分支里，紧随 `nav_route_draw` 那个 `else if` 之后加：

```java
            } else if (id == R.id.nav_route_sim) {
                Intent intent = new Intent(MainActivity.this, RouteSimulationActivity.class);

                startActivity(intent);
```

`MainActivity.onResume()` 里加对账（`super.onResume()` 之后）：

```java
        // 模拟界面也能独立启动 ServiceGo，那时本 Activity 的 isMockServStart 是陈旧的。
        // 顺带修掉「服务被系统杀死后字段仍是 true」的既有隐患。
        isMockServStart = ServiceGo.isAlive();
```

> **警示：这一段已废弃，不要照做。** `isMockServStart` 同时守着 `stopGoLocation()` 与 `onDestroy()` 里的两处 `unbindService`（它是「MainActivity 绑定过服务」的代理），而 `mServiceBinder` 只在 MainActivity 自己的连接回调里赋值。按 `isAlive()` 直接赋值会造成「字段为 true 但没绑定」：解绑未注册的连接抛 `IllegalArgumentException`，FAB 分支再解引用空 binder 崩溃。正解是拆开「是否绑定」与「服务是否存活」，**本分支未做**，见 `CLAUDE.md` 的「已知缺陷」一节。

若 `MainActivity` 尚未 import `ServiceGo`，补上 `import com.iterlocus.pathway.service.ServiceGo;`。

- [ ] **Step 8: 验证三件套**

```bash
JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" ./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

预期：`BUILD SUCCESSFUL`，61 个单元测试 0 失败。lint 允许既有的 6 条 warning 加上 `route_sim_placeholder` 这条新的 `UnusedResources`；**error 必须为 0**。

- [ ] **Step 9: 真机截图验收**

编译与 lint 覆盖不到布局与配色。装到设备上，进「模拟路线」，逐项确认。

```bash
adb install -r app/build/outputs/apk/debug/Pathway_1.0.0_arm64-v8a_debug.apk
# WelcomeActivity 是启动页；MainActivity 不是 launcher，且 RouteSimulationActivity
# 是 exported="false"，adb 都直接拉不起来，只能人工点进去
adb shell monkey -p com.iterlocus.pathway -c android.intent.category.LAUNCHER 1
# 人工：主界面 → 侧滑菜单 → 模拟路线，然后截图
adb exec-out screencap -p > sim-screen.png
```

要看清的：路线行是否读得出来（名称 / 点数 / 闭合 / 总长）；**选中一行后背景是否确实变主题色、文字变白**——这是 `state_activated` 那条链路唯一的验证方式，也是本次界面里最容易静默失效的地方（键名写错就完全没反应，编译和 lint 都不会报）；档位片的选中态；库里没有路线时列表的空态与「开始模拟」的置灰外观。

若库里一条路线都没有，先去「绘制路线」画一条最短的（两个点即可）再回来——否则空态之外的项都验不到。

- [ ] **Step 10: 提交**

```bash
git add app/src/main/java/com/iterlocus/pathway/RouteSimulationActivity.java app/src/main/res/layout/activity_route_simulation.xml app/src/main/res/layout/route_sim_route_item.xml app/src/main/res/drawable/bg_route_row.xml app/src/main/res/color/route_row_text.xml app/src/main/res/drawable/bg_tool_chip_accent.xml app/src/main/res/menu/menu_nav.xml app/src/main/java/com/iterlocus/pathway/MainActivity.java app/src/main/res/values/strings.xml
git commit -m "feat: 模拟路线界面（选路线、选档位、轮询进度、侧滑入口）"
```

---

### Task 6: 补 CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: 前五个任务的全部产出
- Produces: 无

- [ ] **Step 1: 在 `### 绘制路线` 一节之后新增 `### 路线模拟` 一节**

内容要覆盖（用中文，与上下文风格一致）：

- 引擎在 `ServiceGo` 而不是 Activity 里，**为什么**：用户按下开始后一定切走，Activity 定时器会被冻结；服务已有 10Hz 循环与位置单元格，摇杆的移动本质就是改它，所以推送代码无需改动
- `RoutePlayer` 是纯逻辑、只认 **WGS84** 的 `double[][]`（每个元素 `{经度, 纬度}`），刻意不收不携带坐标系信息的 `LatLng`；BD09→WGS84 在载入列表时一次性完成
- `dt` 用 `SystemClock.elapsedRealtime()` 的差值并夹在 `MAX_TICK_SECONDS`，不要写死 0.1
- 手动干预（`setPosition`）会终止播放；播放期间摇杆输入在 **ServiceGo 的两个 listener 回调**里被忽略（权威层），`JoyStick.setInputEnabled` 只是让禁用看起来成立
- 闭合路线无限循环且 `getDistanceCovered()` 每圈归零，所以进度显示必须带 `getLapCount()`
- 进度靠轮询不靠回调；`RouteProgress.getRouteName()` 是系统重建后恢复界面状态的唯一来源
- 列表行选中态用 `state_activated`，**不能**复用键在 `state_checked` 的 `bg_tool_chip_toggle`
- `bindService` 配 `BIND_AUTO_CREATE` 会**创建**服务；模拟界面因此只在 `ServiceGo.isAlive()` 时才在 `onResume` 里绑定
- 启动服务时必须把路线首点作为 extras 传入，否则 `onStartCommand` 会先把位置扔到 `DEFAULT_LAT/DEFAULT_LNG`
- 待办：NFC 卡片 URL 的解析尚未做

- [ ] **Step 2: 校验没有破坏格式**

```bash
JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" ./gradlew :app:assembleDebug
```

预期：`BUILD SUCCESSFUL`（改文档不涉及构建，跑一次确认工作区没被顺手改坏）。

- [ ] **Step 3: 提交**

```bash
git add CLAUDE.md
git commit -m "docs: CLAUDE.md 补路线模拟一节"
```

---

## 完成之后

**必须真机验证的一条**：按下开始模拟后**切到别的 App**（或锁屏），位置是否继续移动。这是整个架构选择的立足点——如果它不成立，把引擎放进服务就失去了全部意义。

其余装机验收项：选路线是否顺手、档位切换是否在播放中生效、摇杆在播放期间是否确实拖不动且看得出是灰的、到达终点是否停住并提示、通知栏「结束路线」是否管用、**旋转屏幕后选中行与按钮状态是否对得回去**（验证 `getRouteName()` 那套恢复逻辑的唯一办法）。

延后项（不在本计划范围）：解析 NFC 卡片 URL、路线的编辑与删除、播放期间的实时轨迹回放、速度自由输入。

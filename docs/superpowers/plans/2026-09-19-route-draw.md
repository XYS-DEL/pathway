# 绘制路线 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在百度地图上手动绘制路线（点绘制 / 线绘制、可闭合、可按总长等距加密），保存为本地配置供后续线路模拟调用。

**Architecture:** 纯逻辑（几何、名称校验）与 Android 代码彻底分离，前者先行 TDD；绘制层是一个盖在 `MapView` 上的透明自定义 `View`，靠 `Projection` 做屏幕↔经纬度换算；持久化沿用项目既有的 `SQLiteOpenHelper` 模式，点集序列化成 JSON 存一个 TEXT 列。

**Tech Stack:** Java 11、compileSdk 32 / minSdk 27、百度地图 SDK（`app/libs/BaiduLBS_Android.jar`）、JUnit 4.13.2、SQLite。

**Spec:** `docs/superpowers/specs/2026-09-19-route-draw-design.md`

## Global Constraints

- 包根 `com.iterlocus.pathway`；Activity 放根包，SQLite 助手放 `com.iterlocus.pathway.database`。
- 注释与提交信息用中文；用户可见文案进 `res/values/strings.xml`，沿用 `app_*` 前缀。
- 出错记 `XLog.e("ROUTE: ERROR - <method>")` 并降级，**不抛异常、不崩溃**。此约束作用于**运行时数据路径**（查询、插入、编解码）。
  **DDL 例外**：`onCreate`/`onUpgrade` 建表失败时，记日志后**照抛**——建表失败是编程错误而非运行时读写失败（spec 的错误处理表只覆盖「数据库读写失败」），静默吞掉会让此后每次保存都无声失败，比崩更难查。详见 `DataBaseRoute` 的实现与 `CLAUDE.md`。
- 路线坐标一律 **BD09**（地图原生）。模拟侧调用时才转 WGS84。
- `MapUtils` 入参顺序是 **(经度, 纬度)**；`LatLng` 构造是 **(纬度, 经度)**。写反偏 500 米且不报错。
- 「密化」的 N 是**新增点数量**，不是分段数：总长等分 N+1 段。
- 闭合最少 **2 个点**；两种绘制模式都支持闭合。
- 同名路线**拒绝写入**，不覆盖、不自动改名。
- `RouteDrawActivity` 保持**默认启动模式**，并转发 `MapView` 的 `onResume`/`onPause`/`onDestroy`。
- 每步跑测试的命令：`./gradlew :app:testDebugUnitTest --tests "com.iterlocus.pathway.<类名>"`

## 已核实的外部事实（不要重新怀疑）

- `LatLng(double, double)` 可在普通 JVM 单元测试中构造——字节码只调 `Double.isNaN/isInfinite` 后赋字段，无 Android 调用。已有守卫测试 `LatLngProbeTest`。
- `UiSettings.setAllGesturesEnabled(boolean)`、`Projection.fromScreenLocation(Point)`、`Projection.toScreenLocation(LatLng)`、`BaiduMap.getProjection()/getUiSettings()/setOnMapStatusChangeListener()` 均存在。
- `BaiduMap.OnMapStatusChangeListener` 有 **4 个抽象方法**，无默认实现，必须全写：`onMapStatusChangeStart(MapStatus)`、`onMapStatusChangeStart(MapStatus, int)`、`onMapStatusChange(MapStatus)`、`onMapStatusChangeFinish(MapStatus)`。

---

### Task 1: RouteNameValidator（纯逻辑，TDD）

**Files:**
- Create: `app/src/main/java/com/iterlocus/pathway/RouteNameValidator.java`
- Test: `app/src/test/java/com/iterlocus/pathway/RouteNameValidatorTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `RouteNameValidator.normalize(String) -> String`；`findProblem(String) -> Problem|null`；`isValid(String) -> boolean`；枚举 `Problem{EMPTY, TOO_LONG, CONTROL_CHAR, FORBIDDEN_CHAR}`；常量 `MAX_LENGTH=32`、`FORBIDDEN_CHARS`

- [ ] **Step 1: 写失败的测试**

```java
package com.iterlocus.pathway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.iterlocus.pathway.RouteNameValidator.Problem;

import org.junit.Test;

public class RouteNameValidatorTest {

    private static String repeat(char c, int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(c);
        }
        return builder.toString();
    }

    @Test
    public void acceptsNormalNames() {
        assertTrue(RouteNameValidator.isValid("上班路线"));
        assertTrue(RouteNameValidator.isValid("Route A-1(早高峰).v2"));
    }

    @Test
    public void acceptsExactlyMaxLength() {
        assertTrue(RouteNameValidator.isValid(repeat('a', RouteNameValidator.MAX_LENGTH)));
    }

    @Test
    public void rejectsOverMaxLength() {
        assertEquals(Problem.TOO_LONG,
                RouteNameValidator.findProblem(repeat('a', RouteNameValidator.MAX_LENGTH + 1)));
    }

    @Test
    public void rejectsNullEmptyAndBlank() {
        assertEquals(Problem.EMPTY, RouteNameValidator.findProblem(null));
        assertEquals(Problem.EMPTY, RouteNameValidator.findProblem(""));
        assertEquals(Problem.EMPTY, RouteNameValidator.findProblem("   "));
    }

    @Test
    public void rejectsControlCharacters() {
        assertEquals(Problem.CONTROL_CHAR, RouteNameValidator.findProblem("路线\nA"));
        assertEquals(Problem.CONTROL_CHAR, RouteNameValidator.findProblem("路线\rA"));
        assertEquals(Problem.CONTROL_CHAR, RouteNameValidator.findProblem("路线\tA"));
        assertEquals(Problem.CONTROL_CHAR, RouteNameValidator.findProblem("路线\u0000A"));
        assertEquals(Problem.CONTROL_CHAR, RouteNameValidator.findProblem("路线\u007FA"));
    }

    @Test
    public void rejectsEveryForbiddenCharacter() {
        for (char c : RouteNameValidator.FORBIDDEN_CHARS.toCharArray()) {
            assertEquals("应拒绝字符: " + c,
                    Problem.FORBIDDEN_CHAR, RouteNameValidator.findProblem("路线" + c));
        }
    }

    @Test
    public void normalizeTrimsAndHandlesNull() {
        assertEquals("", RouteNameValidator.normalize(null));
        assertEquals("路线", RouteNameValidator.normalize("  路线  "));
    }

    @Test
    public void lengthIsMeasuredAfterTrim() {
        String padded = "  " + repeat('a', RouteNameValidator.MAX_LENGTH) + "  ";
        assertNull(RouteNameValidator.findProblem(padded));
        assertFalse(RouteNameValidator.isValid("  " + repeat('a', RouteNameValidator.MAX_LENGTH + 1)));
    }
}
```

- [ ] **Step 2: 运行,确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.iterlocus.pathway.RouteNameValidatorTest"`
Expected: 编译失败，`找不到符号: 类 RouteNameValidator`

- [ ] **Step 3: 写实现**

```java
package com.iterlocus.pathway;

/**
 * 路线名称的合规校验。纯逻辑，不依赖 Android，可单元测试。
 *
 * <p>名字会进数据库、被用户看到，也可能在后续被导出，所以在入口处就挡掉
 * 空白、超长、控制字符和文件名敏感字符。
 */
public final class RouteNameValidator {

    /** 名称长度上限，按 trim 后的字符数计。 */
    public static final int MAX_LENGTH = 32;

    /** 这些字符在后续若要导出成文件名时会惹麻烦，提前挡掉。 */
    public static final String FORBIDDEN_CHARS = "/\\:*?\"<>|";

    /** 不合规的原因。界面据此映射到 strings.xml 的文案。 */
    public enum Problem {
        /** trim 后为空 */
        EMPTY,
        /** 超过 {@link #MAX_LENGTH} */
        TOO_LONG,
        /** 含控制字符（0x00–0x1F 与 0x7F，含 \n \r \t） */
        CONTROL_CHAR,
        /** 含 {@link #FORBIDDEN_CHARS} 中的字符 */
        FORBIDDEN_CHAR
    }

    private RouteNameValidator() {
    }

    /** 归一化：null 视为空串，去掉首尾空白。入库与比较都用归一化后的值。 */
    public static String normalize(String rawName) {
        return rawName == null ? "" : rawName.trim();
    }

    /** 合规返回 null；否则返回第一个命中的原因。 */
    public static Problem findProblem(String rawName) {
        String name = normalize(rawName);
        if (name.isEmpty()) {
            return Problem.EMPTY;
        }
        if (name.length() > MAX_LENGTH) {
            return Problem.TOO_LONG;
        }
        for (int index = 0; index < name.length(); index++) {
            char c = name.charAt(index);
            if (c < 0x20 || c == 0x7F) {
                return Problem.CONTROL_CHAR;
            }
            if (FORBIDDEN_CHARS.indexOf(c) >= 0) {
                return Problem.FORBIDDEN_CHAR;
            }
        }
        return null;
    }

    public static boolean isValid(String rawName) {
        return findProblem(rawName) == null;
    }
}
```

- [ ] **Step 4: 运行,确认通过**（8 个测试）
- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/iterlocus/pathway/RouteNameValidator.java app/src/test/java/com/iterlocus/pathway/RouteNameValidatorTest.java
git commit -m "feat: 路线名称校验（纯逻辑 + 单元测试）"
```

---

### Task 2: RouteGeometry — 测地距离与弧长（TDD）

**Files:**
- Create: `app/src/main/java/com/iterlocus/pathway/RouteGeometry.java`
- Test: `app/src/test/java/com/iterlocus/pathway/RouteGeometryTest.java`

**Interfaces:**
- Consumes: `com.baidu.mapapi.model.LatLng`
- Produces: `RouteGeometry.distanceMeters(LatLng, LatLng) -> double`；`totalLengthMeters(List<LatLng>, boolean) -> double`；常量 `MIN_POINTS_FOR_CLOSE = 2`

- [ ] **Step 1: 写失败的测试**

```java
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
```

- [ ] **Step 2: 运行,确认失败**（`找不到符号: 类 RouteGeometry`）
- [ ] **Step 3: 写实现**

```java
package com.iterlocus.pathway;

import com.baidu.mapapi.model.LatLng;

import java.util.List;

/**
 * 路线几何计算：与 Android 无关的纯逻辑，可在普通 JVM 单元测试里跑。
 *
 * <p>坐标一律按 BD09 处理——本类只做距离与插值，不涉及坐标系转换。
 * 转换是模拟侧喂给 ServiceGo 之前的事（见 CLAUDE.md）。
 */
public final class RouteGeometry {

    /** 地球平均半径，米。 */
    private static final double EARTH_RADIUS_M = 6371008.8;

    /** 闭合所需的最少点数。 */
    public static final int MIN_POINTS_FOR_CLOSE = 2;

    private RouteGeometry() {
    }

    /** 两点间测地距离（haversine），单位米。 */
    public static double distanceMeters(LatLng a, LatLng b) {
        double lat1 = Math.toRadians(a.latitude);
        double lat2 = Math.toRadians(b.latitude);
        double dLat = lat2 - lat1;
        double dLng = Math.toRadians(b.longitude - a.longitude);
        double sinLat = Math.sin(dLat / 2);
        double sinLng = Math.sin(dLng / 2);
        double h = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLng * sinLng;
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }

    /** 折线总长，单位米。点数不足 2 时为 0；closed 时把末点→首点那段算进去。 */
    public static double totalLengthMeters(List<LatLng> points, boolean closed) {
        if (points == null || points.size() < MIN_POINTS_FOR_CLOSE) {
            return 0d;
        }
        double total = 0d;
        for (int index = 1; index < points.size(); index++) {
            total += distanceMeters(points.get(index - 1), points.get(index));
        }
        if (closed) {
            total += distanceMeters(points.get(points.size() - 1), points.get(0));
        }
        return total;
    }
}
```

- [ ] **Step 4: 运行,确认通过**（7 个测试）
- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/iterlocus/pathway/RouteGeometry.java app/src/test/java/com/iterlocus/pathway/RouteGeometryTest.java
git commit -m "feat: 路线几何——测地距离与折线弧长"
```

---

### Task 3: RouteGeometry — 按总长等距密化（TDD）

**Files:**
- Modify: `app/src/main/java/com/iterlocus/pathway/RouteGeometry.java`
- Test: `app/src/test/java/com/iterlocus/pathway/RouteGeometryTest.java`

**Interfaces:**
- Produces: `densify(List<LatLng> points, boolean closed, int addCount) -> List<LatLng>`

**语义（照抄，别自行理解）：** `addCount` 是**新增点的数量**。总长等分为 `addCount + 1` 段，在 `addCount` 个等分位置各插一个点。原顶点全保留，与新点按弧长排序合并。

- [ ] **Step 1: 追加失败的测试**

在 `RouteGeometryTest` 顶部补 `import static org.junit.Assert.assertTrue;`（**只补这一个**——`assertFalse` 是 Task 4 才用到的，提前加会留下一个未使用 import），然后追加：

```java
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
```

- [ ] **Step 2: 运行,确认失败**（`找不到符号: 方法 densify`）
- [ ] **Step 3: 写实现**

在 `RouteGeometry` 顶部补 `import java.util.ArrayList;`、`import java.util.Collections;`、`import java.util.Comparator;`，追加：

```java
    /**
     * 按总长等距插入 {@code addCount} 个新点。
     *
     * <p>{@code addCount} 是<b>新增点的数量</b>，不是分段数：总长等分为
     * {@code addCount + 1} 段，在 {@code addCount} 个等分位置各插一个点。
     *
     * <p>原有顶点全部保留，与新点按弧长排序合并——角点不会被抹掉。
     * 新点经纬度在所在段上线性插值，段短时误差可忽略。
     *
     * <p>{@code addCount <= 0}、点数不足 2、总长为 0 时原样返回副本。
     */
    public static List<LatLng> densify(List<LatLng> points, boolean closed, int addCount) {
        if (points == null) {
            return new ArrayList<>();
        }
        if (points.size() < MIN_POINTS_FOR_CLOSE || addCount <= 0) {
            return new ArrayList<>(points);
        }

        double total = totalLengthMeters(points, closed);
        if (total <= 0d) {
            return new ArrayList<>(points);
        }

        // 闭合时在末尾补上首点，让等分沿闭合路径走完整圈
        List<LatLng> path = new ArrayList<>(points);
        if (closed) {
            path.add(points.get(0));
        }

        double[] cumulative = new double[path.size()];
        for (int index = 1; index < path.size(); index++) {
            cumulative[index] = cumulative[index - 1]
                    + distanceMeters(path.get(index - 1), path.get(index));
        }

        // {弧长, 纬度, 经度}；先放原顶点（闭合时末尾那个重复首点不再计入）
        List<double[]> entries = new ArrayList<>();
        for (int index = 0; index < points.size(); index++) {
            entries.add(new double[]{
                    cumulative[index], path.get(index).latitude, path.get(index).longitude});
        }

        for (int k = 1; k <= addCount; k++) {
            double target = total * k / (addCount + 1.0);
            int segment = segmentIndexFor(cumulative, target);
            double segmentStart = cumulative[segment];
            double segmentLength = cumulative[segment + 1] - segmentStart;
            double ratio = segmentLength <= 0d ? 0d : (target - segmentStart) / segmentLength;
            LatLng from = path.get(segment);
            LatLng to = path.get(segment + 1);
            entries.add(new double[]{
                    target,
                    from.latitude + (to.latitude - from.latitude) * ratio,
                    from.longitude + (to.longitude - from.longitude) * ratio});
        }

        Collections.sort(entries, new Comparator<double[]>() {
            @Override
            public int compare(double[] left, double[] right) {
                return Double.compare(left[0], right[0]);
            }
        });

        List<LatLng> result = new ArrayList<>(entries.size());
        for (double[] entry : entries) {
            result.add(new LatLng(entry[1], entry[2]));
        }
        return result;
    }

    /** 找出 target 弧长落在哪一段上。 */
    private static int segmentIndexFor(double[] cumulative, double target) {
        for (int index = 0; index < cumulative.length - 1; index++) {
            if (target <= cumulative[index + 1]) {
                return index;
            }
        }
        return cumulative.length - 2;
    }
```

- [ ] **Step 4: 运行,确认通过**（14 个测试）
- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/iterlocus/pathway/RouteGeometry.java app/src/test/java/com/iterlocus/pathway/RouteGeometryTest.java
git commit -m "feat: 路线按总长等距密化"
```

---

### Task 4: RouteGeometry — 闭合判定与采样过滤（TDD）

**Files:**
- Modify: `app/src/main/java/com/iterlocus/pathway/RouteGeometry.java`
- Test: `app/src/test/java/com/iterlocus/pathway/RouteGeometryTest.java`

**Interfaces:**
- Produces: `canClose(int) -> boolean`；`isWithinHitRadius(int,int,int,int,float) -> boolean`；`shouldSample(LatLng, LatLng, double) -> boolean`；常量 `LINE_SAMPLE_MIN_DISTANCE_METERS = 2.0`

**注意：** 这几个方法刻意收 `int` 像素而不是 `android.graphics.Point`——碰 Android 类会让普通单元测试抛 "not mocked"。

- [ ] **Step 1: 追加失败的测试**

```java
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
        // 期望值 true 是独立可知的（"≥" 在等号处为真），不是拿被测代码当期望。
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
```

- [ ] **Step 2: 运行,确认失败**（`找不到符号: 方法 canClose`）
- [ ] **Step 3: 写实现**（追加到 `RouteGeometry`）

```java
    /** 线绘制时相邻采样点的最小间距（米）。低于它的移动被忽略，避免一次拖动画出上千个重合点。 */
    public static final double LINE_SAMPLE_MIN_DISTANCE_METERS = 2.0;

    /** 点数是否达到可闭合的下限。 */
    public static boolean canClose(int pointCount) {
        return pointCount >= MIN_POINTS_FOR_CLOSE;
    }

    /**
     * 屏幕落点是否落在锚点的命中半径内（欧氏距离，不是轴距）。
     *
     * <p>刻意收 int 像素而不是 {@code android.graphics.Point}：本类要能在普通 JVM
     * 单元测试里跑，碰 Android 类会抛 "not mocked"。
     */
    public static boolean isWithinHitRadius(int tapX, int tapY,
                                            int anchorX, int anchorY, float radiusPx) {
        double dx = tapX - anchorX;
        double dy = tapY - anchorY;
        return Math.sqrt(dx * dx + dy * dy) <= radiusPx;
    }

    /** 线绘制采样：与上一个点的距离是否够远。上一个点为空时一律收下。 */
    public static boolean shouldSample(LatLng last, LatLng candidate, double minDistanceMeters) {
        if (last == null) {
            return true;
        }
        return distanceMeters(last, candidate) >= minDistanceMeters;
    }
```

- [ ] **Step 4: 运行,确认通过**（21 个测试）
- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/iterlocus/pathway/RouteGeometry.java app/src/test/java/com/iterlocus/pathway/RouteGeometryTest.java
git commit -m "feat: 路线闭合判定与线绘制采样过滤"
```

---

### Task 5: RouteConfig 与 DataBaseRoute

**Files:**
- Create: `app/src/main/java/com/iterlocus/pathway/RouteConfig.java`
- Create: `app/src/main/java/com/iterlocus/pathway/database/DataBaseRoute.java`

**Interfaces:**
- Produces：
  - `RouteConfig(String name, boolean closed, List<LatLng> points, long createdAt)`；`getName()` / `isClosed()` / `getPoints()` / `getCreatedAt()` / `getPointCount()`
  - `DataBaseRoute(Context)`；常量 `TABLE_NAME="RouteConfig"` 与 5 个列名常量
  - `DataBaseRoute.nameExists(SQLiteDatabase, String) -> boolean`
  - `DataBaseRoute.insertRoute(SQLiteDatabase, String, boolean, List<LatLng>) -> long`（失败 -1）
  - `DataBaseRoute.queryAllNames(SQLiteDatabase) -> List<String>`
  - `DataBaseRoute.queryAll(SQLiteDatabase) -> List<RouteConfig>`

**为什么有 `queryAllNames`：** spec 只要求保存。但没有列出的话，用户既无法确认保存成功，也不知道哪些名字已被占用（而重名是要拒绝的）。把已有名称显示在保存弹框里，一次解决两个问题。

**`queryAll` 与 `RouteConfig` 在本计划里没有任何调用点**，是为下一步的线路模拟预留的读取路径——spec 写明这份配置就是给它用的。若要严格 YAGNI，可以把这两个删掉、等做模拟时再加；`RouteConfig.db` 的表结构本身就是那层契约。保留是我按 spec 的意图做的选择，不是疏漏。

- [ ] **Step 1: 写数据模型**

```java
package com.iterlocus.pathway;

import com.baidu.mapapi.model.LatLng;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 一条已保存的路线。坐标是 BD09（地图原生），模拟侧调用时自行转 WGS84。 */
public final class RouteConfig {

    private final String name;
    private final boolean closed;
    private final List<LatLng> points;
    private final long createdAt;

    public RouteConfig(String name, boolean closed, List<LatLng> points, long createdAt) {
        this.name = name == null ? "" : name.trim();
        this.closed = closed;
        this.points = points == null ? new ArrayList<>() : new ArrayList<>(points);
        this.createdAt = createdAt;
    }

    public String getName() {
        return name;
    }

    public boolean isClosed() {
        return closed;
    }

    /** 只读视图，调用方改不了内部列表。 */
    public List<LatLng> getPoints() {
        return Collections.unmodifiableList(points);
    }

    /** 创建时间，秒。 */
    public long getCreatedAt() {
        return createdAt;
    }

    public int getPointCount() {
        return points.size();
    }
}
```

- [ ] **Step 2: 写 SQLite 助手**

```java
package com.iterlocus.pathway.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.baidu.mapapi.model.LatLng;
import com.elvishew.xlog.XLog;
import com.iterlocus.pathway.RouteConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 绘制路线的持久化。
 *
 * <p>点集序列化成 JSON 数组塞进一个 TEXT 列——路线点数不定，拆成行既难查也没意义。
 *
 * <p>名称列带 {@code COLLATE NOCASE UNIQUE}，重名在数据库层也拦得住。
 */
public class DataBaseRoute extends SQLiteOpenHelper {

    public static final String TABLE_NAME = "RouteConfig";
    public static final String DB_COLUMN_ID = "DB_COLUMN_ID";
    public static final String DB_COLUMN_NAME = "DB_COLUMN_NAME";
    public static final String DB_COLUMN_CLOSED = "DB_COLUMN_CLOSED";
    public static final String DB_COLUMN_POINTS = "DB_COLUMN_POINTS";
    public static final String DB_COLUMN_CREATED_AT = "DB_COLUMN_CREATED_AT";

    private static final int DB_VERSION = 1;
    private static final String DB_NAME = "RouteConfig.db";

    // 用常量拼 SQL 而不是写字面量：项目里另两个助手把列名硬写死在 SQL 里，
    // 一旦有人改了常量就对不上，这里不沿用那个写法。
    private static final String CREATE_TABLE = "create table if not exists " + TABLE_NAME
            + " (" + DB_COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
            + DB_COLUMN_NAME + " TEXT NOT NULL COLLATE NOCASE UNIQUE, "
            + DB_COLUMN_CLOSED + " INTEGER NOT NULL, "
            + DB_COLUMN_POINTS + " TEXT NOT NULL, "
            + DB_COLUMN_CREATED_AT + " BIGINT NOT NULL)";

    public DataBaseRoute(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        try {
            db.execSQL(CREATE_TABLE);
        } catch (RuntimeException e) {
            /* 建表失败是编程错误（DDL 写坏了），不是运行时读写失败。
             * spec 的错误处理表只覆盖后者。吞掉会让数据库没有表、此后每次保存都无声失败，
             * 对用户是永久且无法解释的；记日志后照抛，让它在开发者第一次实测时立刻暴露。 */
            XLog.e("ROUTE: ERROR - onCreate");
            throw e;
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        try {
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
            onCreate(db);
        } catch (RuntimeException e) {
            XLog.e("ROUTE: ERROR - onUpgrade");
            throw e;
        }
    }

    /** 名称是否已被占用（大小写不敏感，与列的 COLLATE NOCASE 一致）。 */
    public static boolean nameExists(SQLiteDatabase db, String name) {
        if (db == null || name == null) {
            return false;
        }
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_NAME, new String[]{DB_COLUMN_ID},
                    DB_COLUMN_NAME + " = ? COLLATE NOCASE",
                    new String[]{name.trim()}, null, null, null, "1");
            return cursor != null && cursor.moveToFirst();
        } catch (Exception e) {
            XLog.e("ROUTE: ERROR - nameExists");
            return false;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /** 插入一条路线，返回行 id；失败返回 -1。 */
    public static long insertRoute(SQLiteDatabase db, String name, boolean closed,
                                   List<LatLng> points) {
        if (db == null || name == null || points == null || points.isEmpty()) {
            return -1L;
        }
        try {
            ContentValues values = new ContentValues();
            values.put(DB_COLUMN_NAME, name.trim());
            values.put(DB_COLUMN_CLOSED, closed ? 1 : 0);
            values.put(DB_COLUMN_POINTS, encodePoints(points));
            values.put(DB_COLUMN_CREATED_AT, System.currentTimeMillis() / 1000);
            return db.insert(TABLE_NAME, null, values);
        } catch (Exception e) {
            XLog.e("ROUTE: ERROR - insertRoute");
            return -1L;
        }
    }

    /** 所有已保存路线的名称，最近创建的在前。 */
    public static List<String> queryAllNames(SQLiteDatabase db) {
        List<String> names = new ArrayList<>();
        if (db == null) {
            return names;
        }
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_NAME, new String[]{DB_COLUMN_NAME},
                    null, null, null, null, DB_COLUMN_CREATED_AT + " DESC");
            while (cursor != null && cursor.moveToNext()) {
                names.add(cursor.getString(0));
            }
        } catch (Exception e) {
            XLog.e("ROUTE: ERROR - queryAllNames");
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return names;
    }

    /** 所有已保存路线，最近创建的在前。 */
    public static List<RouteConfig> queryAll(SQLiteDatabase db) {
        List<RouteConfig> routes = new ArrayList<>();
        if (db == null) {
            return routes;
        }
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_NAME, null, null, null, null, null,
                    DB_COLUMN_CREATED_AT + " DESC");
            while (cursor != null && cursor.moveToNext()) {
                String name = cursor.getString(cursor.getColumnIndexOrThrow(DB_COLUMN_NAME));
                boolean closed = cursor.getInt(cursor.getColumnIndexOrThrow(DB_COLUMN_CLOSED)) == 1;
                String pointsJson = cursor.getString(cursor.getColumnIndexOrThrow(DB_COLUMN_POINTS));
                long createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(DB_COLUMN_CREATED_AT));
                routes.add(new RouteConfig(name, closed, decodePoints(pointsJson), createdAt));
            }
        } catch (Exception e) {
            XLog.e("ROUTE: ERROR - queryAll");
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return routes;
    }

    /** 点集 → JSON 数组，元素形如 {"lat":39.9,"lng":116.4}。 */
    static String encodePoints(List<LatLng> points) {
        JSONArray array = new JSONArray();
        for (LatLng point : points) {
            JSONObject item = new JSONObject();
            try {
                item.put("lat", point.latitude);
                item.put("lng", point.longitude);
                array.put(item);
            } catch (Exception ignored) {
                // 单点写失败就跳过，不影响其余
            }
        }
        return array.toString();
    }

    /** JSON 数组 → 点集。解析失败的条目跳过。 */
    static List<LatLng> decodePoints(String json) {
        List<LatLng> points = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return points;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null) {
                    continue;
                }
                points.add(new LatLng(item.optDouble("lat"), item.optDouble("lng")));
            }
        } catch (Exception e) {
            XLog.e("ROUTE: ERROR - decodePoints");
        }
        return points;
    }
}
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/iterlocus/pathway/RouteConfig.java app/src/main/java/com/iterlocus/pathway/database/DataBaseRoute.java
git commit -m "feat: 路线数据模型与 SQLite 持久化"
```

---

### Task 6: RouteDrawOverlayView 绘制层

**Files:**
- Create: `app/src/main/java/com/iterlocus/pathway/RouteDrawOverlayView.java`

**Interfaces:**
- Consumes: `RouteGeometry`
- Produces：
  - `RouteDrawOverlayView(Context, AttributeSet)`
  - `setBaiduMap(BaiduMap)`、`setDrawMode(int)`、`setDrawEnabled(boolean)`
  - `setPoints(List<LatLng>)`、`getPoints() -> List<LatLng>`、`isClosed()`、`setClosed(boolean)`
  - `clearRoute()`
  - `setOnRouteChangedListener(OnRouteChangedListener)`
  - 常量 `MODE_POINT = 0`、`MODE_LINE = 1`
  - 接口 `OnRouteChangedListener { void onRouteChanged(); }`

- [ ] **Step 1: 写实现**

```java
package com.iterlocus.pathway;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.baidu.mapapi.map.BaiduMap;
import com.baidu.mapapi.map.MapStatus;
import com.baidu.mapapi.map.Projection;
import com.baidu.mapapi.model.LatLng;

import java.util.ArrayList;
import java.util.List;

/**
 * 盖在百度地图上的绘制层。
 *
 * <p>不使用地图的 Polyline 覆盖物：折线本身它画得了，但顶点手柄、起点高亮、闭合指示、
 * 线绘制的橡皮筋仍要自定义层，两套混用更难维护。这里统一自己画，坐标全部经
 * {@link Projection} 换算，地图状态一变就重绘。
 *
 * <p>只在 {@link #setDrawEnabled(boolean)} 为 true 时消费触摸。地图手势由 Activity
 * 开关，两者互斥。
 */
public class RouteDrawOverlayView extends View {

    public static final int MODE_POINT = 0;
    public static final int MODE_LINE = 1;

    /** 闭合命中半径，dp。 */
    private static final float HIT_RADIUS_DP = 24f;
    private static final float VERTEX_RADIUS_DP = 5f;
    private static final float START_RADIUS_DP = 9f;
    private static final float LINE_WIDTH_DP = 4f;

    public interface OnRouteChangedListener {
        /** 点集或闭合状态变化。Activity 据此更新撤销栈与按钮状态。 */
        void onRouteChanged();
    }

    private final Paint mLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mVertexPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStartPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<LatLng> mPoints = new ArrayList<>();
    /** 线绘制进行中的临时轨迹，抬指后并入 mPoints */
    private final List<LatLng> mStroke = new ArrayList<>();
    private final Point mScratch = new Point();

    private BaiduMap mBaiduMap;
    private boolean mClosed;
    private boolean mDrawEnabled;
    private int mMode = MODE_POINT;
    private boolean mTouching;
    private float mTouchX;
    private float mTouchY;

    private float mHitRadiusPx;
    private float mVertexRadiusPx;
    private float mStartRadiusPx;
    private float mLineWidthPx;

    private OnRouteChangedListener mListener;

    public RouteDrawOverlayView(Context context) {
        this(context, null);
    }

    public RouteDrawOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        mHitRadiusPx = HIT_RADIUS_DP * density;
        mVertexRadiusPx = VERTEX_RADIUS_DP * density;
        mStartRadiusPx = START_RADIUS_DP * density;
        mLineWidthPx = LINE_WIDTH_DP * density;

        mLinePaint.setStyle(Paint.Style.STROKE);
        mLinePaint.setStrokeWidth(mLineWidthPx);
        mLinePaint.setStrokeCap(Paint.Cap.ROUND);
        mLinePaint.setColor(Color.parseColor("#FF3F51B5"));

        mVertexPaint.setStyle(Paint.Style.FILL);
        mVertexPaint.setColor(Color.WHITE);
        mVertexPaint.setShadowLayer(2f, 0f, 0f, Color.BLACK);
        setLayerType(LAYER_TYPE_SOFTWARE, null);

        mStartPaint.setStyle(Paint.Style.FILL);
        mStartPaint.setColor(Color.parseColor("#FFFF5722"));

        mStrokePaint.setStyle(Paint.Style.STROKE);
        mStrokePaint.setStrokeWidth(mLineWidthPx);
        mStrokePaint.setStrokeCap(Paint.Cap.ROUND);
        mStrokePaint.setColor(Color.parseColor("#99FF3F51B5"));
    }

    public void setOnRouteChangedListener(OnRouteChangedListener listener) {
        mListener = listener;
    }

    /** 绑定地图。绑定后自行注册状态监听，地图一动就重绘。 */
    public void setBaiduMap(BaiduMap baiduMap) {
        mBaiduMap = baiduMap;
        if (mBaiduMap != null) {
            mBaiduMap.setOnMapStatusChangeListener(new BaiduMap.OnMapStatusChangeListener() {
                @Override
                public void onMapStatusChangeStart(MapStatus mapStatus) {
                    invalidate();
                }

                @Override
                public void onMapStatusChangeStart(MapStatus mapStatus, int reason) {
                    invalidate();
                }

                @Override
                public void onMapStatusChange(MapStatus mapStatus) {
                    invalidate();
                }

                @Override
                public void onMapStatusChangeFinish(MapStatus mapStatus) {
                    invalidate();
                }
            });
        }
        invalidate();
    }

    public void setDrawMode(int mode) {
        mMode = mode;
        mStroke.clear();
        // 作废进行中的手势：否则手指仍按着时，下一次 MOVE 会在空笔画上重新播种一段幽灵轨迹
        mTouching = false;
        invalidate();
    }

    public int getDrawMode() {
        return mMode;
    }

    /** true 时消费触摸事件；false 时完全交给下面的地图。 */
    public void setDrawEnabled(boolean enabled) {
        mDrawEnabled = enabled;
        if (!enabled) {
            mTouching = false;
            mStroke.clear();
            invalidate();
        }
    }

    public List<LatLng> getPoints() {
        return new ArrayList<>(mPoints);
    }

    /** 整体替换点集（撤销恢复、载入时用）。 */
    public void setPoints(List<LatLng> points) {
        mPoints.clear();
        if (points != null) {
            mPoints.addAll(points);
        }
        invalidate();
    }

    public boolean isClosed() {
        return mClosed;
    }

    public void setClosed(boolean closed) {
        mClosed = closed;
        invalidate();
    }

    public void clearRoute() {
        mPoints.clear();
        mStroke.clear();
        mClosed = false;
        // 同 setDrawMode：清空笔画必须一并作废手势
        mTouching = false;
        invalidate();
    }

    private Projection projection() {
        return mBaiduMap == null ? null : mBaiduMap.getProjection();
    }

    private Point toScreen(LatLng latLng) {
        Projection projection = projection();
        return projection == null ? null : projection.toScreenLocation(latLng);
    }

    private LatLng fromScreen(float x, float y) {
        Projection projection = projection();
        if (projection == null) {
            return null;
        }
        mScratch.set((int) x, (int) y);
        return projection.fromScreenLocation(mScratch);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!mDrawEnabled || projection() == null) {
            return false;
        }

        mTouchX = event.getX();
        mTouchY = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mTouching = true;
                if (mMode == MODE_LINE) {
                    startStroke();
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                // 没有进行中的手势就忽略：setDrawMode/clearRoute 会在手指仍按着时清空笔画，
                // 不设这道守卫，下一次 MOVE 会用 shouldSample(null,...) 恒真从空笔画里播种出一段幽灵轨迹
                if (!mTouching) {
                    return true;
                }
                if (mMode == MODE_LINE) {
                    extendStroke();
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
                if (!mTouching) {
                    return true;
                }
                mTouching = false;
                if (mMode == MODE_POINT) {
                    handlePointTap(event.getX(), event.getY());
                } else {
                    finishStroke(event.getX(), event.getY(), false);
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_CANCEL:
                mTouching = false;
                if (mMode == MODE_LINE) {
                    finishStroke(event.getX(), event.getY(), true);
                }
                invalidate();
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    /** 点绘制：落一个顶点，或命中起始点时闭合。 */
    private void handlePointTap(float x, float y) {
        LatLng tapped = fromScreen(x, y);
        if (tapped == null) {
            return;
        }

        if (isHittingStart(x, y, mPoints)) {
            mClosed = true;
            notifyChanged();
            return;
        }

        mPoints.add(tapped);
        // 加了新点就不再是闭合形状——想闭合就再点一次起始点
        mClosed = false;
        notifyChanged();
    }

    private void startStroke() {
        mStroke.clear();
        LatLng start = fromScreen(mTouchX, mTouchY);
        if (start != null) {
            mStroke.add(start);
        }
    }

    private void extendStroke() {
        LatLng candidate = fromScreen(mTouchX, mTouchY);
        if (candidate == null) {
            return;
        }
        LatLng last = mStroke.isEmpty() ? null : mStroke.get(mStroke.size() - 1);
        if (RouteGeometry.shouldSample(last, candidate,
                RouteGeometry.LINE_SAMPLE_MIN_DISTANCE_METERS)) {
            mStroke.add(candidate);
        }
    }

    private void finishStroke(float x, float y, boolean cancelled) {
        if (cancelled) {
            mStroke.clear();
            return;
        }

        LatLng end = fromScreen(x, y);
        if (end != null) {
            LatLng last = mStroke.isEmpty() ? null : mStroke.get(mStroke.size() - 1);
            if (RouteGeometry.shouldSample(last, end,
                    RouteGeometry.LINE_SAMPLE_MIN_DISTANCE_METERS)) {
                mStroke.add(end);
            }
        }

        if (mStroke.isEmpty()) {
            return;
        }

        // 首个笔画时，起点就是这条笔画自己的第一个点
        List<LatLng> reference = mPoints.isEmpty() ? mStroke : mPoints;
        boolean closedNow = isHittingStart(x, y, reference);

        mPoints.addAll(mStroke);
        mStroke.clear();
        mClosed = closedNow;
        notifyChanged();
    }

    /** 落点是否命中给定列表的首点，且点数已达闭合下限。 */
    private boolean isHittingStart(float x, float y, List<LatLng> reference) {
        if (!RouteGeometry.canClose(reference.size())) {
            return false;
        }
        Point first = toScreen(reference.get(0));
        if (first == null) {
            return false;
        }
        return RouteGeometry.isWithinHitRadius(
                (int) x, (int) y, first.x, first.y, mHitRadiusPx);
    }

    private void notifyChanged() {
        invalidate();
        if (mListener != null) {
            mListener.onRouteChanged();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (projection() == null) {
            return;
        }

        drawPolyline(canvas, mPoints, mClosed, mLinePaint);
        drawPolyline(canvas, mStroke, false, mStrokePaint);

        for (LatLng point : mPoints) {
            Point screen = toScreen(point);
            if (screen != null) {
                canvas.drawCircle(screen.x, screen.y, mVertexRadiusPx, mVertexPaint);
            }
        }

        if (!mPoints.isEmpty()) {
            Point start = toScreen(mPoints.get(0));
            if (start != null) {
                canvas.drawCircle(start.x, start.y, mStartRadiusPx, mStartPaint);
            }
        }
    }

    private void drawPolyline(Canvas canvas, List<LatLng> points, boolean closed, Paint paint) {
        if (points.size() < 2) {
            return;
        }
        Path path = new Path();
        Point first = null;
        boolean started = false;
        for (LatLng point : points) {
            Point screen = toScreen(point);
            if (screen == null) {
                continue;
            }
            if (!started) {
                path.moveTo(screen.x, screen.y);
                first = screen;
                started = true;
            } else {
                path.lineTo(screen.x, screen.y);
            }
        }
        if (closed && first != null) {
            path.lineTo(first.x, first.y);
        }
        canvas.drawPath(path, paint);
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/iterlocus/pathway/RouteDrawOverlayView.java
git commit -m "feat: 路线绘制层（投影换算、点/线两种手势、闭合命中）"
```

---

### Task 7: RouteDrawActivity 骨架、布局与地图锁定

**Files:**
- Create: `app/src/main/java/com/iterlocus/pathway/RouteDrawActivity.java`
- Create: `app/src/main/res/layout/activity_route_draw.xml`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Produces: `RouteDrawActivity`，含字段 `mMapView`、`mBaiduMap`、`mOverlay`、`mRouteDb`、`mLocClient`、`mUndoStack`（撤销栈；早先此处误写成 `mStroke`，那是绘制层内部的字段名，与本类无关）
- 供 Task 8 使用的内部方法名：`applyMapGestures(boolean locked)`、`pushUndoSnapshot()`、`updateStatusText()`、`updateUndoButton()`。（Task 8 的 `undo()` 直接操作 `mUndoStack`，**不另设** `popUndoSnapshot()`——早先的 Interfaces 块误列过这个名字，实现里从不存在。）

- [ ] **Step 1: 加字符串**

在 `strings.xml` 的 `</resources>` 之前追加：

```xml
    <!-- 绘制路线 -->
    <string name="app_route_draw">绘制路线</string>
    <string name="nav_menu_route_draw">绘制路线</string>
    <string name="route_draw_tools_title">工具</string>
    <string name="route_draw_tools_toggle">工具</string>
    <string name="route_draw_mode_point">点绘制</string>
    <string name="route_draw_mode_line">线绘制</string>
    <string name="route_draw_lock_map">锁定地图</string>
    <string name="route_draw_btn_densify">密化</string>
    <string name="route_draw_btn_undo">撤销上一步</string>
    <string name="route_draw_btn_clear">清空</string>
    <string name="route_draw_btn_finish">完成绘制</string>
    <string name="route_draw_densify_title">均匀加点</string>
    <string name="route_draw_densify_hint">加点数 N</string>
    <string name="route_draw_densify_note">在总长上等分 N+1 段，均匀插入 N 个新点。原有顶点会保留。</string>
    <string name="route_draw_densify_invalid">请输入大于 0 的整数</string>
    <string name="route_draw_densify_done">已插入 %1$d 个点</string>
    <string name="route_draw_densify_no_route">还没有画出可密化的路线</string>
    <string name="route_draw_save_title">保存路线</string>
    <string name="route_draw_save_name_hint">路线名称</string>
    <string name="route_draw_save_existing">已保存的路线：</string>
    <string name="route_draw_save_none">尚未保存过路线</string>
    <string name="route_draw_save_ok">已保存</string>
    <string name="route_draw_save_too_few_points">至少需要 2 个点才能保存</string>
    <string name="route_draw_save_duplicate">该名称已存在，请换一个</string>
    <string name="route_draw_save_failed">保存失败，请重试</string>
    <string name="route_draw_name_empty">名称不能为空</string>
    <string name="route_draw_name_too_long">名称最多 32 个字符</string>
    <string name="route_draw_name_control_char">名称不能包含换行或制表符等控制字符</string>
    <string name="route_draw_name_forbidden_char">名称不能包含 / \ : * ? " &lt; &gt; | 这些字符</string>
    <string name="route_draw_clear_title">清空路线</string>
    <string name="route_draw_clear_message">将清空当前绘制的内容，且无法撤销。确定吗？</string>
    <string name="route_draw_undo_empty">没有可撤销的操作</string>
    <string name="route_draw_status_idle">点绘制：点击地图落点；回到起点可闭合</string>
    <string name="route_draw_status_line">线绘制：按住拖动描绘；松手时靠近起点可闭合</string>
    <string name="route_draw_point_count">已绘制 %1$d 个点</string>
    <string name="route_draw_locate_failed">定位失败，请解锁地图后手动平移到目标区域</string>
```

- [ ] **Step 2: 写布局**

`app/src/main/res/layout/activity_route_draw.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <com.baidu.mapapi.map.MapView
        android:id="@+id/route_draw_map"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:clickable="true" />

    <com.iterlocus.pathway.RouteDrawOverlayView
        android:id="@+id/route_draw_overlay"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

    <!-- 面板收起时的入口。
         刻意放在 start|top：面板是 end 侧、match_parent 高、且在子视图列表里更靠后，
         会盖住 end 侧的一切——放 end|top 的话面板一打开，收起按钮就被自己盖住，关不掉。 -->
    <Button
        android:id="@+id/route_draw_tools_toggle"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="start|top"
        android:layout_margin="12dp"
        android:text="@string/route_draw_tools_toggle" />

    <include
        android:id="@+id/route_draw_tools"
        layout="@layout/route_draw_tools"
        android:layout_width="wrap_content"
        android:layout_height="match_parent"
        android:layout_gravity="end" />

    <TextView
        android:id="@+id/route_draw_status"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="start|bottom"
        android:layout_margin="12dp"
        android:background="#CCFFFFFF"
        android:padding="6dp"
        android:text="@string/route_draw_status_idle"
        android:textSize="12sp" />

</FrameLayout>
```

`app/src/main/res/layout/route_draw_tools.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="200dp"
    android:layout_height="match_parent"
    android:background="#F2FFFFFF"
    android:visibility="gone">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="12dp">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/route_draw_tools_title"
            android:textSize="16sp"
            android:textStyle="bold" />

        <RadioGroup
            android:id="@+id/route_draw_mode_group"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:orientation="vertical">

            <RadioButton
                android:id="@+id/route_draw_mode_point"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:checked="true"
                android:text="@string/route_draw_mode_point" />

            <RadioButton
                android:id="@+id/route_draw_mode_line"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/route_draw_mode_line" />
        </RadioGroup>

        <CheckBox
            android:id="@+id/route_draw_lock_map"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:checked="true"
            android:text="@string/route_draw_lock_map" />

        <Button
            android:id="@+id/route_draw_btn_densify"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:text="@string/route_draw_btn_densify" />

        <Button
            android:id="@+id/route_draw_btn_undo"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="@string/route_draw_btn_undo" />

        <Button
            android:id="@+id/route_draw_btn_clear"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="@string/route_draw_btn_clear" />

        <Button
            android:id="@+id/route_draw_btn_finish"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:text="@string/route_draw_btn_finish" />

    </LinearLayout>
</ScrollView>
```

- [ ] **Step 3: 写骨架 Activity**

```java
package com.iterlocus.pathway;

import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;

import com.baidu.location.BDAbstractLocationListener;
import com.baidu.location.BDLocation;
import com.baidu.location.LocationClient;
import com.baidu.location.LocationClientOption;
import com.baidu.mapapi.map.BaiduMap;
import com.baidu.mapapi.map.MapStatus;
import com.baidu.mapapi.map.MapStatusUpdateFactory;
import com.baidu.mapapi.map.MapView;
import com.baidu.mapapi.model.LatLng;
import com.elvishew.xlog.XLog;
import com.iterlocus.pathway.database.DataBaseRoute;
import com.iterlocus.pathway.utils.GoUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 绘制路线：在百度地图上点绘或线绘一条路线，可闭合、可密化，最后存为本地配置。
 *
 * <p>这是<b>绘制</b>，不是模拟。开始模拟、沿路线移动都在后续的线路模拟功能里。
 */
public class RouteDrawActivity extends BaseActivity {

    private MapView mMapView;
    private BaiduMap mBaiduMap;
    private RouteDrawOverlayView mOverlay;

    private ScrollView mToolsPanel;
    private TextView mStatusText;
    private Button mUndoButton;

    private SQLiteDatabase mRouteDb;

    /** 只在进入界面时取一次位置，把地图居中；拿到就停。 */
    private LocationClient mLocClient;

    /** 撤销栈：每次「落点 / 完成一次拖绘 / 闭合 / 密化」压一份点集+闭合标记的快照。 */
    private final List<Snapshot> mUndoStack = new ArrayList<>();

    private static final class Snapshot {
        final List<LatLng> points;
        final boolean closed;

        Snapshot(List<LatLng> points, boolean closed) {
            this.points = points;
            this.closed = closed;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(getResources().getColor(R.color.colorPrimary, this.getTheme()));
        setContentView(R.layout.activity_route_draw);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        try {
            mRouteDb = new DataBaseRoute(getApplicationContext()).getWritableDatabase();
        } catch (Exception e) {
            XLog.e("ROUTE: ERROR - open database");
        }

        mMapView = findViewById(R.id.route_draw_map);
        mBaiduMap = mMapView.getMap();
        mBaiduMap.setMapStatus(MapStatusUpdateFactory.newMapStatus(
                new MapStatus.Builder().zoom(18.0f).build()));

        mOverlay = findViewById(R.id.route_draw_overlay);
        mOverlay.setBaiduMap(mBaiduMap);
        mOverlay.setOnRouteChangedListener(this::onRouteChanged);

        mToolsPanel = findViewById(R.id.route_draw_tools);
        mStatusText = findViewById(R.id.route_draw_status);
        mUndoButton = findViewById(R.id.route_draw_btn_undo);

        findViewById(R.id.route_draw_tools_toggle).setOnClickListener(v -> toggleToolsPanel());

        RadioGroup modeGroup = findViewById(R.id.route_draw_mode_group);
        modeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int mode = checkedId == R.id.route_draw_mode_line
                    ? RouteDrawOverlayView.MODE_LINE : RouteDrawOverlayView.MODE_POINT;
            mOverlay.setDrawMode(mode);
            updateStatusText();
        });

        CheckBox lockMap = findViewById(R.id.route_draw_lock_map);
        lockMap.setOnCheckedChangeListener((button, checked) -> {
            applyMapGestures(checked);
            mOverlay.setDrawEnabled(checked);
        });

        // 默认锁定：进入就能直接画
        applyMapGestures(true);
        mOverlay.setDrawEnabled(true);

        centerOnCurrentLocation();

        updateStatusText();
        updateUndoButton();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mMapView.onResume();
    }

    @Override
    protected void onPause() {
        mMapView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopLocationClient();
        mMapView.onDestroy();
        if (mRouteDb != null) {
            mRouteDb.close();
        }
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

    /** 锁定时关掉地图自身的手势，触摸全归绘制层；解锁后相反。 */
    private void applyMapGestures(boolean locked) {
        if (mBaiduMap != null) {
            mBaiduMap.getUiSettings().setAllGesturesEnabled(!locked);
        }
    }

    private void toggleToolsPanel() {
        boolean visible = mToolsPanel.getVisibility() == View.VISIBLE;
        mToolsPanel.setVisibility(visible ? View.GONE : View.VISIBLE);
    }

    private void onRouteChanged() {
        pushUndoSnapshot();
        updateStatusText();
        updateUndoButton();
    }

    /** 压入当前状态。注意：调用点是在状态<b>已经改变之后</b>，所以栈里存的是新状态，
     *  撤销时恢复到「上一个」快照需要先弹出栈顶。见 {@link #undo()}。 */
    private void pushUndoSnapshot() {
        mUndoStack.add(new Snapshot(mOverlay.getPoints(), mOverlay.isClosed()));
    }

    private void updateUndoButton() {
        if (mUndoButton != null) {
            mUndoButton.setEnabled(!mUndoStack.isEmpty());
        }
    }

    /**
     * 百度定位的失败码远多于成功码（TypeNone、TypeCriteriaException、TypeNetWorkException、
     * TypeOffLineLocationFail、TypeServerError 等十余个），所以判定必须走**成功白名单**。
     *
     * <p>反过来的写法（白名单两三个失败码、其余当成功）会让绝大多数失败落到
     * {@code animateMapStatus} 上：失败的 BDLocation 经纬度常为 0，相机会飞到几内亚湾，
     * 而且不弹任何提示——用户只看到地图莫名跑到海上。
     *
     * <p>注意 {@code TypeGpsLocation} 与 {@code TypeGnssLocation} 的取值都是 61（javap 核实），
     * 那个 {@code ||} 项在数值上冗余；保留两个名字是为了表意，不要以为这里写错了。
     */
    private static boolean isLocateSuccess(int locType) {
        return locType == BDLocation.TypeGpsLocation
                || locType == BDLocation.TypeGnssLocation
                || locType == BDLocation.TypeNetWorkLocation
                || locType == BDLocation.TypeCoarseLocation
                || locType == BDLocation.TypeOffLineLocation
                || locType == BDLocation.TypeCacheLocation;
    }

    /**
     * 取一次当前位置把地图居中，省得每次进来都要手动平移。
     *
     * <p>这里刻意用 {@code setScanSpan(0)}（只定位一次），与 MainActivity 的
     * 1000ms 持续定位是两种不同配置——不要因为「看着像」就把 MainActivity 的
     * getLocationClientOption() 抄过来，那既多余又会被审查判为复制逻辑块。
     *
     * <p>定位失败不拦路：停在默认中心，提示用户手动平移。
     */
    private void centerOnCurrentLocation() {
        try {
            mLocClient = new LocationClient(getApplicationContext());
            mLocClient.registerLocationListener(new BDAbstractLocationListener() {
                @Override
                public void onReceiveLocation(BDLocation bdLocation) {
                    if (bdLocation == null || mBaiduMap == null) {
                        return;
                    }
                    if (!isLocateSuccess(bdLocation.getLocType())) {
                        GoUtils.DisplayToast(RouteDrawActivity.this,
                                getResources().getString(R.string.route_draw_locate_failed));
                        stopLocationClient();
                        return;
                    }

                    mBaiduMap.animateMapStatus(MapStatusUpdateFactory.newMapStatus(
                            new MapStatus.Builder()
                                    .target(new LatLng(bdLocation.getLatitude(),
                                            bdLocation.getLongitude()))
                                    .zoom(18.0f)
                                    .build()));
                    stopLocationClient();
                }
            });

            LocationClientOption option = new LocationClientOption();
            // 必须与地图一致：本项目地图用的是 BD09LL（见 CLAUDE.md 的坐标系一节）
            option.setCoorType("bd09ll");
            // 0 = 只定位一次。绘制界面只要一个初始中心，不需要持续定位
            option.setScanSpan(0);
            option.setOpenGnss(true);
            option.setIsNeedAddress(false);
            option.setIsNeedLocationDescribe(false);
            option.setIsNeedLocationPoiList(false);

            mLocClient.setLocOption(option);
            mLocClient.start();
        } catch (Exception e) {
            XLog.e("ROUTE: ERROR - centerOnCurrentLocation");
        }
    }

    private void stopLocationClient() {
        if (mLocClient != null) {
            mLocClient.stop();
            mLocClient = null;
        }
    }

    private void updateStatusText() {
        if (mStatusText == null) {
            return;
        }
        String hint = mOverlay.getDrawMode() == RouteDrawOverlayView.MODE_LINE
                ? getResources().getString(R.string.route_draw_status_line)
                : getResources().getString(R.string.route_draw_status_idle);
        String count = getResources().getString(
                R.string.route_draw_point_count, mOverlay.getPoints().size());
        mStatusText.setText(hint + "\n" + count);
    }
}
```

**注意**：`undo()`、密化、清空、保存 都在 Task 8 里补。这个 Task 只到「能打开界面、能切换模式、能锁定地图、能画（点集变化会更新状态栏）」为止。

- [ ] **Step 4: 编译验证**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`（`RouteDrawActivity` 还没在 manifest 注册，此时不会报错）

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/iterlocus/pathway/RouteDrawActivity.java app/src/main/res/layout/activity_route_draw.xml app/src/main/res/layout/route_draw_tools.xml app/src/main/res/values/strings.xml
git commit -m "feat: 绘制路线界面骨架（地图、绘制层、工具面板、地图锁定）"
```

---

### Task 8: 工具面板动作（密化 / 撤销 / 清空 / 保存）

**Files:**
- Modify: `app/src/main/java/com/iterlocus/pathway/RouteDrawActivity.java`

**Interfaces:**
- Consumes: `RouteGeometry.densify`、`RouteNameValidator`、`DataBaseRoute.nameExists/insertRoute/queryAllNames`
- Produces: `RouteDrawActivity` 的 `undo()`、`showDensifyDialog()`、`confirmClear()`、`showSaveDialog()`

**撤销语义（读懂了再写）：** 监听器在状态**改变之后**触发，所以每次压入的是新状态。撤销 = 弹出栈顶（丢弃当前状态），恢复到新的栈顶；栈空则不做事。

- [ ] **Step 1: 接线四个按钮**

在 `onCreate` 的 `updateUndoButton();` 之前插入：

```java
        findViewById(R.id.route_draw_btn_densify).setOnClickListener(v -> showDensifyDialog());
        findViewById(R.id.route_draw_btn_undo).setOnClickListener(v -> undo());
        findViewById(R.id.route_draw_btn_clear).setOnClickListener(v -> confirmClear());
        findViewById(R.id.route_draw_btn_finish).setOnClickListener(v -> showSaveDialog());
```

- [ ] **Step 2: 补实现方法**

在 `updateStatusText()` 之前插入：

```java
    /** 撤销一步：丢弃当前状态，恢复到上一个快照。 */
    private void undo() {
        if (mUndoStack.isEmpty()) {
            GoUtils.DisplayToast(this, getResources().getString(R.string.route_draw_undo_empty));
            return;
        }
        mUndoStack.remove(mUndoStack.size() - 1);   // 丢掉「当前」

        if (mUndoStack.isEmpty()) {
            mOverlay.setPoints(new ArrayList<LatLng>());
            mOverlay.setClosed(false);
        } else {
            Snapshot previous = mUndoStack.get(mUndoStack.size() - 1);
            mOverlay.setPoints(previous.points);
            mOverlay.setClosed(previous.closed);
        }
        updateStatusText();
        updateUndoButton();
    }

    /** 密化：弹框输入加点数 N，按总长等距插入 N 个新点。 */
    private void showDensifyDialog() {
        final List<LatLng> current = mOverlay.getPoints();
        if (current.size() < RouteGeometry.MIN_POINTS_FOR_CLOSE) {
            GoUtils.DisplayToast(this,
                    getResources().getString(R.string.route_draw_densify_no_route));
            return;
        }

        final EditText input = new EditText(this);
        input.setHint(R.string.route_draw_densify_hint);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        int padding = (int) (getResources().getDisplayMetrics().density * 20);
        input.setPadding(padding, padding / 2, padding, 0);

        new AlertDialog.Builder(this)
                .setTitle(R.string.route_draw_densify_title)
                .setMessage(R.string.route_draw_densify_note)
                .setView(input)
                .setPositiveButton(R.string.app_dialog_confirm, (dialog, which) -> {
                    int addCount;
                    try {
                        addCount = Integer.parseInt(input.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        GoUtils.DisplayToast(this,
                                getResources().getString(R.string.route_draw_densify_invalid));
                        return;
                    }
                    if (addCount <= 0) {
                        GoUtils.DisplayToast(this,
                                getResources().getString(R.string.route_draw_densify_invalid));
                        return;
                    }

                    double before = RouteGeometry.totalLengthMeters(current, mOverlay.isClosed());
                    List<LatLng> densified = RouteGeometry.densify(
                            current, mOverlay.isClosed(), addCount);
                    if (before <= 0d || densified.size() == current.size()) {
                        GoUtils.DisplayToast(this,
                                getResources().getString(R.string.route_draw_densify_no_route));
                        return;
                    }

                    mOverlay.setPoints(densified);
                    onRouteChanged();
                    GoUtils.DisplayToast(this, getResources().getString(
                            R.string.route_draw_densify_done, densified.size() - current.size()));
                })
                .setNegativeButton(R.string.app_dialog_cancel, null)
                .show();
    }

    /** 清空不可撤销，二次确认。 */
    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.route_draw_clear_title)
                .setMessage(R.string.route_draw_clear_message)
                .setPositiveButton(R.string.app_dialog_confirm, (dialog, which) -> {
                    mOverlay.clearRoute();
                    mUndoStack.clear();          // 清空后没有可回到的状态
                    updateStatusText();
                    updateUndoButton();
                })
                .setNegativeButton(R.string.app_dialog_cancel, null)
                .show();
    }

    /** 保存：校验收名称与重名，再写库。 */
    private void showSaveDialog() {
        final List<LatLng> points = mOverlay.getPoints();
        if (points.size() < RouteGeometry.MIN_POINTS_FOR_CLOSE) {
            GoUtils.DisplayToast(this,
                    getResources().getString(R.string.route_draw_save_too_few_points));
            return;
        }

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (getResources().getDisplayMetrics().density * 20);
        container.setPadding(padding, padding / 2, padding, 0);

        final EditText nameInput = new EditText(this);
        nameInput.setHint(R.string.route_draw_save_name_hint);
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT);
        container.addView(nameInput);

        // 列出已有名称：既让用户知道哪些名字被占用（重名会被拒），也验证保存确实生效
        TextView existing = new TextView(this);
        List<String> names = mRouteDb == null
                ? new ArrayList<String>()
                : DataBaseRoute.queryAllNames(mRouteDb);
        existing.setTextSize(12);
        if (names.isEmpty()) {
            existing.setText(R.string.route_draw_save_none);
        } else {
            StringBuilder builder = new StringBuilder(
                    getResources().getString(R.string.route_draw_save_existing));
            for (String name : names) {
                builder.append("\n· ").append(name);
            }
            existing.setText(builder.toString());
        }
        container.addView(existing);

        new AlertDialog.Builder(this)
                .setTitle(R.string.route_draw_save_title)
                .setView(container)
                .setPositiveButton(R.string.app_dialog_confirm, (dialog, which) ->
                        saveRoute(nameInput.getText().toString()))
                .setNegativeButton(R.string.app_dialog_cancel, null)
                .show();
    }

    private void saveRoute(String rawName) {
        RouteNameValidator.Problem problem = RouteNameValidator.findProblem(rawName);
        if (problem != null) {
            GoUtils.DisplayToast(this, describeNameProblem(problem));
            return;
        }

        String name = RouteNameValidator.normalize(rawName);

        if (mRouteDb == null) {
            GoUtils.DisplayToast(this, getResources().getString(R.string.route_draw_save_failed));
            return;
        }
        if (DataBaseRoute.nameExists(mRouteDb, name)) {
            GoUtils.DisplayToast(this,
                    getResources().getString(R.string.route_draw_save_duplicate));
            return;
        }

        long rowId = DataBaseRoute.insertRoute(
                mRouteDb, name, mOverlay.isClosed(), mOverlay.getPoints());
        if (rowId < 0) {
            // 撞上 UNIQUE 约束（并发兜底）也走重名提示
            GoUtils.DisplayToast(this, DataBaseRoute.nameExists(mRouteDb, name)
                    ? getResources().getString(R.string.route_draw_save_duplicate)
                    : getResources().getString(R.string.route_draw_save_failed));
            return;
        }

        GoUtils.DisplayToast(this, getResources().getString(R.string.route_draw_save_ok));
    }

    private String describeNameProblem(RouteNameValidator.Problem problem) {
        switch (problem) {
            case TOO_LONG:
                return getResources().getString(R.string.route_draw_name_too_long);
            case CONTROL_CHAR:
                return getResources().getString(R.string.route_draw_name_control_char);
            case FORBIDDEN_CHAR:
                return getResources().getString(R.string.route_draw_name_forbidden_char);
            case EMPTY:
            default:
                return getResources().getString(R.string.route_draw_name_empty);
        }
    }
```

顶部补 import：

```java
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.appcompat.app.AlertDialog;
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 跑全部单元测试**

Run: `./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`，含 `RouteGeometryTest`、`RouteNameValidatorTest`、`LatLngProbeTest`、既有的 `ExampleUnitTest`

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/iterlocus/pathway/RouteDrawActivity.java
git commit -m "feat: 绘制路线的密化、撤销、清空与保存"
```

---

### Task 9: 入口接线与文档

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/menu/menu_nav.xml`
- Modify: `app/src/main/java/com/iterlocus/pathway/MainActivity.java`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: `RouteDrawActivity`
- Produces: 侧滑菜单项 `nav_route_draw`

- [ ] **Step 1: 注册 Activity**

在 `AndroidManifest.xml` 的 `.NfcCardActivity` 之后插入：

```xml
        <!-- 锁定竖屏：本界面持有 MapView、GL 覆盖层、一次性定位客户端，
             以及用户正在画的那条路线。旋转会重建 Activity，把整条绘制连同撤销栈一起丢掉，
             而 onSaveInstanceState 只能救点集、救不了 MapView 与 GL 上下文。
             代价是失去横屏作画的空间——若日后要做横屏，应改为持久化点集而非直接解锁。 -->
        <activity
            android:name=".RouteDrawActivity"
            android:label="@string/app_route_draw"
            android:exported="false"
            android:screenOrientation="portrait" />
```

- [ ] **Step 2: 加侧滑入口**

`menu_nav.xml` 第一个 `<group>` 内、`nav_nfc_card` 之后插入：

```xml
        <item
            android:id="@+id/nav_route_draw"
            android:icon="@drawable/ic_menu_nfc"
            android:title="@string/nav_menu_route_draw" />
```

（暂时复用 NFC 图标；想换图标时新增一个 drawable 再改这里。）

- [ ] **Step 3: MainActivity 处理菜单项**

在 `initNavigationView()` 里 `nav_nfc_card` 分支之后插入：

```java
            } else if (id == R.id.nav_route_draw) {
                Intent intent = new Intent(MainActivity.this, RouteDrawActivity.class);

                startActivity(intent);
            } else if (id == R.id.nav_settings) {
```

（`nav_settings` 那一行只是用来定位插入点，它在文件里本来就有，不要重复添加。）

- [ ] **Step 4: 编译并跑全量检查**

Run: `./gradlew assembleDebug lintDebug testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 补 CLAUDE.md**

在 `## Architecture` 一节的 `### NFC 位置卡` 之前插入：

```markdown
### 绘制路线

`RouteDrawActivity` 在百度地图上画路线：点绘制（点击落点）与线绘制（按住拖动采样）两种模式，
可闭合（两种模式都支持，点数不足 2 时拒绝），可按总长等距密化（「密化」按钮弹框输入**加点数 N**，
总长等分 N+1 段），保存进 `RouteConfig.db` 的 `RouteConfig` 表。

- **`RouteGeometry` 与 `RouteNameValidator` 是纯逻辑**，不依赖 Android，可在普通 JVM 单元测试里跑。
  几何计算刻意收 `int` 像素而不是 `android.graphics.Point`——碰 Android 类会抛 "not mocked"。
- **存的坐标是 BD09**（地图原生）。后续线路模拟调用时必须逐个 `MapUtils.bd2wgs()` 转 WGS84
  再喂给 `ServiceGo`，因为 `setTestProviderLocation` 要的是 WGS84。注意 `MapUtils` 入参是
  **(经度, 纬度)**，而 `LatLng` 构造是 **(纬度, 经度)**。
- **同名路线拒绝写入**，不覆盖、不自动改名；名称先过 `RouteNameValidator`（trim、1–32 字符、
  禁控制字符、禁 `/\:*?"<>|`）。数据库列另有 `COLLATE NOCASE UNIQUE` 兜底。
- 地图手势与绘制手势靠工具面板的「锁定地图」互斥：锁定时 `setAllGesturesEnabled(false)`
  且绘制层消费触摸，解锁后相反。
- `RouteDrawActivity` 含 `MapView`，必须转发 `onResume`/`onPause`/`onDestroy`；
  且保持默认启动模式。
```

- [ ] **Step 6: 提交**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/res/menu/menu_nav.xml app/src/main/java/com/iterlocus/pathway/MainActivity.java CLAUDE.md
git commit -m "feat: 绘制路线入口接线，并补文档"
```

---

## 完成标准

- `./gradlew assembleDebug lintDebug testDebugUnitTest` 全绿
- `RouteGeometryTest` 22 个用例、`RouteNameValidatorTest` 9 个用例全过
- 装机后可：进入绘制界面 → **地图自动居中到当前位置**（定位失败则提示手动平移）→ 锁定地图 → 点绘/线绘 → 闭合 → 密化 → 保存 → 再打开保存弹框能看到刚存的名称
- 重名保存被拒并提示换名；非法名称（空、超长、含 `/` 或换行）被拒并提示具体原因

## 明确不做

- 线路模拟（读 `RouteConfig` 驱动 `ServiceGo`）
- 已保存路线的列表、编辑、删除界面
- 从 NFC 位置卡进入绘制界面

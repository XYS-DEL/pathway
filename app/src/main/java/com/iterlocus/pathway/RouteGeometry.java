package com.iterlocus.pathway;

import com.baidu.mapapi.model.LatLng;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
}

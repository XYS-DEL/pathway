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

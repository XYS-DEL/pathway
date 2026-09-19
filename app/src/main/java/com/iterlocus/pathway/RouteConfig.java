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

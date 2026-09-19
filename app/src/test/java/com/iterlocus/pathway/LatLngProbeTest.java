package com.iterlocus.pathway;

import static org.junit.Assert.assertEquals;

import com.baidu.mapapi.model.LatLng;

import org.junit.Test;

/**
 * 守卫测试：确认 {@link LatLng} 能在普通 JVM 单元测试里构造。
 *
 * <p>纯逻辑类（{@code RouteGeometry}、{@code RouteNameValidator}）刻意做成不依赖 Android，
 * 才能在 {@code testDebugUnitTest} 里跑。但 {@code LatLng} 来自百度 jar，而单元测试跑的是
 * android.jar 的桩——碰 Android 方法会抛 "not mocked"。
 *
 * <p>已核实 {@code LatLng(double, double)} 的字节码只调 {@code Double.isNaN/isInfinite}
 * 后赋值字段，没有任何 Android 调用，因此可用。这个测试把该结论钉住：若将来 Gradle 配置
 * （如 {@code returnDefaultValues}）或百度 jar 变化打破了它，这里会先失败，而不是让
 * {@code RouteGeometryTest} 里一堆看不懂的报错去解释。
 */
public class LatLngProbeTest {

    @Test
    public void latLngIsConstructibleInPlainUnitTest() {
        LatLng point = new LatLng(39.915, 116.404);
        assertEquals(39.915, point.latitude, 1e-9);
        assertEquals(116.404, point.longitude, 1e-9);
    }
}

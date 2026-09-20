package com.iterlocus.pathway;

/**
 * 路线模拟的运动引擎：纯逻辑，与 Android 无关，可在普通 JVM 单元测试里跑。
 *
 * <p><b>坐标一律是 WGS84。</b>入参 {@code double[][]} 每个元素是 {@code {经度, 纬度}}，
 * 正是 {@link com.iterlocus.pathway.utils.MapUtils#bd2wgs(double, double)} 的返回形状。
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

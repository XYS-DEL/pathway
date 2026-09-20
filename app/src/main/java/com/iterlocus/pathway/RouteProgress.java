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

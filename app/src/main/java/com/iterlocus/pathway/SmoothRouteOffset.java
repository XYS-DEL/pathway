package com.iterlocus.pathway;

import java.util.Random;

/** Continuous bounded meter offset for long-running route playback. */
public final class SmoothRouteOffset {
    private static final double MIN_SEGMENT_SECONDS = 20d;
    private static final double SEGMENT_RANDOM_SECONDS = 20d;
    private static final double MAX_DRIFT_SPEED_MPS = 0.35d;

    private final double mMaxEastMeters;
    private final double mMaxNorthMeters;
    private final Random mRandom;

    private double mEastMeters;
    private double mNorthMeters;
    private double mStartEastMeters;
    private double mStartNorthMeters;
    private double mTargetEastMeters;
    private double mTargetNorthMeters;
    private double mElapsedSeconds;
    private double mDurationSeconds;

    public SmoothRouteOffset(double maxEastMeters, double maxNorthMeters) {
        this(maxEastMeters, maxNorthMeters, new Random());
    }

    SmoothRouteOffset(double maxEastMeters, double maxNorthMeters, Random random) {
        mMaxEastMeters = finiteAbsolute(maxEastMeters);
        mMaxNorthMeters = finiteAbsolute(maxNorthMeters);
        mRandom = random == null ? new Random() : random;
        chooseNextTarget();
    }

    /** Advances the smooth drift. Pausing is handled by not calling this method. */
    public void advance(double dtSeconds) {
        if (!(dtSeconds > 0d) || !Double.isFinite(dtSeconds)) {
            return;
        }

        double remaining = dtSeconds;
        while (remaining > 0d) {
            double segmentRemaining = mDurationSeconds - mElapsedSeconds;
            double step = Math.min(remaining, segmentRemaining);
            mElapsedSeconds += step;
            remaining -= step;
            updateCurrentOffset();

            if (mElapsedSeconds >= mDurationSeconds) {
                mEastMeters = mTargetEastMeters;
                mNorthMeters = mTargetNorthMeters;
                chooseNextTarget();
            }
        }
    }

    public double getEastMeters() {
        return mEastMeters;
    }

    public double getNorthMeters() {
        return mNorthMeters;
    }

    private void chooseNextTarget() {
        mStartEastMeters = mEastMeters;
        mStartNorthMeters = mNorthMeters;

        // sqrt gives uniform area density instead of clustering points near the center.
        double radius = Math.sqrt(mRandom.nextDouble());
        double angle = mRandom.nextDouble() * Math.PI * 2d;
        mTargetEastMeters = radius * Math.cos(angle) * mMaxEastMeters;
        mTargetNorthMeters = radius * Math.sin(angle) * mMaxNorthMeters;

        double distance = Math.hypot(
                mTargetEastMeters - mStartEastMeters,
                mTargetNorthMeters - mStartNorthMeters);
        double speedLimitedDuration = 1.5d * distance / MAX_DRIFT_SPEED_MPS;
        mDurationSeconds = Math.max(
                MIN_SEGMENT_SECONDS + mRandom.nextDouble() * SEGMENT_RANDOM_SECONDS,
                speedLimitedDuration);
        mElapsedSeconds = 0d;
    }

    private void updateCurrentOffset() {
        double progress = Math.min(1d, mElapsedSeconds / mDurationSeconds);
        double smooth = progress * progress * (3d - 2d * progress);
        mEastMeters = interpolate(mStartEastMeters, mTargetEastMeters, smooth);
        mNorthMeters = interpolate(mStartNorthMeters, mTargetNorthMeters, smooth);
    }

    private static double interpolate(double start, double end, double progress) {
        return start + (end - start) * progress;
    }

    private static double finiteAbsolute(double value) {
        return Double.isFinite(value) ? Math.abs(value) : 0d;
    }
}

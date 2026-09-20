package com.iterlocus.pathway;

import java.util.Random;

/** Smooth bounded one-dimensional random drift. */
public final class SmoothValueOffset {
    private final double mMaximum;
    private final double mMinimumDurationSeconds;
    private final double mRandomDurationSeconds;
    private final double mMaximumChangePerSecond;
    private final Random mRandom;

    private double mValue;
    private double mStart;
    private double mTarget;
    private double mElapsed;
    private double mDuration;

    public SmoothValueOffset(double maximum, double minimumDurationSeconds,
                             double randomDurationSeconds, double maximumChangePerSecond) {
        this(maximum, minimumDurationSeconds, randomDurationSeconds,
                maximumChangePerSecond, new Random());
    }

    SmoothValueOffset(double maximum, double minimumDurationSeconds,
                      double randomDurationSeconds, double maximumChangePerSecond, Random random) {
        mMaximum = finiteAbsolute(maximum);
        mMinimumDurationSeconds = positiveOrDefault(minimumDurationSeconds, 1d);
        mRandomDurationSeconds = finiteAbsolute(randomDurationSeconds);
        mMaximumChangePerSecond = positiveOrDefault(maximumChangePerSecond, 0.1d);
        mRandom = random == null ? new Random() : random;
        chooseTarget();
    }

    public void advance(double dtSeconds) {
        if (!(dtSeconds > 0d) || !Double.isFinite(dtSeconds)) {
            return;
        }
        double remaining = dtSeconds;
        while (remaining > 0d) {
            double step = Math.min(remaining, mDuration - mElapsed);
            mElapsed += step;
            remaining -= step;
            double progress = Math.min(1d, mElapsed / mDuration);
            double smooth = progress * progress * (3d - 2d * progress);
            mValue = mStart + (mTarget - mStart) * smooth;
            if (mElapsed >= mDuration) {
                mValue = mTarget;
                chooseTarget();
            }
        }
    }

    public double getValue() {
        return mValue;
    }

    private void chooseTarget() {
        mStart = mValue;
        mTarget = (mRandom.nextDouble() * 2d - 1d) * mMaximum;
        double distance = Math.abs(mTarget - mStart);
        double speedLimitedDuration = 1.5d * distance / mMaximumChangePerSecond;
        mDuration = Math.max(
                mMinimumDurationSeconds + mRandom.nextDouble() * mRandomDurationSeconds,
                speedLimitedDuration);
        mElapsed = 0d;
    }

    private static double finiteAbsolute(double value) {
        return Double.isFinite(value) ? Math.abs(value) : 0d;
    }

    private static double positiveOrDefault(double value, double fallback) {
        return Double.isFinite(value) && value > 0d ? value : fallback;
    }
}

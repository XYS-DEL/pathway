package com.iterlocus.pathway;

/** Android-free local meter offset conversion for map coordinates. */
public final class LocationOffset {
    private static final double METERS_PER_LATITUDE_DEGREE = 110_574d;
    private static final double METERS_PER_LONGITUDE_DEGREE_AT_EQUATOR = 111_320d;

    private LocationOffset() {
    }

    /** Returns {@code {longitude, latitude}} after applying east/north offsets in meters. */
    public static double[] applyMeters(double longitude, double latitude,
                                       double eastMeters, double northMeters) {
        if (!Double.isFinite(longitude) || !Double.isFinite(latitude)
                || !Double.isFinite(eastMeters) || !Double.isFinite(northMeters)) {
            return new double[]{longitude, latitude};
        }

        double resultLatitude = latitude + northMeters / METERS_PER_LATITUDE_DEGREE;
        double metersPerLongitudeDegree = METERS_PER_LONGITUDE_DEGREE_AT_EQUATOR
                * Math.cos(Math.toRadians(latitude));
        double resultLongitude = longitude;
        if (Math.abs(metersPerLongitudeDegree) > 1e-6d) {
            resultLongitude += eastMeters / metersPerLongitudeDegree;
        }
        return new double[]{resultLongitude, resultLatitude};
    }
}

package com.example.realmap.coord;

/**
 * Converts between GPS coordinates (lat/lon) and Minecraft block coordinates.
 *
 * World origin (blockX=0, blockZ=0) maps to the configured anchor lat/lon.
 * Longitude increases → +X. Latitude increases → -Z (north = -Z in Minecraft).
 */
public class GeoCoordTransformer {

    private static final double METERS_PER_DEG_LAT = 111320.0;

    private final double anchorLat;
    private final double anchorLon;
    private final double scaleMetersPerBlock;

    // derived, cached
    private final double metersPerDegLon;

    public GeoCoordTransformer(double anchorLat, double anchorLon, double scaleMetersPerBlock) {
        this.anchorLat = anchorLat;
        this.anchorLon = anchorLon;
        this.scaleMetersPerBlock = scaleMetersPerBlock;
        this.metersPerDegLon = METERS_PER_DEG_LAT * Math.cos(Math.toRadians(anchorLat));
    }

    /** GPS → block X (east-west). */
    public double lonToBlockX(double lon) {
        return (lon - anchorLon) * metersPerDegLon / scaleMetersPerBlock;
    }

    /** GPS → block Z (north-south). Latitude increases → blockZ decreases (north = -Z). */
    public double latToBlockZ(double lat) {
        return -((lat - anchorLat) * METERS_PER_DEG_LAT / scaleMetersPerBlock);
    }

    /** Block X → longitude. */
    public double blockXToLon(double blockX) {
        return anchorLon + (blockX * scaleMetersPerBlock) / metersPerDegLon;
    }

    /** Block Z → latitude. */
    public double blockZToLat(double blockZ) {
        return anchorLat - (blockZ * scaleMetersPerBlock) / METERS_PER_DEG_LAT;
    }

    // --- convenience int-block versions ---

    public int lonToBlockXInt(double lon) {
        return (int) Math.floor(lonToBlockX(lon));
    }

    public int latToBlockZInt(double lat) {
        return (int) Math.floor(latToBlockZ(lat));
    }

    public double getAnchorLat()            { return anchorLat; }
    public double getAnchorLon()            { return anchorLon; }
    public double getScaleMetersPerBlock()  { return scaleMetersPerBlock; }
}

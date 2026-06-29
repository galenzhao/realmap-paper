package com.example.realmap.coord;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GeoCoordTransformerTest {

    // Anchor: Times Square, NYC  (approx)
    private static final double ANCHOR_LAT = 40.7580;
    private static final double ANCHOR_LON = -73.9855;
    private static final double SCALE = 1.0; // 1 meter = 1 block

    private final GeoCoordTransformer t = new GeoCoordTransformer(ANCHOR_LAT, ANCHOR_LON, SCALE);

    @Test
    void anchorMapsToOrigin() {
        assertEquals(0.0, t.lonToBlockX(ANCHOR_LON), 0.001);
        assertEquals(0.0, t.latToBlockZ(ANCHOR_LAT), 0.001);
    }

    @Test
    void roundTripX() {
        double lon = -73.9800;
        double blockX = t.lonToBlockX(lon);
        double recovered = t.blockXToLon(blockX);
        assertEquals(lon, recovered, 1e-9);
    }

    @Test
    void roundTripZ() {
        double lat = 40.7600;
        double blockZ = t.latToBlockZ(lat);
        double recovered = t.blockZToLat(blockZ);
        assertEquals(lat, recovered, 1e-9);
    }

    @Test
    void eastIsPositiveX() {
        // Moving east (lon increases) → blockX increases
        double blockX = t.lonToBlockX(ANCHOR_LON + 0.001);
        assertTrue(blockX > 0, "East should be +X, got " + blockX);
    }

    @Test
    void northIsNegativeZ() {
        // Moving north (lat increases) → blockZ decreases (Minecraft convention)
        double blockZ = t.latToBlockZ(ANCHOR_LAT + 0.001);
        assertTrue(blockZ < 0, "North should be -Z, got " + blockZ);
    }

    @Test
    void knownDistanceApprox() {
        // ~111 meters north of anchor (0.001 deg lat ≈ 111 m) should be ~111 blocks away
        double blockZ = t.latToBlockZ(ANCHOR_LAT + 0.001);
        assertEquals(-111.32, blockZ, 1.0); // within 1 block
    }

    @Test
    void scaleFactorRespected() {
        GeoCoordTransformer half = new GeoCoordTransformer(ANCHOR_LAT, ANCHOR_LON, 2.0);
        double blockZ1 = t.latToBlockZ(ANCHOR_LAT + 0.001);
        double blockZ2 = half.latToBlockZ(ANCHOR_LAT + 0.001);
        assertEquals(blockZ1 / 2.0, blockZ2, 0.01);
    }
}

package com.example.realmap.arnis;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * M3 prep: test whether --projection web_mercator places .mca files at
 * globally-correct chunk coordinates, making direct file-copy viable.
 *
 * We run Arnis twice with the same small bbox, once with local (default)
 * and once with web_mercator, then compare which .mca region files appear.
 *
 * Anchor point: 40.7580, -73.9855 (Times Square, NYC)
 * Tile (0,0): bbox covering blockX 0..127, blockZ 0..127
 *   → roughly lat 40.7580..40.7591, lon -73.9855..-73.9840
 */
public class ArnisProjectionTest {

    static final String ARNIS_EXE = System.getenv().getOrDefault("ARNIS_EXE",
        java.nio.file.Path.of("arnis", "arnis-windows.exe").toAbsolutePath().toString());
    static final String BASE_DIR  = System.getProperty("java.io.tmpdir") + "\\arnis_proj_test";

    // Anchor: Times Square NYC
    static final double ANCHOR_LAT = 40.7580;
    static final double ANCHOR_LON = -73.9855;
    static final double SCALE      = 1.0; // 1 meter per block

    public static void main(String[] args) throws Exception {
        // bbox for tile(0,0): 128x128 blocks starting at anchor
        // ~128 blocks = 128 meters
        double metersPerDegLat = 111320.0;
        double metersPerDegLon = 111320.0 * Math.cos(Math.toRadians(ANCHOR_LAT));

        double minLat = ANCHOR_LAT;
        double maxLat = ANCHOR_LAT + (128 * SCALE) / metersPerDegLat;
        double minLon = ANCHOR_LON;
        double maxLon = ANCHOR_LON + (128 * SCALE) / metersPerDegLon;

        String bbox = String.format("%.6f,%.6f,%.6f,%.6f", minLat, minLon, maxLat, maxLon);
        System.out.println("BBox for tile(0,0): " + bbox);
        System.out.println();

        runAndReport("local",        bbox, BASE_DIR + "\\local");
        runAndReport("web_mercator", bbox, BASE_DIR + "\\web_mercator");
    }

    static void runAndReport(String projection, String bbox, String outDir) throws Exception {
        Files.createDirectories(Path.of(outDir));
        System.out.println("=== Projection: " + projection + " ===");

        List<String> cmd = new ArrayList<>(Arrays.asList(
            ARNIS_EXE,
            "--bbox",       bbox,
            "--output-dir", outDir,
            "--scale",      "1",
            "--projection", projection,
            "--interior",   "false",
            "--roof",       "false",
            "--no-3d",
            "--land-cover", "false"
        ));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // only print progress lines to reduce noise
                if (line.contains("[") || line.contains("Created") || line.contains("Warning"))
                    System.out.println("  " + line.trim());
            }
        }
        process.waitFor(10, TimeUnit.MINUTES);
        System.out.println("  Exit: " + process.exitValue());

        // Find and list all .mca files
        File outFile = new File(outDir);
        listMca(outFile, "  ");
        System.out.println();
    }

    static void listMca(File dir, String indent) {
        if (!dir.isDirectory()) return;
        for (File f : dir.listFiles()) {
            if (f.isDirectory()) listMca(f, indent + "  ");
            else if (f.getName().endsWith(".mca"))
                System.out.println(indent + f.getPath().replace(System.getProperty("java.io.tmpdir"), "<tmp>")
                    + "  (" + f.length() / 1024 + " KB)");
        }
    }
}

package com.example.realmap.arnis;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * M2 verification: run Arnis via ProcessBuilder against a fixed bbox,
 * then confirm .mca region files appear in the output directory.
 *
 * Run this as a plain Java main() from IDEA — not a JUnit test,
 * because Arnis can take several minutes and would time out a test runner.
 *
 * Small bbox used: a ~300m x 300m block in Times Square, NYC.
 * Adjust ARNIS_EXE and OUTPUT_DIR as needed.
 */
public class ArnisSubprocessTest {

    static final String ARNIS_EXE  = System.getenv().getOrDefault("ARNIS_EXE",
        java.nio.file.Path.of("arnis", "arnis-windows.exe").toAbsolutePath().toString());
    static final String OUTPUT_DIR = System.getProperty("java.io.tmpdir") + "\\arnis_m2_test";

    // Small bbox: ~300m x 300m in NYC Times Square
    static final String BBOX = "40.7570,-73.9870,40.7590,-73.9840";

    public static void main(String[] args) throws Exception {
        Path outputPath = Path.of(OUTPUT_DIR);
        Files.createDirectories(outputPath);
        System.out.println("Output dir: " + outputPath.toAbsolutePath());

        List<String> cmd = List.of(
            ARNIS_EXE,
            "--bbox",     BBOX,
            "--output-dir", OUTPUT_DIR,
            "--scale",    "1",
            "--interior", "false",   // skip interiors for speed
            "--roof",     "false",   // skip roofs for speed
            "--no-3d",               // skip 3D models for speed
            "--land-cover", "false"  // skip satellite land cover for speed
        );

        System.out.println("Running: " + String.join(" ", cmd));
        System.out.println("(This may take 1-5 minutes depending on network speed)");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);   // merge stderr into stdout
        Process process = pb.start();

        // Stream output live so we can see progress
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("[arnis] " + line);
            }
        }

        boolean finished = process.waitFor(10, TimeUnit.MINUTES);
        int exitCode = finished ? process.exitValue() : -1;
        System.out.println("Exit code: " + exitCode + (finished ? "" : " (TIMED OUT)"));

        // Check for .mca region files
        File regionDir = new File(OUTPUT_DIR, "region");
        if (!regionDir.exists()) {
            // Arnis may put them directly or under a world subdirectory
            regionDir = findRegionDir(outputPath.toFile());
        }

        if (regionDir != null && regionDir.exists()) {
            File[] mcaFiles = regionDir.listFiles((d, n) -> n.endsWith(".mca"));
            if (mcaFiles != null && mcaFiles.length > 0) {
                System.out.println("\n✓ SUCCESS: Found " + mcaFiles.length + " .mca file(s) in " + regionDir);
                for (File f : mcaFiles) {
                    System.out.println("  " + f.getName() + " (" + f.length() / 1024 + " KB)");
                }
            } else {
                System.out.println("\n✗ FAIL: region/ dir exists but no .mca files found");
            }
        } else {
            System.out.println("\n✗ FAIL: no region/ directory found in output");
            System.out.println("Output dir contents:");
            printTree(outputPath.toFile(), "  ");
        }
    }

    private static File findRegionDir(File dir) {
        if (dir.isDirectory()) {
            if (dir.getName().equals("region")) return dir;
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    File found = findRegionDir(child);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }

    private static void printTree(File dir, String indent) {
        File[] entries = dir.listFiles();
        if (entries == null) return;
        for (File e : entries) {
            System.out.println(indent + e.getName() + (e.isDirectory() ? "/" : " (" + e.length() + " bytes)"));
            if (e.isDirectory()) printTree(e, indent + "  ");
        }
    }
}

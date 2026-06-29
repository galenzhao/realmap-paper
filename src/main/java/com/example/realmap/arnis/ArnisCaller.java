package com.example.realmap.arnis;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Invokes the Arnis CLI executable via ProcessBuilder.
 * Each call generates one tile (local projection) into an isolated staging directory.
 * The caller is responsible for cleaning up the staging directory after merging.
 */
public class ArnisCaller {

    private final String arnisBinaryPath;
    private final long timeoutSeconds;
    private final Logger log;

    public ArnisCaller(String arnisBinaryPath, long timeoutSeconds, Logger log) {
        this.arnisBinaryPath = arnisBinaryPath;
        this.timeoutSeconds = timeoutSeconds;
        this.log = log;
    }

    /**
     * Generates a tile into a fresh staging directory.
     *
     * @param bbox            "minLat,minLon,maxLat,maxLon"
     * @param stagingParent   parent directory; a unique sub-dir will be created inside
     * @return path to the generated world directory (contains region/ folder)
     * @throws ArnisBuildException if Arnis exits non-zero or times out
     */
    public Path generate(String bbox, Path stagingParent) throws ArnisBuildException, IOException, InterruptedException {
        Files.createDirectories(stagingParent);

        List<String> cmd = new ArrayList<>(List.of(
            arnisBinaryPath,
            "--bbox",         bbox,
            "--output-dir",   stagingParent.toString(),
            "--scale",        "1",
            "--projection",   "local",
            "--interior",     "false",
            "--roof",         "false",
            "--no-3d",
            "--land-cover",   "false"
        ));

        log.info("[ArnisCaller] Running: " + String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // Stream Arnis stdout/stderr to our plugin logger
        Thread reader = Thread.ofVirtual().start(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    log.info("[arnis] " + line.trim());
                }
            } catch (IOException ignored) {}
        });

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        reader.join(2000);

        if (!finished) {
            process.destroyForcibly();
            throw new ArnisBuildException("Arnis timed out after " + timeoutSeconds + "s for bbox=" + bbox);
        }
        if (process.exitValue() != 0) {
            throw new ArnisBuildException("Arnis exited with code " + process.exitValue() + " for bbox=" + bbox);
        }

        // Arnis creates "Arnis World 1" subdirectory inside stagingParent
        Path worldDir = findWorldDir(stagingParent);
        if (worldDir == null || !worldDir.resolve("region").toFile().exists()) {
            throw new ArnisBuildException("Arnis exited 0 but no region/ dir found under " + stagingParent);
        }
        return worldDir;
    }

    /** Finds the generated world directory (Arnis creates "Arnis World N" subdirectory). */
    private Path findWorldDir(Path parent) throws IOException {
        try (var stream = Files.list(parent)) {
            return stream
                .filter(p -> p.toFile().isDirectory())
                .filter(p -> p.resolve("region").toFile().exists())
                .findFirst()
                .orElse(null);
        }
    }

    public static class ArnisBuildException extends Exception {
        public ArnisBuildException(String msg) { super(msg); }
    }
}

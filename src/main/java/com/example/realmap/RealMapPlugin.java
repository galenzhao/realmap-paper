package com.example.realmap;

import com.example.realmap.arnis.ArnisCaller;
import com.example.realmap.coord.GeoCoordTransformer;
import com.example.realmap.listener.PlayerJoinHandler;
import com.example.realmap.listener.TileBoundaryGuard;
import com.example.realmap.tile.*;
import com.example.realmap.world.VoidChunkGenerator;
import com.mojang.brigadier.Command;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.Comparator;

@SuppressWarnings("UnstableApiUsage")
public class RealMapPlugin extends JavaPlugin {

    public static final String REALMAP_WORLD = "realmap";

    private GeoCoordTransformer transformer;
    private ArnisCaller arnisCaller;
    private TileDatabase db;
    private TileScheduler scheduler;

    private int tileSizeChunks;
    private int maxRetries;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // DEV MODE: wipe all generated data on every startup for clean testing
        if (getConfig().getBoolean("dev.clear_on_startup", false)) {
            clearDevData();
        }

        double anchorLat   = getConfig().getDouble("anchor.lat",   40.7580);
        double anchorLon   = getConfig().getDouble("anchor.lon",  -73.9855);
        double scale       = getConfig().getDouble("anchor.scale_meters_per_block", 1.0);
        String arnisPath   = getConfig().getString("arnis.binary_path",
                                 "arnis/arnis-windows.exe");
        long   timeout     = getConfig().getLong("arnis.timeout_seconds", 300);
        tileSizeChunks     = getConfig().getInt("tile.size_chunks", 8);
        maxRetries         = getConfig().getInt("retry.max_retries", 3);
        int maxConcurrent  = getConfig().getInt("scheduler.max_concurrent_generations", 1);
        long tickInterval  = getConfig().getLong("scheduler.tick_interval_seconds", 2);
        int lookaheadTiles = getConfig().getInt("scheduler.lookahead_tiles", 3);
        int preloadRadius  = getConfig().getInt("scheduler.preload_radius_tiles", 2);
        int bufferTiles    = getConfig().getInt("scheduler.buffer_tiles_omnidirectional", 1);
        boolean blockUnloaded = getConfig().getBoolean("scheduler.block_unloaded_tiles", true);

        transformer = new GeoCoordTransformer(anchorLat, anchorLon, scale);
        arnisCaller = new ArnisCaller(arnisPath, timeout, getLogger());

        // --- SQLite ---
        try {
            db = new TileDatabase(getDataFolder());
            int reset = db.resetInFlight();
            if (reset > 0) getLogger().info("Reset " + reset + " in-flight tile(s) after restart.");
        } catch (SQLException e) {
            getLogger().severe("Failed to open tile database: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // --- RealMap world (void generator) ---
        World rw = Bukkit.getWorld(REALMAP_WORLD);
        if (rw == null) {
            rw = new WorldCreator(REALMAP_WORLD)
                .generator(new VoidChunkGenerator())
                .createWorld();
        }
        if (rw == null) {
            getLogger().severe("Failed to create/load RealMap world!");
            return;
        }
        // Paper keeps spawn chunks force-loaded, which prevents unloadChunk() from working
        // after we merge .mca data. Disabling this lets our unloadChunk calls take effect
        // so Paper re-reads merged chunk data from disk instead of using void cache.
        rw.setKeepSpawnInMemory(false);

        // --- Tile scheduler ---
        Path stagingDir = getDataFolder().toPath().resolve("staging");
        scheduler = new TileScheduler(
            this, db, arnisCaller, transformer,
            REALMAP_WORLD, tileSizeChunks, maxConcurrent, maxRetries, stagingDir,
            preloadRadius, lookaheadTiles, bufferTiles
        );

        // --- First-start detection ---
        String preloadMode = getConfig().getString("scheduler.preload_mode", "center");
        try {
            boolean firstStart = db.countByStatus(TileStatus.MERGED) == 0
                              && db.countByStatus(TileStatus.PENDING) == 0;
            if (firstStart) {
                int queued = queueSpawnTiles(preloadMode);
                getLogger().info("First start — queued " + queued
                    + " tile(s) [mode=" + preloadMode + "].");
            } else {
                getLogger().info("Resumed. Merged=" + db.countByStatus(TileStatus.MERGED)
                    + " Pending=" + (db.countByStatus(TileStatus.PENDING)
                                   + db.countByStatus(TileStatus.QUEUED)));
            }
        } catch (SQLException e) {
            getLogger().warning("DB error during startup: " + e.getMessage());
        }

        try {
            scheduler.loadStatusCache();
        } catch (SQLException e) {
            getLogger().warning("Failed to load tile status cache: " + e.getMessage());
        }

        // --- Start scheduler (now that tiles are in DB) ---
        scheduler.start(tickInterval);

        TileBoundaryGuard boundaryGuard = new TileBoundaryGuard(scheduler, blockUnloaded);

        // --- Listener ---
        getServer().getPluginManager().registerEvents(
            new PlayerJoinHandler(this, db, scheduler, REALMAP_WORLD, boundaryGuard), this);
        getServer().getPluginManager().registerEvents(boundaryGuard, this);

        // --- Commands ---
        LifecycleEventManager<Plugin> manager = this.getLifecycleManager();
        manager.registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands reg = event.registrar();
            reg.register(
                Commands.literal("realmap")
                    .then(Commands.literal("status")
                        .executes(ctx -> { handleStatus(ctx.getSource().getSender()); return Command.SINGLE_SUCCESS; }))
                    .then(Commands.literal("tp")
                        .executes(ctx -> { handleTp(ctx.getSource().getSender()); return Command.SINGLE_SUCCESS; }))
                    .then(Commands.literal("m3test")
                        .executes(ctx -> { handleM3Test(ctx.getSource().getSender()); return Command.SINGLE_SUCCESS; }))
                    .build(),
                "RealMap commands"
            );
        });

        getLogger().info("RealMap enabled. Anchor: " + anchorLat + ", " + anchorLon);
    }

    @Override
    public void onDisable() {
        if (scheduler != null) scheduler.stop();
        if (db != null) db.close();
        getLogger().info("RealMap disabled.");
    }

    @Override
    public @Nullable ChunkGenerator getDefaultWorldGenerator(@NotNull String worldName, @Nullable String id) {
        if (REALMAP_WORLD.equals(worldName)) return new VoidChunkGenerator();
        return null;
    }

    // -------------------------------------------------------------------------

    /**
     * Queue initial tiles based on preload_mode config:
     *   "center"   — only (0,0)
     *   "strip_N"  — (0,0) + N-1 tiles east: (1,0),(2,0),...
     *   "grid_R"   — square from (-R,-R) to (R,R)
     */
    private int queueSpawnTiles(String mode) throws SQLException {
        java.util.List<TileCoord> tiles = new java.util.ArrayList<>();

        if (mode.equals("center")) {
            tiles.add(new TileCoord(0, 0));

        } else if (mode.startsWith("strip_")) {
            int n = Integer.parseInt(mode.substring(6));
            for (int tx = 0; tx < n; tx++) tiles.add(new TileCoord(tx, 0));

        } else if (mode.startsWith("grid_")) {
            int r = Integer.parseInt(mode.substring(5));
            for (int tx = -r; tx <= r; tx++)
                for (int tz = -r; tz <= r; tz++)
                    tiles.add(new TileCoord(tx, tz));

        } else {
            getLogger().warning("Unknown preload_mode '" + mode + "', falling back to center.");
            tiles.add(new TileCoord(0, 0));
        }

        int queued = 0;
        for (TileCoord coord : tiles) {
            if (db.isUnknown(coord)) {
                db.upsert(coord, TileStatus.PENDING);
                queued++;
            }
        }
        return queued;
    }

    // -------------------------------------------------------------------------
    // Commands
    // -------------------------------------------------------------------------

    private void handleStatus(org.bukkit.command.CommandSender sender) {
        try {
            sender.sendMessage("[RealMap] Tile status:");
            sender.sendMessage("  MERGED:     " + db.countByStatus(TileStatus.MERGED));
            sender.sendMessage("  PENDING:    " + db.countByStatus(TileStatus.PENDING));
            sender.sendMessage("  QUEUED:     " + db.countByStatus(TileStatus.QUEUED));
            sender.sendMessage("  GENERATING: " + db.countByStatus(TileStatus.GENERATING));
            sender.sendMessage("  FAILED:     " + db.countByStatus(TileStatus.FAILED));
            sender.sendMessage("  Anchor: " + transformer.getAnchorLat() + ", " + transformer.getAnchorLon());
        } catch (SQLException e) {
            sender.sendMessage("[RealMap] DB error: " + e.getMessage());
        }
    }

    private void handleTp(org.bukkit.command.CommandSender sender) {
        if (!(sender instanceof Player player)) { sender.sendMessage("In-game only."); return; }
        World rw = Bukkit.getWorld(REALMAP_WORLD);
        if (rw == null) { sender.sendMessage("RealMap world not loaded."); return; }
        Location spawn = scheduler.getCachedSpawnLoc();
        if (spawn == null || spawn.getWorld() == null) spawn = rw.getSpawnLocation();
        player.teleport(spawn);
    }

    private void handleM3Test(org.bukkit.command.CommandSender sender) {
        sender.sendMessage("[RealMap] /realmap m3test is deprecated — the async scheduler now handles generation automatically.");
        sender.sendMessage("[RealMap] Use /realmap status to check progress.");
    }

    // -------------------------------------------------------------------------

    private void clearDevData() {
        getLogger().info("[DEV] Full wipe — resetting server to first-boot state...");

        World rw = Bukkit.getWorld(REALMAP_WORLD);
        if (rw != null) {
            boolean unloaded = Bukkit.unloadWorld(rw, false);
            if (!unloaded) {
                getLogger().warning("[DEV] Could not unload realmap — restart via start.bat for a clean wipe.");
            }
        }

        Path worldContainer = getServer().getWorldContainer().toPath();
        Path defaultWorld = resolveDefaultWorldPath(worldContainer);

        // All dimensions: overworld, nether, end, realmap
        safeDelete(defaultWorld.resolve("dimensions"));
        safeDelete(defaultWorld.resolve("players"));
        safeDelete(worldContainer.resolve(REALMAP_WORLD));

        Path pluginDir = getDataFolder().toPath();
        safeDelete(pluginDir.resolve("staging"));
        safeDelete(pluginDir.resolve("tiles.db"));
        safeDelete(pluginDir.resolve("tiles.db-wal"));
        safeDelete(pluginDir.resolve("tiles.db-shm"));

        getLogger().info("[DEV] Wipe complete. Use start.bat so the wipe runs before the JVM locks world files.");
    }

    private Path resolveDefaultWorldPath(Path worldContainer) {
        World overworld = Bukkit.getWorld("world");
        if (overworld != null) {
            return overworld.getWorldFolder().toPath();
        }
        return worldContainer.resolve("world");
    }

    private void safeDelete(Path path) {
        if (!Files.exists(path)) return;
        try {
            if (Files.isDirectory(path)) {
                try (var walk = Files.walk(path)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            getLogger().warning("[DEV] Could not delete " + p + ": " + e.getMessage());
                        }
                    });
                }
            } else {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            getLogger().warning("[DEV] Could not delete " + path + ": " + e.getMessage());
        }
    }
}

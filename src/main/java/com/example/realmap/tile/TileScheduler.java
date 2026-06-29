package com.example.realmap.tile;

import com.example.realmap.arnis.ArnisCaller;
import com.example.realmap.coord.GeoCoordTransformer;
import com.example.realmap.world.VoidChunkGenerator;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Generates tiles on demand as players explore.
 *
 * Preload: every tick, queue unknown tiles within {@code preloadRadiusTiles}
 * around each player, plus {@code lookaheadTiles} extra in movement/facing
 * direction. Worker picks PENDING tiles nearest to any online player first.
 */
public class TileScheduler {

    private final JavaPlugin plugin;
    private final TileDatabase db;
    private final ArnisCaller arnisCaller;
    private final GeoCoordTransformer transformer;
    private final RegionFileMerger merger;
    private final String liveWorldName;

    private final int tileSizeChunks;
    private final int tileSizeBlocks;
    private final int maxConcurrent;
    private final int maxRetries;
    private final int preloadRadiusTiles;
    private final int lookaheadTiles;
    private final int bufferTilesOmnidirectional;

    private final Path stagingBaseDir;

    private final Set<TileCoord> inFlight = ConcurrentHashMap.newKeySet();

    /** In-memory mirror of DB tile statuses — used by boundary guard without per-move SQL. */
    private final Map<TileCoord, TileStatus> tileStatusCache = new ConcurrentHashMap<>();

    // Tracks which tile each player was last seen in (main-thread only)
    private final Map<UUID, TileCoord> playerLastTile = new HashMap<>();

    // Movement or facing direction per player, updated each preload scan
    private final Map<UUID, int[]> playerDirection = new HashMap<>();

    private ExecutorService workers;
    private BukkitTask tickTask;

    private final Set<Runnable> onSpawnReadyCallbacks = ConcurrentHashMap.newKeySet();

    /** Prevents overlapping world reloads when several tiles finish merging close together. */
    private final AtomicBoolean worldReloadInProgress = new AtomicBoolean(false);

    // Cached spawn location set after tile(0,0) merges. Used by join handler directly
    // instead of World.getSpawnLocation() which is unreliable after world reload.
    private volatile org.bukkit.Location cachedSpawnLoc = null;

    public org.bukkit.Location getCachedSpawnLoc() { return cachedSpawnLoc; }

    public int getTileSizeBlocks() { return tileSizeBlocks; }

    public String getLiveWorldName() { return liveWorldName; }

    /** Load all persisted tile statuses into the in-memory cache (call once on startup). */
    public void loadStatusCache() throws SQLException {
        tileStatusCache.clear();
        tileStatusCache.putAll(db.loadAll());
    }

    public TileCoord tileAt(Location loc) {
        return new TileCoord(
            Math.floorDiv(loc.getBlockX(), tileSizeBlocks),
            Math.floorDiv(loc.getBlockZ(), tileSizeBlocks));
    }

    /** True when the tile is safe to enter (MERGED or FALLBACK_FLAT). */
    public boolean isTileTraversable(TileCoord tile) {
        TileStatus status = tileStatusCache.get(tile);
        return status != null && status.isTerminal();
    }

    /** Queue a tile for generation if it has never been recorded. */
    public void ensureQueued(TileCoord tile) {
        try {
            if (db.isUnknown(tile)) {
                recordStatus(tile, TileStatus.PENDING);
                plugin.getLogger().info("Queued " + tile + " from boundary guard.");
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("DB error queuing tile from boundary: " + e.getMessage());
        }
    }

    public TileScheduler(JavaPlugin plugin, TileDatabase db, ArnisCaller arnisCaller,
                         GeoCoordTransformer transformer, String liveWorldName,
                         int tileSizeChunks, int maxConcurrent, int maxRetries,
                         Path stagingBaseDir, int preloadRadiusTiles, int lookaheadTiles,
                         int bufferTilesOmnidirectional) {
        this.plugin          = plugin;
        this.db              = db;
        this.arnisCaller     = arnisCaller;
        this.transformer     = transformer;
        this.liveWorldName   = liveWorldName;
        this.tileSizeChunks  = tileSizeChunks;
        this.tileSizeBlocks  = tileSizeChunks * 16;
        this.maxConcurrent   = maxConcurrent;
        this.maxRetries      = maxRetries;
        this.preloadRadiusTiles = preloadRadiusTiles;
        this.lookaheadTiles  = lookaheadTiles;
        this.bufferTilesOmnidirectional = bufferTilesOmnidirectional;
        this.stagingBaseDir  = stagingBaseDir;
        this.merger          = new RegionFileMerger(tileSizeChunks, plugin.getLogger());
    }

    public void start(long tickIntervalSeconds) {
        workers = Executors.newFixedThreadPool(maxConcurrent, r -> {
            Thread t = new Thread(r, "realmap-worker");
            t.setDaemon(true);
            return t;
        });
        long ticks = tickIntervalSeconds * 20L;
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, ticks, ticks);
        plugin.getLogger().info("TileScheduler started (interval=" + tickIntervalSeconds
            + "s, workers=" + maxConcurrent
            + ", radius=" + preloadRadiusTiles
            + ", lookahead=" + lookaheadTiles
            + ", buffer=" + bufferTilesOmnidirectional + ").");
    }

    public void stop() {
        if (tickTask != null) tickTask.cancel();
        if (workers  != null) {
            workers.shutdown();
            try { workers.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) {}
        }
    }

    public void onSpawnReady(Runnable callback) {
        onSpawnReadyCallbacks.add(callback);
    }

    // -------------------------------------------------------------------------
    // Main tick
    // -------------------------------------------------------------------------

    private void tick() {
        World rw = Bukkit.getWorld(liveWorldName);
        List<Player> players = (rw != null) ? rw.getPlayers() : List.of();

        // Queue tiles around each player; re-runs every tick (not only on tile change)
        scanPlayerPreload(players);

        if (inFlight.size() >= maxConcurrent) return;

        try {
            List<TileCoord> pending = db.getByStatus(TileStatus.PENDING);
            if (pending.isEmpty()) return;

            // Sort by proximity to nearest player; fall back to distance from origin
            pending.sort(priorityComparator(players));

            for (TileCoord tile : pending) {
                if (inFlight.size() >= maxConcurrent) break;
                if (inFlight.contains(tile)) continue;
                inFlight.add(tile);
                recordStatus(tile, TileStatus.QUEUED);
                workers.submit(() -> generateAsync(tile));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("TileScheduler tick DB error: " + e.getMessage());
        }
    }

    /**
     * Every scheduler tick: scan a Chebyshev-radius square around each player,
     * queue unknown tiles, and extend {@code lookaheadTiles} further in the
     * movement (or facing) direction. {@code bufferTilesOmnidirectional} sets
     * the minimum radius even when {@code preload_radius_tiles} is 0.
     */
    private void scanPlayerPreload(List<Player> players) {
        Set<UUID> online = new HashSet<>();
        for (Player p : players) online.add(p.getUniqueId());
        playerLastTile.keySet().retainAll(online);
        playerDirection.keySet().retainAll(online);

        int radius = Math.max(preloadRadiusTiles, bufferTilesOmnidirectional);

        for (Player player : players) {
            TileCoord current = tileAt(player.getLocation());
            TileCoord last = playerLastTile.get(player.getUniqueId());
            playerLastTile.put(player.getUniqueId(), current);

            int[] dir = resolveDirection(player, current, last);
            playerDirection.put(player.getUniqueId(), dir);

            if (radius > 0) {
                for (int ox = -radius; ox <= radius; ox++) {
                    for (int oz = -radius; oz <= radius; oz++) {
                        if (Math.max(Math.abs(ox), Math.abs(oz)) <= radius) {
                            tryQueue(new TileCoord(current.x() + ox, current.z() + oz));
                        }
                    }
                }
            }

            // Extra strip beyond the radius, in movement/facing direction
            for (int i = 1; i <= lookaheadTiles; i++) {
                int dist = radius + i;
                tryQueue(new TileCoord(current.x() + dir[0] * dist, current.z() + dir[1] * dist));
            }
        }
    }

    /** Prefer recent movement; fall back to horizontal facing from yaw. */
    private int[] resolveDirection(Player player, TileCoord current, TileCoord last) {
        if (last != null && !last.equals(current)) {
            return new int[] {
                Integer.signum(current.x() - last.x()),
                Integer.signum(current.z() - last.z())
            };
        }
        float yaw = player.getLocation().getYaw();
        double rad = Math.toRadians(yaw);
        int dx = Integer.signum((int) Math.round(-Math.sin(rad)));
        int dz = Integer.signum((int) Math.round(Math.cos(rad)));
        if (dx == 0 && dz == 0) dz = 1;
        return new int[] { dx, dz };
    }

    private void tryQueue(TileCoord coord) {
        try {
            if (db.isUnknown(coord)) {
                recordStatus(coord, TileStatus.PENDING);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("DB error queuing preload " + coord + ": " + e.getMessage());
        }
    }

    private Comparator<TileCoord> priorityComparator(List<Player> players) {
        if (players.isEmpty()) {
            return Comparator.comparingDouble(t -> t.x() * (double) t.x() + t.z() * (double) t.z());
        }
        return Comparator
            .comparingDouble((TileCoord tile) -> minTileDistanceToPlayers(tile, players))
            .thenComparingInt(TileCoord::x)
            .thenComparingInt(TileCoord::z);
    }

    /** Euclidean distance in tile space from {@code tile} to the nearest online player. */
    private double minTileDistanceToPlayers(TileCoord tile, List<Player> players) {
        double min = Double.MAX_VALUE;
        for (Player p : players) {
            int ptx = Math.floorDiv(p.getLocation().getBlockX(), tileSizeBlocks);
            int ptz = Math.floorDiv(p.getLocation().getBlockZ(), tileSizeBlocks);
            double d = Math.hypot(tile.x() - ptx, tile.z() - ptz);
            if (d < min) min = d;
        }
        return min;
    }

    // -------------------------------------------------------------------------
    // Async worker
    // -------------------------------------------------------------------------

    private void generateAsync(TileCoord tile) {
        plugin.getLogger().info("Starting generation for " + tile);
        Path stagingDir = stagingBaseDir.resolve("tile_" + tile.x() + "_" + tile.z());

        try {
            setStatus(tile, TileStatus.GENERATING);
            if (Files.exists(stagingDir)) deleteDir(stagingDir);

            String bbox = buildBbox(tile);
            plugin.getLogger().info(tile + " bbox: " + bbox);

            Path stagingWorldDir = arnisCaller.generate(bbox, stagingDir);
            setStatus(tile, TileStatus.STAGED);

            setStatus(tile, TileStatus.MERGING);
            World rw = Bukkit.getWorld(liveWorldName);
            if (rw == null) throw new IOException("Live world '" + liveWorldName + "' not found");

            Path liveRegionDir = rw.getWorldFolder().toPath().resolve("region");
            merger.mergeTile(stagingWorldDir.resolve("region"), liveRegionDir, tile);

            Bukkit.getScheduler().runTask(plugin, () -> finishOnMainThread(tile, rw));

        } catch (ArnisCaller.ArnisBuildException | IOException | InterruptedException e) {
            plugin.getLogger().warning("Generation failed for " + tile + ": " + e.getMessage());
            Bukkit.getScheduler().runTask(plugin, () -> handleFailure(tile, e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // Main-thread finalisation
    // -------------------------------------------------------------------------

    private void finishOnMainThread(TileCoord tile, World rw) {
        // Tile(0,0) uses reloadWorldThenSetSpawn (spawn + RegionFile cache).
        // Other tiles: if any chunk in the target .mca region is already loaded,
        // Paper keeps a stale RegionFile header in memory — unloadChunk alone does
        // not re-read merged data from disk and the client keeps seeing void.
        if (tile.x() != 0 || tile.z() != 0) {
            scheduleWorldReloadIfRegionHot(rw, tile);
        }

        try { recordStatus(tile, TileStatus.MERGED); }
        catch (SQLException e) {
            plugin.getLogger().warning("DB error marking MERGED: " + e.getMessage());
        }

        inFlight.remove(tile);
        plugin.getLogger().info(tile + " → MERGED.");

        if (tile.x() == 0 && tile.z() == 0) {
            reloadWorldThenSetSpawn();
        }
    }

    /**
     * If the merged tile's region file was already touched (void chunks loaded in
     * player view distance), reload the world so Paper re-opens .mca files from disk.
     * Players are briefly moved to the overworld because Paper refuses to unload a
     * world that still has players inside it.
     */
    private void scheduleWorldReloadIfRegionHot(World rw, TileCoord tile) {
        if (!isRegionHot(rw, tile)) return;

        if (!worldReloadInProgress.compareAndSet(false, true)) {
            plugin.getLogger().info("World reload already in progress — " + tile
                + " merge will be visible after it completes.");
            return;
        }

        plugin.getLogger().info(tile + " region was hot — reloading world so clients"
            + " pick up merged chunk data.");

        Map<UUID, org.bukkit.Location> savedPlayers = new HashMap<>();
        for (Player p : rw.getPlayers()) {
            savedPlayers.put(p.getUniqueId(), p.getLocation().clone());
        }

        World holdingWorld = findHoldingWorld();
        if (holdingWorld == null) {
            plugin.getLogger().warning("No holding world for realmap reload — cannot refresh "
                + tile + " while players are online.");
            worldReloadInProgress.set(false);
            return;
        }

        org.bukkit.Location holdingSpot = holdingWorld.getSpawnLocation();
        for (Player p : rw.getPlayers()) {
            p.sendMessage("§e[RealMap] Map updated — refreshing view...");
            p.teleport(holdingSpot);
        }

        // Wait two ticks so Paper releases player chunk tickets before we unload.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            World live = Bukkit.getWorld(liveWorldName);
            if (live == null) {
                restorePlayers(savedPlayers);
                worldReloadInProgress.set(false);
                return;
            }

            boolean unloaded = Bukkit.unloadWorld(live, false);
            if (!unloaded) {
                plugin.getLogger().warning("Could not unload realmap world to refresh " + tile
                    + " — players may still see void until rejoin.");
                restorePlayers(savedPlayers);
                worldReloadInProgress.set(false);
                return;
            }

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                try {
                    World newRw = new WorldCreator(liveWorldName)
                        .generator(new VoidChunkGenerator())
                        .createWorld();
                    if (newRw == null) {
                        plugin.getLogger().severe("Failed to recreate realmap world after " + tile + " merge!");
                        return;
                    }
                    newRw.setKeepSpawnInMemory(false);

                    for (Map.Entry<UUID, org.bukkit.Location> entry : savedPlayers.entrySet()) {
                        Player p = Bukkit.getPlayer(entry.getKey());
                        if (p == null || !p.isOnline()) continue;
                        org.bukkit.Location old = entry.getValue();
                        p.teleport(new org.bukkit.Location(newRw,
                            old.getX(), old.getY(), old.getZ(), old.getYaw(), old.getPitch()));
                    }

                    refreshChunksAroundPlayers(newRw);
                    plugin.getLogger().info("Realmap world reloaded — " + tile + " now visible to clients.");
                } finally {
                    worldReloadInProgress.set(false);
                }
            }, 1L);
        }, 2L);
    }

    /** Any loaded world other than the live realmap world (typically overworld). */
    private World findHoldingWorld() {
        for (World w : Bukkit.getWorlds()) {
            if (!w.getName().equals(liveWorldName)) return w;
        }
        return null;
    }

    private void restorePlayers(Map<UUID, org.bukkit.Location> saved) {
        World rw = Bukkit.getWorld(liveWorldName);
        if (rw == null) return;
        for (Map.Entry<UUID, org.bukkit.Location> entry : saved.entrySet()) {
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p == null || !p.isOnline()) continue;
            org.bukkit.Location old = entry.getValue();
            p.teleport(new org.bukkit.Location(rw,
                old.getX(), old.getY(), old.getZ(), old.getYaw(), old.getPitch()));
        }
    }

    /** True when any chunk in the merged tile's .mca region file is currently loaded. */
    private boolean isRegionHot(World rw, TileCoord tile) {
        int originCX = tile.originChunkX(tileSizeChunks);
        int originCZ = tile.originChunkZ(tileSizeChunks);
        int regionX = Math.floorDiv(originCX, 32);
        int regionZ = Math.floorDiv(originCZ, 32);

        for (int rcx = 0; rcx < 32; rcx++) {
            for (int rcz = 0; rcz < 32; rcz++) {
                if (rw.isChunkLoaded(regionX * 32 + rcx, regionZ * 32 + rcz)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Resend every chunk in each player's view distance so clients drop stale void data. */
    private void refreshChunksAroundPlayers(World rw) {
        int vd = rw.getViewDistance();
        for (Player p : rw.getPlayers()) {
            int pcx = p.getLocation().getBlockX() >> 4;
            int pcz = p.getLocation().getBlockZ() >> 4;
            for (int dx = -vd; dx <= vd; dx++) {
                for (int dz = -vd; dz <= vd; dz++) {
                    rw.refreshChunk(pcx + dx, pcz + dz);
                }
            }
        }
    }

    /**
     * Unload the entire realmap world (no save) then recreate it on the next tick.
     * This clears Paper's RegionFile header cache for r.0.0.mca so that the next
     * read uses our merged data from disk instead of the stale void-chunk offsets.
     */
    private void reloadWorldThenSetSpawn() {
        World rw = Bukkit.getWorld(liveWorldName);
        if (rw != null) {
            boolean unloaded = Bukkit.unloadWorld(rw, false); // save=false: discard void chunks
            if (!unloaded) {
                plugin.getLogger().warning(
                    "Could not unload realmap world — spawn may land in void.");
            } else {
                plugin.getLogger().info("Realmap world unloaded (RegionFile cache cleared).");
            }
        }

        // Recreate on next tick so Paper opens r.0.0.mca fresh from disk.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            World newRw = new WorldCreator(liveWorldName)
                .generator(new VoidChunkGenerator())
                .createWorld();
            if (newRw == null) {
                plugin.getLogger().severe("Failed to recreate realmap world after merge!");
                return;
            }
            newRw.setKeepSpawnInMemory(false);
            plugin.getLogger().info("Realmap world recreated — merged data now active.");
            setWorldSpawn(newRw);
        }, 1L);
    }

    private void handleFailure(TileCoord tile, String reason) {
        inFlight.remove(tile);
        try {
            int retries = db.getRetryCount(tile);
            if (retries < maxRetries) {
                db.incrementRetry(tile);
                tileStatusCache.put(tile, TileStatus.PENDING);
                plugin.getLogger().warning(tile + " failed (retry "
                    + (retries + 1) + "/" + maxRetries + "): " + reason);
            } else {
                recordStatus(tile, TileStatus.FAILED);
                plugin.getLogger().severe(tile + " permanently failed after "
                    + maxRetries + " retries.");
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("DB error on failure handling: " + e.getMessage());
        }
    }

    private void setWorldSpawn(World rw) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            plugin.getLogger().info("[setWorldSpawn] world=" + rw.getName()
                + " minY=" + rw.getMinHeight() + " maxY=" + rw.getMaxHeight());
            // Try several X/Z positions so one of them hits a road/building from Arnis
            int bestY = Integer.MIN_VALUE;
            for (int[] pos : new int[][]{{4,4},{8,8},{16,16},{0,0}}) {
                int y = rawScanForGround(rw, pos[0], pos[1]);
                plugin.getLogger().info("[setWorldSpawn] scanForGround(" + pos[0] + "," + pos[1] + ")=" + y);
                if (y > bestY) bestY = y;
            }
            // Only fall back when every scan column had no solid blocks.
            // Negative Y is valid (e.g. Arnis terrain around Y=-62 in a -64..320 world).
            if (bestY == Integer.MIN_VALUE) {
                plugin.getLogger().warning("[setWorldSpawn] no solid ground found at spawn scans"
                    + " — using fallback Y=4");
                bestY = 4;
            }
            int safeY = bestY + 1;
            // Use int overload — Location overload can silently fail due to world-equality check
            rw.setSpawnLocation(4, safeY, 4);
            // Cache so PlayerJoinHandler bypasses getSpawnLocation() entirely
            cachedSpawnLoc = new org.bukkit.Location(rw, 4.5, safeY, 4.5);
            plugin.getLogger().info("[setWorldSpawn] cachedSpawnLoc=(4," + safeY + ",4). "
                + "getSpawnLocation=" + rw.getSpawnLocation());

            onSpawnReadyCallbacks.forEach(Runnable::run);
            onSpawnReadyCallbacks.clear();
        }, 40L);
    }

    /** Raw scan — returns actual Y found (possibly negative) or Integer.MIN_VALUE if nothing. */
    private int rawScanForGround(World rw, int bx, int bz) {
        int minY = rw.getMinHeight();
        for (int y = 250; y >= minY; y--) {
            if (rw.getBlockAt(bx, y, bz).getType().isSolid()) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    private int scanForGround(World rw, int bx, int bz) {
        int minY = rw.getMinHeight();
        for (int y = 250; y >= minY; y--) {
            if (rw.getBlockAt(bx, y, bz).getType().isSolid()) {
                plugin.getLogger().info("scanForGround found solid at Y=" + y
                    + " block=" + rw.getBlockAt(bx, y, bz).getType());
                return y;
            }
        }
        plugin.getLogger().warning("scanForGround: no solid block found at (" + bx + "," + bz
            + ") — chunk may not be loaded from disk yet.");
        return Integer.MIN_VALUE;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String buildBbox(TileCoord tile) {
        double minLat = transformer.blockZToLat((tile.z() + 1) * tileSizeBlocks);
        double maxLat = transformer.blockZToLat(tile.z()       * tileSizeBlocks);
        double minLon = transformer.blockXToLon(tile.x()       * tileSizeBlocks);
        double maxLon = transformer.blockXToLon((tile.x() + 1) * tileSizeBlocks);
        return String.format("%.7f,%.7f,%.7f,%.7f", minLat, minLon, maxLat, maxLon);
    }

    private void setStatus(TileCoord tile, TileStatus status) {
        try { recordStatus(tile, status); }
        catch (SQLException e) { plugin.getLogger().warning("DB write error: " + e.getMessage()); }
    }

    private void recordStatus(TileCoord tile, TileStatus status) throws SQLException {
        db.upsert(tile, status);
        tileStatusCache.put(tile, status);
    }

    private void deleteDir(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }
}

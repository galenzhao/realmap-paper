package com.example.realmap.listener;

import com.example.realmap.tile.TileCoord;
import com.example.realmap.tile.TileDatabase;
import com.example.realmap.tile.TileScheduler;
import com.example.realmap.tile.TileStatus;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.sql.SQLException;
import java.time.Duration;

/**
 * Two-stage join gate:
 *
 * Stage 1 — AsyncPlayerPreLoginEvent (fires before the player enters the world):
 *   If spawn tile is not MERGED yet, kick with a friendly "generating..." message.
 *   The player sees this at the Minecraft disconnect/loading screen and just retries.
 *
 * Stage 2 — PlayerJoinEvent (spawn tile is ready):
 *   Teleport the player to the realmap world spawn.
 */
public class PlayerJoinHandler implements Listener {

    private static final TileCoord SPAWN_TILE = new TileCoord(0, 0);

    private final JavaPlugin plugin;
    private final TileDatabase db;
    private final TileScheduler scheduler;
    private final String liveWorldName;
    private final TileBoundaryGuard boundaryGuard;

    // Flipped to true on main thread once tile(0,0) is MERGED,
    // so AsyncPlayerPreLoginEvent (async thread) can read it without DB hit.
    private volatile boolean spawnReady = false;

    public PlayerJoinHandler(JavaPlugin plugin, TileDatabase db,
                             TileScheduler scheduler, String liveWorldName,
                             TileBoundaryGuard boundaryGuard) {
        this.plugin       = plugin;
        this.db           = db;
        this.scheduler    = scheduler;
        this.liveWorldName = liveWorldName;
        this.boundaryGuard = boundaryGuard;

        // Check current DB state synchronously at construction (runs on main thread)
        try {
            spawnReady = db.getStatus(SPAWN_TILE)
                           .map(s -> s == TileStatus.MERGED)
                           .orElse(false);
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not read spawn tile status: " + e.getMessage());
        }

        // When the scheduler finishes tile(0,0), flip the flag
        scheduler.onSpawnReady(() -> {
            spawnReady = true;
            plugin.getLogger().info("Spawn tile ready — players may now join.");
        });
    }

    // -------------------------------------------------------------------------
    // Stage 1: block at the connection screen if map is not ready
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (spawnReady) return; // fast path — no DB hit needed

        // Double-check DB in case flag got set between ticks
        try {
            TileStatus status = db.getStatus(SPAWN_TILE).orElse(TileStatus.PENDING);
            if (status == TileStatus.MERGED) {
                spawnReady = true;
                return;
            }

            String detail = switch (status) {
                case QUEUED     -> "Queued — starting shortly...";
                case GENERATING -> "Downloading & generating map data...";
                case STAGED,
                     MERGING    -> "Applying map to world...";
                case FAILED     -> "Generation failed — ask an admin to check the logs.";
                default         -> "Waiting for generation to begin...";
            };

            int merged = 0;
            int total  = 9; // 3×3 spawn grid
            try { merged = db.countByStatus(TileStatus.MERGED); } catch (SQLException ignored) {}
            String progress = "Tiles ready: " + merged + " / " + total
                + " (" + Math.round(merged * 100.0 / total) + "%)";

            event.disallow(
                AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                Component.text()
                    .append(Component.text("World is being generated\n\n",
                        NamedTextColor.YELLOW, TextDecoration.BOLD))
                    .append(Component.text(detail + "\n", NamedTextColor.GRAY))
                    .append(Component.text(progress + "\n\n", NamedTextColor.AQUA))
                    .append(Component.text("You can reconnect once the first tile is ready.\n",
                        NamedTextColor.WHITE))
                    .append(Component.text("(Only tile 0,0 is required — others load in background)",
                        NamedTextColor.DARK_GRAY))
                    .build()
            );
        } catch (SQLException e) {
            // DB error — let them in rather than blocking all logins
            plugin.getLogger().warning("DB error in pre-login check: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Stage 2: spawn tile is ready — teleport to realmap world
    // -------------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // 1-tick delay so the player fully loads client-side before teleport
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            teleportToRealmap(player);
        }, 5L);
    }

    // -------------------------------------------------------------------------
    // Stage 3: redirect respawn to realmap world
    // -------------------------------------------------------------------------

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        // Only override if the player has no bed/anchor spawn set
        if (event.isBedSpawn() || event.isAnchorSpawn()) return;

        World rw = Bukkit.getWorld(liveWorldName);
        if (rw == null) return;

        Location spawn = rw.getSpawnLocation();
        if (spawn.getBlockY() > rw.getMinHeight()) {
            event.setRespawnLocation(spawn);
        }
    }

    // -------------------------------------------------------------------------

    private void teleportToRealmap(Player player) {
        World rw = Bukkit.getWorld(liveWorldName);
        if (rw == null) {
            player.sendMessage(Component.text("[RealMap] World not loaded — contact admin.",
                NamedTextColor.RED));
            return;
        }

        // Use the location cached by TileScheduler after tile(0,0) merge.
        // Avoids relying on World.getSpawnLocation() which is unreliable after world reload.
        Location spawn = scheduler.getCachedSpawnLoc();
        if (spawn == null || spawn.getWorld() == null) {
            // Fallback for resumed sessions where tile(0,0) was already merged before this run
            spawn = rw.getSpawnLocation();
        }
        plugin.getLogger().info("Teleporting " + player.getName() + " to "
            + spawn.getBlockX() + "," + spawn.getBlockY() + "," + spawn.getBlockZ());
        player.teleport(spawn);
        if (boundaryGuard != null) boundaryGuard.rememberSafe(player, spawn);
        player.showTitle(Title.title(
            Component.text("RealMap", NamedTextColor.GREEN),
            Component.text("Welcome!", NamedTextColor.GRAY),
            Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(2), Duration.ofMillis(500))
        ));
    }
}

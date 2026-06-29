package com.example.realmap.listener;

import com.example.realmap.tile.TileCoord;
import com.example.realmap.tile.TileScheduler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Blocks movement into tiles that are not yet MERGED (design doc §5.6).
 *
 * Players can walk freely inside ready tiles. Crossing into a pending/generating
 * tile is cancelled and they see an action-bar hint. The target tile is also
 * queued for generation if it was not known yet.
 */
public class TileBoundaryGuard implements Listener {

    private static final long MESSAGE_COOLDOWN_MS = 2_000L;
    private static final Component BLOCKED_MSG = Component.text(
        "前方地图正在加载…", NamedTextColor.YELLOW);

    private final TileScheduler scheduler;
    private final boolean enabled;

    private final Map<UUID, Location> lastSafeLoc = new HashMap<>();
    private final Map<UUID, Long> lastMessageAt = new HashMap<>();

    public TileBoundaryGuard(TileScheduler scheduler, boolean enabled) {
        this.scheduler = scheduler;
        this.enabled = enabled;
    }

    public void rememberSafe(Player player, Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        if (!loc.getWorld().getName().equals(scheduler.getLiveWorldName())) return;
        lastSafeLoc.put(player.getUniqueId(), loc.clone());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!enabled) return;
        Location to = event.getTo();
        if (to == null || !isRealMap(to)) return;
        if (onlyRotationChanged(event)) return;

        Player player = event.getPlayer();
        TileCoord toTile = scheduler.tileAt(to);

        if (scheduler.isTileTraversable(toTile)) {
            rememberSafe(player, to);
            return;
        }

        scheduler.ensureQueued(toTile);
        blockEntry(player, event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (!enabled) return;
        Location to = event.getTo();
        if (to == null || !isRealMap(to)) return;

        if (scheduler.isTileTraversable(scheduler.tileAt(to))) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        maybeSendBlocked(player);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastSafeLoc.remove(id);
        lastMessageAt.remove(id);
    }

    private void blockEntry(Player player, PlayerMoveEvent event) {
        Location from = event.getFrom();
        TileCoord fromTile = scheduler.tileAt(from);

        if (scheduler.isTileTraversable(fromTile)) {
            event.setCancelled(true);
            pushBack(player, from);
            rememberSafe(player, from);
        } else {
            Location safe = lastSafeLoc.get(player.getUniqueId());
            if (safe != null && safe.getWorld() != null) {
                event.setCancelled(true);
                player.teleport(safe);
            } else {
                event.setCancelled(true);
            }
        }

        maybeSendBlocked(player);
    }

    /** Nudge the player away from the blocked tile along their movement axis. */
    private void pushBack(Player player, Location from) {
        Vector velocity = player.getVelocity();
        if (Math.abs(velocity.getX()) > Math.abs(velocity.getZ())) {
            velocity.setX(-Math.signum(velocity.getX()) * 0.15);
        } else if (Math.abs(velocity.getZ()) > 0.01) {
            velocity.setZ(-Math.signum(velocity.getZ()) * 0.15);
        }
        player.setVelocity(velocity);
    }

    private void maybeSendBlocked(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastMessageAt.get(player.getUniqueId());
        if (last != null && now - last < MESSAGE_COOLDOWN_MS) return;
        lastMessageAt.put(player.getUniqueId(), now);
        player.sendActionBar(BLOCKED_MSG);
    }

    private boolean isRealMap(Location loc) {
        return loc.getWorld() != null
            && loc.getWorld().getName().equals(scheduler.getLiveWorldName());
    }

    private static boolean onlyRotationChanged(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        return from.getBlockX() == to.getBlockX()
            && from.getBlockY() == to.getBlockY()
            && from.getBlockZ() == to.getBlockZ();
    }
}

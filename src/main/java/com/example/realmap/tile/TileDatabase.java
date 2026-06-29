package com.example.realmap.tile;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SQLite-backed persistence for tile state.
 * All methods are synchronous; callers on async threads must manage their own
 * concurrency (SQLite WAL mode handles concurrent reads safely).
 */
public class TileDatabase implements AutoCloseable {

    private final Connection conn;

    public TileDatabase(File dataFolder) throws SQLException {
        dataFolder.mkdirs();
        String url = "jdbc:sqlite:" + new File(dataFolder, "tiles.db").getAbsolutePath();
        conn = DriverManager.getConnection(url);
        // WAL mode: safe for concurrent reads from async threads
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("""
                CREATE TABLE IF NOT EXISTS tiles (
                    tile_x      INTEGER NOT NULL,
                    tile_z      INTEGER NOT NULL,
                    status      TEXT    NOT NULL DEFAULT 'PENDING',
                    retry_count INTEGER NOT NULL DEFAULT 0,
                    updated_at  INTEGER NOT NULL,
                    PRIMARY KEY (tile_x, tile_z)
                )
            """);
        }
    }

    /** Insert or update a tile's status. */
    public void upsert(TileCoord coord, TileStatus status) throws SQLException {
        upsert(coord, status, 0);
    }

    public void upsert(TileCoord coord, TileStatus status, int retryCount) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO tiles (tile_x, tile_z, status, retry_count, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(tile_x, tile_z) DO UPDATE SET
                    status      = excluded.status,
                    retry_count = excluded.retry_count,
                    updated_at  = excluded.updated_at
                """)) {
            ps.setInt(1, coord.x());
            ps.setInt(2, coord.z());
            ps.setString(3, status.name());
            ps.setInt(4, retryCount);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    /** Increment retry count and set status to PENDING for re-queue. */
    public void incrementRetry(TileCoord coord) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE tiles SET retry_count = retry_count + 1,
                                 status = 'PENDING',
                                 updated_at = ?
                WHERE tile_x = ? AND tile_z = ?
                """)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setInt(2, coord.x());
            ps.setInt(3, coord.z());
            ps.executeUpdate();
        }
    }

    public Optional<TileStatus> getStatus(TileCoord coord) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT status FROM tiles WHERE tile_x = ? AND tile_z = ?")) {
            ps.setInt(1, coord.x());
            ps.setInt(2, coord.z());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(TileStatus.valueOf(rs.getString(1)));
                return Optional.empty();
            }
        }
    }

    public int getRetryCount(TileCoord coord) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT retry_count FROM tiles WHERE tile_x = ? AND tile_z = ?")) {
            ps.setInt(1, coord.x());
            ps.setInt(2, coord.z());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Returns true if this coord has never been recorded (brand-new tile). */
    public boolean isUnknown(TileCoord coord) throws SQLException {
        return getStatus(coord).isEmpty();
    }

    /** Count of tiles with a given status. */
    public int countByStatus(TileStatus status) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM tiles WHERE status = ?")) {
            ps.setString(1, status.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * On restart: reset any tiles that were mid-flight back to PENDING
     * so the scheduler can re-evaluate them.
     */
    public int resetInFlight() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE tiles SET status = 'PENDING', updated_at = ?
                WHERE status IN ('QUEUED', 'GENERATING', 'MERGING')
                """)) {
            ps.setLong(1, System.currentTimeMillis());
            return ps.executeUpdate();
        }
    }

    /** All tiles matching a status, ordered by insertion time (caller sorts by priority). */
    public List<TileCoord> getByStatus(TileStatus status) throws SQLException {
        List<TileCoord> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT tile_x, tile_z FROM tiles WHERE status = ? ORDER BY updated_at ASC")) {
            ps.setString(1, status.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(new TileCoord(rs.getInt(1), rs.getInt(2)));
            }
        }
        return result;
    }

    /** All recorded tiles and their current status. */
    public Map<TileCoord, TileStatus> loadAll() throws SQLException {
        Map<TileCoord, TileStatus> result = new HashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT tile_x, tile_z, status FROM tiles")) {
            while (rs.next()) {
                result.put(new TileCoord(rs.getInt(1), rs.getInt(2)),
                    TileStatus.valueOf(rs.getString(3)));
            }
        }
        return result;
    }

    @Override
    public void close() {
        try { if (conn != null && !conn.isClosed()) conn.close(); }
        catch (SQLException ignored) {}
    }
}

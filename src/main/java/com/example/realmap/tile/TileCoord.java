package com.example.realmap.tile;

/**
 * Immutable tile grid coordinate.
 * Tile (tx, tz) covers world blocks [tx*128 .. tx*128+127, tz*128 .. tz*128+127]
 * (assuming TILE_SIZE_CHUNKS=8, so 8*16=128 blocks per tile side).
 */
public record TileCoord(int x, int z) {

    /** World block X of this tile's north-west corner. */
    public int originBlockX(int tileSizeBlocks) { return x * tileSizeBlocks; }

    /** World block Z of this tile's north-west corner. */
    public int originBlockZ(int tileSizeBlocks) { return z * tileSizeBlocks; }

    /** Chunk X of this tile's north-west corner. */
    public int originChunkX(int tileSizeChunks) { return x * tileSizeChunks; }

    /** Chunk Z of this tile's north-west corner. */
    public int originChunkZ(int tileSizeChunks) { return z * tileSizeChunks; }

    @Override
    public String toString() { return "Tile(" + x + "," + z + ")"; }
}

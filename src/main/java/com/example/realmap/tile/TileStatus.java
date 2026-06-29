package com.example.realmap.tile;

/**
 * Lifecycle of a single map tile.
 *
 * PENDING      → recognized as needed, not yet submitted to worker
 * QUEUED       → submitted to the async generation thread pool
 * GENERATING   → Arnis subprocess is running
 * STAGED       → Arnis finished, .mca files written to staging dir, awaiting merge
 * MERGING      → .mca files being copied into the live world
 * MERGED       → visible in-game, done
 * FAILED       → generation or merge failed after max retries
 * FALLBACK_FLAT→ permanently failed; a flat placeholder was placed to avoid void holes
 */
public enum TileStatus {
    PENDING,
    QUEUED,
    GENERATING,
    STAGED,
    MERGING,
    MERGED,
    FAILED,
    FALLBACK_FLAT;

    public boolean isTerminal() {
        return this == MERGED || this == FALLBACK_FLAT;
    }

    public boolean needsGeneration() {
        return this == PENDING || this == QUEUED;
    }
}

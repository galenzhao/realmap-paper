package com.example.realmap.world;

import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.Random;

/**
 * Void generator for the live RealMap world.
 * All generation steps return false so Paper never fills in vanilla terrain.
 * Arnis-generated .mca files are copied in by TileMerger before players arrive.
 *
 * Note: shouldGenerateBedrock() is intentionally omitted — it is deprecated
 * in Paper 26 and has no effect (bedrock is part of the surface step).
 */
public class VoidChunkGenerator extends ChunkGenerator {

    @Override public boolean shouldGenerateNoise(WorldInfo w, Random r, int x, int z)        { return false; }
    @Override public boolean shouldGenerateSurface(WorldInfo w, Random r, int x, int z)      { return false; }
    @Override public boolean shouldGenerateCaves(WorldInfo w, Random r, int x, int z)        { return false; }
    @Override public boolean shouldGenerateDecorations(WorldInfo w, Random r, int x, int z)  { return false; }
    @Override public boolean shouldGenerateMobs(WorldInfo w, Random r, int x, int z)         { return false; }
    @Override public boolean shouldGenerateStructures(WorldInfo w, Random r, int x, int z)   { return false; }
}

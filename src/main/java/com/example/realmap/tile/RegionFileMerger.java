package com.example.realmap.tile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Copies Arnis-generated chunks into the correct position of the live world's .mca region files.
 *
 * Critical: Arnis always writes chunks with xPos/zPos relative to its own (0,0) origin.
 * We must patch those NBT fields to the actual live-world chunk coordinates before writing,
 * otherwise Paper rejects the chunk with "expected chunk (X,Z) but got (A,B)".
 */
public class RegionFileMerger {

    private final int tileSizeChunks;
    private final Logger log;

    public RegionFileMerger(int tileSizeChunks, Logger log) {
        this.tileSizeChunks = tileSizeChunks;
        this.log = log;
    }

    public void mergeTile(Path stagingRegionDir, Path liveRegionDir, TileCoord tile)
            throws IOException {

        Path stagingMca = stagingRegionDir.resolve("r.0.0.mca");
        if (!Files.exists(stagingMca)) {
            log.warning("No r.0.0.mca found in staging for " + tile);
            return;
        }

        Files.createDirectories(liveRegionDir);

        int chunkOffsetX = tile.x() * tileSizeChunks;
        int chunkOffsetZ = tile.z() * tileSizeChunks;

        // With tileSizeChunks=8 and region size=32, a tile always fits in one region file.
        int liveRegionX = Math.floorDiv(chunkOffsetX, 32);
        int liveRegionZ = Math.floorDiv(chunkOffsetZ, 32);
        Path liveMca = liveRegionDir.resolve("r." + liveRegionX + "." + liveRegionZ + ".mca");

        int copied = 0;
        for (int lx = 0; lx < tileSizeChunks; lx++) {
            for (int lz = 0; lz < tileSizeChunks; lz++) {
                byte[] raw = readChunkRaw(stagingMca, lx, lz);
                if (raw == null) continue;

                int worldCX = chunkOffsetX + lx;
                int worldCZ = chunkOffsetZ + lz;

                // Patch xPos/zPos so Paper accepts the chunk in its live position
                raw = patchChunkPosition(raw, worldCX, worldCZ);

                int posX = Math.floorMod(worldCX, 32);
                int posZ = Math.floorMod(worldCZ, 32);
                writeChunkRaw(liveMca, posX, posZ, raw);
                copied++;
            }
        }
        log.info("Merged " + copied + " chunk(s) from " + tile
            + " → r." + liveRegionX + "." + liveRegionZ + ".mca");
    }

    // -----------------------------------------------------------------------
    // NBT position patching
    // -----------------------------------------------------------------------

    /**
     * Decompress chunk, update xPos and zPos to live-world coords, recompress.
     * Raw layout: [4-byte data-length][1-byte compression-type][compressed-bytes...]
     */
    private byte[] patchChunkPosition(byte[] raw, int worldCX, int worldCZ) throws IOException {
        if (raw.length < 6) return raw;
        int compression = raw[4] & 0xFF;
        if (compression != 2) return raw; // only zlib (type 2) supported

        byte[] compressed = new byte[raw.length - 5];
        System.arraycopy(raw, 5, compressed, 0, compressed.length);

        byte[] nbt = decompress(compressed);
        nbt = patchNbtInt(nbt, "xPos", worldCX);
        nbt = patchNbtInt(nbt, "zPos", worldCZ);
        byte[] recompressed = compress(nbt);

        int dataLen = 1 + recompressed.length; // compression byte + data
        byte[] result = new byte[4 + dataLen];
        result[0] = (byte)(dataLen >>> 24);
        result[1] = (byte)(dataLen >>> 16);
        result[2] = (byte)(dataLen >>> 8);
        result[3] = (byte)(dataLen);
        result[4] = (byte)compression;
        System.arraycopy(recompressed, 0, result, 5, recompressed.length);
        return result;
    }

    /**
     * Scan raw NBT bytes for TAG_Int named {@code name} and overwrite the value.
     * Pattern: 0x03 [2-byte name-len] [name-bytes] [4-byte int]
     */
    private byte[] patchNbtInt(byte[] nbt, String name, int value) {
        byte[] nb = name.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i <= nbt.length - 3 - nb.length - 4; i++) {
            if ((nbt[i] & 0xFF) != 0x03) continue;                     // TAG_Int
            int nl = ((nbt[i+1] & 0xFF) << 8) | (nbt[i+2] & 0xFF);
            if (nl != nb.length) continue;
            for (int j = 0; j < nb.length; j++) {
                if (nbt[i + 3 + j] != nb[j]) continue outer;
            }
            int off = i + 3 + nb.length;
            nbt[off]   = (byte)(value >>> 24);
            nbt[off+1] = (byte)(value >>> 16);
            nbt[off+2] = (byte)(value >>> 8);
            nbt[off+3] = (byte)(value);
            return nbt;
        }
        return nbt;
    }

    private byte[] decompress(byte[] data) throws IOException {
        Inflater inf = new Inflater();
        inf.setInput(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 4);
        byte[] buf = new byte[8192];
        try {
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && inf.needsInput()) break;
                out.write(buf, 0, n);
            }
        } catch (DataFormatException e) {
            throw new IOException("zlib decompress failed", e);
        } finally {
            inf.end();
        }
        return out.toByteArray();
    }

    private byte[] compress(byte[] data) {
        Deflater def = new Deflater(Deflater.DEFAULT_COMPRESSION);
        def.setInput(data);
        def.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        byte[] buf = new byte[8192];
        while (!def.finished()) {
            out.write(buf, 0, def.deflate(buf));
        }
        def.end();
        return out.toByteArray();
    }

    // -----------------------------------------------------------------------
    // MCA binary format helpers
    // -----------------------------------------------------------------------

    private byte[] readChunkRaw(Path mcaFile, int localX, int localZ) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(mcaFile.toFile(), "r")) {
            if (raf.length() < 8192) return null;

            int headerIdx = (localX & 31) + (localZ & 31) * 32;
            raf.seek(headerIdx * 4L);
            int locationEntry = raf.readInt();

            int sectorOffset = (locationEntry >>> 8) & 0xFFFFFF;
            int sectorCount  = locationEntry & 0xFF;
            if (sectorOffset < 2 || sectorCount == 0) return null;

            raf.seek(sectorOffset * 4096L);
            int chunkLength = raf.readInt();
            if (chunkLength <= 0 || chunkLength > sectorCount * 4096) return null;

            byte[] raw = new byte[4 + chunkLength];
            raf.seek(sectorOffset * 4096L);
            raf.readFully(raw);
            return raw;
        }
    }

    private synchronized void writeChunkRaw(Path mcaFile, int posX, int posZ, byte[] raw)
            throws IOException {

        if (!Files.exists(mcaFile)) {
            Files.write(mcaFile, new byte[8192]);
        }

        try (RandomAccessFile raf = new RandomAccessFile(mcaFile.toFile(), "rw")) {
            long fileLen = Math.max(raf.length(), 8192);
            int newSectorOffset = (int)((fileLen + 4095) / 4096);
            int sectorsNeeded   = (raw.length + 4095) / 4096;

            raf.seek(newSectorOffset * 4096L);
            raf.write(raw);
            int remainder = raw.length % 4096;
            if (remainder != 0) raf.write(new byte[4096 - remainder]);

            int headerIdx     = (posX & 31) + (posZ & 31) * 32;
            int locationEntry = ((newSectorOffset & 0xFFFFFF) << 8) | (sectorsNeeded & 0xFF);
            raf.seek(headerIdx * 4L);
            raf.writeInt(locationEntry);

            raf.seek(4096L + headerIdx * 4L);
            raf.writeInt((int)(System.currentTimeMillis() / 1000L));
        }
    }
}

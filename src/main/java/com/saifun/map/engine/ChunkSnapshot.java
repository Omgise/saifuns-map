package com.saifun.map.engine;

/**
 * Immutable 16x16 chunk scan snapshot captured on the client thread
 * and processed/shaded asynchronously on the background map worker pool.
 */
public final class ChunkSnapshot {

    public final int dimensionId;
    public final int chunkX;
    public final int chunkZ;

    // 256 entries indexed by (localZ << 4) | localX
    public final int[] surfaceRgb = new int[256];
    public final short[] surfaceHeight = new short[256];
    public final byte[] waterDepth = new byte[256];
    public final int[] waterRgb = new int[256];
    public final byte[] blockLight = new byte[256];

    public final int[] caveRgb = new int[256];
    public final short[] caveHeight = new short[256];
    public final boolean hasCaveData;

    public ChunkSnapshot(int dimensionId, int chunkX, int chunkZ, boolean hasCaveData) {
        this.dimensionId = dimensionId;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.hasCaveData = hasCaveData;
    }
}

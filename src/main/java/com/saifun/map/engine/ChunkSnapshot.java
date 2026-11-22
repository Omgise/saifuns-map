package com.saifun.map.engine;

/**
 * Immutable 16x16 chunk scan snapshot captured on the client thread
 * and processed/shaded asynchronously on the background map worker pool.
 * Allocates cave arrays lazily only when Cave Mode scanning is active.
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

    public final int[] caveRgb;
    public final short[] caveHeight;
    public final boolean hasCaveData;

    public ChunkSnapshot(int dimensionId, int chunkX, int chunkZ, boolean hasCaveData) {
        this.dimensionId = dimensionId;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.hasCaveData = hasCaveData;
        if (hasCaveData) {
            this.caveRgb = new int[256];
            this.caveHeight = new short[256];
        } else {
            this.caveRgb = null;
            this.caveHeight = null;
        }
    }
}

package com.saifun.map.engine;

import com.saifun.map.SaifunsMapMod;
import com.saifun.map.config.MapConfig;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 32x32 chunk (512x512 blocks) region holding Surface + Cave map data,
 * lazy dirty-rect OpenGL textures, and compressed binary (.smap) disk persistence.
 */
public class MapRegion {

    public static final int CHUNKS_PER_SIDE = 32;
    public static final int SIZE = CHUNKS_PER_SIDE * 16; // 512
    public static final int PIXEL_COUNT = SIZE * SIZE;

    private static final int FILE_MAGIC = 0x534D4150; // "SMAP"
    private static final int FILE_VERSION = 1;

    // Reusable scratch buffer for 16x16 chunk sub-uploads (1 KB, main GL thread only)
    private static final ByteBuffer CHUNK_UPLOAD_BUF = BufferUtils.createByteBuffer(16 * 16 * 4);
    private static final ByteBuffer FULL_UPLOAD_BUF = BufferUtils.createByteBuffer(SIZE * SIZE * 4);

    public final int dimensionId;
    public final int regionX;
    public final int regionZ;

    // Surface buffers
    private final int[] surfaceBaseRgb = new int[PIXEL_COUNT];
    private final short[] surfaceHeight = new short[PIXEL_COUNT];
    private final byte[] waterDepth = new byte[PIXEL_COUNT];
    private final int[] waterRgb = new int[PIXEL_COUNT];
    private final byte[] blockLight = new byte[PIXEL_COUNT];
    private final int[] surfaceShadedArgb = new int[PIXEL_COUNT];

    // Cave buffers (allocated lazily on first cave data to save ~2.5 MB RAM per overworld region!)
    private int[] caveBaseRgb = null;
    private short[] caveHeight = null;
    private int[] caveShadedArgb = null;

    // Fine-grained lock ONLY for BitSet dirty flags so the GL render thread NEVER stalls on background shading!
    private final Object dirtyLock = new Object();

    // Chunk presence & dirty upload tracking
    private final BitSet populatedChunks = new BitSet(CHUNKS_PER_SIDE * CHUNKS_PER_SIDE);
    private final BitSet dirtySurfaceChunks = new BitSet(CHUNKS_PER_SIDE * CHUNKS_PER_SIDE);
    private final BitSet dirtyCaveChunks = new BitSet(CHUNKS_PER_SIDE * CHUNKS_PER_SIDE);
    private volatile boolean fullSurfaceUploadNeeded = false;
    private volatile boolean fullCaveUploadNeeded = false;
    private volatile boolean hasAnySurfaceData = false;
    private volatile boolean hasAnyCaveData = false;

    private int surfaceTextureId = 0;
    private int caveTextureId = 0;

    private volatile boolean dirtyOnDisk = false;
    private volatile long lastAccessTime = System.currentTimeMillis();

    public MapRegion(int dimensionId, int regionX, int regionZ) {
        this.dimensionId = dimensionId;
        this.regionX = regionX;
        this.regionZ = regionZ;
    }

    private void ensureCaveBuffers() {
        if (caveBaseRgb == null) {
            caveBaseRgb = new int[PIXEL_COUNT];
            caveHeight = new short[PIXEL_COUNT];
            caveShadedArgb = new int[PIXEL_COUNT];
        }
    }

    public void touch() {
        this.lastAccessTime = System.currentTimeMillis();
    }

    public long getLastAccessTime() {
        return lastAccessTime;
    }

    public boolean isDirtyOnDisk() {
        return dirtyOnDisk;
    }

    public synchronized void applyChunkSnapshot(ChunkSnapshot snap) {
        int localCx = snap.chunkX & 31;
        int localCz = snap.chunkZ & 31;
        int baseX = localCx << 4;
        int baseZ = localCz << 4;
        int chunkIdx = (localCz << 5) | localCx;

        if (snap.hasCaveData) {
            ensureCaveBuffers();
            hasAnyCaveData = true;
        }

        for (int lz = 0; lz < 16; lz++) {
            int rowOff = (baseZ + lz) * SIZE + baseX;
            int srcOff = lz << 4;
            for (int lx = 0; lx < 16; lx++) {
                int dst = rowOff + lx;
                int src = srcOff + lx;
                surfaceBaseRgb[dst] = snap.surfaceRgb[src];
                surfaceHeight[dst] = snap.surfaceHeight[src];
                waterDepth[dst] = snap.waterDepth[src];
                waterRgb[dst] = snap.waterRgb[src];
                blockLight[dst] = snap.blockLight[src];

                if (snap.hasCaveData) {
                    caveBaseRgb[dst] = snap.caveRgb[src];
                    caveHeight[dst] = snap.caveHeight[src];
                }
            }
        }

        synchronized (dirtyLock) {
            populatedChunks.set(chunkIdx);
        }
        hasAnySurfaceData = true;

        // Reshade this chunk and immediate E/S/SE neighbors for seamless directional NW->SE sun shadows
        reshadeChunk(localCx, localCz, snap.hasCaveData);
        if (localCx + 1 < CHUNKS_PER_SIDE && isChunkPopulated(localCx + 1, localCz)) {
            reshadeChunk(localCx + 1, localCz, snap.hasCaveData);
        }
        if (localCz + 1 < CHUNKS_PER_SIDE && isChunkPopulated(localCx, localCz + 1)) {
            reshadeChunk(localCx, localCz + 1, snap.hasCaveData);
        }
        if (localCx + 1 < CHUNKS_PER_SIDE && localCz + 1 < CHUNKS_PER_SIDE
            && isChunkPopulated(localCx + 1, localCz + 1)) {
            reshadeChunk(localCx + 1, localCz + 1, snap.hasCaveData);
        }
        if (snap.hasCaveData) {
            if (localCx > 0 && isChunkPopulated(localCx - 1, localCz)) {
                reshadeChunk(localCx - 1, localCz, true);
            }
            if (localCz > 0 && isChunkPopulated(localCx, localCz - 1)) {
                reshadeChunk(localCx, localCz - 1, true);
            }
        }

        dirtyOnDisk = true;
        touch();
    }

    public synchronized void reshadeAll() {
        for (int cz = 0; cz < CHUNKS_PER_SIDE; cz++) {
            for (int cx = 0; cx < CHUNKS_PER_SIDE; cx++) {
                if (populatedChunks.get((cz << 5) | cx)) {
                    reshadeChunk(cx, cz, true);
                }
            }
        }
        fullSurfaceUploadNeeded = true;
        fullCaveUploadNeeded = true;
    }

    private void reshadeChunk(int localCx, int localCz, boolean includeCave) {
        MapConfig cfg = MapConfig.get();
        boolean shading = cfg.terrainShading;
        boolean waterTrans = cfg.waterTransparency;

        int baseX = localCx << 4;
        int baseZ = localCz << 4;

        for (int lz = 0; lz < 16; lz++) {
            int rz = baseZ + lz;
            int rowOff = rz * SIZE;
            for (int lx = 0; lx < 16; lx++) {
                int rx = baseX + lx;
                int idx = rowOff + rx;

                int rgb = surfaceBaseRgb[idx];
                if (rgb != 0) {
                    int h = surfaceHeight[idx];
                    float shade = 1.0f;
                    float shadowIntensity = 0.0f;
                    float sunHighlight = 0.0f;
                    int wDepth = waterDepth[idx] & 0xFF;

                    if (shading) {
                        int surfaceY = h + wDepth;
                        boolean hasN = rz > 0 && surfaceBaseRgb[idx - SIZE] != 0;
                        boolean hasW = rx > 0 && surfaceBaseRgb[idx - 1] != 0;
                        boolean hasNW = rz > 0 && rx > 0 && surfaceBaseRgb[idx - SIZE - 1] != 0;
                        boolean hasS = rz + 1 < SIZE && surfaceBaseRgb[idx + SIZE] != 0;
                        boolean hasE = rx + 1 < SIZE && surfaceBaseRgb[idx + 1] != 0;

                        int hN = hasN ? (surfaceHeight[idx - SIZE] + (waterDepth[idx - SIZE] & 0xFF)) : surfaceY;
                        int hW = hasW ? (surfaceHeight[idx - 1] + (waterDepth[idx - 1] & 0xFF)) : surfaceY;
                        int hNW = hasNW ? (surfaceHeight[idx - SIZE - 1] + (waterDepth[idx - SIZE - 1] & 0xFF)) : surfaceY;
                        int hS = hasS ? (surfaceHeight[idx + SIZE] + (waterDepth[idx + SIZE] & 0xFF)) : surfaceY;
                        int hE = hasE ? (surfaceHeight[idx + 1] + (waterDepth[idx + 1] & 0xFF)) : surfaceY;

                        if (wDepth > 0) {
                            // Water surface receives crisp NW riverbank/tree cast shadows + gentle riverbed contour
                            int maxBank = Math.max(Math.max(hN - surfaceY, hW - surfaceY), hNW - surfaceY);
                            if (maxBank >= 1) {
                                float bankShadow = Math.min(0.28f, maxBank * 0.065f);
                                shade *= (1.0f - bankShadow);
                                shadowIntensity = Math.max(shadowIntensity, bankShadow);
                            }
                            // 3-step NW raycast so riverside trees/cliffs cast real shadows onto water!
                            for (int d = 1; d <= 3; d++) {
                                if (rx >= d && rz >= d) {
                                    int nIdx = idx - d * SIZE - d;
                                    if (surfaceBaseRgb[nIdx] != 0) {
                                        int occluderY = surfaceHeight[nIdx] + (waterDepth[nIdx] & 0xFF);
                                        if (occluderY > surfaceY + d * 1.6f) {
                                            float s = 0.22f * (1.0f - (d - 1) * 0.25f);
                                            shade *= (1.0f - s);
                                            shadowIntensity = Math.max(shadowIntensity, s);
                                            break;
                                        }
                                    }
                                }
                            }
                        } else {
                            // 1. Directional 3-neighbor topographic slope relief (NW sunlit, SE shaded)
                            int slope = (h - hN) * 2 + (h - hW) * 2 + (h - hNW);
                            if (slope > 0) {
                                float boost = Math.min(0.20f, Math.min(6, slope) * 0.036f);
                                shade += boost;
                                sunHighlight = boost;
                            } else if (slope < 0) {
                                float dark = Math.min(0.36f, Math.min(6, -slope) * 0.062f);
                                shade -= dark;
                                shadowIntensity = Math.max(shadowIntensity, dark);
                            }

                            // 2. 4-step NW->SE Directional Sun Shadow Raycast (mountains, cliffs, trees & GTNH bases!)
                            float castShadow = 0.0f;
                            for (int d = 1; d <= 4; d++) {
                                if (rx >= d && rz >= d) {
                                    int nIdx = idx - d * SIZE - d;
                                    if (surfaceBaseRgb[nIdx] != 0) {
                                        int occluderY = surfaceHeight[nIdx] + (waterDepth[nIdx] & 0xFF);
                                        float heightExcess = occluderY - (h + d * 1.45f);
                                        if (heightExcess > 0.0f) {
                                            float falloff = 1.0f - (d - 1) * 0.20f;
                                            float strength = Math.min(0.30f, (0.14f + heightExcess * 0.028f) * falloff);
                                            if (strength > castShadow) {
                                                castShadow = strength;
                                            }
                                        }
                                    }
                                }
                            }
                            if (castShadow > 0.0f) {
                                shade *= (1.0f - castShadow);
                                shadowIntensity = Math.max(shadowIntensity, castShadow);
                            }

                            // 3. Crease / valley Ambient Occlusion (darkens corners & ravines surrounded by higher blocks)
                            int higherCount = (hN > h ? 1 : 0) + (hW > h ? 1 : 0) + (hS > h ? 1 : 0) + (hE > h ? 1 : 0);
                            if (higherCount >= 2) {
                                float ao = (higherCount - 1) * 0.045f;
                                shade *= (1.0f - ao);
                                shadowIntensity = Math.max(shadowIntensity, ao * 1.4f);
                            }

                            // 4. Gentle altitude contour lift so peaks pop cleanly above valleys
                            shade *= 0.93f + Math.min(1.0f, Math.max(0.0f, (h - 48) / 100.0f)) * 0.16f;
                            shade = Math.max(0.52f, Math.min(1.24f, shade));
                        }
                    }

                    int finalRgb = BlockColorResolver.shadeColor(rgb, shade);

                    // Color-temperature polish: cool slate shadows + subtle warm sunlit ridges (zero muddy grey!)
                    if (shading && wDepth == 0) {
                        if (shadowIntensity > 0.08f) {
                            float coolMix = Math.min(0.16f, (shadowIntensity - 0.06f) * 0.45f);
                            finalRgb = BlockColorResolver.blendColor(finalRgb, 0x1A2845, coolMix);
                        } else if (sunHighlight > 0.05f) {
                            float warmMix = Math.min(0.08f, sunHighlight * 0.35f);
                            finalRgb = BlockColorResolver.blendColor(finalRgb, 0xFFF7DC, warmMix);
                        }
                    }

                    if (wDepth > 0) {
                        // Crisp water gradient: bright turquoise-blue shallows -> rich royal/sapphire deep water
                        int shallowWater = 0x4580DF;
                        int deepWaterBase = 0x2249A6;
                        float tDepth = Math.min(1.0f, wDepth / 9.0f);
                        int wCol = BlockColorResolver.blendColor(shallowWater, deepWaterBase, tDepth);
                        if (waterRgb[idx] != 0) {
                            wCol = BlockColorResolver.blendColor(wCol, waterRgb[idx], 0.25f);
                        }
                        if (waterTrans) {
                            float alpha = Math.min(0.94f, 0.54f + Math.min(10, wDepth) * 0.040f);
                            finalRgb = BlockColorResolver.blendColor(finalRgb, wCol, alpha);
                        } else {
                            finalRgb = wCol;
                        }
                        if (shade < 0.99f) {
                            finalRgb = BlockColorResolver.shadeColor(finalRgb, shade);
                            if (shadowIntensity > 0.06f) {
                                finalRgb = BlockColorResolver.blendColor(finalRgb, 0x13203B, Math.min(0.18f, shadowIntensity * 0.5f));
                            }
                        }
                    }

                    surfaceShadedArgb[idx] = 0xFF000000 | (finalRgb & 0x00FFFFFF);
                } else {
                    surfaceShadedArgb[idx] = 0;
                }

                if (includeCave && caveBaseRgb != null) {
                    int cRgb = caveBaseRgb[idx];
                    if (cRgb != 0) {
                        int ch = caveHeight[idx];
                        boolean isLava = (cRgb == 0xFF6B1A || cRgb == 0xEB5E1A);
                        float cShade = 1.0f;

                        if (shading && !isLava) {
                            boolean hasN = rz > 0 && caveBaseRgb[idx - SIZE] != 0;
                            boolean hasW = rx > 0 && caveBaseRgb[idx - 1] != 0;
                            boolean hasNW = rz > 0 && rx > 0 && caveBaseRgb[idx - SIZE - 1] != 0;
                            boolean hasS = rz + 1 < SIZE && caveBaseRgb[idx + SIZE] != 0;
                            boolean hasE = rx + 1 < SIZE && caveBaseRgb[idx + 1] != 0;

                            int chN = hasN ? caveHeight[idx - SIZE] : ch;
                            int chW = hasW ? caveHeight[idx - 1] : ch;
                            int chNW = hasNW ? caveHeight[idx - SIZE - 1] : ch;

                            // 1. Directional 3-neighbor cave slope relief (only across valid open cave floors!)
                            int slope = (ch - chN) * 2 + (ch - chW) * 2 + (ch - chNW);
                            if (slope > 0) {
                                cShade = Math.min(1.26f, 1.0f + Math.min(6, slope) * 0.046f);
                            } else if (slope < 0) {
                                cShade = Math.max(0.54f, 1.0f + Math.max(-6, slope) * 0.075f);
                            }

                            // 2. Crisp 2-pixel drop-shadow for cave ledges, stairs, and ravines
                            int ledgeDrop = Math.max(chN - ch, chW - ch);
                            if (ledgeDrop >= 2) {
                                cShade *= Math.max(0.68f, 1.0f - Math.min(6, ledgeDrop) * 0.052f);
                            } else {
                                boolean hasN2 = rz > 1 && caveBaseRgb[idx - SIZE * 2] != 0;
                                boolean hasW2 = rx > 1 && caveBaseRgb[idx - 2] != 0;
                                int chN2 = hasN2 ? caveHeight[idx - SIZE * 2] : ch;
                                int chW2 = hasW2 ? caveHeight[idx - 2] : ch;
                                if (Math.max(chN2 - ch, chW2 - ch) >= 4) {
                                    cShade *= 0.85f;
                                }
                            }

                            // 3. Recessed tunnel-wall ambient occlusion (darkens edges hugging solid rock walls)
                            int wallCount = (hasN ? 0 : 1) + (hasW ? 0 : 1) + (hasS ? 0 : 1) + (hasE ? 0 : 1);
                            if (wallCount > 0) {
                                cShade *= (1.0f - wallCount * 0.055f);
                            }
                            // Extra subtle rim shadow along N/W cave walls so tunnels feel deeply carved
                            if (!hasN || !hasW) {
                                cShade *= 0.91f;
                            }

                            // 4. Depth elevation gradient: deeper caves/ravines darken naturally, higher shelves brighten
                            float depthFactor = 0.68f + Math.min(1.0f, Math.max(0.0f, (ch - 8) / 64.0f)) * 0.46f;
                            cShade *= depthFactor;
                        }

                        int finalCave = BlockColorResolver.shadeColor(cRgb, cShade);
                        // Subtle cool shadow tint in very deep tunnels (Y < 32) for instant vertical depth readability
                        if (!isLava && ch < 32) {
                            float cool = (32 - Math.max(0, ch)) / 32.0f * 0.16f;
                            finalCave = BlockColorResolver.blendColor(finalCave, 0x1E2A44, cool);
                        }
                        caveShadedArgb[idx] = 0xFF000000 | (finalCave & 0x00FFFFFF);
                    } else {
                        caveShadedArgb[idx] = 0;
                    }
                }
            }
        }

        int chunkIdx = (localCz << 5) | localCx;
        synchronized (dirtyLock) {
            dirtySurfaceChunks.set(chunkIdx);
            if (includeCave && caveShadedArgb != null) {
                dirtyCaveChunks.set(chunkIdx);
            }
        }
    }

    /**
     * Binds (and lazily allocates/updates dirty sub-regions of) the OpenGL texture on the render thread.
     * Lock-free against background chunk shading (uses microsecond dirtyLock only for BitSet!).
     */
    public int getOrUpdateTexture(boolean caveMode, boolean linearFilter) {
        touch();
        int[] pixels = caveMode ? caveShadedArgb : surfaceShadedArgb;
        if (pixels == null) {
            // Never fall back to surface terrain when rendering Cave Mode!
            return 0;
        }

        int texId = caveMode ? caveTextureId : surfaceTextureId;
        if (texId == 0) {
            texId = allocateEmptyTexture();
            if (caveMode) {
                caveTextureId = texId;
                fullCaveUploadNeeded = true;
            } else {
                surfaceTextureId = texId;
                fullSurfaceUploadNeeded = true;
            }
        }

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
        int filter = linearFilter ? GL11.GL_LINEAR : GL11.GL_NEAREST;
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);

        boolean fullUpload = caveMode ? fullCaveUploadNeeded : fullSurfaceUploadNeeded;
        if (fullUpload) {
            if (caveMode) fullCaveUploadNeeded = false;
            else fullSurfaceUploadNeeded = false;
            synchronized (dirtyLock) {
                (caveMode ? dirtyCaveChunks : dirtySurfaceChunks).clear();
            }
            uploadFullTexture(pixels);
        } else {
            // Drain up to 8 dirty chunks under microsecond dirtyLock, then upload to GPU lock-free
            int[] toUpload = null;
            int uploadCount = 0;
            synchronized (dirtyLock) {
                BitSet dirty = caveMode ? dirtyCaveChunks : dirtySurfaceChunks;
                if (!dirty.isEmpty()) {
                    toUpload = new int[8];
                    for (int bit = dirty.nextSetBit(0); bit >= 0 && uploadCount < 8; bit = dirty.nextSetBit(bit + 1)) {
                        toUpload[uploadCount++] = bit;
                        dirty.clear(bit);
                    }
                }
            }
            for (int i = 0; i < uploadCount; i++) {
                int bit = toUpload[i];
                uploadChunkRect(pixels, (bit & 31) << 4, (bit >>> 5) << 4);
            }
        }

        return texId;
    }

    private static int allocateEmptyTexture() {
        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
            SIZE, SIZE, 0,
            GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null
        );
        return id;
    }

    private static void uploadFullTexture(int[] argbPixels) {
        FULL_UPLOAD_BUF.clear();
        for (int i = 0; i < PIXEL_COUNT; i++) {
            putRgba(FULL_UPLOAD_BUF, argbPixels[i]);
        }
        FULL_UPLOAD_BUF.flip();
        GL11.glTexSubImage2D(
            GL11.GL_TEXTURE_2D, 0,
            0, 0, SIZE, SIZE,
            GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, FULL_UPLOAD_BUF
        );
    }

    private static void uploadChunkRect(int[] argbPixels, int baseX, int baseZ) {
        CHUNK_UPLOAD_BUF.clear();
        for (int lz = 0; lz < 16; lz++) {
            int rowOff = (baseZ + lz) * SIZE + baseX;
            for (int lx = 0; lx < 16; lx++) {
                putRgba(CHUNK_UPLOAD_BUF, argbPixels[rowOff + lx]);
            }
        }
        CHUNK_UPLOAD_BUF.flip();
        GL11.glTexSubImage2D(
            GL11.GL_TEXTURE_2D, 0,
            baseX, baseZ, 16, 16,
            GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, CHUNK_UPLOAD_BUF
        );
    }

    private static void putRgba(ByteBuffer buf, int argb) {
        buf.put((byte) ((argb >>> 16) & 0xFF));
        buf.put((byte) ((argb >>> 8) & 0xFF));
        buf.put((byte) (argb & 0xFF));
        buf.put((byte) ((argb >>> 24) & 0xFF));
    }

    /**
     * Releases OpenGL textures. MUST be called on the main GL thread.
     */
    public synchronized void deleteTextures() {
        if (surfaceTextureId != 0) {
            GL11.glDeleteTextures(surfaceTextureId);
            surfaceTextureId = 0;
        }
        if (caveTextureId != 0) {
            GL11.glDeleteTextures(caveTextureId);
            caveTextureId = 0;
        }
    }

    public boolean hasSurfaceData() {
        return hasAnySurfaceData;
    }

    public boolean hasCaveData() {
        return hasAnyCaveData && caveShadedArgb != null;
    }

    public boolean isChunkPopulated(int localCx, int localCz) {
        if (localCx < 0 || localCx >= CHUNKS_PER_SIDE || localCz < 0 || localCz >= CHUNKS_PER_SIDE) return false;
        synchronized (dirtyLock) {
            return populatedChunks.get((localCz << 5) | localCx);
        }
    }

    /**
     * Saves only populated chunks in a compact GZIP binary stream (.smap).
     */
    public void saveToDisk(File file) {
        int[] copyRgb;
        short[] copyH;
        byte[] copyWDepth;
        int[] copyWRgb;
        BitSet copyPop;

        synchronized (this) {
            if (populatedChunks.isEmpty()) {
                dirtyOnDisk = false;
                return;
            }
            copyPop = (BitSet) populatedChunks.clone();
            copyRgb = surfaceBaseRgb.clone();
            copyH = surfaceHeight.clone();
            copyWDepth = waterDepth.clone();
            copyWRgb = waterRgb.clone();
            dirtyOnDisk = false;
        }

        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(tmp)))
            )) {
                out.writeInt(FILE_MAGIC);
                out.writeInt(FILE_VERSION);
                int chunkCount = copyPop.cardinality();
                out.writeShort(chunkCount);
                for (int bit = copyPop.nextSetBit(0); bit >= 0; bit = copyPop.nextSetBit(bit + 1)) {
                    out.writeShort(bit);
                    int baseX = (bit & 31) << 4;
                    int baseZ = (bit >>> 5) << 4;
                    for (int lz = 0; lz < 16; lz++) {
                        int rowOff = (baseZ + lz) * SIZE + baseX;
                        for (int lx = 0; lx < 16; lx++) {
                            int idx = rowOff + lx;
                            out.writeInt(copyRgb[idx]);
                            out.writeShort(copyH[idx]);
                            out.writeByte(copyWDepth[idx]);
                            out.writeInt(copyWRgb[idx]);
                        }
                    }
                }
            }
            if (file.exists() && !file.delete()) {
                // On Windows overwrite fallback
                file.delete();
            }
            tmp.renameTo(file);
        } catch (Exception e) {
            SaifunsMapMod.LOG.warn("Failed to save map region {}: {}", file.getName(), e.getMessage());
        }
    }

    public boolean loadFromDisk(File file) {
        if (file == null || !file.exists()) return false;
        try (DataInputStream in = new DataInputStream(
            new BufferedInputStream(new GZIPInputStream(new FileInputStream(file)))
        )) {
            int magic = in.readInt();
            int version = in.readInt();
            if (magic != FILE_MAGIC || version != FILE_VERSION) {
                return false;
            }
            int chunkCount = in.readUnsignedShort();
            // Decode off-lock so the render thread NEVER blocks during disk I/O!
            int[] tmpRgb = new int[PIXEL_COUNT];
            short[] tmpH = new short[PIXEL_COUNT];
            byte[] tmpWD = new byte[PIXEL_COUNT];
            int[] tmpWRgb = new int[PIXEL_COUNT];
            BitSet tmpPop = new BitSet(CHUNKS_PER_SIDE * CHUNKS_PER_SIDE);

            for (int i = 0; i < chunkCount; i++) {
                int bit = in.readUnsignedShort();
                if (bit >= 1024) break;
                int baseX = (bit & 31) << 4;
                int baseZ = (bit >>> 5) << 4;
                for (int lz = 0; lz < 16; lz++) {
                    int rowOff = (baseZ + lz) * SIZE + baseX;
                    for (int lx = 0; lx < 16; lx++) {
                        int idx = rowOff + lx;
                        tmpRgb[idx] = in.readInt();
                        tmpH[idx] = in.readShort();
                        tmpWD[idx] = in.readByte();
                        tmpWRgb[idx] = in.readInt();
                    }
                }
                tmpPop.set(bit);
            }

            synchronized (this) {
                for (int bit = tmpPop.nextSetBit(0); bit >= 0; bit = tmpPop.nextSetBit(bit + 1)) {
                    boolean alreadyPop;
                    synchronized (dirtyLock) {
                        alreadyPop = populatedChunks.get(bit);
                    }
                    if (alreadyPop) continue;
                    int baseX = (bit & 31) << 4;
                    int baseZ = (bit >>> 5) << 4;
                    for (int lz = 0; lz < 16; lz++) {
                        int rowOff = (baseZ + lz) * SIZE + baseX;
                        System.arraycopy(tmpRgb, rowOff, surfaceBaseRgb, rowOff, 16);
                        System.arraycopy(tmpH, rowOff, surfaceHeight, rowOff, 16);
                        System.arraycopy(tmpWD, rowOff, waterDepth, rowOff, 16);
                        System.arraycopy(tmpWRgb, rowOff, waterRgb, rowOff, 16);
                    }
                    synchronized (dirtyLock) {
                        populatedChunks.set(bit);
                    }
                    hasAnySurfaceData = true;
                }
                reshadeAll();
                dirtyOnDisk = false;
            }
            return true;
        } catch (Exception e) {
            SaifunsMapMod.LOG.warn("Failed to load map region {}: {}", file.getName(), e.getMessage());
            return false;
        }
    }
}

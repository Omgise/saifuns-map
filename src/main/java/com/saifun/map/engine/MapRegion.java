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

    // Cave buffers
    private final int[] caveBaseRgb = new int[PIXEL_COUNT];
    private final short[] caveHeight = new short[PIXEL_COUNT];
    private final int[] caveShadedArgb = new int[PIXEL_COUNT];

    // Chunk presence & dirty upload tracking
    private final BitSet populatedChunks = new BitSet(CHUNKS_PER_SIDE * CHUNKS_PER_SIDE);
    private final BitSet dirtySurfaceChunks = new BitSet(CHUNKS_PER_SIDE * CHUNKS_PER_SIDE);
    private final BitSet dirtyCaveChunks = new BitSet(CHUNKS_PER_SIDE * CHUNKS_PER_SIDE);
    private boolean fullSurfaceUploadNeeded = false;
    private boolean fullCaveUploadNeeded = false;

    private int surfaceTextureId = 0;
    private int caveTextureId = 0;

    private volatile boolean dirtyOnDisk = false;
    private volatile long lastAccessTime = System.currentTimeMillis();

    public MapRegion(int dimensionId, int regionX, int regionZ) {
        this.dimensionId = dimensionId;
        this.regionX = regionX;
        this.regionZ = regionZ;
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

        populatedChunks.set(chunkIdx);

        // Reshade this chunk and immediate east/south neighbor borders for seamless slope shading
        reshadeChunk(localCx, localCz, snap.hasCaveData);
        if (localCx + 1 < CHUNKS_PER_SIDE && populatedChunks.get((localCz << 5) | (localCx + 1))) {
            reshadeChunk(localCx + 1, localCz, snap.hasCaveData);
        }
        if (localCz + 1 < CHUNKS_PER_SIDE && populatedChunks.get(((localCz + 1) << 5) | localCx)) {
            reshadeChunk(localCx, localCz + 1, snap.hasCaveData);
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
                    if (shading) {
                        int hN = rz > 0 ? surfaceHeight[idx - SIZE] : h;
                        int hW = rx > 0 ? surfaceHeight[idx - 1] : h;
                        int slope = (h - hN) * 2 + (h - hW);
                        if (slope > 0) {
                            shade = Math.min(1.22f, 1.0f + slope * 0.055f);
                        } else if (slope < 0) {
                            shade = Math.max(0.70f, 1.0f + slope * 0.065f);
                        }
                        // Subtle elevation contrast so mountains & valleys pop like Xaero
                        shade *= 0.94f + Math.min(1.0f, Math.max(0.0f, (h - 40) / 140.0f)) * 0.12f;
                    }

                    int finalRgb = BlockColorResolver.shadeColor(rgb, shade);

                    int wDepth = waterDepth[idx] & 0xFF;
                    if (wDepth > 0) {
                        int wCol = waterRgb[idx] != 0 ? waterRgb[idx] : 0x2F5FD0;
                        if (waterTrans) {
                            float depthFactor = Math.min(0.86f, 0.34f + wDepth * 0.045f);
                            int deepWater = BlockColorResolver.shadeColor(wCol, Math.max(0.55f, 1.0f - wDepth * 0.025f));
                            finalRgb = BlockColorResolver.blendColor(finalRgb, deepWater, depthFactor);
                        } else {
                            finalRgb = BlockColorResolver.shadeColor(wCol, Math.max(0.60f, 1.0f - wDepth * 0.02f));
                        }
                    }

                    surfaceShadedArgb[idx] = 0xFF000000 | (finalRgb & 0x00FFFFFF);
                } else {
                    surfaceShadedArgb[idx] = 0;
                }

                if (includeCave) {
                    int cRgb = caveBaseRgb[idx];
                    if (cRgb != 0) {
                        int ch = caveHeight[idx];
                        float cShade = 1.0f;
                        if (shading) {
                            int chN = rz > 0 ? caveHeight[idx - SIZE] : ch;
                            int chW = rx > 0 ? caveHeight[idx - 1] : ch;
                            int slope = (ch - chN) * 2 + (ch - chW);
                            if (slope > 0) cShade = Math.min(1.20f, 1.0f + slope * 0.05f);
                            else if (slope < 0) cShade = Math.max(0.68f, 1.0f + slope * 0.06f);
                        }
                        int finalCave = BlockColorResolver.shadeColor(cRgb, cShade);
                        caveShadedArgb[idx] = 0xFF000000 | (finalCave & 0x00FFFFFF);
                    } else {
                        caveShadedArgb[idx] = 0;
                    }
                }
            }
        }

        int chunkIdx = (localCz << 5) | localCx;
        dirtySurfaceChunks.set(chunkIdx);
        if (includeCave) {
            dirtyCaveChunks.set(chunkIdx);
        }
    }

    /**
     * Binds (and lazily allocates/updates dirty sub-regions of) the OpenGL texture on the render thread.
     */
    public synchronized int getOrUpdateTexture(boolean caveMode, boolean linearFilter) {
        touch();
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
        int[] pixels = caveMode ? caveShadedArgb : surfaceShadedArgb;
        BitSet dirty = caveMode ? dirtyCaveChunks : dirtySurfaceChunks;

        if (fullUpload) {
            uploadFullTexture(pixels);
            dirty.clear();
            if (caveMode) fullCaveUploadNeeded = false;
            else fullSurfaceUploadNeeded = false;
        } else if (!dirty.isEmpty()) {
            for (int bit = dirty.nextSetBit(0); bit >= 0; bit = dirty.nextSetBit(bit + 1)) {
                int localCx = bit & 31;
                int localCz = bit >>> 5;
                uploadChunkRect(pixels, localCx << 4, localCz << 4);
            }
            dirty.clear();
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
        return !populatedChunks.isEmpty();
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
            synchronized (this) {
                for (int i = 0; i < chunkCount; i++) {
                    int bit = in.readUnsignedShort();
                    if (bit >= 1024) break;
                    int baseX = (bit & 31) << 4;
                    int baseZ = (bit >>> 5) << 4;
                    for (int lz = 0; lz < 16; lz++) {
                        int rowOff = (baseZ + lz) * SIZE + baseX;
                        for (int lx = 0; lx < 16; lx++) {
                            int idx = rowOff + lx;
                            surfaceBaseRgb[idx] = in.readInt();
                            surfaceHeight[idx] = in.readShort();
                            waterDepth[idx] = in.readByte();
                            waterRgb[idx] = in.readInt();
                        }
                    }
                    populatedChunks.set(bit);
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

package com.saifun.map.engine;

import com.saifun.map.config.MapConfig;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.util.MathHelper;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.Chunk;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Incremental, frame-budgeted chunk scanner running on the client tick.
 * Scans dirty/nearby chunks into lightweight ChunkSnapshots and handles smart Cave Mode detection.
 */
public class ChunkScanner {

    private static final int SCAN_RADIUS_CHUNKS = 9;
    private static final long RESCAN_NEAR_MS = 2800L;
    private static final long RESCAN_FAR_MS = 12000L;
    private static final ThreadLocal<Random> SLIME_RNG = ThreadLocal.withInitial(Random::new);

    private final MapWorldCache worldCache;
    private final Deque<Long> priorityQueue = new ArrayDeque<>();
    private final Set<Long> queuedKeys = new HashSet<>();
    private final Map<Long, Long> lastScanTime = new HashMap<>();

    private boolean activeCaveMode = false;
    private int lastCaveAnchorY = 64;
    private int tickCounter = 0;
    private int burstScanTicks = 0;
    private int forceCaveScanTicks = 0;

    public ChunkScanner(MapWorldCache worldCache) {
        this.worldCache = worldCache;
    }

    public boolean isCaveModeActive() {
        return activeCaveMode;
    }

    /**
     * Triggers an immediate high-priority cave rescan of all loaded chunks around the player
     * (used when toggling Cave Mode in World Map or entering caves so old visuals clear fast).
     */
    public void triggerCaveRescan() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityClientPlayerMP player = mc.thePlayer;
        if (player == null) return;
        this.burstScanTicks = 16;
        this.forceCaveScanTicks = 16;
        enqueueRadius(MathHelper.floor_double(player.posX) >> 4, MathHelper.floor_double(player.posZ) >> 4, SCAN_RADIUS_CHUNKS, true);
    }

    public void reset() {
        priorityQueue.clear();
        queuedKeys.clear();
        lastScanTime.clear();
        activeCaveMode = false;
        burstScanTicks = 0;
        forceCaveScanTicks = 0;
    }

    public void enqueueChunk(int chunkX, int chunkZ) {
        long key = packChunk(chunkX, chunkZ);
        if (queuedKeys.add(key)) {
            priorityQueue.addLast(key);
        }
    }

    public void onClientTick() {
        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.theWorld;
        EntityClientPlayerMP player = mc.thePlayer;
        if (world == null || player == null) return;

        int dimId = world.provider.dimensionId;
        worldCache.ensureWorldAndDimension(dimId);

        boolean prevCave = activeCaveMode;
        activeCaveMode = detectCaveMode(world, player);
        int caveAnchorY = computeCaveAnchorY(world, player);

        // If player just entered cave mode or shifted vertically, burst-scan all nearby chunks immediately!
        boolean enteredCave = activeCaveMode && !prevCave;
        boolean caveSliceShifted = activeCaveMode && Math.abs(caveAnchorY - lastCaveAnchorY) >= 4;
        if (enteredCave || caveSliceShifted) {
            lastCaveAnchorY = caveAnchorY;
            int rescanRad = enteredCave ? SCAN_RADIUS_CHUNKS : 6;
            if (enteredCave) {
                burstScanTicks = Math.max(burstScanTicks, 14);
            }
            enqueueRadius(MathHelper.floor_double(player.posX) >> 4, MathHelper.floor_double(player.posZ) >> 4, rescanRad, true);
        }

        // Periodically enqueue surrounding chunks for fresh updates
        tickCounter++;
        if (tickCounter % 10 == 0) {
            int pcx = MathHelper.floor_double(player.posX) >> 4;
            int pcz = MathHelper.floor_double(player.posZ) >> 4;
            enqueueRadius(pcx, pcz, SCAN_RADIUS_CHUNKS, false);
        }

        // Prevent unbounded memory growth in long sessions
        if (lastScanTime.size() > 2048) {
            long cutoff = System.currentTimeMillis() - 60_000L;
            lastScanTime.values().removeIf(t -> t < cutoff);
        }

        boolean scanCaveNow = activeCaveMode || forceCaveScanTicks > 0;
        int budget = MapConfig.get().chunksScannedPerTick;
        if (burstScanTicks > 0) {
            budget = Math.max(budget, 20);
            burstScanTicks--;
        }
        if (forceCaveScanTicks > 0) {
            forceCaveScanTicks--;
        }
        int scanned = 0;
        long now = System.currentTimeMillis();

        while (scanned < budget && !priorityQueue.isEmpty()) {
            long key = priorityQueue.pollFirst();
            queuedKeys.remove(key);

            int cx = unpackX(key);
            int cz = unpackZ(key);

            if (!world.getChunkProvider().chunkExists(cx, cz)) continue;
            Chunk chunk = world.getChunkFromChunkCoords(cx, cz);
            if (chunk == null || !chunk.isChunkLoaded || chunk.isEmpty()) continue;

            ChunkSnapshot snap = scanChunk(world, chunk, dimId, scanCaveNow, caveAnchorY);
            worldCache.submitChunkSnapshot(snap);
            lastScanTime.put(key, now);
            scanned++;
        }
    }

    private void enqueueRadius(int centerCx, int centerCz, int radius, boolean force) {
        long now = System.currentTimeMillis();
        // Spiral / ring order from nearest to farthest so chunks right under the player appear instantly
        for (int r = 0; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    int cx = centerCx + dx;
                    int cz = centerCz + dz;
                    long key = packChunk(cx, cz);
                    if (!force) {
                        Long last = lastScanTime.get(key);
                        long interval = r <= 2 ? RESCAN_NEAR_MS : RESCAN_FAR_MS;
                        if (last != null && now - last < interval) continue;
                    }
                    if (queuedKeys.add(key)) {
                        priorityQueue.addLast(key);
                    }
                }
            }
        }
    }

    private boolean detectCaveMode(World world, EntityClientPlayerMP player) {
        MapConfig.CaveMode mode = MapConfig.get().caveMode;
        if (mode == MapConfig.CaveMode.ON) return true;
        if (mode == MapConfig.CaveMode.OFF) return false;

        // AUTO: Always use cave slice in roofed / no-sky dimensions (e.g. Nether)
        if (world.provider.hasNoSky) {
            return true;
        }

        int px = MathHelper.floor_double(player.posX);
        int py = MathHelper.floor_double(player.posY + 1.0);
        int pz = MathHelper.floor_double(player.posZ);
        if (py < 0) return true;
        if (py >= 250) return false;

        // Check a 3x3 column above the player's eyes for solid non-foliage roof
        int roofHits = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (hasSolidCeiling(world, px + dx, py + 1, pz + dz)) {
                    roofHits++;
                }
            }
        }
        return roofHits >= 6;
    }

    private static boolean hasSolidCeiling(World world, int x, int startY, int z) {
        if (!world.blockExists(x, startY, z)) return false;
        int top = world.getHeightValue(x, z);
        if (top <= startY) return false;
        for (int y = startY; y <= Math.min(top, 255); y++) {
            Block b = world.getBlock(x, y, z);
            if (b != null && b != Blocks.air) {
                Material mat = b.getMaterial();
                if (mat.isOpaque() && mat != Material.leaves && mat != Material.glass) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int computeCaveAnchorY(World world, EntityClientPlayerMP player) {
        int py = MathHelper.floor_double(player.posY);
        if (world.provider.hasNoSky && py > 122) {
            // Standing on Nether roof: show the roof surface
            return 127;
        }
        return Math.max(1, Math.min(250, py));
    }

    private static ChunkSnapshot scanChunk(World world, Chunk chunk, int dimId, boolean scanCave, int caveAnchorY) {
        ChunkSnapshot snap = new ChunkSnapshot(dimId, chunk.xPosition, chunk.zPosition, scanCave);
        int baseX = chunk.xPosition << 4;
        int baseZ = chunk.zPosition << 4;
        int topSeg = Math.min(255, chunk.getTopFilledSegment() + 15);
        int sliceUp = MapConfig.get().caveSliceHeight;

        for (int lz = 0; lz < 16; lz++) {
            int wz = baseZ + lz;
            int rowOff = lz << 4;
            for (int lx = 0; lx < 16; lx++) {
                int wx = baseX + lx;
                int idx = rowOff + lx;
                BiomeGenBase biome = chunk.getBiomeGenForWorldCoords(lx, lz, world.getWorldChunkManager());

                // --- Surface pass ---
                int y = Math.min(topSeg, Math.max(0, chunk.getHeightValue(lx, lz) + 1));
                int wDepth = 0;
                int wColor = 0;
                int solidRgb = 0;
                int solidY = 0;
                int light = 15;
                int flatAccentRgb = 0;
                boolean snowCovered = false;

                for (; y >= 0; y--) {
                    Block block = chunk.getBlock(lx, y, lz);
                    if (BlockColorResolver.isTransparentForSurface(block)) {
                        continue;
                    }
                    int meta = chunk.getBlockMetadata(lx, y, lz);
                    if (BlockColorResolver.isFlatAccentBlock(block)) {
                        if (block == Blocks.snow_layer) {
                            snowCovered = true;
                        } else if (flatAccentRgb == 0) {
                            flatAccentRgb = BlockColorResolver.getBlockColor(world, block, meta, wx, y, wz, biome);
                        }
                        continue;
                    }
                    if (BlockColorResolver.isWaterLike(block)) {
                        if (wDepth == 0) {
                            wColor = BlockColorResolver.getBlockColor(world, block, meta, wx, y, wz, biome);
                        }
                        wDepth++;
                        continue;
                    }
                    solidRgb = BlockColorResolver.getBlockColor(world, block, meta, wx, y, wz, biome);
                    solidY = y;
                    if (y + 1 < 256) {
                        light = chunk.getSavedLightValue(EnumSkyBlock.Block, lx, y + 1, lz);
                    }
                    break;
                }

                if (solidRgb == 0 && wDepth > 0) {
                    solidRgb = wColor;
                    solidY = 0;
                } else if (snowCovered && wDepth == 0) {
                    solidRgb = 0xEDF4FA;
                } else if (flatAccentRgb != 0 && wDepth == 0) {
                    // Subtle flower/flora fleck on top of smooth grass (like modern Xaero!)
                    solidRgb = BlockColorResolver.blendColor(solidRgb, flatAccentRgb, 0.58f);
                }

                snap.surfaceRgb[idx] = solidRgb;
                snap.surfaceHeight[idx] = (short) solidY;
                snap.waterDepth[idx] = (byte) Math.min(255, wDepth);
                snap.waterRgb[idx] = wColor;
                snap.blockLight[idx] = (byte) light;

                // --- Cave / Nether slice pass ---
                if (scanCave) {
                    // In sky dimensions, strictly start BELOW the top solid surface (solidY - 1) with foundAir=false
                    // so open-sky grass, trees, and rivers NEVER bleed into Cave Mode!
                    int ceilingCap = world.provider.hasNoSky ? 124 : Math.min(254, solidY - 1);
                    int maxY = Math.min(ceilingCap, caveAnchorY + sliceUp);
                    int minY = Math.max(1, caveAnchorY - 32);
                    // Step down from maxY: first find open underground air pocket, then first solid floor (or lava/water)
                    boolean foundAir = false;
                    int caveCol = 0;
                    int caveY = 0;
                    int caveWaterDepth = 0;

                    for (int cy = maxY; cy >= minY; cy--) {
                        Block b = chunk.getBlock(lx, cy, lz);
                        if (b == null || b == Blocks.air) {
                            foundAir = true;
                            continue;
                        }
                        Material mat = b.getMaterial();
                        if (mat == Material.lava) {
                            if (foundAir) {
                                caveCol = 0xFF6B1A; // Vibrant glowing cave lava
                                caveY = cy;
                                break;
                            }
                            continue;
                        }
                        if (mat == Material.water) {
                            if (foundAir) {
                                caveWaterDepth++;
                            }
                            continue;
                        }
                        boolean open = !mat.blocksMovement();
                        if (!foundAir) {
                            if (open) {
                                foundAir = true;
                            }
                        } else if (!open && !BlockColorResolver.isTransparentForSurface(b)) {
                            int meta = chunk.getBlockMetadata(lx, cy, lz);
                            caveCol = BlockColorResolver.getBlockColor(world, b, meta, wx, cy, wz, biome);
                            caveY = cy;
                            if (caveWaterDepth > 0) {
                                float wAlpha = Math.min(0.85f, 0.48f + Math.min(8, caveWaterDepth) * 0.045f);
                                caveCol = BlockColorResolver.blendColor(caveCol, 0x2F66D0, wAlpha);
                            }
                            break;
                        }
                    }

                    snap.caveRgb[idx] = caveCol;
                    snap.caveHeight[idx] = (short) caveY;
                }
            }
        }

        return snap;
    }

    /**
     * Exact Minecraft 1.7.10 Slime Chunk formula (available when world seed is known, e.g. singleplayer).
     */
    public static boolean isSlimeChunk(World world, int chunkX, int chunkZ) {
        if (world == null) return false;
        long seed = world.getSeed();
        if (seed == 0L) return false;
        Random rnd = SLIME_RNG.get();
        rnd.setSeed(
            seed
                + (long) (chunkX * chunkX * 0x4c1906)
                + (long) (chunkX * 0x5ac0db)
                + (long) (chunkZ * chunkZ) * 0x4307a7L
                + (long) (chunkZ * 0x5f24f) ^ 0x3ad8025fL
        );
        return rnd.nextInt(10) == 0;
    }

    /**
     * Exact GTNH / GT5U ore vein grid alignment (verified against VisualProspecting 1.5.42):
     * Each ore vein spans a 3x3 chunk square [3k .. 3k+2], with center chunk at 3k + 1.
     */
    public static boolean isOreVeinBorderChunk(int chunkCoord) {
        return Math.floorMod(chunkCoord, 3) == 0;
    }

    public static boolean isOreVeinCenterChunk(int chunkX, int chunkZ) {
        return Math.floorMod(chunkX, 3) == 1 && Math.floorMod(chunkZ, 3) == 1;
    }

    private static long packChunk(int cx, int cz) {
        return (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
    }

    private static int unpackX(long key) {
        return (int) (key >> 32);
    }

    private static int unpackZ(long key) {
        return (int) key;
    }
}

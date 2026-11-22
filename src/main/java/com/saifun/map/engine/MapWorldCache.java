package com.saifun.map.engine;

import com.saifun.map.SaifunsMapMod;
import com.saifun.map.config.MapConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.World;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Manages cached 512x512 map regions per world & dimension, async shading, and async binary disk I/O.
 */
public class MapWorldCache {

    private static final int MAX_LOADED_REGIONS = 64;

    private final File dataRoot;
    private final Map<Long, MapRegion> regions = new ConcurrentHashMap<>();
    private final Set<Long> pendingDiskLoads = ConcurrentHashMap.newKeySet();
    private final Set<Long> missingDiskRegions = ConcurrentHashMap.newKeySet();
    private final Queue<MapRegion> pendingTextureDeletes = new ConcurrentLinkedQueue<>();

    private final ExecutorService workerPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SaifunsMap-Worker");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private final ExecutorService ioPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SaifunsMap-IO");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    });

    private volatile String activeWorldId = null;
    private volatile int activeDimension = Integer.MIN_VALUE;
    private long lastSaveTickMs = 0L;

    public MapWorldCache(File dataRoot) {
        this.dataRoot = dataRoot;
    }

    public File getDataRoot() {
        return dataRoot;
    }

    public String getActiveWorldId() {
        return activeWorldId;
    }

    public int getActiveDimension() {
        return activeDimension;
    }

    public void ensureWorldAndDimension(int dimensionId) {
        String worldId = resolveCurrentWorldId();
        if (worldId == null) return;

        if (!worldId.equals(this.activeWorldId) || dimensionId != this.activeDimension) {
            switchContext(worldId, dimensionId);
        }
    }

    private void switchContext(String newWorldId, int newDimensionId) {
        flushAndClear();
        this.activeWorldId = newWorldId;
        this.activeDimension = newDimensionId;
        SaifunsMapMod.LOG.debug("Switched map cache context to world='{}' dim={}", newWorldId, newDimensionId);
    }

    public void flushAndClear() {
        final String oldWorld = this.activeWorldId;
        final int oldDim = this.activeDimension;
        List<MapRegion> snapshot = new ArrayList<>(regions.values());
        regions.clear();
        pendingDiskLoads.clear();
        missingDiskRegions.clear();

        for (MapRegion region : snapshot) {
            pendingTextureDeletes.add(region);
            if (oldWorld != null && MapConfig.get().diskCacheEnabled && region.isDirtyOnDisk()) {
                final File target = getRegionFile(oldWorld, oldDim, region.regionX, region.regionZ);
                ioPool.submit(() -> region.saveToDisk(target));
            }
        }
    }

    public void submitChunkSnapshot(ChunkSnapshot snap) {
        if (snap == null || snap.dimensionId != activeDimension) return;
        int rx = Math.floorDiv(snap.chunkX, MapRegion.CHUNKS_PER_SIDE);
        int rz = Math.floorDiv(snap.chunkZ, MapRegion.CHUNKS_PER_SIDE);
        MapRegion region = getOrCreateRegion(rx, rz);
        if (region == null) return;

        workerPool.submit(() -> {
            if (region.dimensionId == activeDimension) {
                region.applyChunkSnapshot(snap);
            }
        });
    }

    public MapRegion getRegionIfLoaded(int regionX, int regionZ) {
        long key = packRegionKey(regionX, regionZ);
        MapRegion r = regions.get(key);
        if (r != null) {
            r.touch();
            return r;
        }
        // Trigger lazy async disk load if file exists
        requestDiskLoad(regionX, regionZ);
        return null;
    }

    public boolean isChunkExplored(int chunkX, int chunkZ) {
        int rx = Math.floorDiv(chunkX, MapRegion.CHUNKS_PER_SIDE);
        int rz = Math.floorDiv(chunkZ, MapRegion.CHUNKS_PER_SIDE);
        MapRegion r = regions.get(packRegionKey(rx, rz));
        if (r == null) return false;
        return r.isChunkPopulated(chunkX & 31, chunkZ & 31);
    }

    public MapRegion getOrCreateRegion(int regionX, int regionZ) {
        if (activeWorldId == null) return null;
        long key = packRegionKey(regionX, regionZ);
        MapRegion existing = regions.get(key);
        if (existing != null) {
            existing.touch();
            return existing;
        }

        MapRegion created = new MapRegion(activeDimension, regionX, regionZ);
        MapRegion raced = regions.putIfAbsent(key, created);
        if (raced != null) {
            return raced;
        }
        missingDiskRegions.remove(key);

        if (MapConfig.get().diskCacheEnabled) {
            final String worldSnap = activeWorldId;
            final int dimSnap = activeDimension;
            final File file = getRegionFile(worldSnap, dimSnap, regionX, regionZ);
            if (file.exists()) {
                ioPool.submit(() -> created.loadFromDisk(file));
            }
        }
        return created;
    }

    private void requestDiskLoad(int regionX, int regionZ) {
        if (activeWorldId == null || !MapConfig.get().diskCacheEnabled) return;
        long key = packRegionKey(regionX, regionZ);
        if (regions.containsKey(key) || missingDiskRegions.contains(key) || !pendingDiskLoads.add(key)) return;

        final String worldSnap = activeWorldId;
        final int dimSnap = activeDimension;
        final File file = getRegionFile(worldSnap, dimSnap, regionX, regionZ);

        ioPool.submit(() -> {
            try {
                if (!file.exists()) {
                    if (worldSnap.equals(activeWorldId) && dimSnap == activeDimension) {
                        missingDiskRegions.add(key);
                    }
                    return;
                }
                if (!worldSnap.equals(activeWorldId) || dimSnap != activeDimension) return;
                MapRegion loaded = new MapRegion(dimSnap, regionX, regionZ);
                if (loaded.loadFromDisk(file)) {
                    if (worldSnap.equals(activeWorldId) && dimSnap == activeDimension) {
                        regions.putIfAbsent(key, loaded);
                    }
                } else if (worldSnap.equals(activeWorldId) && dimSnap == activeDimension) {
                    missingDiskRegions.add(key);
                }
            } finally {
                pendingDiskLoads.remove(key);
            }
        });
    }

    public void reshadeAllLoaded() {
        List<MapRegion> list = new ArrayList<>(regions.values());
        workerPool.submit(() -> {
            for (MapRegion r : list) {
                r.reshadeAll();
            }
        });
    }

    /**
     * Deletes all explored map tiles (.smap) for the current dimension in memory and on disk,
     * while preserving waypoints.json and all other dimensions untouched.
     */
    public int clearCurrentDimensionMapData() {
        final String worldSnap = this.activeWorldId;
        final int dimSnap = this.activeDimension;

        List<MapRegion> snapshot = new ArrayList<>(regions.values());
        regions.clear();
        pendingDiskLoads.clear();
        missingDiskRegions.clear();
        for (MapRegion region : snapshot) {
            pendingTextureDeletes.add(region);
        }

        if (worldSnap == null) return 0;

        File dimDir = new File(new File(dataRoot, "worlds/" + worldSnap), "dim_" + dimSnap);
        int deleted = 0;
        if (dimDir.exists() && dimDir.isDirectory()) {
            File[] files = dimDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && (f.getName().endsWith(".smap") || f.getName().endsWith(".smap.tmp"))) {
                        if (f.delete()) {
                            deleted++;
                        }
                    }
                }
            }
        }
        SaifunsMapMod.LOG.info("Cleared {} cached region file(s) for world='{}' dim={} (waypoints preserved).", deleted, worldSnap, dimSnap);
        return deleted;
    }

    /**
     * Called on the main GL thread each frame/tick to free orphaned textures and trigger periodic saves.
     */
    public void onRenderTick() {
        MapRegion toDelete;
        while ((toDelete = pendingTextureDeletes.poll()) != null) {
            toDelete.deleteTextures();
        }

        long now = System.currentTimeMillis();
        if (now - lastSaveTickMs >= 15_000L) {
            lastSaveTickMs = now;
            periodicMaintenance();
        }
    }

    private void periodicMaintenance() {
        String worldSnap = activeWorldId;
        int dimSnap = activeDimension;
        if (worldSnap == null) return;

        // Periodic async save of dirty regions
        if (MapConfig.get().diskCacheEnabled) {
            for (MapRegion r : regions.values()) {
                if (r.isDirtyOnDisk()) {
                    File file = getRegionFile(worldSnap, dimSnap, r.regionX, r.regionZ);
                    ioPool.submit(() -> r.saveToDisk(file));
                }
            }
        }

        // Evict oldest LRU regions if cache grows past limit
        if (regions.size() > MAX_LOADED_REGIONS) {
            List<Map.Entry<Long, MapRegion>> entries = new ArrayList<>(regions.entrySet());
            entries.sort((a, b) -> Long.compare(a.getValue().getLastAccessTime(), b.getValue().getLastAccessTime()));
            int toRemove = regions.size() - MAX_LOADED_REGIONS;
            for (int i = 0; i < toRemove; i++) {
                Map.Entry<Long, MapRegion> e = entries.get(i);
                MapRegion removed = regions.remove(e.getKey());
                if (removed != null) {
                    if (MapConfig.get().diskCacheEnabled && removed.isDirtyOnDisk()) {
                        File file = getRegionFile(worldSnap, dimSnap, removed.regionX, removed.regionZ);
                        ioPool.submit(() -> removed.saveToDisk(file));
                    }
                    pendingTextureDeletes.add(removed);
                }
            }
        }
    }

    public File getRegionFile(String worldId, int dimId, int rx, int rz) {
        return new File(new File(new File(dataRoot, "worlds/" + worldId), "dim_" + dimId), "r." + rx + "." + rz + ".smap");
    }

    public static long packRegionKey(int rx, int rz) {
        return (((long) rx) << 32) | (rz & 0xFFFFFFFFL);
    }

    private static final Pattern SANITIZE_PATTERN = Pattern.compile("[^a-zA-Z0-9._-]");
    private static WeakReference<World> cachedWorldRef = new WeakReference<>(null);
    private static String cachedWorldId = null;

    public static String resolveCurrentWorldId() {
        Minecraft mc = Minecraft.getMinecraft();
        World w = mc.theWorld;
        if (w == null) {
            cachedWorldId = null;
            return null;
        }
        if (cachedWorldId != null && cachedWorldRef.get() == w) {
            return cachedWorldId;
        }

        String id;
        if (mc.isSingleplayer()) {
            IntegratedServer is = mc.getIntegratedServer();
            if (is != null && is.getFolderName() != null) {
                id = "sp_" + sanitize(is.getFolderName());
            } else {
                id = "sp_world";
            }
        } else {
            ServerData sd = mc.func_147104_D();
            if (sd != null && sd.serverIP != null && !sd.serverIP.isEmpty()) {
                id = "mp_" + sanitize(sd.serverIP);
            } else {
                id = "mp_server";
            }
        }
        cachedWorldRef = new WeakReference<>(w);
        cachedWorldId = id;
        return id;
    }

    private static String sanitize(String raw) {
        return SANITIZE_PATTERN.matcher(raw).replaceAll("_");
    }
}

package com.saifun.map.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.saifun.map.SaifunsMapMod;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * Persisted JSON configuration for Saifun's Map.
 * All fields are directly editable in-game via GuiMapSettings with live preview.
 */
public class MapConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static File configFile;
    private static MapConfig instance = new MapConfig();

    public enum Corner {
        TOP_RIGHT,
        TOP_LEFT,
        BOTTOM_RIGHT,
        BOTTOM_LEFT
    }

    public enum CaveMode {
        AUTO,
        ON,
        OFF
    }

    public enum LightingMode {
        AUTO,
        DAY,
        NIGHT
    }

    public enum RadarIconStyle {
        HEADS,
        DOTS
    }

    public enum FrameStyle {
        XAERO_MODERN,
        MINIMAL,
        NONE
    }

    // --- Minimap Display ---
    public boolean minimapEnabled = true;
    public boolean circularMinimap = false;
    public int minimapSize = 148;
    public Corner minimapCorner = Corner.TOP_RIGHT;
    public int offsetX = 8;
    public int offsetY = 8;
    public boolean lockNorth = false;
    public float minimapZoom = 2.0f;
    public float minimapOpacity = 1.0f;
    public FrameStyle frameStyle = FrameStyle.XAERO_MODERN;
    public boolean showCompass = true;
    public boolean showInfoBar = true;
    public boolean showCoordinates = true;
    public boolean showBiome = true;
    public boolean showTimeAndLight = true;
    public boolean showDimensionCoords = true;

    // --- Map Rendering & Engine ---
    public boolean terrainShading = true;
    public boolean biomeColors = true;
    public boolean waterTransparency = true;
    public LightingMode lightingMode = LightingMode.AUTO;
    public CaveMode caveMode = CaveMode.AUTO;
    public int caveSliceHeight = 6;
    public int chunksScannedPerTick = 6;
    public boolean diskCacheEnabled = true;

    // --- Overlays (GTNH-friendly) ---
    public boolean showChunkGrid = false;
    public boolean showOreVeinGrid = true;
    public boolean highlightOreVeinCenter = true;
    public boolean showSlimeChunks = false;

    // --- Waypoints & 3D World Markers ---
    public boolean showWaypointsOnMinimap = true;
    public boolean showWaypointsOnWorldMap = true;
    public boolean showWaypointsInWorld = true;
    public boolean showWaypointBeam = false;
    public boolean autoDeathWaypoints = true;
    public int maxWaypointRenderDistance = 10000;
    public float waypointScale = 1.0f;

    // --- GTNH Navigator Bridge (VisualProspecting / ServerUtilities) ---
    public boolean navigatorEnabled = true;
    public boolean showNavigatorOnMinimap = true;
    public boolean showNavigatorOnWorldMap = true;

    // --- Entity Radar (Zero FairPlay restrictions!) ---
    public boolean radarEnabled = true;
    public RadarIconStyle radarIconStyle = RadarIconStyle.HEADS;
    public float radarIconScale = 1.0f;
    public boolean showHeightIndicator = true;
    public boolean showPlayers = true;
    public boolean showPlayerNames = true;
    public boolean showHostileMobs = true;
    public boolean showPassiveMobs = true;
    public boolean showTamedMobs = true;
    public boolean showBossMobs = true;
    public boolean showItems = false;
    public int maxRadarEntities = 128;
    public boolean serverPlayerSyncEnabled = true;

    // --- Colors (ARGB) ---
    public int frameBorderColor = 0xEE181A20;
    public int frameAccentColor = 0xFF3A7BD5;
    public int chunkGridColor = 0x44FFFFFF;
    public int oreVeinGridColor = 0x99F5A623;
    public int oreVeinCenterColor = 0x30F5A623;
    public int slimeChunkColor = 0x4444FF55;

    public static MapConfig get() {
        return instance;
    }

    public static void load(File file) {
        configFile = file;
        if (file != null && file.exists()) {
            try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                MapConfig loaded = GSON.fromJson(reader, MapConfig.class);
                if (loaded != null) {
                    instance = loaded;
                    instance.clampValues();
                }
            } catch (Exception e) {
                SaifunsMapMod.LOG.warn("Failed to load config {}, using defaults: {}", file, e.getMessage());
                instance = new MapConfig();
            }
        }
        save();
    }

    public static void save() {
        if (configFile == null) return;
        instance.clampValues();
        try {
            File parent = configFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(configFile), StandardCharsets.UTF_8)) {
                GSON.toJson(instance, writer);
            }
        } catch (Exception e) {
            SaifunsMapMod.LOG.error("Failed to save config {}", configFile, e);
        }
    }

    public void clampValues() {
        if (minimapSize < 80) minimapSize = 80;
        if (minimapSize > 320) minimapSize = 320;
        if (minimapZoom < 0.5f) minimapZoom = 0.5f;
        if (minimapZoom > 8.0f) minimapZoom = 8.0f;
        if (minimapOpacity < 0.25f) minimapOpacity = 0.25f;
        if (minimapOpacity > 1.0f) minimapOpacity = 1.0f;
        if (radarIconScale < 0.5f) radarIconScale = 0.5f;
        if (radarIconScale > 2.0f) radarIconScale = 2.0f;
        if (chunksScannedPerTick < 1) chunksScannedPerTick = 1;
        if (chunksScannedPerTick > 24) chunksScannedPerTick = 24;
        if (caveSliceHeight < 2) caveSliceHeight = 2;
        if (caveSliceHeight > 16) caveSliceHeight = 16;
        if (maxRadarEntities < 16) maxRadarEntities = 16;
        if (maxRadarEntities > 512) maxRadarEntities = 512;
        if (minimapCorner == null) minimapCorner = Corner.TOP_RIGHT;
        if (caveMode == null) caveMode = CaveMode.AUTO;
        if (lightingMode == null) lightingMode = LightingMode.AUTO;
        if (radarIconStyle == null) radarIconStyle = RadarIconStyle.HEADS;
        if (frameStyle == null) frameStyle = FrameStyle.XAERO_MODERN;
    }
}

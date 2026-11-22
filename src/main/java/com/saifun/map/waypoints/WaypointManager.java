package com.saifun.map.waypoints;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.saifun.map.SaifunsMapMod;
import com.saifun.map.config.MapConfig;
import com.saifun.map.engine.MapWorldCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MathHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages per-world waypoints, auto-death points, chat sharing/parsing (JourneyMap & Xaero compatible),
 * and Navigator active waypoints.
 */
public class WaypointManager {

    public static final int[] PALETTE = {
        0x3B82F6, // Azure
        0xEF4444, // Red
        0x22C55E, // Emerald
        0xF59E0B, // Amber
        0xA855F7, // Purple
        0x06B6D4, // Cyan
        0xEC4899, // Pink
        0x84CC16, // Lime
        0xF97316, // Orange
        0xEAB308, // Gold
        0x14B8A6, // Teal
        0xF8FAFC  // White
    };

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<Waypoint>>() {}.getType();
    private static final Random RANDOM = new Random();

    // Parses standard GTNH JourneyMap shared waypoints: [name:"Base", x:100, y:64, z:-200, dim:0]
    private static final Pattern JM_CHAT_PATTERN = Pattern.compile(
        "\\[name:\"?([^\",\\]]+)\"?,\\s*x:(-?\\d+),\\s*y:(-?\\d+),\\s*z:(-?\\d+)(?:,\\s*dim:(-?\\d+))?\\]"
    );
    // Parses Xaero shared waypoints: xaero-waypoint:Name:I:x:y:z:color:...
    private static final Pattern XAERO_CHAT_PATTERN = Pattern.compile(
        "xaero-waypoint:([^:]+):([^:]*):(-?\\d+):(~|-?\\d+):(-?\\d+):(\\d+):"
    );

    private final MapWorldCache worldCache;
    private final List<Waypoint> waypoints = new ArrayList<>();
    private volatile Map<Integer, List<Waypoint>> dimCache = Collections.emptyMap();
    private volatile String loadedWorldId = null;
    private boolean wasDeadLastTick = false;

    public WaypointManager(MapWorldCache worldCache) {
        this.worldCache = worldCache;
    }

    private void rebuildDimCache() {
        if (waypoints.isEmpty()) {
            dimCache = Collections.emptyMap();
            return;
        }
        Map<Integer, List<Waypoint>> map = new HashMap<>();
        for (int i = 0; i < waypoints.size(); i++) {
            Waypoint wp = waypoints.get(i);
            map.computeIfAbsent(wp.dimensionId, k -> new ArrayList<>()).add(wp);
        }
        for (Map.Entry<Integer, List<Waypoint>> e : map.entrySet()) {
            e.setValue(Collections.unmodifiableList(e.getValue()));
        }
        dimCache = map;
    }

    public synchronized void ensureLoaded() {
        String worldId = MapWorldCache.resolveCurrentWorldId();
        if (worldId == null) return;
        if (worldId.equals(loadedWorldId)) return;
        loadForWorld(worldId);
    }

    public synchronized void clearSession() {
        save();
        waypoints.clear();
        dimCache = Collections.emptyMap();
        loadedWorldId = null;
        wasDeadLastTick = false;
    }

    private synchronized void loadForWorld(String worldId) {
        waypoints.clear();
        loadedWorldId = worldId;
        File file = getWaypointsFile(worldId);
        if (file.exists()) {
            try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                List<Waypoint> loaded = GSON.fromJson(reader, LIST_TYPE);
                if (loaded != null) {
                    for (Waypoint wp : loaded) {
                        if (wp != null && !wp.temporary) {
                            if (wp.id == null) wp.id = java.util.UUID.randomUUID().toString();
                            if (wp.type == null) wp.type = Waypoint.Type.NORMAL;
                            wp.initials = Waypoint.normalizeInitials(wp.initials, wp.name);
                            waypoints.add(wp);
                        }
                    }
                }
            } catch (Exception e) {
                SaifunsMapMod.LOG.warn("Failed to load waypoints from {}: {}", file, e.getMessage());
            }
        }
        rebuildDimCache();
    }

    public synchronized void save() {
        rebuildDimCache();
        if (loadedWorldId == null) return;
        File file = getWaypointsFile(loadedWorldId);
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            List<Waypoint> toSave = new ArrayList<>();
            for (Waypoint wp : waypoints) {
                if (!wp.temporary) toSave.add(wp);
            }
            try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
                GSON.toJson(toSave, LIST_TYPE, writer);
            }
        } catch (Exception e) {
            SaifunsMapMod.LOG.error("Failed to save waypoints to {}", file, e);
        }
    }

    private File getWaypointsFile(String worldId) {
        return new File(new File(worldCache.getDataRoot(), "worlds/" + worldId), "waypoints.json");
    }

    public synchronized List<Waypoint> getAllWaypoints() {
        ensureLoaded();
        return Collections.unmodifiableList(new ArrayList<>(waypoints));
    }

    /**
     * Lock-free, zero-allocation lookup for render-thread frame loops (150+ FPS).
     */
    public List<Waypoint> getWaypointsForDimension(int dimId) {
        List<Waypoint> cached = dimCache.get(dimId);
        return cached != null ? cached : Collections.emptyList();
    }

    public synchronized void addWaypoint(Waypoint wp) {
        ensureLoaded();
        waypoints.add(wp);
        save();
    }

    public synchronized boolean addIfNotDuplicate(Waypoint candidate) {
        ensureLoaded();
        for (Waypoint existing : waypoints) {
            if (existing.dimensionId == candidate.dimensionId
                && existing.x == candidate.x
                && existing.y == candidate.y
                && existing.z == candidate.z
                && existing.name.equalsIgnoreCase(candidate.name)) {
                return false;
            }
        }
        waypoints.add(candidate);
        return true;
    }

    public synchronized void removeWaypoint(Waypoint wp) {
        if (waypoints.remove(wp)) {
            save();
        }
    }

    public synchronized void setNavigatorWaypoint(String tagKey, String label, int x, int y, int z, int dimId, int color) {
        ensureLoaded();
        clearNavigatorWaypoints(tagKey);
        Waypoint wp = new Waypoint(label, "NV", x, y, z, dimId, color, Waypoint.Type.NAVIGATOR);
        wp.id = "nav:" + tagKey;
        wp.temporary = true;
        waypoints.add(wp);
        rebuildDimCache();
    }

    public synchronized void clearNavigatorWaypoints(String tagKey) {
        String targetId = "nav:" + tagKey;
        boolean changed = false;
        Iterator<Waypoint> it = waypoints.iterator();
        while (it.hasNext()) {
            Waypoint wp = it.next();
            if (wp.type == Waypoint.Type.NAVIGATOR && targetId.equals(wp.id)) {
                it.remove();
                changed = true;
            }
        }
        if (changed) rebuildDimCache();
    }

    public synchronized boolean hasNavigatorWaypoint(String tagKey) {
        String targetId = "nav:" + tagKey;
        for (Waypoint wp : waypoints) {
            if (wp.type == Waypoint.Type.NAVIGATOR && targetId.equals(wp.id)) {
                return true;
            }
        }
        return false;
    }

    public void onClientTick() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityClientPlayerMP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) {
            wasDeadLastTick = false;
            return;
        }

        ensureLoaded();

        boolean deadNow = player.isDead || player.getHealth() <= 0.0f;
        if (deadNow && !wasDeadLastTick && MapConfig.get().autoDeathWaypoints) {
            int dx = MathHelper.floor_double(player.posX);
            int dy = Math.max(0, Math.min(255, MathHelper.floor_double(player.posY)));
            int dz = MathHelper.floor_double(player.posZ);
            int dim = mc.theWorld.provider.dimensionId;
            Waypoint death = new Waypoint("Death (" + dx + ", " + dz + ")", "X", dx, dy, dz, dim, 0xEF4444, Waypoint.Type.DEATH);
            addWaypoint(death);
            player.addChatMessage(new ChatComponentText(
                "\u00a7b[Saifun's Map]\u00a7r Saved death waypoint at \u00a7e" + dx + ", " + dy + ", " + dz + "\u00a7r (Dim " + dim + ")"
            ));
        }
        wasDeadLastTick = deadNow;
    }

    public static void teleportTo(Waypoint wp) {
        teleportTo(wp.x, wp.y, wp.z);
    }

    public static void teleportTo(int x, int y, int z) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) {
            mc.thePlayer.sendChatMessage("/tp " + x + " " + y + " " + z);
        }
    }

    public static void shareInChat(Waypoint wp) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) {
            mc.thePlayer.sendChatMessage(wp.formatChatShare());
        }
    }

    /**
     * Detects JourneyMap or Xaero shared waypoints in incoming chat and returns a parsed Waypoint if found.
     */
    public static Waypoint tryParseChatWaypoint(String message, int currentDim) {
        if (message == null || message.isEmpty()) return null;
        String clean = message.replaceAll("\u00a7[0-9a-fk-or]", "");

        Matcher jm = JM_CHAT_PATTERN.matcher(clean);
        if (jm.find()) {
            String name = jm.group(1).trim();
            int x = Integer.parseInt(jm.group(2));
            int y = Integer.parseInt(jm.group(3));
            int z = Integer.parseInt(jm.group(4));
            int dim = jm.group(5) != null ? Integer.parseInt(jm.group(5)) : currentDim;
            return new Waypoint(name, null, x, y, z, dim, pickRandomColor(), Waypoint.Type.NORMAL);
        }

        Matcher xm = XAERO_CHAT_PATTERN.matcher(clean);
        if (xm.find()) {
            String name = xm.group(1).trim();
            String initials = xm.group(2).trim();
            int x = Integer.parseInt(xm.group(3));
            int y = "~".equals(xm.group(4)) ? 70 : Integer.parseInt(xm.group(4));
            int z = Integer.parseInt(xm.group(5));
            return new Waypoint(name, initials, x, y, z, currentDim, pickRandomColor(), Waypoint.Type.NORMAL);
        }
        return null;
    }

    public static int pickRandomColor() {
        return PALETTE[RANDOM.nextInt(PALETTE.length)];
    }
}

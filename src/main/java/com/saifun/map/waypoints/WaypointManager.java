package com.saifun.map.waypoints;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.saifun.map.SaifunsMapMod;
import com.saifun.map.compat.ShareWhereIAmBridge;
import com.saifun.map.config.MapConfig;
import com.saifun.map.engine.MapWorldCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.MathHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
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

    /**
     * Toggles a single temporary 3D beacon (not persisted to disk).
     * Returns true if the beacon was enabled/moved, false if the same beacon was toggled off.
     */
    public synchronized boolean toggleTempBeacon(String name, int x, int y, int z, int dimId, int color) {
        ensureLoaded();
        String targetId = "shared:beacon";
        Waypoint existing = null;
        for (Waypoint wp : waypoints) {
            if (targetId.equals(wp.id)) {
                existing = wp;
                break;
            }
        }
        if (existing != null) {
            waypoints.remove(existing);
            boolean samePos = existing.dimensionId == dimId && existing.x == x && existing.y == y && existing.z == z;
            if (samePos) {
                rebuildDimCache();
                return false;
            }
        }
        Waypoint beacon = new Waypoint(name, "!", x, y, z, dimId, color, Waypoint.Type.SHARED_BEACON);
        beacon.id = targetId;
        beacon.temporary = true;
        waypoints.add(beacon);
        rebuildDimCache();
        return true;
    }

    /**
     * Adds or refreshes a timed transient 3D ping from a teammate (e.g. ShareWhereIAm Insert ping).
     */
    public synchronized void addTransientPing(
        String playerName,
        String label,
        int x, int y, int z,
        int dimId,
        int color,
        long durationMs
    ) {
        ensureLoaded();
        String key = "ping:" + (playerName != null ? playerName : "anon");
        Iterator<Waypoint> it = waypoints.iterator();
        while (it.hasNext()) {
            if (key.equals(it.next().id)) {
                it.remove();
            }
        }
        String displayName = (label != null && !label.trim().isEmpty())
            ? label.trim()
            : (playerName != null ? playerName : "Ping");
        Waypoint ping = new Waypoint(displayName, "!", x, y, z, dimId, color, Waypoint.Type.SHARED_BEACON);
        ping.id = key;
        ping.temporary = true;
        ping.expiresAt = System.currentTimeMillis() + Math.max(3000L, durationMs);
        waypoints.add(ping);
        rebuildDimCache();
    }

    public void onClientTick() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityClientPlayerMP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) {
            wasDeadLastTick = false;
            return;
        }

        ensureLoaded();
        pruneExpiredTemporaryWaypoints();

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

    private synchronized void pruneExpiredTemporaryWaypoints() {
        long now = System.currentTimeMillis();
        boolean removed = false;
        Iterator<Waypoint> it = waypoints.iterator();
        while (it.hasNext()) {
            Waypoint wp = it.next();
            if (wp.temporary && wp.expiresAt > 0L && now >= wp.expiresAt) {
                it.remove();
                removed = true;
            }
        }
        if (removed) {
            rebuildDimCache();
        }
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
        shareInChat(wp, "");
    }

    public static void shareInChat(Waypoint wp, String extraInfo) {
        if (wp == null) return;
        // Prefer GTNH ShareWhereIAm packet protocol so both JourneyMap and Saifun's Map teammates
        // receive native clickable [Save] [Edit] [Beacon/Toggle] chat cards!
        if (ShareWhereIAmBridge.shareWaypoint(wp, extraInfo)) {
            return;
        }
        // Fallback for servers/packs without ShareWhereIAm: standard JourneyMap chat text format
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) {
            mc.thePlayer.sendChatMessage(wp.formatChatShare());
        }
    }

    public static String encodeSharePayload(Waypoint wp) {
        JsonObject obj = new JsonObject();
        obj.addProperty("n", wp.name != null ? wp.name : "Waypoint");
        obj.addProperty("x", wp.x);
        obj.addProperty("y", wp.y);
        obj.addProperty("z", wp.z);
        obj.addProperty("d", wp.dimensionId);
        obj.addProperty("c", wp.color & 0xFFFFFF);
        byte[] bytes = obj.toString().getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static Waypoint decodeSharePayload(String base64) {
        if (base64 == null || base64.isEmpty()) return null;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(base64);
            String json = new String(bytes, StandardCharsets.UTF_8);
            JsonObject obj = new JsonParser().parse(json).getAsJsonObject();
            String name = obj.has("n") ? obj.get("n").getAsString() : "Waypoint";
            int x = obj.has("x") ? obj.get("x").getAsInt() : 0;
            int y = obj.has("y") ? obj.get("y").getAsInt() : 70;
            int z = obj.has("z") ? obj.get("z").getAsInt() : 0;
            int dim = obj.has("d") ? obj.get("d").getAsInt() : 0;
            int color = obj.has("c") ? obj.get("c").getAsInt() : pickRandomColor();
            return new Waypoint(name, null, x, y, z, dim, color, Waypoint.Type.NORMAL);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Posts a clickable chat card with [Save], [Edit], and [Beacon] actions for a shared waypoint.
     */
    public static void postClickableShareChat(String sender, Waypoint wp, String extraInfo) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityClientPlayerMP player = mc.thePlayer;
        if (player == null || wp == null) return;

        String payload = encodeSharePayload(wp);

        if (sender != null && !sender.trim().isEmpty()) {
            ChatComponentText header = new ChatComponentText("");
            ChatComponentText tag = new ChatComponentText("[Saifun's Map]");
            tag.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.AQUA));
            header.appendSibling(tag);
            header.appendText(" ");
            ChatComponentText who = new ChatComponentText(sender);
            who.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.GREEN));
            header.appendSibling(who);
            header.appendText(" shared a waypoint:");
            player.addChatMessage(header);
        }

        ChatComponentText line = new ChatComponentText("");
        if (sender == null || sender.trim().isEmpty()) {
            ChatComponentText tag = new ChatComponentText("[Saifun's Map] ");
            tag.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.AQUA));
            line.appendSibling(tag);
        }

        ChatComponentText nameComp = new ChatComponentText(wp.name);
        nameComp.setChatStyle(new ChatStyle().setBold(true).setColor(EnumChatFormatting.WHITE));
        line.appendSibling(nameComp);

        String coordsStr = String.format(" (%d, %d, %d) [Dim %d] ", wp.x, wp.y, wp.z, wp.dimensionId);
        ChatComponentText coordsComp = new ChatComponentText(coordsStr);
        coordsComp.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.GRAY));
        line.appendSibling(coordsComp);

        line.appendSibling(makeChatButton(
            "[Save]",
            EnumChatFormatting.GREEN,
            "Click to save waypoint immediately",
            "/smap save " + payload
        ));
        line.appendText(" ");
        line.appendSibling(makeChatButton(
            "[Edit]",
            EnumChatFormatting.AQUA,
            "Click to edit & save waypoint",
            "/smap edit " + payload
        ));
        line.appendText(" ");
        line.appendSibling(makeChatButton(
            "[Beacon]",
            EnumChatFormatting.GOLD,
            "Click to toggle temporary 3D beacon",
            "/smap beacon " + payload
        ));

        player.addChatMessage(line);

        if (extraInfo != null && !extraInfo.trim().isEmpty()) {
            ChatComponentText extraComp = new ChatComponentText("  " + extraInfo.trim());
            extraComp.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.GRAY));
            player.addChatMessage(extraComp);
        }
    }

    private static IChatComponent makeChatButton(String label, EnumChatFormatting color, String hoverText, String command) {
        ChatComponentText comp = new ChatComponentText(label);
        ChatStyle style = new ChatStyle()
            .setBold(true)
            .setColor(color)
            .setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText(hoverText)))
            .setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command));
        comp.setChatStyle(style);
        return comp;
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

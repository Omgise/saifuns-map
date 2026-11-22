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
import com.saifun.map.util.Lang;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages per-world waypoints, auto-death points, chat sharing/parsing (ShareWhereIAm & JourneyMap compatible),
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
    private static final Type STRING_SET_TYPE = new TypeToken<Set<String>>() {}.getType();
    private static final Random RANDOM = new Random();

    // Parses standard GTNH JourneyMap shared waypoints (including commas/parens inside quotes):
    // e.g. [name:"Death (-36, -16)", x:-36, y:42, z:-16, dim:-1] or [name:Base, x:100, y:64, z:-200, dim:0]
    private static final Pattern JM_CHAT_PATTERN = Pattern.compile(
        "\\[name:(?:\"([^\"]+)\"|([^,\\]]+)),\\s*x:(-?\\d+),\\s*y:(-?\\d+),\\s*z:(-?\\d+)(?:,\\s*dim:(-?\\d+))?\\]"
    );

    private final MapWorldCache worldCache;
    private final List<Waypoint> waypoints = new ArrayList<>();
    private final Set<String> hiddenGroupsLower = new HashSet<>();
    private volatile Map<Integer, List<Waypoint>> dimCache = Collections.emptyMap();
    private volatile String loadedWorldId = null;
    private boolean wasDeadLastTick = false;

    public WaypointManager(MapWorldCache worldCache) {
        this.worldCache = worldCache;
    }

    public synchronized void refreshRenderCache() {
        rebuildDimCache();
    }

    private void rebuildDimCache() {
        if (waypoints.isEmpty()) {
            dimCache = Collections.emptyMap();
            return;
        }
        boolean autoLinkPortals = MapConfig.get().autoLinkNetherPortals;
        Map<Integer, List<Waypoint>> map = new HashMap<>();

        for (int i = 0; i < waypoints.size(); i++) {
            Waypoint wp = waypoints.get(i);
            if (!isWaypointVisible(wp)) continue;

            map.computeIfAbsent(wp.dimensionId, k -> new ArrayList<>()).add(wp);

            // Auto-project Overworld (0) <-> Nether (-1) at x8 / div8 scale when enabled
            if (!wp.temporary && (wp.dimensionId == 0 || wp.dimensionId == -1)) {
                boolean shouldLink = wp.linkNether
                    || (autoLinkPortals && wp.getEffectiveIcon() == Waypoint.IconType.PORTAL);
                if (shouldLink) {
                    int targetDim = (wp.dimensionId == 0) ? -1 : 0;
                    int px = (targetDim == -1) ? Math.floorDiv(wp.x, 8) : (wp.x * 8);
                    int pz = (targetDim == -1) ? Math.floorDiv(wp.z, 8) : (wp.z * 8);
                    String suffix = (targetDim == -1) ? " [\u00f78]" : " [\u00d78]";
                    Waypoint proj = new Waypoint(
                        wp.name + suffix,
                        wp.initials,
                        px, wp.y, pz,
                        targetDim,
                        wp.color,
                        Waypoint.Type.NORMAL
                    );
                    proj.id = "link:" + wp.id;
                    proj.temporary = true;
                    proj.projectedCrossDim = true;
                    proj.icon = wp.getEffectiveIcon();
                    proj.group = wp.getEffectiveGroup();
                    proj.linkNether = true;
                    map.computeIfAbsent(targetDim, k -> new ArrayList<>()).add(proj);
                }
            }
        }
        for (Map.Entry<Integer, List<Waypoint>> e : map.entrySet()) {
            e.setValue(Collections.unmodifiableList(e.getValue()));
        }
        dimCache = map;
    }

    public boolean isGroupEnabled(String group) {
        if (group == null || group.trim().isEmpty()) return !hiddenGroupsLower.contains("default");
        return !hiddenGroupsLower.contains(group.trim().toLowerCase(Locale.ROOT));
    }

    public synchronized void setGroupEnabled(String group, boolean enabled) {
        ensureLoaded();
        String key = (group == null || group.trim().isEmpty()) ? "default" : group.trim().toLowerCase(Locale.ROOT);
        if (enabled) {
            hiddenGroupsLower.remove(key);
        } else {
            hiddenGroupsLower.add(key);
        }
        saveGroups();
        rebuildDimCache();
    }

    public synchronized boolean toggleGroupEnabled(String group) {
        boolean next = !isGroupEnabled(group);
        setGroupEnabled(group, next);
        return next;
    }

    public boolean isWaypointVisible(Waypoint wp) {
        if (wp == null || !wp.enabled) return false;
        if (wp.type == Waypoint.Type.NAVIGATOR || wp.type == Waypoint.Type.SHARED_BEACON) {
            return true;
        }
        return isGroupEnabled(wp.getEffectiveGroup());
    }

    public synchronized List<String> getAllGroupNames() {
        ensureLoaded();
        LinkedHashSet<String> groups = new LinkedHashSet<>();
        for (String preset : Waypoint.PRESET_GROUPS) {
            groups.add(preset);
        }
        for (Waypoint wp : waypoints) {
            if (!wp.temporary) {
                groups.add(wp.getEffectiveGroup());
            }
        }
        return new ArrayList<>(groups);
    }

    public synchronized int countDeaths() {
        ensureLoaded();
        int count = 0;
        for (Waypoint wp : waypoints) {
            if (!wp.temporary && isDeathWaypoint(wp)) {
                count++;
            }
        }
        return count;
    }

    public synchronized int removeAllDeaths() {
        ensureLoaded();
        int removed = 0;
        Iterator<Waypoint> it = waypoints.iterator();
        while (it.hasNext()) {
            Waypoint wp = it.next();
            if (!wp.temporary && isDeathWaypoint(wp)) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            save();
        }
        return removed;
    }

    public static boolean isDeathWaypoint(Waypoint wp) {
        if (wp == null) return false;
        return wp.type == Waypoint.Type.DEATH
            || Waypoint.GROUP_DEATHS.equalsIgnoreCase(wp.getEffectiveGroup());
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
        hiddenGroupsLower.clear();
        dimCache = Collections.emptyMap();
        loadedWorldId = null;
        wasDeadLastTick = false;
    }

    private synchronized void loadForWorld(String worldId) {
        waypoints.clear();
        hiddenGroupsLower.clear();
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
                            if (wp.icon == null) {
                                wp.icon = (wp.type == Waypoint.Type.DEATH) ? Waypoint.IconType.DEATH : Waypoint.IconType.PIN;
                            }
                            wp.group = Waypoint.normalizeGroup(wp.group, wp.type);
                            wp.initials = Waypoint.normalizeInitials(wp.initials, wp.name);
                            waypoints.add(wp);
                        }
                    }
                }
            } catch (Exception e) {
                SaifunsMapMod.LOG.warn("Failed to load waypoints from {}: {}", file, e.getMessage());
            }
        }
        loadGroups(worldId);
        rebuildDimCache();
    }

    private void loadGroups(String worldId) {
        File gFile = getGroupsFile(worldId);
        if (!gFile.exists()) return;
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(gFile), StandardCharsets.UTF_8)) {
            Set<String> loaded = GSON.fromJson(reader, STRING_SET_TYPE);
            if (loaded != null) {
                for (String g : loaded) {
                    if (g != null && !g.trim().isEmpty()) {
                        hiddenGroupsLower.add(g.trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
        } catch (Exception e) {
            SaifunsMapMod.LOG.warn("Failed to load hidden waypoint groups from {}: {}", gFile, e.getMessage());
        }
    }

    private void saveGroups() {
        if (loadedWorldId == null) return;
        File gFile = getGroupsFile(loadedWorldId);
        try {
            File parent = gFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(gFile), StandardCharsets.UTF_8)) {
                GSON.toJson(hiddenGroupsLower, STRING_SET_TYPE, writer);
            }
        } catch (Exception e) {
            SaifunsMapMod.LOG.warn("Failed to save hidden waypoint groups to {}: {}", gFile, e.getMessage());
        }
    }

    private File getGroupsFile(String worldId) {
        return new File(new File(worldCache.getDataRoot(), "worlds/" + worldId), "waypoint_groups.json");
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
     * Returns real (non-projected) waypoints for management UI (includes disabled & hidden-group waypoints).
     */
    public synchronized List<Waypoint> getEditableWaypoints(boolean allDimensions, int currentDim) {
        ensureLoaded();
        List<Waypoint> out = new ArrayList<>();
        for (Waypoint wp : waypoints) {
            if (wp.projectedCrossDim) continue;
            if (!allDimensions && wp.dimensionId != currentDim) continue;
            out.add(wp);
        }
        return out;
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
        setNavigatorWaypoint(tagKey, label, x, y, z, dimId, color, null);
    }

    public synchronized void setNavigatorWaypoint(
        String tagKey,
        String label,
        int x, int y, int z,
        int dimId,
        int color,
        Waypoint.CustomIconDrawer customIconDrawer
    ) {
        ensureLoaded();
        clearNavigatorWaypoints(tagKey);
        String cleanLabel = (label != null ? label.replaceAll("\u00a7[0-9a-fk-or]", "").trim() : "");
        if (cleanLabel.isEmpty()) cleanLabel = "Target";
        int safeColor = (color & 0xFFFFFF) != 0 ? (color & 0xFFFFFF) : 0xFACC15;
        Waypoint wp = new Waypoint(cleanLabel, null, x, y, z, dimId, safeColor, Waypoint.Type.NAVIGATOR);
        wp.id = "nav:" + tagKey;
        wp.temporary = true;
        wp.icon = Waypoint.IconType.STAR;
        wp.customIconDrawer = customIconDrawer;
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
            String timeStr = new SimpleDateFormat("HH:mm:ss").format(new Date());
            String deathName = Lang.tr("saifunsmap.waypoint.death_name", timeStr);
            Waypoint death = new Waypoint(deathName, "X", dx, dy, dz, dim, 0xEF4444, Waypoint.Type.DEATH);
            death.icon = Waypoint.IconType.DEATH;
            death.group = Waypoint.GROUP_DEATHS;
            addWaypoint(death);
            player.addChatMessage(new ChatComponentText(
                "\u00a7b[Saifun's Map]\u00a7r " + Lang.tr("saifunsmap.chat.death_saved", dx, dy, dz, dim)
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
     * Builds a single crisp clickable chat card with [Save], [Edit], and [Beacon] actions for a shared waypoint.
     */
    public static IChatComponent buildClickableShareComponent(String sender, Waypoint wp, String extraInfo) {
        String payload = encodeSharePayload(wp);

        ChatComponentText line = new ChatComponentText("");
        ChatComponentText tag = new ChatComponentText("[Saifun's Map] ");
        tag.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.AQUA));
        line.appendSibling(tag);

        if (sender != null && !sender.trim().isEmpty()) {
            ChatComponentText who = new ChatComponentText("<" + sender.trim() + "> ");
            who.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.GREEN));
            line.appendSibling(who);
        }

        ChatComponentText nameComp = new ChatComponentText(wp.name);
        nameComp.setChatStyle(new ChatStyle().setBold(true).setColor(EnumChatFormatting.WHITE));
        line.appendSibling(nameComp);

        String coordsStr = String.format(" (%d, %d, %d) [%s] ", wp.x, wp.y, wp.z, Lang.tr("saifunsmap.common.dim", wp.dimensionId));
        ChatComponentText coordsComp = new ChatComponentText(coordsStr);
        coordsComp.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.GRAY));
        line.appendSibling(coordsComp);

        line.appendSibling(makeChatButton(
            Lang.tr("saifunsmap.chat.btn.save"),
            EnumChatFormatting.GREEN,
            Lang.tr("saifunsmap.chat.btn.save_hover"),
            "/smap save " + payload
        ));
        line.appendText(" ");
        line.appendSibling(makeChatButton(
            Lang.tr("saifunsmap.chat.btn.edit"),
            EnumChatFormatting.AQUA,
            Lang.tr("saifunsmap.chat.btn.edit_hover"),
            "/smap edit " + payload
        ));
        line.appendText(" ");
        line.appendSibling(makeChatButton(
            Lang.tr("saifunsmap.chat.btn.beacon"),
            EnumChatFormatting.GOLD,
            Lang.tr("saifunsmap.chat.btn.beacon_hover"),
            "/smap beacon " + payload
        ));

        if (extraInfo != null && !extraInfo.trim().isEmpty()) {
            ChatComponentText extraComp = new ChatComponentText(" [" + extraInfo.trim() + "]");
            extraComp.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.GRAY));
            line.appendSibling(extraComp);
        }
        return line;
    }

    /**
     * Posts a clickable chat card with [Save], [Edit], and [Beacon] actions for a shared waypoint.
     */
    public static void postClickableShareChat(String sender, Waypoint wp, String extraInfo) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityClientPlayerMP player = mc.thePlayer;
        if (player == null || wp == null) return;
        player.addChatMessage(buildClickableShareComponent(sender, wp, extraInfo));
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
     * Detects JourneyMap shared waypoints in incoming chat and returns a parsed Waypoint if found.
     */
    public static Waypoint tryParseChatWaypoint(String message, int currentDim) {
        if (message == null || message.isEmpty()) return null;
        String clean = message.replaceAll("\u00a7[0-9a-fk-or]", "");

        Matcher jm = JM_CHAT_PATTERN.matcher(clean);
        if (jm.find()) {
            String rawName = jm.group(1) != null ? jm.group(1) : jm.group(2);
            String name = rawName != null ? rawName.trim() : "Waypoint";
            int x = Integer.parseInt(jm.group(3));
            int y = Integer.parseInt(jm.group(4));
            int z = Integer.parseInt(jm.group(5));
            int dim = jm.group(6) != null ? Integer.parseInt(jm.group(6)) : currentDim;
            return new Waypoint(name, null, x, y, z, dim, pickRandomColor(), Waypoint.Type.NORMAL);
        }
        return null;
    }

    public static int pickRandomColor() {
        return PALETTE[RANDOM.nextInt(PALETTE.length)];
    }
}

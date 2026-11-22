package com.saifun.map.waypoints;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.saifun.map.SaifunsMapMod;
import net.minecraft.client.Minecraft;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * One-click importer for existing JourneyMap (JSON) and legacy Xaero's Minimap (txt) waypoints.
 */
public final class WaypointImporter {

    private static final int[] XAERO_COLORS = {
        0x000000, 0x0000AA, 0x00AA00, 0x00AAAA,
        0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
        0x555555, 0x5555FF, 0x55FF55, 0x55FFFF,
        0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
    };

    private WaypointImporter() {}

    public static int importFromJourneyMap(WaypointManager manager) {
        File jmData = new File(Minecraft.getMinecraft().mcDataDir, "journeymap/data");
        if (!jmData.exists() || !jmData.isDirectory()) return 0;

        List<File> jsonFiles = new ArrayList<>();
        collectFilesNamed(jmData, ".json", "waypoints", jsonFiles);

        int imported = 0;
        JsonParser parser = new JsonParser();
        for (File f : jsonFiles) {
            try (InputStreamReader reader = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
                JsonElement el = parser.parse(reader);
                if (!el.isJsonObject()) continue;
                JsonObject obj = el.getAsJsonObject();
                if (!obj.has("name") || !obj.has("x") || !obj.has("z")) continue;

                String name = obj.get("name").getAsString();
                int x = obj.get("x").getAsInt();
                int y = obj.has("y") ? obj.get("y").getAsInt() : 64;
                int z = obj.get("z").getAsInt();
                int r = obj.has("r") ? obj.get("r").getAsInt() : 59;
                int g = obj.has("g") ? obj.get("g").getAsInt() : 130;
                int b = obj.has("b") ? obj.get("b").getAsInt() : 246;
                int rgb = ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
                boolean enabled = !obj.has("enable") || obj.get("enable").getAsBoolean();
                Waypoint.Type type = (obj.has("type") && "Death".equalsIgnoreCase(obj.get("type").getAsString()))
                    ? Waypoint.Type.DEATH
                    : Waypoint.Type.NORMAL;

                List<Integer> dims = new ArrayList<>();
                if (obj.has("dimensions") && obj.get("dimensions").isJsonArray()) {
                    JsonArray arr = obj.getAsJsonArray("dimensions");
                    for (JsonElement d : arr) {
                        dims.add(d.getAsInt());
                    }
                }
                if (dims.isEmpty()) dims.add(0);

                for (int dim : dims) {
                    Waypoint wp = new Waypoint(name, null, x, y, z, dim, rgb, type);
                    wp.enabled = enabled;
                    if (manager.addIfNotDuplicate(wp)) {
                        imported++;
                    }
                }
            } catch (Exception e) {
                SaifunsMapMod.LOG.debug("Skipped JourneyMap file {}: {}", f.getName(), e.getMessage());
            }
        }
        if (imported > 0) manager.save();
        return imported;
    }

    public static int importFromXaero(WaypointManager manager) {
        File xaeroRoot = new File(Minecraft.getMinecraft().mcDataDir, "XaeroWaypoints");
        if (!xaeroRoot.exists() || !xaeroRoot.isDirectory()) return 0;

        List<File> txtFiles = new ArrayList<>();
        collectFilesNamed(xaeroRoot, ".txt", null, txtFiles);

        int imported = 0;
        for (File f : txtFiles) {
            int dim = parseXaeroDimension(f.getParentFile());
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.startsWith("waypoint:")) continue;
                    String[] parts = line.split(":");
                    if (parts.length < 7) continue;
                    String name = parts[1];
                    String initials = parts[2];
                    int x = Integer.parseInt(parts[3]);
                    int y = "~".equals(parts[4]) ? 70 : Integer.parseInt(parts[4]);
                    int z = Integer.parseInt(parts[5]);
                    int colorIdx = Math.max(0, Math.min(XAERO_COLORS.length - 1, Integer.parseInt(parts[6])));
                    boolean disabled = parts.length > 7 && Boolean.parseBoolean(parts[7]);

                    Waypoint wp = new Waypoint(name, initials, x, y, z, dim, XAERO_COLORS[colorIdx], Waypoint.Type.NORMAL);
                    wp.enabled = !disabled;
                    if (manager.addIfNotDuplicate(wp)) {
                        imported++;
                    }
                }
            } catch (Exception e) {
                SaifunsMapMod.LOG.debug("Skipped Xaero waypoint file {}: {}", f.getName(), e.getMessage());
            }
        }
        if (imported > 0) manager.save();
        return imported;
    }

    private static int parseXaeroDimension(File dimFolder) {
        if (dimFolder == null) return 0;
        String name = dimFolder.getName();
        if (name.startsWith("dim%")) {
            try {
                return Integer.parseInt(name.substring(4));
            } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    private static void collectFilesNamed(File dir, String ext, String requiredParent, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File c : children) {
            if (c.isDirectory()) {
                collectFilesNamed(c, ext, requiredParent, out);
            } else if (c.getName().endsWith(ext)) {
                if (requiredParent == null || (c.getParentFile() != null && requiredParent.equalsIgnoreCase(c.getParentFile().getName()))) {
                    out.add(c);
                }
            }
        }
    }
}

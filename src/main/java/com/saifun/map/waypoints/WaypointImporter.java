package com.saifun.map.waypoints;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.saifun.map.SaifunsMapMod;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * One-click importer for existing JourneyMap (JSON) waypoints.
 */
public final class WaypointImporter {

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
                    int wx = (dim == -1) ? (x / 8) : x;
                    int wz = (dim == -1) ? (z / 8) : z;
                    Waypoint wp = new Waypoint(name, null, wx, y, wz, dim, rgb, type);
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

package com.saifun.map.waypoints;

import java.util.UUID;

/**
 * Waypoint data model persisted to JSON per world.
 */
public class Waypoint {

    public enum Type {
        NORMAL,
        DEATH,
        NAVIGATOR,
        SHARED_BEACON
    }

    public String id;
    public String name;
    public String initials;
    public int x;
    public int y;
    public int z;
    public int dimensionId;
    public int color; // 0xRRGGBB
    public boolean enabled = true;
    public boolean temporary = false;
    public Type type = Type.NORMAL;
    public long createdAt;
    public transient long expiresAt = 0L;

    public Waypoint() {}

    public Waypoint(String name, String initials, int x, int y, int z, int dimensionId, int color, Type type) {
        this.id = UUID.randomUUID().toString();
        this.name = name != null && !name.trim().isEmpty() ? name.trim() : "Waypoint";
        this.initials = normalizeInitials(initials, this.name);
        this.x = x;
        this.y = y;
        this.z = z;
        this.dimensionId = dimensionId;
        this.color = color & 0xFFFFFF;
        this.type = type != null ? type : Type.NORMAL;
        this.enabled = true;
        this.createdAt = System.currentTimeMillis();
    }

    public static String normalizeInitials(String initials, String fallbackName) {
        if (initials != null && !initials.trim().isEmpty()) {
            String t = initials.trim();
            return t.length() <= 2 ? t.toUpperCase() : t.substring(0, 2).toUpperCase();
        }
        if (fallbackName == null || fallbackName.trim().isEmpty()) return "W";
        // Strip leading punctuation (e.g. ",base" -> "BA") when deriving automatic initials
        String cleaned = fallbackName.trim().replaceAll("^[^\\p{L}\\p{N}]+", "");
        if (cleaned.isEmpty()) cleaned = fallbackName.trim();
        return cleaned.length() <= 2 ? cleaned.toUpperCase() : cleaned.substring(0, 2).toUpperCase();
    }

    public int getArgb(int alpha) {
        return ((alpha & 0xFF) << 24) | (color & 0xFFFFFF);
    }

    public String formatChatShare() {
        return String.format(
            "[name:\"%s\", x:%d, y:%d, z:%d, dim:%d]",
            name.replace("\"", "'"),
            x, y, z, dimensionId
        );
    }
}

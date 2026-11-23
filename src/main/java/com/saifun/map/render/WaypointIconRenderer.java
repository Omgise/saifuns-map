package com.saifun.map.render;

import com.saifun.map.waypoints.Waypoint;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Crisp pixel-art waypoint icon renderer backed by MIT-licensed Minecraft-style PNG textures
 * (assets/saifunsmap/textures/waypoints/*.png, GL_NEAREST).
 * - Monochrome white-with-black-outline icons (Pin, Home, Star, Death Skull) dynamically tint
 *   to the waypoint's custom color while preserving their crisp black 1px pixel outline.
 * - Full-color Minecraft-style POI icons (Pickaxe, Sword, Portal, Village, Gold, Food, Armor, Diamond)
 *   render in their authentic palette without color distortion.
 * - Tracked GTNH Navigator waypoints (Ore Veins, Thaumcraft Nodes, etc.) delegate to their
 *   exact fullscreen overlay icon via Waypoint.customIconDrawer.
 */
public final class WaypointIconRenderer {

    private static final Map<Waypoint.IconType, ResourceLocation> TEXTURES = new EnumMap<>(Waypoint.IconType.class);
    private static final Set<Waypoint.IconType> FILTER_INITIALIZED = EnumSet.noneOf(Waypoint.IconType.class);

    public static void clearCache() {
        FILTER_INITIALIZED.clear();
    }

    static {
        TEXTURES.put(Waypoint.IconType.PIN, new ResourceLocation("saifunsmap", "textures/waypoints/pin.png"));
        TEXTURES.put(Waypoint.IconType.HOME, new ResourceLocation("saifunsmap", "textures/waypoints/home.png"));
        TEXTURES.put(Waypoint.IconType.MINE, new ResourceLocation("saifunsmap", "textures/waypoints/mine.png"));
        TEXTURES.put(Waypoint.IconType.DUNGEON, new ResourceLocation("saifunsmap", "textures/waypoints/dungeon.png"));
        TEXTURES.put(Waypoint.IconType.PORTAL, new ResourceLocation("saifunsmap", "textures/waypoints/portal.png"));
        TEXTURES.put(Waypoint.IconType.VILLAGE, new ResourceLocation("saifunsmap", "textures/waypoints/village.png"));
        TEXTURES.put(Waypoint.IconType.STAR, new ResourceLocation("saifunsmap", "textures/waypoints/star.png"));
        TEXTURES.put(Waypoint.IconType.CHEST, new ResourceLocation("saifunsmap", "textures/waypoints/chest.png"));
        TEXTURES.put(Waypoint.IconType.FARM, new ResourceLocation("saifunsmap", "textures/waypoints/farm.png"));
        TEXTURES.put(Waypoint.IconType.BOSS, new ResourceLocation("saifunsmap", "textures/waypoints/boss.png"));
        TEXTURES.put(Waypoint.IconType.CRYSTAL, new ResourceLocation("saifunsmap", "textures/waypoints/crystal.png"));
        TEXTURES.put(Waypoint.IconType.DEATH, new ResourceLocation("saifunsmap", "textures/waypoints/death.png"));
    }

    private WaypointIconRenderer() {}

    public static boolean isTintable(Waypoint.IconType type) {
        if (type == null) return true;
        return type == Waypoint.IconType.PIN
            || type == Waypoint.IconType.HOME
            || type == Waypoint.IconType.STAR
            || type == Waypoint.IconType.DEATH;
    }

    /**
     * Draws a waypoint's icon centered at (cx, cy) with visual size {@code size}.
     * Delegates to Waypoint.customIconDrawer first for tracked GTNH Navigator targets.
     */
    public static void drawWaypointIcon(
        Waypoint wp,
        double cx,
        double cy,
        double size,
        float alpha,
        int overrideColor
    ) {
        if (wp == null) return;
        if (wp.customIconDrawer != null) {
            try {
                if (wp.customIconDrawer.draw(cx, cy, size, alpha)) {
                    return;
                }
            } catch (Throwable ignored) {}
        }
        drawIcon(wp.getEffectiveIcon(), cx, cy, size, overrideColor, alpha);
    }

    /**
     * Draws one of the 12 built-in pixel-art PNG icons centered at (cx, cy).
     */
    public static void drawIcon(
        Waypoint.IconType type,
        double cx,
        double cy,
        double size,
        int rgbColor,
        float alpha
    ) {
        if (type == null) type = Waypoint.IconType.PIN;
        ResourceLocation tex = TEXTURES.get(type);
        if (tex == null) tex = TEXTURES.get(Waypoint.IconType.PIN);

        double half = size * 0.5;
        double x1 = cx - half;
        double y1 = cy - half;
        double x2 = cx + half;
        double y2 = cy + half;

        GLDraw.ensureBlend();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        Minecraft.getMinecraft().getTextureManager().bindTexture(tex);
        if (FILTER_INITIALIZED.add(type)) {
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        }

        if (isTintable(type)) {
            float r = ((rgbColor >>> 16) & 0xFF) / 255.0f;
            float g = ((rgbColor >>> 8) & 0xFF) / 255.0f;
            float b = (rgbColor & 0xFF) / 255.0f;
            GL11.glColor4f(r, g, b, alpha);
        } else {
            GL11.glColor4f(1.0f, 1.0f, 1.0f, alpha);
        }

        GLDraw.texturedQuad(x1, y1, x2, y2, 0.0, 0.0, 1.0, 1.0);
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
    }
}

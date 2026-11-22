package com.saifun.map.waypoints;

import com.saifun.map.config.MapConfig;
import com.saifun.map.engine.BlockColorResolver;
import com.saifun.map.render.GLDraw;
import com.saifun.map.render.GLStateGuard;
import com.saifun.map.render.WaypointIconRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.util.Vec3;
import org.lwjgl.opengl.GL11;

import java.util.List;

/**
 * Modern minimalist 3D in-world waypoint renderer.
 * - Constant, crisp ~13px screen size at any distance (never blows up across the screen).
 * - Smart crosshair focus: shows full name & distance smoothly when aiming near the waypoint
 *   or when close (< 24m); keeps distant/off-axis waypoints as clean minimal badges.
 */
public class WaypointWorldRenderer {

    private final WaypointManager waypointManager;

    public WaypointWorldRenderer(WaypointManager waypointManager) {
        this.waypointManager = waypointManager;
    }

    public void renderInWorld(float partialTicks) {
        MapConfig cfg = MapConfig.get();
        if (!cfg.showWaypointsInWorld) return;

        Minecraft mc = Minecraft.getMinecraft();
        EntityClientPlayerMP player = mc.thePlayer;
        if (player == null || mc.theWorld == null || mc.gameSettings.hideGUI) return;

        List<Waypoint> waypoints = waypointManager.getWaypointsForDimension(mc.theWorld.provider.dimensionId);
        if (waypoints.isEmpty()) return;

        double camX = RenderManager.renderPosX;
        double camY = RenderManager.renderPosY;
        double camZ = RenderManager.renderPosZ;

        Vec3 look = player.getLook(partialTicks);
        FontRenderer fr = mc.fontRenderer;
        int maxDist = cfg.maxWaypointRenderDistance;
        double maxDistSq = (double) maxDist * (double) maxDist;

        boolean hasPriorityTarget = false;
        for (int i = 0; i < waypoints.size(); i++) {
            Waypoint wp = waypoints.get(i);
            if (wp.enabled && isPriorityTarget(wp)) {
                hasPriorityTarget = true;
                break;
            }
        }

        try (GLStateGuard ignored = new GLStateGuard()) {
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GLDraw.ensureBlend();

            // Pass 0: regular waypoints (dimmed & darkened when a target vein/beacon is active!)
            // Pass 1: priority targets (Ore Veins, Thaumcraft Nodes, Shared Beacons) crisp on top!
            int passes = hasPriorityTarget ? 2 : 1;
            for (int pass = 0; pass < passes; pass++) {
                boolean renderPriorityPass = (pass == 1);
                for (int i = 0; i < waypoints.size(); i++) {
                    Waypoint wp = waypoints.get(i);
                    if (!wp.enabled) continue;
                    boolean priority = isPriorityTarget(wp);
                    if (hasPriorityTarget && priority != renderPriorityPass) continue;

                    double wx = wp.x + 0.5;
                    double wy = wp.y + 1.5;
                    double wz = wp.z + 0.5;

                    double dx = wx - camX;
                    double dy = wy - camY;
                    double dz = wz - camZ;
                    double distSq = dx * dx + dy * dy + dz * dz;
                    if (!priority && distSq > maxDistSq) continue;
                    if (distSq < 2.25) continue;
                    double dist = Math.sqrt(distSq);

                    // Dot product with camera view ray to check if player is looking toward this waypoint
                    double invDist = 1.0 / dist;
                    double dot = (dx * invDist) * look.xCoord + (dy * invDist) * look.yCoord + (dz * invDist) * look.zCoord;
                    if (dot < 0.05) continue; // Behind camera

                    boolean focused = dot > 0.985; // Aimed within ~10 degrees of crosshair
                    boolean dimmedByTarget = hasPriorityTarget && !priority;
                    boolean showFullLabel = priority
                        || (dimmedByTarget ? focused : (cfg.alwaysShowWaypointNames || focused || dist < 24.0));

                    if ((priority && dist > 3.0) || (cfg.showWaypointBeam && dist > 6.0 && !dimmedByTarget && (focused || dist < 200.0))) {
                        renderBeam(wx - camX, -camY, wz - camZ, wp.color, priority);
                    }

                    // Project onto fixed depth sphere (48 blocks) for crisp, zero-jitter constant screen pixel size
                    double renderDist = Math.min(dist, 48.0);
                    double proj = renderDist * invDist;
                    double rx = dx * proj;
                    double ry = dy * proj;
                    double rz = dz * proj;

                    // Distant off-axis waypoints gently shrink and soften; dimmed background waypoints step back cleanly
                    float distFactor = priority
                        ? (focused ? 1.16f : 1.08f)
                        : (focused ? 1.08f : (dist > 1200.0 ? 0.72f : (dist > 300.0 ? 0.86f : 1.0f)));
                    float alpha = priority
                        ? 1.0f
                        : (focused ? 1.0f : (dist > 2000.0 ? 0.62f : (dist > 500.0 ? 0.80f : 0.95f)));

                    int badgeColor = wp.color;
                    if (dimmedByTarget) {
                        if (focused) {
                            alpha = 0.86f;
                        } else {
                            alpha *= 0.36f;
                            distFactor *= 0.86f;
                            badgeColor = BlockColorResolver.blendColor(wp.color, 0x141923, 0.52f);
                        }
                    }

                    // ~0.0021 * renderDist yields a crisp ~13-15px badge at 1080p/1440p regardless of distance!
                    float pixelScale = (float) (0.0021 * renderDist * cfg.waypointScale * distFactor);

                    GL11.glPushMatrix();
                    GL11.glTranslated(rx, ry, rz);
                    GL11.glRotatef(-RenderManager.instance.playerViewY, 0.0f, 1.0f, 0.0f);
                    GL11.glRotatef(RenderManager.instance.playerViewX, 1.0f, 0.0f, 0.0f);
                    GL11.glScalef(-pixelScale, -pixelScale, pixelScale);

                    GL11.glDisable(GL11.GL_DEPTH_TEST);
                    GL11.glDepthMask(false);

                    // Crisp pixel-art icon OR native GTNH Navigator icon (Ore Vein, Thaumcraft Node, etc.)
                    double hs = (priority || focused) ? 6.6 : 5.4;
                    if (wp.customIconDrawer != null) {
                        int accent = (wp.type == Waypoint.Type.NAVIGATOR) ? 0xFFFACC15 : 0xFF38BDF8;
                        GLDraw.rect(-hs - 2.0, -hs - 2.0, hs + 2.0, hs + 2.0, GLDraw.withAlpha(0x070A0F, 0.94f * alpha));
                        GLDraw.rect(-hs - 1.1, -hs - 1.1, hs + 1.1, hs + 1.1, GLDraw.withAlpha(accent, alpha));
                        GLDraw.rect(-hs, -hs, hs, hs, GLDraw.withAlpha(0x0E131D, 0.92f * alpha));
                        WaypointIconRenderer.drawWaypointIcon(wp, 0.0, 0.0, hs * 2.0, alpha, badgeColor);
                    } else if (priority) {
                        int accent = (wp.type == Waypoint.Type.NAVIGATOR) ? 0xFFFACC15 : 0xFF38BDF8;
                        GLDraw.rect(-hs - 1.5, -hs - 1.5, hs + 1.5, hs + 1.5, GLDraw.withAlpha(0x070A0F, 0.80f * alpha));
                        GLDraw.rectOutline(-hs - 0.9, -hs - 0.9, hs + 0.9, hs + 0.9, 1.1, GLDraw.withAlpha(accent, alpha));
                        WaypointIconRenderer.drawWaypointIcon(wp, 0.0, 0.0, hs * 2.0, alpha, badgeColor);
                    } else {
                        WaypointIconRenderer.drawWaypointIcon(wp, 0.0, 0.0, hs * 2.0, alpha, badgeColor);
                    }

                    if (showFullLabel) {
                        String distStr = formatDistance(dist);
                        String label = wp.name + " \u00a77(" + distStr + ")";
                        float textScale = priority ? 0.82f : 0.76f;
                        int lw = fr.getStringWidth(label);
                        double bw = (lw * textScale) * 0.5 + 3.2;
                        double ty = hs + 2.8;
                        GLDraw.rect(-bw, ty - 1.0, bw, ty + fr.FONT_HEIGHT * textScale + 1.2, GLDraw.withAlpha(0x0A0D12, alpha * 0.82f));
                        if (priority) {
                            int accent = (wp.type == Waypoint.Type.NAVIGATOR) ? 0xCCFACC15 : 0xCC38BDF8;
                            GLDraw.rect(-bw, ty - 1.0, bw, ty - 0.1, accent);
                        }
                        GLDraw.drawCenteredText(fr, label, 0.0, ty, textScale, GLDraw.withAlpha(0xF8FAFC, alpha));
                    } else if (!dimmedByTarget && dist < 250.0) {
                        // Subtle compact distance tag under nearby unfocused waypoints
                        String distStr = formatDistance(dist);
                        GLDraw.drawCenteredText(fr, distStr, 0.0, hs + 2.0, 0.62f, GLDraw.withAlpha(0xCBD5E1, alpha * 0.85f));
                    }

                    GL11.glPopMatrix();
                }
            }
        }
    }

    private static boolean isPriorityTarget(Waypoint wp) {
        return wp.type == Waypoint.Type.NAVIGATOR || wp.type == Waypoint.Type.SHARED_BEACON;
    }

    private static String formatDistance(double dist) {
        if (dist >= 1000.0) {
            int tenths = (int) Math.round(dist / 100.0);
            return (tenths / 10) + "." + (tenths % 10) + "km";
        }
        return ((int) Math.round(dist)) + "m";
    }

    private static void renderBeam(double rx, double baseY, double rz, int color, boolean priority) {
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GLDraw.ensureBlend();
        GL11.glDisable(GL11.GL_TEXTURE_2D);

        float r = ((color >>> 16) & 0xFF) / 255.0f;
        float g = ((color >>> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;

        if (priority) {
            // Sleek two-layer tactical beacon column: soft colored outer glow + crisp bright inner core
            drawCrossBeam(rx, baseY, rz, 0.36, r, g, b, 0.24f);
            drawCrossBeam(rx, baseY, rz, 0.14, Math.min(1.0f, r * 0.65f + 0.35f), Math.min(1.0f, g * 0.65f + 0.35f), Math.min(1.0f, b * 0.65f + 0.35f), 0.56f);
        } else {
            drawCrossBeam(rx, baseY, rz, 0.14, r, g, b, 0.22f);
        }

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private static void drawCrossBeam(double rx, double baseY, double rz, double hw, float r, float g, float b, float alpha) {
        GL11.glColor4f(r, g, b, alpha);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex3d(rx - hw, baseY, rz);
        GL11.glVertex3d(rx + hw, baseY, rz);
        GL11.glVertex3d(rx + hw, baseY + 256.0, rz);
        GL11.glVertex3d(rx - hw, baseY + 256.0, rz);

        GL11.glVertex3d(rx, baseY, rz - hw);
        GL11.glVertex3d(rx, baseY, rz + hw);
        GL11.glVertex3d(rx, baseY + 256.0, rz + hw);
        GL11.glVertex3d(rx, baseY + 256.0, rz - hw);
        GL11.glEnd();
    }
}

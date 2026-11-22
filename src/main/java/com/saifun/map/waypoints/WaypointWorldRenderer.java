package com.saifun.map.waypoints;

import com.saifun.map.config.MapConfig;
import com.saifun.map.render.GLDraw;
import com.saifun.map.render.GLStateGuard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.util.Vec3;
import org.lwjgl.opengl.GL11;

import java.util.List;

/**
 * Modern Xaero-style 3D in-world waypoint renderer.
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

        try (GLStateGuard ignored = new GLStateGuard()) {
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GLDraw.ensureBlend();

            for (int i = 0; i < waypoints.size(); i++) {
                Waypoint wp = waypoints.get(i);
                if (!wp.enabled) continue;

                double wx = wp.x + 0.5;
                double wy = wp.y + 1.5;
                double wz = wp.z + 0.5;

                double dx = wx - camX;
                double dy = wy - camY;
                double dz = wz - camZ;
                double distSq = dx * dx + dy * dy + dz * dz;
                if (distSq > maxDistSq || distSq < 2.25) continue;
                double dist = Math.sqrt(distSq);

                // Dot product with camera view ray to check if player is looking toward this waypoint
                double invDist = 1.0 / dist;
                double dot = (dx * invDist) * look.xCoord + (dy * invDist) * look.yCoord + (dz * invDist) * look.zCoord;
                if (dot < 0.05) continue; // Behind camera

                boolean focused = dot > 0.985; // Aimed within ~10 degrees of crosshair
                boolean showFullLabel = cfg.alwaysShowWaypointNames || focused || dist < 24.0;

                if (cfg.showWaypointBeam && dist > 6.0 && (focused || dist < 200.0)) {
                    renderBeam(wx - camX, -camY, wz - camZ, wp.color);
                }

                // Project onto fixed depth sphere (48 blocks) for crisp, zero-jitter constant screen pixel size
                double renderDist = Math.min(dist, 48.0);
                double proj = renderDist * invDist;
                double rx = dx * proj;
                double ry = dy * proj;
                double rz = dz * proj;

                // Distant off-axis waypoints gently shrink and soften so dozens of waypoints never clutter the view
                float distFactor = focused ? 1.08f : (dist > 1200.0 ? 0.72f : (dist > 300.0 ? 0.86f : 1.0f));
                float alpha = focused ? 1.0f : (dist > 2000.0 ? 0.62f : (dist > 500.0 ? 0.80f : 0.95f));

                // ~0.0021 * renderDist yields a crisp ~13-15px badge at 1080p/1440p regardless of distance!
                float pixelScale = (float) (0.0021 * renderDist * cfg.waypointScale * distFactor);

                GL11.glPushMatrix();
                GL11.glTranslated(rx, ry, rz);
                GL11.glRotatef(-RenderManager.instance.playerViewY, 0.0f, 1.0f, 0.0f);
                GL11.glRotatef(RenderManager.instance.playerViewX, 1.0f, 0.0f, 0.0f);
                GL11.glScalef(-pixelScale, -pixelScale, pixelScale);

                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glDepthMask(false);

                // Sleek compact Xaero-style badge
                double hs = focused ? 7.2 : 6.0;
                GLDraw.rect(-hs - 1.0, -hs - 1.0, hs + 1.0, hs + 1.0, GLDraw.withAlpha(0x0B0E14, alpha * 0.92f));
                GLDraw.rect(-hs, -hs, hs, hs, GLDraw.withAlpha(wp.color, alpha * 0.92f));

                String init = wp.initials != null ? wp.initials : "W";
                GLDraw.drawCenteredText(fr, init, 0.2, -3.5, 0.85f, GLDraw.withAlpha(0xFFFFFF, alpha));

                if (showFullLabel) {
                    String distStr = formatDistance(dist);
                    String label = wp.name + " \u00a77(" + distStr + ")";
                    float textScale = 0.76f;
                    int lw = fr.getStringWidth(label);
                    double bw = (lw * textScale) * 0.5 + 3.0;
                    double ty = hs + 2.6;
                    GLDraw.rect(-bw, ty - 1.0, bw, ty + fr.FONT_HEIGHT * textScale + 1.2, GLDraw.withAlpha(0x0A0D12, alpha * 0.76f));
                    GLDraw.drawCenteredText(fr, label, 0.0, ty, textScale, GLDraw.withAlpha(0xF8FAFC, alpha));
                } else if (dist < 250.0) {
                    // Subtle compact distance tag under nearby unfocused waypoints
                    String distStr = formatDistance(dist);
                    GLDraw.drawCenteredText(fr, distStr, 0.0, hs + 2.0, 0.62f, GLDraw.withAlpha(0xCBD5E1, alpha * 0.85f));
                }

                GL11.glPopMatrix();
            }
        }
    }

    private static String formatDistance(double dist) {
        if (dist >= 1000.0) {
            int tenths = (int) Math.round(dist / 100.0);
            return (tenths / 10) + "." + (tenths % 10) + "km";
        }
        return ((int) Math.round(dist)) + "m";
    }

    private static void renderBeam(double rx, double baseY, double rz, int color) {
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GLDraw.ensureBlend();
        GL11.glDisable(GL11.GL_TEXTURE_2D);

        float r = ((color >>> 16) & 0xFF) / 255.0f;
        float g = ((color >>> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;
        double hw = 0.14;

        GL11.glColor4f(r, g, b, 0.22f);
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
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
    }
}

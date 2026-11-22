package com.saifun.map.waypoints;

import com.saifun.map.config.MapConfig;
import com.saifun.map.render.GLStateGuard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import org.lwjgl.opengl.GL11;

import java.util.List;

/**
 * Modern Xaero-style 3D in-world waypoint renderer.
 * Renders crisp auto-scaled billboard badges with initials, name, distance, and optional beacon beam.
 * 100% Angelica-safe via GLStateGuard.
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

        FontRenderer fr = mc.fontRenderer;
        int maxDist = cfg.maxWaypointRenderDistance;

        try (GLStateGuard ignored = new GLStateGuard()) {
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

            for (int i = 0; i < waypoints.size(); i++) {
                Waypoint wp = waypoints.get(i);
                if (!wp.enabled) continue;

                double wx = wp.x + 0.5;
                double wy = wp.y + 1.5;
                double wz = wp.z + 0.5;

                double dx = wx - camX;
                double dy = wy - camY;
                double dz = wz - camZ;
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (dist > maxDist) continue;

                // Optional subtle vertical beacon beam
                if (cfg.showWaypointBeam && dist > 4.0) {
                    renderBeam(wx - camX, -camY, wz - camZ, wp.color);
                }

                // Clamp rendering projection distance so distant waypoints stay within clip plane
                double renderDist = Math.min(dist, 64.0);
                double scaleFactor = renderDist / Math.max(dist, 0.001);
                double rx = dx * scaleFactor;
                double ry = dy * scaleFactor;
                double rz = dz * scaleFactor;

                float baseScale = (float) (0.022 * renderDist * cfg.waypointScale);
                baseScale = Math.max(0.03f, Math.min(1.6f, baseScale));

                GL11.glPushMatrix();
                GL11.glTranslated(rx, ry, rz);
                GL11.glRotatef(-RenderManager.instance.playerViewY, 0.0f, 1.0f, 0.0f);
                GL11.glRotatef(RenderManager.instance.playerViewX, 1.0f, 0.0f, 0.0f);
                GL11.glScalef(-baseScale, -baseScale, baseScale);

                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glDepthMask(false);

                // Sleek Xaero-style colored square badge with initials
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                int r = (wp.color >>> 16) & 0xFF;
                int g = (wp.color >>> 8) & 0xFF;
                int b = wp.color & 0xFF;

                // Dark outline
                drawQuad(-8.0, -8.0, 8.0, 8.0, 0xCC0D1117);
                // Colored badge fill
                drawQuad(-6.8, -6.8, 6.8, 6.8, (0xE6 << 24) | (r << 16) | (g << 8) | b);

                GL11.glEnable(GL11.GL_TEXTURE_2D);
                String init = wp.initials != null ? wp.initials : "W";
                int iw = fr.getStringWidth(init);
                fr.drawStringWithShadow(init, -iw / 2, -4, 0xFFFFFFFF);

                // Name + distance pill below badge
                String distStr = Math.round(dist) + "m";
                String label = wp.name + " \u00a77(" + distStr + ")";
                int lw = fr.getStringWidth(label);
                GL11.glPushMatrix();
                GL11.glTranslated(0.0, 10.5, 0.0);
                GL11.glScalef(0.82f, 0.82f, 1.0f);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                drawQuad(-lw * 0.5 - 3.0, -1.5, lw * 0.5 + 3.0, fr.FONT_HEIGHT + 1.5, 0xAA0B0E14);
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                fr.drawStringWithShadow(label, -lw / 2, 0, 0xFFF1F5F9);
                GL11.glPopMatrix();

                GL11.glPopMatrix();
            }
        }
    }

    private static void renderBeam(double rx, double baseY, double rz, int color) {
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_TEXTURE_2D);

        float r = ((color >>> 16) & 0xFF) / 255.0f;
        float g = ((color >>> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;
        double hw = 0.16;

        GL11.glColor4f(r, g, b, 0.28f);
        GL11.glBegin(GL11.GL_QUADS);
        // X-aligned plane
        GL11.glVertex3d(rx - hw, baseY, rz);
        GL11.glVertex3d(rx + hw, baseY, rz);
        GL11.glVertex3d(rx + hw, baseY + 256.0, rz);
        GL11.glVertex3d(rx - hw, baseY + 256.0, rz);
        // Z-aligned plane
        GL11.glVertex3d(rx, baseY, rz - hw);
        GL11.glVertex3d(rx, baseY, rz + hw);
        GL11.glVertex3d(rx, baseY + 256.0, rz + hw);
        GL11.glVertex3d(rx, baseY + 256.0, rz - hw);
        GL11.glEnd();
    }

    private static void drawQuad(double x1, double y1, double x2, double y2, int argb) {
        float a = ((argb >>> 24) & 0xFF) / 255.0f;
        float r = ((argb >>> 16) & 0xFF) / 255.0f;
        float g = ((argb >>> 8) & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;
        GL11.glColor4f(r, g, b, a);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2d(x1, y2);
        GL11.glVertex2d(x2, y2);
        GL11.glVertex2d(x2, y1);
        GL11.glVertex2d(x1, y1);
        GL11.glEnd();
    }
}

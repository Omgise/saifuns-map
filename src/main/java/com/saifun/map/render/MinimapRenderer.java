package com.saifun.map.render;

import com.saifun.map.compat.IGtnhLayerBridge;
import com.saifun.map.config.MapConfig;
import com.saifun.map.engine.ChunkScanner;
import com.saifun.map.engine.MapRegion;
import com.saifun.map.engine.MapWorldCache;
import com.saifun.map.radar.EntityIconRenderer;
import com.saifun.map.radar.EntityRadarManager;
import com.saifun.map.radar.RadarEntity;
import com.saifun.map.waypoints.Waypoint;
import com.saifun.map.waypoints.WaypointManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;
import org.lwjgl.opengl.GL11;

import java.util.List;

/**
 * Modern Xaero-style Minimap HUD renderer.
 * Features circular & square framing (Angelica-safe depth mask), smooth zoom/rotation,
 * GTNH 3x3 ore vein grid, chunk grid, slime chunks, unrestricted entity radar, and info bar.
 */
public class MinimapRenderer {

    private static final double MASK_Z = 50.0;
    private static final int CIRCLE_SEGMENTS = 64;

    private final MapWorldCache worldCache;
    private final ChunkScanner chunkScanner;
    private final EntityRadarManager radarManager;
    private final WaypointManager waypointManager;
    private final IGtnhLayerBridge gtnhBridge;

    private float smoothZoom = 2.0f;

    public MinimapRenderer(
        MapWorldCache worldCache,
        ChunkScanner chunkScanner,
        EntityRadarManager radarManager,
        WaypointManager waypointManager,
        IGtnhLayerBridge gtnhBridge
    ) {
        this.worldCache = worldCache;
        this.chunkScanner = chunkScanner;
        this.radarManager = radarManager;
        this.waypointManager = waypointManager;
        this.gtnhBridge = gtnhBridge;
    }

    public void render(float partialTicks) {
        MapConfig cfg = MapConfig.get();
        if (!cfg.minimapEnabled) return;

        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.theWorld;
        EntityClientPlayerMP player = mc.thePlayer;
        if (world == null || player == null) return;
        if (mc.gameSettings.hideGUI || mc.gameSettings.showDebugInfo) return;

        worldCache.onRenderTick();

        // Smooth zoom interpolation
        smoothZoom += (cfg.minimapZoom - smoothZoom) * 0.25f;
        if (Math.abs(smoothZoom - cfg.minimapZoom) < 0.005f) {
            smoothZoom = cfg.minimapZoom;
        }

        ScaledResolution sr = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int sw = sr.getScaledWidth();
        int sh = sr.getScaledHeight();
        int scaleFactor = sr.getScaleFactor();

        int size = cfg.minimapSize;
        int half = size / 2;

        // Compute top-left position based on selected corner
        int left;
        int top;
        boolean bottomAligned = cfg.minimapCorner == MapConfig.Corner.BOTTOM_LEFT
            || cfg.minimapCorner == MapConfig.Corner.BOTTOM_RIGHT;

        switch (cfg.minimapCorner) {
            case TOP_LEFT:
                left = cfg.offsetX;
                top = cfg.offsetY;
                break;
            case BOTTOM_LEFT:
                left = cfg.offsetX;
                top = sh - size - cfg.offsetY - (cfg.showInfoBar ? 34 : 0);
                break;
            case BOTTOM_RIGHT:
                left = sw - size - cfg.offsetX;
                top = sh - size - cfg.offsetY - (cfg.showInfoBar ? 34 : 0);
                break;
            case TOP_RIGHT:
            default:
                left = sw - size - cfg.offsetX;
                top = cfg.offsetY;
                break;
        }

        double centerX = left + half;
        double centerY = top + half;

        double playerX = player.lastTickPosX + (player.posX - player.lastTickPosX) * partialTicks;
        double playerY = player.lastTickPosY + (player.posY - player.lastTickPosY) * partialTicks;
        double playerZ = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * partialTicks;
        float playerYaw = player.prevRotationYaw + (player.rotationYaw - player.prevRotationYaw) * partialTicks;

        // Rotation angle in degrees: North-lock = 0, otherwise player facing is UP
        float mapRotationDeg = cfg.lockNorth ? 0.0f : (180.0f - playerYaw);
        double blocksPerPixel = 1.0 / smoothZoom;
        double pixelsPerBlock = smoothZoom;
        boolean caveMode = chunkScanner.isCaveModeActive();

        try (GLStateGuard ignored = new GLStateGuard()) {
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_ALPHA_TEST);

            // Compute framebuffer scissor rect around minimap interior
            int scX = Math.max(0, left * scaleFactor);
            int scY = Math.max(0, mc.displayHeight - (top + size) * scaleFactor);
            int scW = Math.max(1, size * scaleFactor);
            int scH = Math.max(1, size * scaleFactor);

            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(scX, scY, scW, scH);

            if (cfg.circularMinimap) {
                // Angelica-safe circular clip using local depth mask inside scissor box (0 stencil bits needed)
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDepthMask(true);
                GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                GL11.glColorMask(false, false, false, false);
                GL11.glDepthFunc(GL11.GL_ALWAYS);
                GL11.glDisable(GL11.GL_TEXTURE_2D);

                drawFilledCircle(centerX, centerY, half - 1.5, MASK_Z, 0xFFFFFFFF);

                GL11.glColorMask(true, true, true, true);
                GL11.glDepthMask(false);
                GL11.glDepthFunc(GL11.GL_EQUAL);
            } else {
                GL11.glDisable(GL11.GL_DEPTH_TEST);
            }

            // 1. Dark map backdrop
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            int bgAlpha = (int) (cfg.minimapOpacity * 235.0f);
            drawRect(left, top, left + size, top + size, MASK_Z, (bgAlpha << 24) | 0x0E1218);

            // 2. Rotated world space: Regions + Overlays
            GL11.glPushMatrix();
            GL11.glTranslated(centerX, centerY, 0.0);
            GL11.glRotatef(mapRotationDeg, 0.0f, 0.0f, 1.0f);

            double worldRadiusBlocks = (half * 1.45) * blocksPerPixel;
            renderRegions(playerX, playerZ, worldRadiusBlocks, pixelsPerBlock, caveMode, cfg.minimapOpacity);
            renderOverlays(world, playerX, playerZ, worldRadiusBlocks, pixelsPerBlock);

            // 2b. GTNH Navigator layers (VisualProspecting Ore Veins, Fluids, Nodes, ServerUtilities Claims)
            if (gtnhBridge != null && cfg.showNavigatorOnMinimap) {
                GL11.glPushMatrix();
                GL11.glTranslated(0.0, 0.0, MASK_Z);
                gtnhBridge.renderMinimapLayers(playerX, playerZ, pixelsPerBlock, mapRotationDeg, worldRadiusBlocks);
                GL11.glPopMatrix();
            }

            GL11.glPopMatrix();

            // End depth/scissor mask before crisp border, compass, entities, waypoints & info bar
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);

            // 3. Entity Radar (clipped cleanly to map radius so icons stay crisp & un-rotated!)
            renderEntities(playerX, playerY, playerZ, centerX, centerY, half, mapRotationDeg, pixelsPerBlock, partialTicks);

            // 3b. Waypoints on Minimap (clamped to rim when outside radius, like Xaero!)
            renderMinimapWaypoints(world.provider.dimensionId, playerX, playerZ, centerX, centerY, half, mapRotationDeg, pixelsPerBlock);

            // 4. Center Player Chevron Arrow
            float arrowAngle = cfg.lockNorth ? (playerYaw - 180.0f) : 0.0f;
            renderPlayerArrow(centerX, centerY, arrowAngle);

            // 5. Crisp Modern Xaero Frame & Compass
            renderFrame(left, top, size, centerX, centerY, half, cfg);
            if (cfg.showCompass) {
                renderCompass(centerX, centerY, half, mapRotationDeg, cfg.circularMinimap);
            }

            // 6. Info Bar (coordinates, biome, cave badge, time & light)
            if (cfg.showInfoBar) {
                renderInfoBar(world, player, left, top, size, bottomAligned, caveMode);
            }
        }
    }

    private void renderRegions(
        double playerX,
        double playerZ,
        double worldRadius,
        double pixelsPerBlock,
        boolean caveMode,
        float opacity
    ) {
        int minRx = Math.floorDiv(MathHelper.floor_double(playerX - worldRadius), MapRegion.SIZE);
        int maxRx = Math.floorDiv(MathHelper.floor_double(playerX + worldRadius), MapRegion.SIZE);
        int minRz = Math.floorDiv(MathHelper.floor_double(playerZ - worldRadius), MapRegion.SIZE);
        int maxRz = Math.floorDiv(MathHelper.floor_double(playerZ + worldRadius), MapRegion.SIZE);

        boolean linearFilter = pixelsPerBlock < 1.2;
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0f, 1.0f, 1.0f, opacity);

        for (int rz = minRz; rz <= maxRz; rz++) {
            for (int rx = minRx; rx <= maxRx; rx++) {
                MapRegion region = worldCache.getRegionIfLoaded(rx, rz);
                if (region == null || !region.hasSurfaceData()) continue;

                region.getOrUpdateTexture(caveMode, linearFilter);

                double wx1 = rx * (double) MapRegion.SIZE;
                double wz1 = rz * (double) MapRegion.SIZE;
                double sx1 = (wx1 - playerX) * pixelsPerBlock;
                double sy1 = (wz1 - playerZ) * pixelsPerBlock;
                double sx2 = sx1 + MapRegion.SIZE * pixelsPerBlock;
                double sy2 = sy1 + MapRegion.SIZE * pixelsPerBlock;

                Tessellator t = Tessellator.instance;
                t.startDrawingQuads();
                t.addVertexWithUV(sx1, sy2, MASK_Z, 0.0, 1.0);
                t.addVertexWithUV(sx2, sy2, MASK_Z, 1.0, 1.0);
                t.addVertexWithUV(sx2, sy1, MASK_Z, 1.0, 0.0);
                t.addVertexWithUV(sx1, sy1, MASK_Z, 0.0, 0.0);
                t.draw();
            }
        }
    }

    private void renderOverlays(
        World world,
        double playerX,
        double playerZ,
        double worldRadius,
        double pixelsPerBlock
    ) {
        MapConfig cfg = MapConfig.get();
        if (!cfg.showChunkGrid && !cfg.showOreVeinGrid && !cfg.showSlimeChunks) return;

        int minCx = Math.floorDiv(MathHelper.floor_double(playerX - worldRadius), 16);
        int maxCx = Math.floorDiv(MathHelper.floor_double(playerX + worldRadius), 16);
        int minCz = Math.floorDiv(MathHelper.floor_double(playerZ - worldRadius), 16);
        int maxCz = Math.floorDiv(MathHelper.floor_double(playerZ + worldRadius), 16);

        GL11.glDisable(GL11.GL_TEXTURE_2D);

        // Filled overlays: Slime Chunks & GTNH Ore Vein Center Chunks
        if (cfg.showSlimeChunks || (cfg.showOreVeinGrid && cfg.highlightOreVeinCenter)) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                for (int cx = minCx; cx <= maxCx; cx++) {
                    double sx1 = ((cx << 4) - playerX) * pixelsPerBlock;
                    double sy1 = ((cz << 4) - playerZ) * pixelsPerBlock;
                    double sx2 = sx1 + 16.0 * pixelsPerBlock;
                    double sy2 = sy1 + 16.0 * pixelsPerBlock;

                    if (cfg.showSlimeChunks && ChunkScanner.isSlimeChunk(world, cx, cz)) {
                        drawRect(sx1, sy1, sx2, sy2, MASK_Z, cfg.slimeChunkColor);
                    }
                    if (cfg.showOreVeinGrid && cfg.highlightOreVeinCenter && ChunkScanner.isOreVeinCenterChunk(cx, cz)) {
                        drawRect(sx1, sy1, sx2, sy2, MASK_Z, cfg.oreVeinCenterColor);
                    }
                }
            }
        }

        // Regular Chunk Grid (16x16)
        if (cfg.showChunkGrid) {
            GL11.glLineWidth(1.0f);
            setColor(cfg.chunkGridColor);
            GL11.glBegin(GL11.GL_LINES);
            double minSy = ((minCz << 4) - playerZ) * pixelsPerBlock;
            double maxSy = (((maxCz + 1) << 4) - playerZ) * pixelsPerBlock;
            for (int cx = minCx; cx <= maxCx + 1; cx++) {
                double sx = ((cx << 4) - playerX) * pixelsPerBlock;
                GL11.glVertex3d(sx, minSy, MASK_Z);
                GL11.glVertex3d(sx, maxSy, MASK_Z);
            }
            double minSx = ((minCx << 4) - playerX) * pixelsPerBlock;
            double maxSx = (((maxCx + 1) << 4) - playerX) * pixelsPerBlock;
            for (int cz = minCz; cz <= maxCz + 1; cz++) {
                double sy = ((cz << 4) - playerZ) * pixelsPerBlock;
                GL11.glVertex3d(minSx, sy, MASK_Z);
                GL11.glVertex3d(maxSx, sy, MASK_Z);
            }
            GL11.glEnd();
        }

        // GTNH 3x3 Chunk Ore Vein Grid (48x48 blocks, aligned to chunkCoord % 3 == 0)
        if (cfg.showOreVeinGrid) {
            GL11.glLineWidth(1.5f);
            setColor(cfg.oreVeinGridColor);
            GL11.glBegin(GL11.GL_LINES);
            double minSy = ((minCz << 4) - playerZ) * pixelsPerBlock;
            double maxSy = (((maxCz + 1) << 4) - playerZ) * pixelsPerBlock;
            for (int cx = minCx; cx <= maxCx + 1; cx++) {
                if (!ChunkScanner.isOreVeinBorderChunk(cx)) continue;
                double sx = ((cx << 4) - playerX) * pixelsPerBlock;
                GL11.glVertex3d(sx, minSy, MASK_Z);
                GL11.glVertex3d(sx, maxSy, MASK_Z);
            }
            double minSx = ((minCx << 4) - playerX) * pixelsPerBlock;
            double maxSx = (((maxCx + 1) << 4) - playerX) * pixelsPerBlock;
            for (int cz = minCz; cz <= maxCz + 1; cz++) {
                if (!ChunkScanner.isOreVeinBorderChunk(cz)) continue;
                double sy = ((cz << 4) - playerZ) * pixelsPerBlock;
                GL11.glVertex3d(minSx, sy, MASK_Z);
                GL11.glVertex3d(maxSx, sy, MASK_Z);
            }
            GL11.glEnd();
            GL11.glLineWidth(1.0f);
        }

        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    private void renderEntities(
        double playerX,
        double playerY,
        double playerZ,
        double centerX,
        double centerY,
        int half,
        float mapRotationDeg,
        double pixelsPerBlock,
        float partialTicks
    ) {
        MapConfig cfg = MapConfig.get();
        if (!cfg.radarEnabled) return;

        List<RadarEntity> entities = radarManager.collectEntities(partialTicks);
        if (entities.isEmpty()) return;

        double rad = Math.toRadians(mapRotationDeg);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        double clipLimit = half - 5.0;

        for (int i = 0; i < entities.size(); i++) {
            RadarEntity re = entities.get(i);
            double dx = (re.x - playerX) * pixelsPerBlock;
            double dz = (re.z - playerZ) * pixelsPerBlock;

            double rx = dx * cos - dz * sin;
            double ry = dx * sin + dz * cos;

            if (cfg.circularMinimap) {
                double distSq = rx * rx + ry * ry;
                if (distSq > clipLimit * clipLimit) {
                    // Pin players to the rim of the minimap so you always know their direction!
                    if (re.category == com.saifun.map.radar.EntityCategory.PLAYER) {
                        double dist = Math.sqrt(distSq);
                        rx = (rx / dist) * clipLimit;
                        ry = (ry / dist) * clipLimit;
                    } else {
                        continue;
                    }
                }
            } else {
                if (Math.abs(rx) > clipLimit || Math.abs(ry) > clipLimit) {
                    if (re.category == com.saifun.map.radar.EntityCategory.PLAYER) {
                        double maxAbs = Math.max(Math.abs(rx), Math.abs(ry));
                        rx = (rx / maxAbs) * clipLimit;
                        ry = (ry / maxAbs) * clipLimit;
                    } else {
                        continue;
                    }
                }
            }

            EntityIconRenderer.renderEntity(re, centerX + rx, centerY + ry, playerY, 1.0f);
        }
    }

    private void renderMinimapWaypoints(
        int dimId,
        double playerX,
        double playerZ,
        double centerX,
        double centerY,
        int half,
        float mapRotationDeg,
        double pixelsPerBlock
    ) {
        MapConfig cfg = MapConfig.get();
        if (!cfg.showWaypointsOnMinimap || waypointManager == null) return;

        List<Waypoint> wps = waypointManager.getWaypointsForDimension(dimId);
        if (wps.isEmpty()) return;

        FontRenderer fr = Minecraft.getMinecraft().fontRenderer;
        double rad = Math.toRadians(mapRotationDeg);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        double clipLimit = half - 4.0;

        for (int i = 0; i < wps.size(); i++) {
            Waypoint wp = wps.get(i);
            if (!wp.enabled) continue;

            double dx = (wp.x + 0.5 - playerX) * pixelsPerBlock;
            double dz = (wp.z + 0.5 - playerZ) * pixelsPerBlock;
            double rx = dx * cos - dz * sin;
            double ry = dx * sin + dz * cos;

            boolean clamped = false;
            if (cfg.circularMinimap) {
                double distSq = rx * rx + ry * ry;
                if (distSq > clipLimit * clipLimit) {
                    double dist = Math.sqrt(distSq);
                    rx = (rx / dist) * clipLimit;
                    ry = (ry / dist) * clipLimit;
                    clamped = true;
                }
            } else {
                if (Math.abs(rx) > clipLimit || Math.abs(ry) > clipLimit) {
                    double maxAbs = Math.max(Math.abs(rx), Math.abs(ry));
                    rx = (rx / maxAbs) * clipLimit;
                    ry = (ry / maxAbs) * clipLimit;
                    clamped = true;
                }
            }

            double sx = centerX + rx;
            double sy = centerY + ry;
            float badgeScale = clamped ? 0.62f : 0.72f;

            GL11.glPushMatrix();
            GL11.glTranslated(sx, sy, 0.0);
            GL11.glScalef(badgeScale, badgeScale, 1.0f);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            drawRect(-6.5, -6.5, 6.5, 6.5, 0.0, 0xDD0D1117);
            drawRect(-5.2, -5.2, 5.2, 5.2, 0.0, wp.getArgb(clamped ? 200 : 245));
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            if (fr != null) {
                String init = wp.initials != null ? wp.initials : "W";
                fr.drawStringWithShadow(init, -fr.getStringWidth(init) / 2, -4, 0xFFFFFFFF);
            }
            GL11.glPopMatrix();
        }
    }

    private static void renderPlayerArrow(double cx, double cy, float angleDeg) {
        GL11.glPushMatrix();
        GL11.glTranslated(cx, cy, 0.0);
        GL11.glRotatef(angleDeg, 0.0f, 0.0f, 1.0f);
        GL11.glDisable(GL11.GL_TEXTURE_2D);

        // Dark outer silhouette
        setColor(0xEE0B0E14);
        GL11.glBegin(GL11.GL_TRIANGLES);
        GL11.glVertex2d(0.0, -5.6);
        GL11.glVertex2d(-4.4, 4.4);
        GL11.glVertex2d(0.0, 2.2);
        GL11.glVertex2d(0.0, -5.6);
        GL11.glVertex2d(0.0, 2.2);
        GL11.glVertex2d(4.4, 4.4);
        GL11.glEnd();

        // Crisp bright white chevron
        setColor(0xFFFFFFFF);
        GL11.glBegin(GL11.GL_TRIANGLES);
        GL11.glVertex2d(0.0, -4.3);
        GL11.glVertex2d(-3.2, 3.2);
        GL11.glVertex2d(0.0, 1.3);
        GL11.glVertex2d(0.0, -4.3);
        GL11.glVertex2d(0.0, 1.3);
        GL11.glVertex2d(3.2, 3.2);
        GL11.glEnd();

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glPopMatrix();
    }

    private static void renderFrame(
        int left, int top, int size,
        double cx, double cy, int half,
        MapConfig cfg
    ) {
        if (cfg.frameStyle == MapConfig.FrameStyle.NONE) return;
        GL11.glDisable(GL11.GL_TEXTURE_2D);

        boolean modern = cfg.frameStyle == MapConfig.FrameStyle.XAERO_MODERN;
        double outerThick = modern ? 2.4 : 1.2;

        if (cfg.circularMinimap) {
            drawCircleRing(cx, cy, half - 1.5, half - 1.5 + outerThick, cfg.frameBorderColor);
            if (modern) {
                drawCircleRing(cx, cy, half - 1.9, half - 1.2, 0x77FFFFFF);
            }
        } else {
            // Outer dark frame
            drawRect(left - outerThick, top - outerThick, left + size + outerThick, top, 0.0, cfg.frameBorderColor);
            drawRect(left - outerThick, top + size, left + size + outerThick, top + size + outerThick, 0.0, cfg.frameBorderColor);
            drawRect(left - outerThick, top, left, top + size, 0.0, cfg.frameBorderColor);
            drawRect(left + size, top, left + size + outerThick, top + size, 0.0, cfg.frameBorderColor);
            if (modern) {
                // Crisp subtle inner bevel highlight
                int bevel = 0x55FFFFFF;
                drawRect(left, top, left + size, top + 0.8, 0.0, bevel);
                drawRect(left, top + size - 0.8, left + size, top + size, 0.0, bevel);
                drawRect(left, top, left + 0.8, top + size, 0.0, bevel);
                drawRect(left + size - 0.8, top, left + size, top + size, 0.0, bevel);
            }
        }

        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    private static void renderCompass(
        double cx, double cy, int half,
        float mapRotationDeg,
        boolean circular
    ) {
        FontRenderer fr = Minecraft.getMinecraft().fontRenderer;
        if (fr == null) return;

        String[] labels = { "N", "E", "S", "W" };
        double[] baseAngles = { -90.0, 0.0, 90.0, 180.0 };
        float scale = 0.72f;
        double radius = half - 1.0;

        for (int i = 0; i < 4; i++) {
            double ang = Math.toRadians(baseAngles[i] + mapRotationDeg);
            double dx = Math.cos(ang);
            double dy = Math.sin(ang);

            double px;
            double py;
            if (circular) {
                px = cx + dx * radius;
                py = cy + dy * radius;
            } else {
                // Project onto square perimeter cleanly
                double m = Math.max(Math.abs(dx), Math.abs(dy));
                px = cx + (dx / m) * (half - 1.5);
                py = cy + (dy / m) * (half - 1.5);
            }

            GL11.glPushMatrix();
            GL11.glTranslated(px, py, 0.0);
            GL11.glScalef(scale, scale, 1.0f);

            String s = labels[i];
            int w = fr.getStringWidth(s);
            int bg = i == 0 ? 0xCC991B1B : 0xCC11161F;
            int fg = i == 0 ? 0xFFFFE4E6 : 0xFFE2E8F0;

            GL11.glDisable(GL11.GL_TEXTURE_2D);
            drawRect(-w * 0.5 - 2.2, -fr.FONT_HEIGHT * 0.5 - 1.2, w * 0.5 + 2.2, fr.FONT_HEIGHT * 0.5 + 1.2, 0.0, bg);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            fr.drawStringWithShadow(s, -w / 2, -fr.FONT_HEIGHT / 2 + 1, fg);

            GL11.glPopMatrix();
        }
    }

    private static void renderInfoBar(
        World world,
        EntityClientPlayerMP player,
        int left,
        int top,
        int size,
        boolean placeAbove,
        boolean caveMode
    ) {
        MapConfig cfg = MapConfig.get();
        FontRenderer fr = Minecraft.getMinecraft().fontRenderer;
        if (fr == null) return;

        int px = MathHelper.floor_double(player.posX);
        int py = MathHelper.floor_double(player.posY);
        int pz = MathHelper.floor_double(player.posZ);

        StringBuilder line1 = new StringBuilder();
        if (cfg.showCoordinates) {
            line1.append(px).append(", ").append(py).append(", ").append(pz);
            if (cfg.showDimensionCoords) {
                if (world.provider.dimensionId == -1) {
                    line1.append(" (OW: ").append(px * 8).append(", ").append(pz * 8).append(")");
                } else if (world.provider.dimensionId == 0 && caveMode) {
                    line1.append(" (N: ").append(px / 8).append(", ").append(pz / 8).append(")");
                }
            }
        }

        String line2 = "";
        if (cfg.showBiome) {
            BiomeGenBase biome = world.getBiomeGenForCoords(px, pz);
            String bName = biome != null ? biome.biomeName : "Unknown";
            line2 = caveMode ? (bName + " [CAVE]") : bName;
        } else if (caveMode) {
            line2 = "[CAVE MODE]";
        }

        String line3 = "";
        if (cfg.showTimeAndLight) {
            long timeOfDay = (world.getWorldTime() + 6000L) % 24000L;
            int hours = (int) (timeOfDay / 1000L);
            int mins = (int) ((timeOfDay % 1000L) * 60L / 1000L);
            int light = world.blockExists(px, Math.max(0, Math.min(255, py)), pz)
                ? world.getBlockLightValue(px, Math.max(0, Math.min(255, py)), pz)
                : 0;
            line3 = String.format("%02d:%02d  |  L:%d  |  Dim %d", hours, mins, light, world.provider.dimensionId);
        }

        int lines = 0;
        if (line1.length() > 0) lines++;
        if (!line2.isEmpty()) lines++;
        if (!line3.isEmpty()) lines++;
        if (lines == 0) return;

        float textScale = 0.72f;
        int lineHeight = Math.round(10 * textScale) + 2;
        int boxH = lines * lineHeight + 5;
        int boxY = placeAbove ? (top - boxH - 4) : (top + size + 4);

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        drawRect(left, boxY, left + size, boxY + boxH, 0.0, 0xB80E1218);
        drawRect(left, boxY, left + size, boxY + 0.8, 0.0, 0x553A7BD5);
        GL11.glEnable(GL11.GL_TEXTURE_2D);

        int curY = boxY + 3;
        if (line1.length() > 0) {
            drawCenteredScaled(fr, line1.toString(), left + size * 0.5, curY, textScale, 0xFFF1F5F9);
            curY += lineHeight;
        }
        if (!line2.isEmpty()) {
            int col = caveMode ? 0xFFFBBF24 : 0xFF93C5FD;
            drawCenteredScaled(fr, line2, left + size * 0.5, curY, textScale, col);
            curY += lineHeight;
        }
        if (!line3.isEmpty()) {
            drawCenteredScaled(fr, line3, left + size * 0.5, curY, textScale, 0xFF94A3B8);
        }
    }

    private static void drawCenteredScaled(
        FontRenderer fr, String text, double centerX, double y, float scale, int color
    ) {
        GL11.glPushMatrix();
        GL11.glTranslated(centerX, y, 0.0);
        GL11.glScalef(scale, scale, 1.0f);
        int w = fr.getStringWidth(text);
        fr.drawStringWithShadow(text, -w / 2, 0, color);
        GL11.glPopMatrix();
    }

    private static void drawFilledCircle(double cx, double cy, double r, double z, int argb) {
        setColor(argb);
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glVertex3d(cx, cy, z);
        for (int i = 0; i <= CIRCLE_SEGMENTS; i++) {
            double ang = (Math.PI * 2.0 * i) / CIRCLE_SEGMENTS;
            GL11.glVertex3d(cx + Math.cos(ang) * r, cy + Math.sin(ang) * r, z);
        }
        GL11.glEnd();
    }

    private static void drawCircleRing(double cx, double cy, double innerR, double outerR, int argb) {
        setColor(argb);
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        for (int i = 0; i <= CIRCLE_SEGMENTS; i++) {
            double ang = (Math.PI * 2.0 * i) / CIRCLE_SEGMENTS;
            double cos = Math.cos(ang);
            double sin = Math.sin(ang);
            GL11.glVertex2d(cx + cos * outerR, cy + sin * outerR);
            GL11.glVertex2d(cx + cos * innerR, cy + sin * innerR);
        }
        GL11.glEnd();
    }

    private static void drawRect(double x1, double y1, double x2, double y2, double z, int argb) {
        setColor(argb);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex3d(x1, y2, z);
        GL11.glVertex3d(x2, y2, z);
        GL11.glVertex3d(x2, y1, z);
        GL11.glVertex3d(x1, y1, z);
        GL11.glEnd();
    }

    private static void setColor(int argb) {
        float a = ((argb >>> 24) & 0xFF) / 255.0f;
        float r = ((argb >>> 16) & 0xFF) / 255.0f;
        float g = ((argb >>> 8) & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;
        GL11.glColor4f(r, g, b, a);
    }
}

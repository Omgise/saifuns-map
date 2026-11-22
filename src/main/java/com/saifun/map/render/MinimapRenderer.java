package com.saifun.map.render;

import com.saifun.map.compat.IGtnhLayerBridge;
import com.saifun.map.config.MapConfig;
import com.saifun.map.engine.ChunkScanner;
import com.saifun.map.engine.MapRegion;
import com.saifun.map.engine.MapWorldCache;
import com.saifun.map.radar.EntityCategory;
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
 * Modern Xaero-style Minimalist Minimap HUD renderer (v2.0).
 * - Crisp 3-layer hairline Xaero frame (or Circle / Minimal / None).
 * - Smooth deltaTime-based zoom and Enlarge Minimap (Z) animations.
 * - Minimalist inside-rim compass letters and bare shadowed coordinates underneath.
 * - Explored-only overlays, compact entity heads/diamonds, waypoints, and GTNH Navigator layers.
 */
public class MinimapRenderer {

    private static final double MASK_Z = 50.0;
    private static final int CIRCLE_SEGMENTS = 64;

    private final MapWorldCache worldCache;
    private final ChunkScanner chunkScanner;
    private final EntityRadarManager radarManager;
    private final WaypointManager waypointManager;
    private final IGtnhLayerBridge gtnhBridge;

    private float smoothZoom = 1.6f;
    private float smoothSize = 144.0f;
    private boolean enlarged = false;
    private long lastFrameNanos = 0L;

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

    public void toggleEnlarged() {
        this.enlarged = !this.enlarged;
    }

    public boolean isEnlarged() {
        return enlarged;
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

        // Frame-rate independent smooth animations
        long now = System.nanoTime();
        double dt = lastFrameNanos == 0L ? 0.016 : Math.min(0.1, (now - lastFrameNanos) * 1e-9);
        lastFrameNanos = now;
        float ease = (float) (1.0 - Math.exp(-dt * 14.0));

        float targetZoom = enlarged ? Math.max(0.75f, cfg.minimapZoom * 0.72f) : cfg.minimapZoom;
        smoothZoom += (targetZoom - smoothZoom) * ease;
        if (Math.abs(smoothZoom - targetZoom) < 0.002f) smoothZoom = targetZoom;

        ScaledResolution sr = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int sw = sr.getScaledWidth();
        int sh = sr.getScaledHeight();
        int scaleFactor = sr.getScaleFactor();

        float targetSize = enlarged ? Math.min(Math.min(sw, sh) - 48, cfg.minimapSize * 1.85f) : cfg.minimapSize;
        if (smoothSize <= 1.0f) smoothSize = targetSize;
        smoothSize += (targetSize - smoothSize) * ease;
        if (Math.abs(smoothSize - targetSize) < 0.25f) smoothSize = targetSize;

        int size = Math.round(smoothSize);
        int half = size / 2;

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
                top = sh - size - cfg.offsetY - (cfg.showInfoBar ? 20 : 0);
                break;
            case BOTTOM_RIGHT:
                left = sw - size - cfg.offsetX;
                top = sh - size - cfg.offsetY - (cfg.showInfoBar ? 20 : 0);
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

        float mapRotationDeg = cfg.lockNorth ? 0.0f : (180.0f - playerYaw);
        double blocksPerPixel = 1.0 / smoothZoom;
        double pixelsPerBlock = smoothZoom;
        boolean caveMode = chunkScanner.isCaveModeActive();

        try (GLStateGuard ignored = new GLStateGuard()) {
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GLDraw.ensureBlend();
            GL11.glEnable(GL11.GL_ALPHA_TEST);

            int scX = Math.max(0, left * scaleFactor);
            int scY = Math.max(0, mc.displayHeight - (top + size) * scaleFactor);
            int scW = Math.max(1, size * scaleFactor);
            int scH = Math.max(1, size * scaleFactor);

            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(scX, scY, scW, scH);

            if (cfg.circularMinimap) {
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDepthMask(true);
                GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                GL11.glColorMask(false, false, false, false);
                GL11.glDepthFunc(GL11.GL_ALWAYS);

                GLDraw.circle(centerX, centerY, half - 1.5, MASK_Z, 0xFFFFFFFF, CIRCLE_SEGMENTS);

                GL11.glColorMask(true, true, true, true);
                GL11.glDepthMask(false);
                GL11.glDepthFunc(GL11.GL_EQUAL);
            } else {
                GL11.glDisable(GL11.GL_DEPTH_TEST);
            }

            // 1. Deep subtle backdrop
            int bgAlpha = (int) (cfg.minimapOpacity * 228.0f);
            GLDraw.rectZ(left, top, left + size, top + size, MASK_Z, (bgAlpha << 24) | 0x0B0F15);

            // 2. Rotated world space: Regions + Explored-only Overlays + GTNH Navigator
            GL11.glPushMatrix();
            GL11.glTranslated(centerX, centerY, 0.0);
            GL11.glRotatef(mapRotationDeg, 0.0f, 0.0f, 1.0f);

            double worldRadiusBlocks = (half * 1.45) * blocksPerPixel;
            renderRegions(playerX, playerZ, worldRadiusBlocks, pixelsPerBlock, caveMode, cfg.minimapOpacity);
            renderOverlays(world, playerX, playerZ, worldRadiusBlocks, pixelsPerBlock);

            if (gtnhBridge != null && cfg.showNavigatorOnMinimap) {
                GL11.glPushMatrix();
                GL11.glTranslated(0.0, 0.0, MASK_Z);
                gtnhBridge.renderMinimapLayers(playerX, playerZ, pixelsPerBlock, mapRotationDeg, worldRadiusBlocks);
                GL11.glPopMatrix();
            }

            GL11.glPopMatrix();

            // End depth/scissor mask
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GLDraw.ensureBlend();

            // 3. Entity Radar (compact crisp heads/diamonds)
            renderEntities(playerX, playerY, playerZ, centerX, centerY, half, mapRotationDeg, pixelsPerBlock, partialTicks);

            // 4. Waypoints on Minimap (clamped neatly inside rim)
            renderMinimapWaypoints(world.provider.dimensionId, playerX, playerZ, centerX, centerY, half, mapRotationDeg, pixelsPerBlock);

            // 5. Crisp minimalist center player marker
            float arrowAngle = cfg.lockNorth ? (playerYaw - 180.0f) : 0.0f;
            renderPlayerMarker(centerX, centerY, arrowAngle, cfg.lockNorth);

            // 6. Clean thin Xaero Frame & inside-rim minimalist Compass
            renderFrame(left, top, size, centerX, centerY, half, cfg);
            if (cfg.showCompass) {
                renderCompass(centerX, centerY, half, mapRotationDeg, cfg.circularMinimap);
            }

            // 7. Minimalist shadowed coordinates underneath (matching modern Xaero screenshot #8!)
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

        boolean linearFilter = pixelsPerBlock < 1.35;
        GLDraw.ensureBlend();
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
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
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

        GLDraw.ensureBlend();
        GL11.glDisable(GL11.GL_TEXTURE_2D);

        // Filled tints ONLY on explored chunks
        if (cfg.showSlimeChunks || (cfg.showOreVeinGrid && cfg.highlightOreVeinCenter)) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                for (int cx = minCx; cx <= maxCx; cx++) {
                    if (!worldCache.isChunkExplored(cx, cz)) continue;
                    double sx1 = ((cx << 4) - playerX) * pixelsPerBlock;
                    double sy1 = ((cz << 4) - playerZ) * pixelsPerBlock;
                    double sx2 = sx1 + 16.0 * pixelsPerBlock;
                    double sy2 = sy1 + 16.0 * pixelsPerBlock;

                    if (cfg.showSlimeChunks && ChunkScanner.isSlimeChunk(world, cx, cz)) {
                        GLDraw.rectZ(sx1, sy1, sx2, sy2, MASK_Z, cfg.slimeChunkColor);
                    }
                    if (cfg.showOreVeinGrid && cfg.highlightOreVeinCenter && ChunkScanner.isOreVeinCenterChunk(cx, cz)) {
                        GLDraw.rectZ(sx1, sy1, sx2, sy2, MASK_Z, cfg.oreVeinCenterColor);
                    }
                }
            }
            GL11.glDisable(GL11.GL_TEXTURE_2D);
        }

        if (cfg.showChunkGrid) {
            GL11.glLineWidth(1.0f);
            GLDraw.setColor(cfg.chunkGridColor);
            GL11.glBegin(GL11.GL_LINES);
            for (int cz = minCz; cz <= maxCz; cz++) {
                for (int cx = minCx; cx <= maxCx; cx++) {
                    if (!worldCache.isChunkExplored(cx, cz)) continue;
                    double x1 = ((cx << 4) - playerX) * pixelsPerBlock;
                    double y1 = ((cz << 4) - playerZ) * pixelsPerBlock;
                    double x2 = x1 + 16.0 * pixelsPerBlock;
                    double y2 = y1 + 16.0 * pixelsPerBlock;
                    GL11.glVertex3d(x1, y1, MASK_Z);
                    GL11.glVertex3d(x2, y1, MASK_Z);
                    GL11.glVertex3d(x1, y1, MASK_Z);
                    GL11.glVertex3d(x1, y2, MASK_Z);
                }
            }
            GL11.glEnd();
        }

        if (cfg.showOreVeinGrid) {
            GL11.glLineWidth(1.0f);
            GLDraw.setColor(cfg.oreVeinGridColor);
            GL11.glBegin(GL11.GL_LINES);
            for (int cz = minCz; cz <= maxCz; cz++) {
                for (int cx = minCx; cx <= maxCx; cx++) {
                    if (!worldCache.isChunkExplored(cx, cz)) continue;
                    double x1 = ((cx << 4) - playerX) * pixelsPerBlock;
                    double y1 = ((cz << 4) - playerZ) * pixelsPerBlock;
                    double x2 = x1 + 16.0 * pixelsPerBlock;
                    double y2 = y1 + 16.0 * pixelsPerBlock;
                    if (ChunkScanner.isOreVeinBorderChunk(cx)) {
                        GL11.glVertex3d(x1, y1, MASK_Z);
                        GL11.glVertex3d(x1, y2, MASK_Z);
                    }
                    if (ChunkScanner.isOreVeinBorderChunk(cz)) {
                        GL11.glVertex3d(x1, y1, MASK_Z);
                        GL11.glVertex3d(x2, y1, MASK_Z);
                    }
                }
            }
            GL11.glEnd();
        }

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
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
        double clipLimit = half - 4.5;

        for (int i = 0; i < entities.size(); i++) {
            RadarEntity re = entities.get(i);
            double dx = (re.x - playerX) * pixelsPerBlock;
            double dz = (re.z - playerZ) * pixelsPerBlock;

            double rx = dx * cos - dz * sin;
            double ry = dx * sin + dz * cos;

            if (cfg.circularMinimap) {
                double distSq = rx * rx + ry * ry;
                if (distSq > clipLimit * clipLimit) {
                    if (re.category == EntityCategory.PLAYER) {
                        double dist = Math.sqrt(distSq);
                        rx = (rx / dist) * clipLimit;
                        ry = (ry / dist) * clipLimit;
                    } else {
                        continue;
                    }
                }
            } else {
                if (Math.abs(rx) > clipLimit || Math.abs(ry) > clipLimit) {
                    if (re.category == EntityCategory.PLAYER) {
                        double maxAbs = Math.max(Math.abs(rx), Math.abs(ry));
                        rx = (rx / maxAbs) * clipLimit;
                        ry = (ry / maxAbs) * clipLimit;
                    } else {
                        continue;
                    }
                }
            }

            EntityIconRenderer.renderEntity(re, centerX + rx, centerY + ry, playerY, 1.0f, 1.0f);
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
        double clipLimit = half - 5.0;

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
            float badgeScale = clamped ? 0.55f : 0.65f;

            GL11.glPushMatrix();
            GL11.glTranslated(sx, sy, 0.0);
            GL11.glScalef(badgeScale, badgeScale, 1.0f);
            GLDraw.rect(-6.2, -6.2, 6.2, 6.2, clamped ? 0xCC0A0D12 : 0xEE0A0D12);
            GLDraw.rect(-5.0, -5.0, 5.0, 5.0, wp.getArgb(clamped ? 205 : 245));
            if (fr != null) {
                String init = wp.initials != null ? wp.initials : "W";
                GLDraw.drawCenteredText(fr, init, 0.3, -3.5, 0.95f, 0xFFFFFFFF);
            }
            GL11.glPopMatrix();
        }
    }

    private static void renderPlayerMarker(double cx, double cy, float angleDeg, boolean showArrow) {
        GL11.glPushMatrix();
        GL11.glTranslated(cx, cy, 0.0);
        GLDraw.ensureBlend();

        if (!showArrow) {
            // Minimalist crisp modern Xaero center crosshair dot (like screenshot #8!)
            GLDraw.rect(-2.5, -0.7, 2.5, 0.7, 0xCC0A0D12);
            GLDraw.rect(-0.7, -2.5, 0.7, 2.5, 0xCC0A0D12);
            GLDraw.rect(-2.0, -0.45, 2.0, 0.45, 0xFFFFFFFF);
            GLDraw.rect(-0.45, -2.0, 0.45, 2.0, 0xFFFFFFFF);
        } else {
            GL11.glRotatef(angleDeg, 0.0f, 0.0f, 1.0f);
            GL11.glDisable(GL11.GL_TEXTURE_2D);

            GLDraw.setColor(0xEE0A0D12);
            GL11.glBegin(GL11.GL_TRIANGLES);
            GL11.glVertex2d(0.0, -4.8);
            GL11.glVertex2d(-3.6, 3.6);
            GL11.glVertex2d(0.0, 1.6);
            GL11.glVertex2d(0.0, -4.8);
            GL11.glVertex2d(0.0, 1.6);
            GL11.glVertex2d(3.6, 3.6);
            GL11.glEnd();

            GLDraw.setColor(0xFFFFFFFF);
            GL11.glBegin(GL11.GL_TRIANGLES);
            GL11.glVertex2d(0.0, -3.6);
            GL11.glVertex2d(-2.5, 2.5);
            GL11.glVertex2d(0.0, 1.0);
            GL11.glVertex2d(0.0, -3.6);
            GL11.glVertex2d(0.0, 1.0);
            GL11.glVertex2d(2.5, 2.5);
            GL11.glEnd();

            GL11.glEnable(GL11.GL_TEXTURE_2D);
        }

        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
        GL11.glPopMatrix();
    }

    private static void renderFrame(
        int left, int top, int size,
        double cx, double cy, int half,
        MapConfig cfg
    ) {
        if (cfg.frameStyle == MapConfig.FrameStyle.NONE) return;
        boolean modern = cfg.frameStyle == MapConfig.FrameStyle.XAERO_MODERN;

        if (cfg.circularMinimap) {
            if (modern) {
                GLDraw.ring(cx, cy, half - 1.6, half - 0.8, 0xFF14171D, CIRCLE_SEGMENTS);
                GLDraw.ring(cx, cy, half - 0.8, half + 0.4, 0xFF8E96A3, CIRCLE_SEGMENTS);
                GLDraw.ring(cx, cy, half + 0.4, half + 1.4, 0xFF14171D, CIRCLE_SEGMENTS);
            } else {
                GLDraw.ring(cx, cy, half - 1.2, half + 0.6, 0xDD14171D, CIRCLE_SEGMENTS);
            }
        } else {
            if (modern) {
                // Exact modern Xaero 3-layer hairline frame (dark -> soft steel gray -> dark, matching screenshot #8!)
                GLDraw.rectOutline(left - 2.4, top - 2.4, left + size + 2.4, top + size + 2.4, 1.0, 0xFF12151B);
                GLDraw.rectOutline(left - 1.4, top - 1.4, left + size + 1.4, top + size + 1.4, 1.0, 0xFF8E96A3);
                GLDraw.rectOutline(left - 0.4, top - 0.4, left + size + 0.4, top + size + 0.4, 0.8, 0xFF12151B);
            } else {
                GLDraw.rectOutline(left - 1.0, top - 1.0, left + size + 1.0, top + size + 1.0, 1.0, 0xCC12151B);
            }
        }
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
        float scale = 0.62f;
        double inset = 7.5;

        for (int i = 0; i < 4; i++) {
            double ang = Math.toRadians(baseAngles[i] + mapRotationDeg);
            double dx = Math.cos(ang);
            double dy = Math.sin(ang);

            double px;
            double py;
            if (circular) {
                px = cx + dx * (half - inset);
                py = cy + dy * (half - inset);
            } else {
                double m = Math.max(Math.abs(dx), Math.abs(dy));
                px = cx + (dx / m) * (half - inset);
                py = cy + (dy / m) * (half - inset);
            }

            // Sleek micro-badge tucked cleanly inside the rim (matching modern Xaero screenshot #8!)
            int bg = i == 0 ? 0xBB4F5FD5 : 0xAA3B478F;
            int fg = i == 0 ? 0xFFFFFFFF : 0xFFE2E8F0;

            GLDraw.rect(px - 3.6, py - 3.6, px + 3.6, py + 3.6, bg);
            GLDraw.drawCenteredText(fr, labels[i], px + 0.3, py - 2.3, scale, fg);
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
            if (cfg.showDimensionCoords && world.provider.dimensionId == -1) {
                line1.append(" (").append(px * 8).append(", ").append(pz * 8).append(")");
            }
        }

        String line2 = "";
        if (cfg.showBiome) {
            BiomeGenBase biome = world.getBiomeGenForCoords(px, pz);
            String bName = biome != null ? biome.biomeName : "Unknown";
            line2 = caveMode ? (bName + " [Cave]") : bName;
        } else if (caveMode) {
            line2 = "Cave Mode";
        }

        String line3 = "";
        if (cfg.showTimeAndLight) {
            long timeOfDay = (world.getWorldTime() + 6000L) % 24000L;
            int hours = (int) (timeOfDay / 1000L);
            int mins = (int) ((timeOfDay % 1000L) * 60L / 1000L);
            int light = world.blockExists(px, Math.max(0, Math.min(255, py)), pz)
                ? world.getBlockLightValue(px, Math.max(0, Math.min(255, py)), pz)
                : 0;
            line3 = String.format("%02d:%02d | L:%d", hours, mins, light);
        }

        int lines = 0;
        if (line1.length() > 0) lines++;
        if (!line2.isEmpty()) lines++;
        if (!line3.isEmpty()) lines++;
        if (lines == 0) return;

        float textScale = 0.78f;
        int lineHeight = 9;
        int totalH = lines * lineHeight;
        int startY = placeAbove ? (top - totalH - 6) : (top + size + 5);

        if (cfg.showInfoBarBackground) {
            GLDraw.rect(left, startY - 2, left + size, startY + totalH + 2, 0x990A0D12);
        }

        int curY = startY;
        if (line1.length() > 0) {
            GLDraw.drawCenteredText(fr, line1.toString(), left + size * 0.5, curY, textScale, 0xFFF8FAFC);
            curY += lineHeight;
        }
        if (!line2.isEmpty()) {
            int col = caveMode ? 0xFFFBBF24 : 0xFFCBD5E1;
            GLDraw.drawCenteredText(fr, line2, left + size * 0.5, curY, 0.70f, col);
            curY += lineHeight;
        }
        if (!line3.isEmpty()) {
            GLDraw.drawCenteredText(fr, line3, left + size * 0.5, curY, 0.68f, 0xFF94A3B8);
        }
    }
}

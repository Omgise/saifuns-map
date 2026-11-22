package com.saifun.map.gui;

import com.saifun.map.compat.IGtnhLayerBridge;
import com.saifun.map.config.MapConfig;
import com.saifun.map.engine.ChunkScanner;
import com.saifun.map.engine.MapRegion;
import com.saifun.map.engine.MapWorldCache;
import com.saifun.map.radar.EntityCategory;
import com.saifun.map.radar.EntityIconRenderer;
import com.saifun.map.radar.EntityRadarManager;
import com.saifun.map.radar.RadarEntity;
import com.saifun.map.render.GLDraw;
import com.saifun.map.render.GLStateGuard;
import com.saifun.map.waypoints.Waypoint;
import com.saifun.map.waypoints.WaypointManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Fullscreen World Map redesigned 1-to-1 in modern Xaero's World Map minimalism (v2.0):
 * - Zero bulky top bars or gray vanilla buttons.
 * - Floating bare shadowed coordinates & biome at top-center, minimalist compass rose top-right,
 *   sleek gear top-left, compact vector icon rail bottom-right, GTNH Navigator rail bottom-left.
 * - True deltaTime-based smooth cursor-anchored exponential zoom, pan inertia, and smooth center glide.
 * - Smart mob head scaling/fading at low zoom (players always crisp at any zoom).
 */
public class GuiWorldMap extends GuiScreen {

    private static final class IconButton {
        final int id;
        final int x, y, size;
        final String tooltip;
        final boolean active;

        IconButton(int id, int x, int y, int size, String tooltip, boolean active) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.size = size;
            this.tooltip = tooltip;
            this.active = active;
        }

        boolean hit(int mx, int my) {
            return mx >= x && mx <= x + size && my >= y && my <= y + size;
        }
    }

    private static final int BTN_SETTINGS = 1;
    private static final int BTN_ZOOM_IN = 2;
    private static final int BTN_ZOOM_OUT = 3;
    private static final int BTN_CENTER = 4;
    private static final int BTN_CAVE = 5;
    private static final int BTN_ORE_GRID = 6;
    private static final int BTN_CHUNK_GRID = 7;
    private static final int BTN_WAYPOINTS = 8;

    private final MapWorldCache worldCache;
    private final ChunkScanner chunkScanner;
    private final EntityRadarManager radarManager;
    private final WaypointManager waypointManager;
    private final IGtnhLayerBridge gtnhBridge;

    private boolean initialized = false;
    private double centerX;
    private double centerZ;
    private double targetZoom = 1.5;
    private double currentZoom = 1.5;
    private boolean caveModeView;

    // Smooth cursor-anchored zoom state
    private boolean zoomAnimating = false;
    private double zoomAnchorScreenX;
    private double zoomAnchorScreenY;
    private double zoomAnchorWorldX;
    private double zoomAnchorWorldZ;

    // Smooth pan inertia & center-glide state
    private double velX = 0.0;
    private double velZ = 0.0;
    private boolean glidingToTarget = false;
    private double glideTargetX;
    private double glideTargetZ;

    private boolean dragging = false;
    private double dragStartRawX;
    private double dragStartRawY;
    private double prevRawX;
    private double prevRawY;
    private double dragStartCenterX;
    private double dragStartCenterZ;
    private boolean dragMoved = false;

    private long lastFrameNanos = 0L;
    private long lastLeftClickMs = 0L;
    private int lastClickX = 0;
    private int lastClickY = 0;

    // Smooth open/close zoom + fade transition
    private static final double OPEN_DURATION_SEC = 0.24;
    private static final double CLOSE_DURATION_SEC = 0.14;
    private double openProgress = 0.0;
    private boolean closing = false;

    // Right-click context menu
    private boolean contextMenuOpen = false;
    private int contextMenuX;
    private int contextMenuY;
    private int contextBlockX;
    private int contextBlockZ;

    // GTNH Navigator search bar
    private GuiTextField searchField;
    private String lastSearchText = "";

    private final List<IconButton> uiButtons = new ArrayList<>();

    public GuiWorldMap(
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

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        dragging = false;
        EntityClientPlayerMP player = mc.thePlayer;
        if (!initialized) {
            if (player != null) {
                centerX = player.posX;
                centerZ = player.posZ;
            }
            caveModeView = chunkScanner.isCaveModeActive();
            gtnhBridge.onWorldMapOpened();
            openProgress = 0.0;
            closing = false;
            initialized = true;
        }
        lastFrameNanos = System.nanoTime();

        searchField = new GuiTextField(this.fontRendererObj, this.width / 2 - 96, this.height - 26, 192, 16);
        searchField.setMaxStringLength(64);
        searchField.setText(lastSearchText);
        rebuildIconButtons();
    }

    private void rebuildIconButtons() {
        uiButtons.clear();
        MapConfig cfg = MapConfig.get();

        // Top-left Gear (Settings)
        uiButtons.add(new IconButton(BTN_SETTINGS, 8, 8, 22, "Settings (Y)", false));

        // Bottom-right vertical icon rail
        int bs = 22;
        int rx = this.width - bs - 8;
        int step = bs + 3;
        int count = 7;
        int startY = this.height - 10 - count * step;

        uiButtons.add(new IconButton(BTN_ZOOM_IN, rx, startY, bs, "Zoom In (+)", false));
        uiButtons.add(new IconButton(BTN_ZOOM_OUT, rx, startY + step, bs, "Zoom Out (-)", false));
        uiButtons.add(new IconButton(BTN_CENTER, rx, startY + step * 2, bs, "Center on Player (C)", false));
        uiButtons.add(new IconButton(BTN_CAVE, rx, startY + step * 3, bs, caveModeView ? "Cave Mode (ON)" : "Surface Mode", caveModeView));
        uiButtons.add(new IconButton(BTN_ORE_GRID, rx, startY + step * 4, bs, cfg.showOreVeinGrid ? "GT Ore Vein Grid 3x3 (ON)" : "GT Ore Vein Grid 3x3 (OFF)", cfg.showOreVeinGrid));
        uiButtons.add(new IconButton(BTN_CHUNK_GRID, rx, startY + step * 5, bs, cfg.showChunkGrid ? "Chunk Grid 16x16 (ON)" : "Chunk Grid 16x16 (OFF)", cfg.showChunkGrid));
        uiButtons.add(new IconButton(BTN_WAYPOINTS, rx, startY + step * 6, bs, "Waypoints (U)", false));
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        gtnhBridge.onWorldMapClosed();
    }

    private void triggerSmoothZoom(double factor, double anchorScreenX, double anchorScreenY) {
        double newTarget = Math.max(0.125, Math.min(16.0, targetZoom * factor));
        if (Math.abs(newTarget - targetZoom) < 1e-4) return;
        this.zoomAnchorScreenX = anchorScreenX;
        this.zoomAnchorScreenY = anchorScreenY;
        this.zoomAnchorWorldX = screenToWorldX(anchorScreenX);
        this.zoomAnchorWorldZ = screenToWorldZ(anchorScreenY);
        this.targetZoom = newTarget;
        this.zoomAnimating = true;
        this.glidingToTarget = false;
        this.velX = 0.0;
        this.velZ = 0.0;
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            int mx = Mouse.getEventX() * this.width / this.mc.displayWidth;
            int my = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
            double factor = wheel > 0 ? 1.32 : (1.0 / 1.32);
            triggerSmoothZoom(factor, mx, my);
            contextMenuOpen = false;
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        if (contextMenuOpen) {
            if (handleContextMenuClick(mouseX, mouseY)) {
                contextMenuOpen = false;
                return;
            }
            contextMenuOpen = false;
        }

        if (gtnhBridge.hasActiveSearchBar() && searchField != null) {
            searchField.mouseClicked(mouseX, mouseY, mouseButton);
            if (searchField.isFocused()) return;
        }

        if (mouseButton == 0 && handleUiIconClick(mouseX, mouseY)) {
            return;
        }

        if (mouseButton == 0 && handleLayerToolbarClick(mouseX, mouseY)) {
            return;
        }

        if (mouseButton == 1) {
            contextMenuOpen = true;
            contextMenuX = Math.min(mouseX, this.width - 134);
            contextMenuY = Math.min(mouseY, this.height - 96);
            contextBlockX = MathHelper.floor_double(screenToWorldX(mouseX));
            contextBlockZ = MathHelper.floor_double(screenToWorldZ(mouseY));
            return;
        }

        if (mouseButton == 0) {
            dragging = true;
            dragMoved = false;
            zoomAnimating = false;
            glidingToTarget = false;
            velX = 0.0;
            velZ = 0.0;
            dragStartRawX = getSubpixelMouseX();
            dragStartRawY = getSubpixelMouseY();
            prevRawX = dragStartRawX;
            prevRawY = dragStartRawY;
            dragStartCenterX = centerX;
            dragStartCenterZ = centerZ;
        }
    }

    private boolean handleUiIconClick(int mouseX, int mouseY) {
        for (int i = 0; i < uiButtons.size(); i++) {
            IconButton b = uiButtons.get(i);
            if (!b.hit(mouseX, mouseY)) continue;
            switch (b.id) {
                case BTN_SETTINGS:
                    mc.displayGuiScreen(new GuiMapSettings(this));
                    return true;
                case BTN_ZOOM_IN:
                    triggerSmoothZoom(1.45, this.width * 0.5, this.height * 0.5);
                    return true;
                case BTN_ZOOM_OUT:
                    triggerSmoothZoom(1.0 / 1.45, this.width * 0.5, this.height * 0.5);
                    return true;
                case BTN_CENTER:
                    if (mc.thePlayer != null) {
                        glideTargetX = mc.thePlayer.posX;
                        glideTargetZ = mc.thePlayer.posZ;
                        glidingToTarget = true;
                        zoomAnimating = false;
                        velX = 0.0;
                        velZ = 0.0;
                    }
                    return true;
                case BTN_CAVE:
                    caveModeView = !caveModeView;
                    rebuildIconButtons();
                    return true;
                case BTN_ORE_GRID:
                    MapConfig.get().showOreVeinGrid = !MapConfig.get().showOreVeinGrid;
                    MapConfig.save();
                    rebuildIconButtons();
                    return true;
                case BTN_CHUNK_GRID:
                    MapConfig.get().showChunkGrid = !MapConfig.get().showChunkGrid;
                    MapConfig.save();
                    rebuildIconButtons();
                    return true;
                case BTN_WAYPOINTS:
                    mc.displayGuiScreen(new GuiWaypoints(this, waypointManager));
                    return true;
            }
        }
        return false;
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        // Sub-pixel raw mouse polling is handled smoothly every frame inside updateAnimations(dt)!
    }

    private double getSubpixelMouseX() {
        return (Mouse.getX() * (double) this.width) / Math.max(1.0, (double) this.mc.displayWidth);
    }

    private double getSubpixelMouseY() {
        return this.height - (Mouse.getY() * (double) this.height) / Math.max(1.0, (double) this.mc.displayHeight);
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int state) {
        super.mouseMovedOrUp(mouseX, mouseY, state);
        if (state == 0 && dragging) {
            dragging = false;
            if (!dragMoved) {
                velX = 0.0;
                velZ = 0.0;
                long now = System.currentTimeMillis();
                boolean isDoubleClick = (now - lastLeftClickMs < 350L)
                    && Math.abs(mouseX - lastClickX) < 6
                    && Math.abs(mouseY - lastClickY) < 6;
                lastLeftClickMs = now;
                lastClickX = mouseX;
                lastClickY = mouseY;

                int bx = MathHelper.floor_double(screenToWorldX(mouseX));
                int bz = MathHelper.floor_double(screenToWorldZ(mouseY));

                if (gtnhBridge.onMapClick(isDoubleClick, mouseX, mouseY, bx, bz)) {
                    return;
                }

                if (isDoubleClick && mc.theWorld != null) {
                    int y = estimateSurfaceY(bx, bz);
                    mc.displayGuiScreen(new GuiWaypointEdit(this, waypointManager, null, bx, y, bz, mc.theWorld.provider.dimensionId));
                }
            }
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (gtnhBridge.hasActiveSearchBar() && searchField != null && searchField.isFocused()) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                searchField.setFocused(false);
                return;
            }
            searchField.textboxKeyTyped(typedChar, keyCode);
            String cur = searchField.getText();
            if (!cur.equals(lastSearchText)) {
                lastSearchText = cur;
                gtnhBridge.onSearchChanged(cur);
            }
            return;
        }

        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (contextMenuOpen) {
                contextMenuOpen = false;
                return;
            }
            requestSmoothClose();
            return;
        }
        if (keyCode == Keyboard.KEY_M) {
            requestSmoothClose();
            return;
        }

        if (!Keyboard.isRepeatEvent() && gtnhBridge.onKeyPressed(keyCode)) {
            return;
        }

        if (keyCode == Keyboard.KEY_EQUALS || keyCode == Keyboard.KEY_ADD) {
            triggerSmoothZoom(1.38, this.width * 0.5, this.height * 0.5);
        } else if (keyCode == Keyboard.KEY_MINUS || keyCode == Keyboard.KEY_SUBTRACT) {
            triggerSmoothZoom(1.0 / 1.38, this.width * 0.5, this.height * 0.5);
        } else if (keyCode == Keyboard.KEY_C && mc.thePlayer != null) {
            glideTargetX = mc.thePlayer.posX;
            glideTargetZ = mc.thePlayer.posZ;
            glidingToTarget = true;
        } else if (keyCode == Keyboard.KEY_U) {
            mc.displayGuiScreen(new GuiWaypoints(this, waypointManager));
        } else if (keyCode == Keyboard.KEY_Y) {
            mc.displayGuiScreen(new GuiMapSettings(this));
        }
    }

    @Override
    public void updateScreen() {
        if (searchField != null) {
            searchField.updateCursorCounter();
        }
    }

    private void requestSmoothClose() {
        if (closing) {
            // Second press during close animation closes immediately
            mc.displayGuiScreen(null);
            return;
        }
        closing = true;
        dragging = false;
        contextMenuOpen = false;
    }

    private static double easeOutCubic(double t) {
        double inv = 1.0 - Math.max(0.0, Math.min(1.0, t));
        return 1.0 - inv * inv * inv;
    }

    private void updateAnimations(double dt) {
        // Open / close transition progress
        if (closing) {
            openProgress -= dt / CLOSE_DURATION_SEC;
            if (openProgress <= 0.0) {
                openProgress = 0.0;
                mc.displayGuiScreen(null);
                return;
            }
        } else if (openProgress < 1.0) {
            openProgress = Math.min(1.0, openProgress + dt / OPEN_DURATION_SEC);
        }

        // 0. Sub-pixel raw framebuffer mouse dragging (3x smoother at GUI Scale 3x, zero stepping/stutter!)
        if (dragging) {
            if (!Mouse.isButtonDown(0)) {
                dragging = false;
            } else {
                double curRawX = getSubpixelMouseX();
                double curRawY = getSubpixelMouseY();
                double totalDx = curRawX - dragStartRawX;
                double totalDy = curRawY - dragStartRawY;
                if (Math.abs(totalDx) > 2.5 || Math.abs(totalDy) > 2.5) {
                    dragMoved = true;
                }
                if (dragMoved) {
                    centerX = dragStartCenterX - totalDx / currentZoom;
                    centerZ = dragStartCenterZ - totalDy / currentZoom;
                    double safeDt = Math.max(0.004, dt);
                    double instVx = -((curRawX - prevRawX) / currentZoom) / safeDt;
                    double instVz = -((curRawY - prevRawY) / currentZoom) / safeDt;
                    velX = velX * 0.55 + instVx * 0.45;
                    velZ = velZ * 0.55 + instVz * 0.45;
                }
                prevRawX = curRawX;
                prevRawY = curRawY;
            }
        }

        // 1. Silky-smooth cursor-anchored exponential zoom
        if (zoomAnimating) {
            double ease = 1.0 - Math.exp(-dt * 15.0);
            double logCur = Math.log(currentZoom);
            double logTar = Math.log(targetZoom);
            double nextLog = logCur + (logTar - logCur) * ease;
            currentZoom = Math.exp(nextLog);

            // Lock the cursor's world block right under the cursor throughout the zoom!
            centerX = zoomAnchorWorldX - (zoomAnchorScreenX - this.width * 0.5) / currentZoom;
            centerZ = zoomAnchorWorldZ - (zoomAnchorScreenY - this.height * 0.5) / currentZoom;

            if (Math.abs(targetZoom - currentZoom) / targetZoom < 0.002) {
                currentZoom = targetZoom;
                centerX = zoomAnchorWorldX - (zoomAnchorScreenX - this.width * 0.5) / currentZoom;
                centerZ = zoomAnchorWorldZ - (zoomAnchorScreenY - this.height * 0.5) / currentZoom;
                zoomAnimating = false;
            }
        }

        // 2. Smooth WASD / arrow continuous panning
        if (searchField == null || !searchField.isFocused()) {
            double speed = (360.0 / currentZoom) * dt;
            if (Keyboard.isKeyDown(Keyboard.KEY_W) || Keyboard.isKeyDown(Keyboard.KEY_UP)) {
                centerZ -= speed;
                glidingToTarget = false;
                zoomAnimating = false;
            }
            if (Keyboard.isKeyDown(Keyboard.KEY_S) || Keyboard.isKeyDown(Keyboard.KEY_DOWN)) {
                centerZ += speed;
                glidingToTarget = false;
                zoomAnimating = false;
            }
            if (Keyboard.isKeyDown(Keyboard.KEY_A) || Keyboard.isKeyDown(Keyboard.KEY_LEFT)) {
                centerX -= speed;
                glidingToTarget = false;
                zoomAnimating = false;
            }
            if (Keyboard.isKeyDown(Keyboard.KEY_D) || Keyboard.isKeyDown(Keyboard.KEY_RIGHT)) {
                centerX += speed;
                glidingToTarget = false;
                zoomAnimating = false;
            }
        }

        // 3. Smooth drag inertia after mouse release
        if (!dragging && !glidingToTarget && (Math.abs(velX) > 0.5 || Math.abs(velZ) > 0.5)) {
            centerX += velX * dt;
            centerZ += velZ * dt;
            double damp = Math.exp(-dt * 9.5);
            velX *= damp;
            velZ *= damp;
        }

        // 4. Smooth center-on-player glide
        if (glidingToTarget) {
            double ease = 1.0 - Math.exp(-dt * 12.0);
            centerX += (glideTargetX - centerX) * ease;
            centerZ += (glideTargetZ - centerZ) * ease;
            if (Math.hypot(glideTargetX - centerX, glideTargetZ - centerZ) < 0.15) {
                centerX = glideTargetX;
                centerZ = glideTargetZ;
                glidingToTarget = false;
            }
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        World world = mc.theWorld;
        EntityClientPlayerMP player = mc.thePlayer;
        if (world == null || player == null) return;

        long now = System.nanoTime();
        double dt = lastFrameNanos == 0L ? 0.016 : Math.min(0.08, (now - lastFrameNanos) * 1e-9);
        lastFrameNanos = now;

        worldCache.onRenderTick();
        updateAnimations(dt);
        if (closing && openProgress <= 0.0) return;

        double ease = easeOutCubic(openProgress);
        float uiAlpha = (float) ease;
        double revealScale = 0.84 + 0.16 * ease;

        double cxScreen = this.width * 0.5;
        double cyScreen = this.height * 0.5;

        try (GLStateGuard ignored = new GLStateGuard()) {
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GLDraw.ensureBlend();

            // 1. Smoothly fading dark canvas backdrop over the 3D world
            GLDraw.rect(0, 0, this.width, this.height, GLDraw.withAlpha(0x0A0D12, uiAlpha));

            // Map world layer with smooth zoom-in reveal transform
            GL11.glPushMatrix();
            if (revealScale < 0.9995) {
                GL11.glTranslated(cxScreen, cyScreen, 0.0);
                GL11.glScaled(revealScale, revealScale, 1.0);
                GL11.glTranslated(-cxScreen, -cyScreen, 0.0);
            }

            // 2. Explored Map Regions
            renderMapRegions(cxScreen, cyScreen, uiAlpha);

            // 3. Explored-only Overlays (Chunk Grid, GT 3x3 Ore Vein Grid, Slime Chunks)
            renderWorldOverlays(world, cxScreen, cyScreen);

            // 4. GTNH Navigator Layers (VisualProspecting Ore Veins, Fluids, Nodes, ServerUtilities Claims)
            gtnhBridge.renderFullscreenLayers(
                this, centerX, centerZ, cxScreen, cyScreen, currentZoom, this.width, this.height
            );

            // Hit-test Navigator layers right AFTER renderFullscreenLayers updated every step's screen bounds!
            if (!contextMenuOpen && !dragging && !closing) {
                gtnhBridge.onMouseMove(mouseX, mouseY);
            } else {
                gtnhBridge.onMouseMove(-9999, -9999);
            }

            // 5. Entity Radar (Players always crisp; mobs scale & fade smoothly at low zoom!)
            renderWorldEntities(player, cxScreen, cyScreen, partialTicks);

            // 6. Waypoints
            Waypoint hoveredWp = renderWorldWaypoints(world.provider.dimensionId, cxScreen, cyScreen, mouseX, mouseY);

            // 7. Crisp red-accent Xaero player arrow
            double pSx = worldToScreenX(player.posX, cxScreen);
            double pSy = worldToScreenY(player.posZ, cyScreen);
            drawPlayerChevron(pSx, pSy, player.rotationYaw - 180.0f);

            GL11.glPopMatrix();

            // 8. Minimalist Top-Center Floating Coordinates & Biome
            renderFloatingCoords(world, mouseX, mouseY);

            // 9. Minimalist Top-Right Compass Rose
            renderCompassRose(this.width - 20, 20);

            // 10. Minimalist Floating Icon Buttons
            String hoveredUiTip = renderUiIcons(mouseX, mouseY);

            // 11. Bottom-Left GTNH Navigator Layer Icons
            String hoveredLayerLabel = renderLayerToolbar(mouseX, mouseY);

            // 12. Bottom-Center subtle zoom readout + Search Bar
            renderBottomControls();

            // 13. Right-click Context Menu OR Hover Tooltip
            if (!closing) {
                if (contextMenuOpen) {
                    renderContextMenu(mouseX, mouseY);
                } else {
                    if (hoveredUiTip != null) {
                        drawHoveringText(Collections.singletonList(hoveredUiTip), mouseX, mouseY, this.fontRendererObj);
                    } else if (hoveredLayerLabel != null) {
                        drawHoveringText(Collections.singletonList(hoveredLayerLabel), mouseX, mouseY, this.fontRendererObj);
                    } else if (hoveredWp != null) {
                        List<String> tip = Arrays.asList(
                            "\u00a7f\u00a7l" + hoveredWp.name,
                            String.format("\u00a77X: %d  Y: %d  Z: %d (Dim %d)", hoveredWp.x, hoveredWp.y, hoveredWp.z, hoveredWp.dimensionId)
                        );
                        drawHoveringText(tip, mouseX, mouseY, this.fontRendererObj);
                    } else {
                        List<String> navTip = gtnhBridge.getHoveredTooltip();
                        if (!navTip.isEmpty()) {
                            drawHoveringText(navTip, mouseX, mouseY, this.fontRendererObj);
                        } else {
                            gtnhBridge.drawHoveredCustomTooltip(this.fontRendererObj, mouseX, mouseY, this.width, this.height);
                        }
                    }
                }
            }
        }
    }

    private void renderMapRegions(double cxScreen, double cyScreen, float alpha) {
        double minWorldX = screenToWorldX(0);
        double maxWorldX = screenToWorldX(this.width);
        double minWorldZ = screenToWorldZ(0);
        double maxWorldZ = screenToWorldZ(this.height);

        int minRx = Math.floorDiv(MathHelper.floor_double(minWorldX), MapRegion.SIZE);
        int maxRx = Math.floorDiv(MathHelper.floor_double(maxWorldX), MapRegion.SIZE);
        int minRz = Math.floorDiv(MathHelper.floor_double(minWorldZ), MapRegion.SIZE);
        int maxRz = Math.floorDiv(MathHelper.floor_double(maxWorldZ), MapRegion.SIZE);

        boolean linear = currentZoom < 1.25;
        GLDraw.ensureBlend();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0f, 1.0f, 1.0f, alpha);

        for (int rz = minRz; rz <= maxRz; rz++) {
            for (int rx = minRx; rx <= maxRx; rx++) {
                MapRegion region = worldCache.getRegionIfLoaded(rx, rz);
                if (region == null || !region.hasSurfaceData()) continue;

                region.getOrUpdateTexture(caveModeView, linear);

                double sx1 = worldToScreenX(rx * (double) MapRegion.SIZE, cxScreen);
                double sy1 = worldToScreenY(rz * (double) MapRegion.SIZE, cyScreen);
                double sx2 = sx1 + MapRegion.SIZE * currentZoom;
                double sy2 = sy1 + MapRegion.SIZE * currentZoom;

                Tessellator t = Tessellator.instance;
                t.startDrawingQuads();
                t.addVertexWithUV(sx1, sy2, 0.0, 0.0, 1.0);
                t.addVertexWithUV(sx2, sy2, 0.0, 1.0, 1.0);
                t.addVertexWithUV(sx2, sy1, 0.0, 1.0, 0.0);
                t.addVertexWithUV(sx1, sy1, 0.0, 0.0, 0.0);
                t.draw();
            }
        }
    }

    private void renderWorldOverlays(World world, double cxScreen, double cyScreen) {
        MapConfig cfg = MapConfig.get();
        if (!cfg.showChunkGrid && !cfg.showOreVeinGrid && !cfg.showSlimeChunks) return;

        int minCx = Math.floorDiv(MathHelper.floor_double(screenToWorldX(0)), 16);
        int maxCx = Math.floorDiv(MathHelper.floor_double(screenToWorldX(this.width)), 16);
        int minCz = Math.floorDiv(MathHelper.floor_double(screenToWorldZ(0)), 16);
        int maxCz = Math.floorDiv(MathHelper.floor_double(screenToWorldZ(this.height)), 16);

        GLDraw.ensureBlend();

        // Explored-only chunk fills
        if (currentZoom >= 0.45 && (cfg.showSlimeChunks || (cfg.showOreVeinGrid && cfg.highlightOreVeinCenter))) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                for (int cx = minCx; cx <= maxCx; cx++) {
                    if (!worldCache.isChunkExplored(cx, cz)) continue;
                    double sx1 = worldToScreenX(cx << 4, cxScreen);
                    double sy1 = worldToScreenY(cz << 4, cyScreen);
                    double sx2 = sx1 + 16.0 * currentZoom;
                    double sy2 = sy1 + 16.0 * currentZoom;
                    if (cfg.showSlimeChunks && ChunkScanner.isSlimeChunk(world, cx, cz)) {
                        GLDraw.rect(sx1, sy1, sx2, sy2, cfg.slimeChunkColor);
                    }
                    if (cfg.showOreVeinGrid && cfg.highlightOreVeinCenter && ChunkScanner.isOreVeinCenterChunk(cx, cz)) {
                        GLDraw.rect(sx1, sy1, sx2, sy2, cfg.oreVeinCenterColor);
                    }
                }
            }
        }

        GL11.glDisable(GL11.GL_TEXTURE_2D);

        if (cfg.showChunkGrid && currentZoom >= 0.55) {
            GL11.glLineWidth(1.0f);
            GLDraw.setColor(cfg.chunkGridColor);
            GL11.glBegin(GL11.GL_LINES);
            for (int cz = minCz; cz <= maxCz; cz++) {
                for (int cx = minCx; cx <= maxCx; cx++) {
                    if (!worldCache.isChunkExplored(cx, cz)) continue;
                    double x1 = worldToScreenX(cx << 4, cxScreen);
                    double y1 = worldToScreenY(cz << 4, cyScreen);
                    double x2 = x1 + 16.0 * currentZoom;
                    double y2 = y1 + 16.0 * currentZoom;
                    GL11.glVertex2d(x1, y1);
                    GL11.glVertex2d(x2, y1);
                    GL11.glVertex2d(x1, y1);
                    GL11.glVertex2d(x1, y2);
                }
            }
            GL11.glEnd();
        }

        // Clean thin semi-transparent GT 3x3 Ore Vein lines ONLY over explored chunks!
        if (cfg.showOreVeinGrid && currentZoom >= 0.30) {
            GL11.glLineWidth(1.0f);
            GLDraw.setColor(cfg.oreVeinGridColor);
            GL11.glBegin(GL11.GL_LINES);
            for (int cz = minCz; cz <= maxCz; cz++) {
                for (int cx = minCx; cx <= maxCx; cx++) {
                    if (!worldCache.isChunkExplored(cx, cz)) continue;
                    double x1 = worldToScreenX(cx << 4, cxScreen);
                    double y1 = worldToScreenY(cz << 4, cyScreen);
                    double x2 = x1 + 16.0 * currentZoom;
                    double y2 = y1 + 16.0 * currentZoom;
                    if (ChunkScanner.isOreVeinBorderChunk(cx)) {
                        GL11.glVertex2d(x1, y1);
                        GL11.glVertex2d(x1, y2);
                    }
                    if (ChunkScanner.isOreVeinBorderChunk(cz)) {
                        GL11.glVertex2d(x1, y1);
                        GL11.glVertex2d(x2, y1);
                    }
                }
            }
            GL11.glEnd();
        }

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private void renderWorldEntities(EntityClientPlayerMP player, double cxScreen, double cyScreen, float partialTicks) {
        if (!MapConfig.get().radarEnabled) return;
        List<RadarEntity> entities = radarManager.collectEntities(partialTicks);

        // Smoothly scale & fade out mobs below 0.75x zoom so distant overviews stay crystal clean!
        float mobAlpha = (float) Math.max(0.0, Math.min(1.0, (currentZoom - 0.40) / 0.35));
        float mobScale = (float) Math.max(0.65, Math.min(1.05, currentZoom * 0.85));

        for (int i = 0; i < entities.size(); i++) {
            RadarEntity re = entities.get(i);
            boolean isPlayer = re.category == EntityCategory.PLAYER;
            if (!isPlayer && mobAlpha <= 0.05f) continue;

            double sx = worldToScreenX(re.x, cxScreen);
            double sy = worldToScreenY(re.z, cyScreen);
            if (sx < -24 || sx > this.width + 24 || sy < -24 || sy > this.height + 24) continue;

            float scale = isPlayer ? 1.15f : mobScale;
            float alpha = isPlayer ? 1.0f : mobAlpha;
            EntityIconRenderer.renderEntity(re, sx, sy, player.posY, scale, alpha);
        }
    }

    private Waypoint renderWorldWaypoints(int dimId, double cxScreen, double cyScreen, int mouseX, int mouseY) {
        if (!MapConfig.get().showWaypointsOnWorldMap) return null;
        List<Waypoint> wps = waypointManager.getWaypointsForDimension(dimId);
        Waypoint hovered = null;

        for (int i = 0; i < wps.size(); i++) {
            Waypoint wp = wps.get(i);
            if (!wp.enabled) continue;
            double sx = worldToScreenX(wp.x + 0.5, cxScreen);
            double sy = worldToScreenY(wp.z + 0.5, cyScreen);
            if (sx < -20 || sx > this.width + 20 || sy < -20 || sy > this.height + 20) continue;

            boolean hit = Math.abs(mouseX - sx) <= 7.5 && Math.abs(mouseY - sy) <= 7.5;
            double hs = hit ? 6.8 : 5.6;

            // Sleek Xaero pin badge with pointer tail (matching screenshot #7's 'D' pin!)
            GLDraw.rect(sx - hs - 0.8, sy - hs - 0.8, sx + hs + 0.8, sy + hs + 0.8, 0xEE0A0D12);
            GLDraw.rect(sx - hs, sy - hs, sx + hs, sy + hs, wp.getArgb(242));

            String init = wp.initials != null ? wp.initials : "W";
            GLDraw.drawCenteredText(this.fontRendererObj, init, sx + 0.3, sy - 3.0, 0.78f, 0xFFFFFFFF);

            if (hit) {
                hovered = wp;
            }
        }
        return hovered;
    }

    private void renderFloatingCoords(World world, int mouseX, int mouseY) {
        int bx = MathHelper.floor_double(screenToWorldX(mouseX));
        int bz = MathHelper.floor_double(screenToWorldZ(mouseY));
        int by = estimateSurfaceY(bx, bz);

        String coordLine = String.format("X: %d  Y: %d  Z: %d", bx, by, bz);
        GLDraw.drawCenteredText(this.fontRendererObj, coordLine, this.width * 0.5, 7, 0.86f, 0xFFF8FAFC);

        BiomeGenBase biome = world.getBiomeGenForCoords(bx, bz);
        String sub = biome != null ? biome.biomeName : ("Dim " + world.provider.dimensionId);
        if (caveModeView) sub = sub + "  [Cave]";
        GLDraw.drawCenteredText(this.fontRendererObj, sub, this.width * 0.5, 17, 0.74f, 0xFFCBD5E1);
    }

    private void renderCompassRose(double cx, double cy) {
        // Minimalist Xaero compass cross in top-right (matching screenshot #7!)
        GLDraw.ensureBlend();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glLineWidth(1.0f);
        GLDraw.setColor(0x99E2E8F0);
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex2d(cx - 5, cy - 5);
        GL11.glVertex2d(cx + 5, cy + 5);
        GL11.glVertex2d(cx + 5, cy - 5);
        GL11.glVertex2d(cx - 5, cy + 5);
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);

        GLDraw.drawCenteredText(this.fontRendererObj, "N", cx, cy - 13, 0.70f, 0xFFF8FAFC);
        GLDraw.drawCenteredText(this.fontRendererObj, "S", cx, cy + 7, 0.70f, 0xFFCBD5E1);
        GLDraw.drawCenteredText(this.fontRendererObj, "W", cx - 10, cy - 3, 0.70f, 0xFFCBD5E1);
        GLDraw.drawCenteredText(this.fontRendererObj, "E", cx + 10, cy - 3, 0.70f, 0xFFCBD5E1);
    }

    private String renderUiIcons(int mouseX, int mouseY) {
        String hoveredTip = null;
        for (int i = 0; i < uiButtons.size(); i++) {
            IconButton b = uiButtons.get(i);
            boolean hover = b.hit(mouseX, mouseY);

            // True modern Xaero minimalism: borderless crisp icons at rest, sleek soft pill ONLY on hover/active!
            if (b.active) {
                GLDraw.rect(b.x, b.y, b.x + b.size, b.y + b.size, 0xAA2563EB);
            } else if (hover) {
                GLDraw.rect(b.x, b.y, b.x + b.size, b.y + b.size, 0x881E293B);
            }

            // Subtle dark drop-shadow behind vector icon so it's readable over bright snow & dark forest alike
            drawVectorIcon(b.id, b.x + b.size * 0.5 + 0.8, b.y + b.size * 0.5 + 0.8, 0xAA070A0F);
            int fg = b.active ? 0xFFFFFFFF : (hover ? 0xFFFFFFFF : 0xE6F1F5F9);
            drawVectorIcon(b.id, b.x + b.size * 0.5, b.y + b.size * 0.5, fg);

            if (hover) {
                hoveredTip = b.tooltip;
            }
        }
        return hoveredTip;
    }

    private void drawVectorIcon(int id, double cx, double cy, int color) {
        switch (id) {
            case BTN_SETTINGS:
                // Minimalist crisp gear ring + spokes
                GLDraw.ring(cx, cy, 2.5, 4.6, color, 16);
                GLDraw.rect(cx - 1.0, cy - 6.0, cx + 1.0, cy + 6.0, color);
                GLDraw.rect(cx - 6.0, cy - 1.0, cx + 6.0, cy + 1.0, color);
                GLDraw.circle(cx, cy, 2.0, 0.0, 0xFF0E131A, 12);
                break;
            case BTN_ZOOM_IN:
                GLDraw.rect(cx - 4.0, cy - 0.7, cx + 4.0, cy + 0.7, color);
                GLDraw.rect(cx - 0.7, cy - 4.0, cx + 0.7, cy + 4.0, color);
                break;
            case BTN_ZOOM_OUT:
                GLDraw.rect(cx - 4.0, cy - 0.7, cx + 4.0, cy + 0.7, color);
                break;
            case BTN_CENTER:
                GLDraw.ring(cx, cy, 3.2, 4.4, color, 16);
                GLDraw.circle(cx, cy, 1.3, 0.0, color, 10);
                break;
            case BTN_CAVE:
                GLDraw.diamond(cx, cy, 4.5, color);
                GLDraw.diamond(cx, cy, 2.2, 0xFF0E131A);
                break;
            case BTN_ORE_GRID:
                GLDraw.rect(cx - 4.5, cy - 2.0, cx + 4.5, cy - 1.1, color);
                GLDraw.rect(cx - 4.5, cy + 1.1, cx + 4.5, cy + 2.0, color);
                GLDraw.rect(cx - 2.0, cy - 4.5, cx - 1.1, cy + 4.5, color);
                GLDraw.rect(cx + 1.1, cy - 4.5, cx + 2.0, cy + 4.5, color);
                break;
            case BTN_CHUNK_GRID:
                GLDraw.rectOutline(cx - 4.5, cy - 4.5, cx + 4.5, cy + 4.5, 1.0, color);
                GLDraw.rect(cx - 4.5, cy - 0.4, cx + 4.5, cy + 0.4, color);
                GLDraw.rect(cx - 0.4, cy - 4.5, cx + 0.4, cy + 4.5, color);
                break;
            case BTN_WAYPOINTS:
                GLDraw.rect(cx - 3.5, cy - 4.5, cx + 3.5, cy + 1.5, color);
                GLDraw.diamond(cx, cy + 2.0, 2.4, color);
                break;
        }
    }

    private static final int NAV_BTN_SIZE = 26;
    private static final int NAV_BTN_GAP = 5;

    private String renderLayerToolbar(int mouseX, int mouseY) {
        List<IGtnhLayerBridge.LayerButtonInfo> buttons = gtnhBridge.getLayerButtons();
        if (buttons.isEmpty()) return null;

        // Comfortable larger bottom-left GTNH Navigator layer icons (26x26, 20px crisp icon!)
        int btnSize = NAV_BTN_SIZE;
        int gap = NAV_BTN_GAP;
        int startX = 10;
        int totalH = buttons.size() * (btnSize + gap);
        int startY = this.height - 14 - totalH;
        String hoveredText = null;

        for (int i = 0; i < buttons.size(); i++) {
            IGtnhLayerBridge.LayerButtonInfo b = buttons.get(i);
            int by = startY + i * (btnSize + gap);
            boolean hover = mouseX >= startX && mouseX <= startX + btnSize && mouseY >= by && mouseY <= by + btnSize;

            if (b.active) {
                GLDraw.rect(startX, by, startX + btnSize, by + btnSize, 0xAA2563EB);
                GLDraw.rect(startX, by, startX + 2.5, by + btnSize, 0xFF93C5FD);
            } else if (hover) {
                GLDraw.rect(startX, by, startX + btnSize, by + btnSize, 0x881E293B);
            }

            if (b.icon != null) {
                GLDraw.ensureBlend();
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                GL11.glColor4f(1.0f, 1.0f, 1.0f, b.active ? 1.0f : (hover ? 0.96f : 0.82f));
                Minecraft.getMinecraft().getTextureManager().bindTexture(b.icon);
                GLDraw.texturedQuad(startX + 3, by + 3, startX + btnSize - 3, by + btnSize - 3, 0, 0, 1, 1);
                GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
            } else {
                String ch = b.label.isEmpty() ? "L" : b.label.substring(0, 1).toUpperCase();
                GLDraw.drawCenteredText(this.fontRendererObj, ch, startX + btnSize * 0.5, by + 9, 0.95f, 0xFFFFFFFF);
            }

            if (hover) {
                hoveredText = b.label + (b.active ? " \u00a7a(ON)" : " \u00a77(OFF)");
            }
        }
        return hoveredText;
    }

    private boolean handleLayerToolbarClick(int mouseX, int mouseY) {
        List<IGtnhLayerBridge.LayerButtonInfo> buttons = gtnhBridge.getLayerButtons();
        if (buttons.isEmpty()) return false;
        int btnSize = NAV_BTN_SIZE;
        int gap = NAV_BTN_GAP;
        int startX = 10;
        int totalH = buttons.size() * (btnSize + gap);
        int startY = this.height - 14 - totalH;

        for (int i = 0; i < buttons.size(); i++) {
            int by = startY + i * (btnSize + gap);
            if (mouseX >= startX && mouseX <= startX + btnSize && mouseY >= by && mouseY <= by + btnSize) {
                buttons.get(i).toggle.run();
                return true;
            }
        }
        return false;
    }

    private void renderBottomControls() {
        boolean hasSearch = gtnhBridge.hasActiveSearchBar() && searchField != null;
        int zoomY = hasSearch ? (this.height - 42) : (this.height - 22);
        String zoomText = String.format("%.2fx", currentZoom);
        GLDraw.drawCenteredText(this.fontRendererObj, zoomText, this.width * 0.5, zoomY, 0.76f, 0xCCECF2F8);

        if (hasSearch) {
            GLDraw.rect(
                searchField.xPosition - 3, searchField.yPosition - 3,
                searchField.xPosition + searchField.width + 3, searchField.yPosition + searchField.height + 3,
                0xBB0B0F15
            );
            searchField.drawTextBox();
            GLDraw.ensureBlend();
            if (searchField.getText().isEmpty() && !searchField.isFocused()) {
                this.fontRendererObj.drawStringWithShadow(
                    "Search GTNH ores / fluids...",
                    searchField.xPosition + 4, searchField.yPosition + 4, 0xFF64748B
                );
            }
        }
    }

    private void renderContextMenu(int mouseX, int mouseY) {
        String[] items = {
            "Add Waypoint Here",
            "Teleport Here (/tp)",
            "Copy Coordinates",
            "Center on Player"
        };
        int w = 128;
        int h = items.length * 18 + 6;
        GLDraw.rect(contextMenuX, contextMenuY, contextMenuX + w, contextMenuY + h, 0xEE0D1117);
        GLDraw.rectOutline(contextMenuX, contextMenuY, contextMenuX + w, contextMenuY + h, 1.0, 0xAA3A7BD5);

        for (int i = 0; i < items.length; i++) {
            int iy = contextMenuY + 3 + i * 18;
            boolean hover = mouseX >= contextMenuX && mouseX <= contextMenuX + w && mouseY >= iy && mouseY < iy + 18;
            if (hover) {
                GLDraw.rect(contextMenuX + 2, iy, contextMenuX + w - 2, iy + 17, 0xBB2563EB);
            }
            this.fontRendererObj.drawStringWithShadow(items[i], contextMenuX + 8, iy + 5, 0xFFF1F5F9);
        }
    }

    private boolean handleContextMenuClick(int mouseX, int mouseY) {
        int w = 128;
        int h = 4 * 18 + 6;
        if (mouseX < contextMenuX || mouseX > contextMenuX + w || mouseY < contextMenuY || mouseY > contextMenuY + h) {
            return false;
        }
        int idx = (mouseY - (contextMenuY + 3)) / 18;
        int y = estimateSurfaceY(contextBlockX, contextBlockZ);

        switch (idx) {
            case 0:
                if (mc.theWorld != null) {
                    mc.displayGuiScreen(new GuiWaypointEdit(this, waypointManager, null, contextBlockX, y, contextBlockZ, mc.theWorld.provider.dimensionId));
                }
                return true;
            case 1:
                WaypointManager.teleportTo(contextBlockX, y, contextBlockZ);
                mc.displayGuiScreen(null);
                return true;
            case 2:
                try {
                    String coords = contextBlockX + " " + y + " " + contextBlockZ;
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(coords), null);
                    if (mc.thePlayer != null) {
                        mc.thePlayer.addChatMessage(new ChatComponentText("\u00a7b[Saifun's Map]\u00a7r Copied: " + coords));
                    }
                } catch (Throwable ignored) {}
                return true;
            case 3:
                if (mc.thePlayer != null) {
                    glideTargetX = mc.thePlayer.posX;
                    glideTargetZ = mc.thePlayer.posZ;
                    glidingToTarget = true;
                }
                return true;
            default:
                return false;
        }
    }

    private int estimateSurfaceY(int bx, int bz) {
        if (mc.theWorld != null && mc.theWorld.blockExists(bx, 64, bz)) {
            int h = mc.theWorld.getHeightValue(bx, bz);
            if (h > 0 && h < 255) return h + 1;
        }
        return mc.thePlayer != null ? MathHelper.floor_double(mc.thePlayer.posY) : 70;
    }

    private double screenToWorldX(double sx) {
        return centerX + (sx - this.width * 0.5) / currentZoom;
    }

    private double screenToWorldZ(double sy) {
        return centerZ + (sy - this.height * 0.5) / currentZoom;
    }

    private double worldToScreenX(double wx, double cxScreen) {
        return cxScreen + (wx - centerX) * currentZoom;
    }

    private double worldToScreenY(double wz, double cyScreen) {
        return cyScreen + (wz - centerZ) * currentZoom;
    }

    private static void drawPlayerChevron(double cx, double cy, float angleDeg) {
        GL11.glPushMatrix();
        GL11.glTranslated(cx, cy, 0.0);
        GL11.glRotatef(angleDeg, 0.0f, 0.0f, 1.0f);
        GLDraw.ensureBlend();
        GL11.glDisable(GL11.GL_TEXTURE_2D);

        // Dark silhouette
        GLDraw.setColor(0xEE0A0D12);
        GL11.glBegin(GL11.GL_TRIANGLES);
        GL11.glVertex2d(0.0, -6.0);
        GL11.glVertex2d(-4.5, 4.5);
        GL11.glVertex2d(0.0, 2.2);
        GL11.glVertex2d(0.0, -6.0);
        GL11.glVertex2d(0.0, 2.2);
        GL11.glVertex2d(4.5, 4.5);
        GL11.glEnd();

        // Crisp red Xaero World Map arrow (matching screenshot #7!)
        GLDraw.setColor(0xFFEF4444);
        GL11.glBegin(GL11.GL_TRIANGLES);
        GL11.glVertex2d(0.0, -4.6);
        GL11.glVertex2d(-3.3, 3.4);
        GL11.glVertex2d(0.0, 1.3);
        GL11.glVertex2d(0.0, -4.6);
        GL11.glVertex2d(0.0, 1.3);
        GL11.glVertex2d(3.3, 3.4);
        GL11.glEnd();

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
        GL11.glPopMatrix();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}

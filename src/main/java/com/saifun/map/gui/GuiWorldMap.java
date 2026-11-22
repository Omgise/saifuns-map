package com.saifun.map.gui;

import com.saifun.map.compat.IGtnhLayerBridge;
import com.saifun.map.config.MapConfig;
import com.saifun.map.engine.ChunkScanner;
import com.saifun.map.engine.MapRegion;
import com.saifun.map.engine.MapWorldCache;
import com.saifun.map.radar.EntityIconRenderer;
import com.saifun.map.radar.EntityRadarManager;
import com.saifun.map.radar.RadarEntity;
import com.saifun.map.render.GLStateGuard;
import com.saifun.map.waypoints.Waypoint;
import com.saifun.map.waypoints.WaypointManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.List;

/**
 * Fullscreen World Map in modern Xaero 24.x/25.x style with full GTNH Navigator parity
 * (VisualProspecting ore veins, underground fluids, Thaumcraft nodes, search bar, ServerUtilities claims),
 * unrestricted entity radar, waypoints, and right-click context menu.
 */
public class GuiWorldMap extends GuiScreen {

    private final MapWorldCache worldCache;
    private final ChunkScanner chunkScanner;
    private final EntityRadarManager radarManager;
    private final WaypointManager waypointManager;
    private final IGtnhLayerBridge gtnhBridge;

    private double centerX;
    private double centerZ;
    private double targetZoom = 1.0; // pixels per block
    private double currentZoom = 1.0;
    private boolean caveModeView;

    private boolean dragging = false;
    private int dragStartMouseX;
    private int dragStartMouseY;
    private double dragStartCenterX;
    private double dragStartCenterZ;
    private boolean dragMoved = false;

    private long lastLeftClickMs = 0L;
    private int lastClickX = 0;
    private int lastClickY = 0;

    // Right-click context menu
    private boolean contextMenuOpen = false;
    private int contextMenuX;
    private int contextMenuY;
    private int contextBlockX;
    private int contextBlockZ;

    // GTNH Navigator search bar (for VisualProspecting vein/fluid search)
    private GuiTextField searchField;
    private String lastSearchText = "";

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
        EntityClientPlayerMP player = mc.thePlayer;
        if (player != null && centerX == 0.0 && centerZ == 0.0) {
            centerX = player.posX;
            centerZ = player.posZ;
        }
        caveModeView = chunkScanner.isCaveModeActive();
        gtnhBridge.onWorldMapOpened();

        this.buttonList.clear();
        int bx = this.width - 62;
        this.buttonList.add(new GuiButton(1, bx, 6, 56, 18, "Center"));
        this.buttonList.add(new GuiButton(2, bx - 58, 6, 54, 18, caveModeView ? "Cave: ON" : "Surface"));
        this.buttonList.add(new GuiButton(3, bx - 122, 6, 60, 18, MapConfig.get().showOreVeinGrid ? "Veins: ON" : "Veins: OFF"));
        this.buttonList.add(new GuiButton(4, bx - 190, 6, 64, 18, "Waypoints"));
        this.buttonList.add(new GuiButton(5, bx - 248, 6, 54, 18, "Settings"));

        searchField = new GuiTextField(this.fontRendererObj, this.width / 2 - 110, this.height - 26, 220, 18);
        searchField.setMaxStringLength(64);
        searchField.setText(lastSearchText);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        gtnhBridge.onWorldMapClosed();
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        contextMenuOpen = false;
        switch (button.id) {
            case 1:
                if (mc.thePlayer != null) {
                    centerX = mc.thePlayer.posX;
                    centerZ = mc.thePlayer.posZ;
                }
                break;
            case 2:
                caveModeView = !caveModeView;
                button.displayString = caveModeView ? "Cave: ON" : "Surface";
                break;
            case 3:
                MapConfig.get().showOreVeinGrid = !MapConfig.get().showOreVeinGrid;
                MapConfig.save();
                button.displayString = MapConfig.get().showOreVeinGrid ? "Veins: ON" : "Veins: OFF";
                break;
            case 4:
                mc.displayGuiScreen(new GuiWaypoints(this, waypointManager));
                break;
            case 5:
                mc.displayGuiScreen(new GuiMapSettings());
                break;
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            int mx = Mouse.getEventX() * this.width / this.mc.displayWidth;
            int my = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;

            double beforeWorldX = screenToWorldX(mx);
            double beforeWorldZ = screenToWorldZ(my);

            if (wheel > 0) {
                targetZoom = Math.min(16.0, targetZoom * 1.28);
            } else {
                targetZoom = Math.max(0.125, targetZoom / 1.28);
            }

            // Keep block under cursor anchored while zooming
            currentZoom = targetZoom;
            double afterWorldX = screenToWorldX(mx);
            double afterWorldZ = screenToWorldZ(my);
            centerX += (beforeWorldX - afterWorldX);
            centerZ += (beforeWorldZ - afterWorldZ);
            contextMenuOpen = false;
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        // 1. Right-click context menu handling
        if (contextMenuOpen) {
            if (handleContextMenuClick(mouseX, mouseY)) {
                contextMenuOpen = false;
                return;
            }
            contextMenuOpen = false;
        }

        // 2. Search bar click
        if (gtnhBridge.hasActiveSearchBar() && searchField != null) {
            searchField.mouseClicked(mouseX, mouseY, mouseButton);
            if (searchField.isFocused()) return;
        }

        // 3. Left GTNH Navigator Layer Toolbar buttons
        if (mouseButton == 0 && handleLayerToolbarClick(mouseX, mouseY)) {
            return;
        }

        // 4. Top bar buttons
        if (mouseY <= 28) {
            super.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }

        // 5. Right click -> open context menu
        if (mouseButton == 1) {
            contextMenuOpen = true;
            contextMenuX = Math.min(mouseX, this.width - 132);
            contextMenuY = Math.min(mouseY, this.height - 96);
            contextBlockX = MathHelper.floor_double(screenToWorldX(mouseX));
            contextBlockZ = MathHelper.floor_double(screenToWorldZ(mouseY));
            return;
        }

        // 6. Left click -> begin drag & detect single/double click
        if (mouseButton == 0) {
            dragging = true;
            dragMoved = false;
            dragStartMouseX = mouseX;
            dragStartMouseY = mouseY;
            dragStartCenterX = centerX;
            dragStartCenterZ = centerZ;
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (dragging && clickedMouseButton == 0) {
            int dx = mouseX - dragStartMouseX;
            int dy = mouseY - dragStartMouseY;
            if (Math.abs(dx) > 3 || Math.abs(dy) > 3) {
                dragMoved = true;
            }
            centerX = dragStartCenterX - dx / currentZoom;
            centerZ = dragStartCenterZ - dy / currentZoom;
        }
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int state) {
        super.mouseMovedOrUp(mouseX, mouseY, state);
        if (state == 0 && dragging) {
            dragging = false;
            if (!dragMoved && mouseY > 28) {
                long now = System.currentTimeMillis();
                boolean isDoubleClick = (now - lastLeftClickMs < 350L)
                    && Math.abs(mouseX - lastClickX) < 6
                    && Math.abs(mouseY - lastClickY) < 6;
                lastLeftClickMs = now;
                lastClickX = mouseX;
                lastClickY = mouseY;

                int bx = MathHelper.floor_double(screenToWorldX(mouseX));
                int bz = MathHelper.floor_double(screenToWorldZ(mouseY));

                // Offer click to GTNH Navigator layers first (VisualProspecting veins / ServerUtilities claims!)
                if (gtnhBridge.onMapClick(isDoubleClick, mouseX, mouseY, bx, bz)) {
                    return;
                }

                // Double-click empty terrain -> quick add waypoint!
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

        if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_M) {
            mc.displayGuiScreen(null);
            return;
        }

        // Pass action keys (e.g. Delete on hovered VisualProspecting ore vein to mark depleted!) to Navigator
        if (gtnhBridge.onKeyPressed(keyCode)) {
            return;
        }

        // Smooth WASD / Arrow keyboard panning
        double panStep = 64.0 / currentZoom;
        if (keyCode == Keyboard.KEY_W || keyCode == Keyboard.KEY_UP) centerZ -= panStep;
        else if (keyCode == Keyboard.KEY_S || keyCode == Keyboard.KEY_DOWN) centerZ += panStep;
        else if (keyCode == Keyboard.KEY_A || keyCode == Keyboard.KEY_LEFT) centerX -= panStep;
        else if (keyCode == Keyboard.KEY_D || keyCode == Keyboard.KEY_RIGHT) centerX += panStep;
        else if (keyCode == Keyboard.KEY_EQUALS || keyCode == Keyboard.KEY_ADD) targetZoom = Math.min(16.0, targetZoom * 1.3);
        else if (keyCode == Keyboard.KEY_MINUS || keyCode == Keyboard.KEY_SUBTRACT) targetZoom = Math.max(0.125, targetZoom / 1.3);
    }

    @Override
    public void updateScreen() {
        if (searchField != null) {
            searchField.updateCursorCounter();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        World world = mc.theWorld;
        EntityClientPlayerMP player = mc.thePlayer;
        if (world == null || player == null) return;

        worldCache.onRenderTick();
        currentZoom += (targetZoom - currentZoom) * 0.35;
        if (Math.abs(currentZoom - targetZoom) < 0.002) currentZoom = targetZoom;

        double cxScreen = this.width * 0.5;
        double cyScreen = this.height * 0.5;

        // Notify Navigator of cursor position for tooltips/hover
        if (!contextMenuOpen) {
            gtnhBridge.onMouseMove(mouseX, mouseY);
        }

        try (GLStateGuard ignored = new GLStateGuard()) {
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

            // 1. Deep dark backdrop
            drawRect(0, 0, this.width, this.height, 0xFF0A0E14);

            // 2. Explored Map Regions (auto-triggers lazy async disk load as you pan!)
            renderMapRegions(cxScreen, cyScreen);

            // 3. Overlays (Slime Chunks, Chunk Grid, GTNH 3x3 Ore Vein Grid)
            renderWorldOverlays(world, cxScreen, cyScreen);

            // 4. GTNH Navigator Layers (VisualProspecting Ore Veins, Fluids, Thaumcraft Nodes, ServerUtilities Claims!)
            gtnhBridge.renderFullscreenLayers(
                this, centerX, centerZ, cxScreen, cyScreen, currentZoom, this.width, this.height
            );

            // 5. Entity Radar (All visible players & mobs, zero FairPlay limits)
            renderWorldEntities(player, cxScreen, cyScreen, partialTicks);

            // 6. Waypoints
            Waypoint hoveredWp = renderWorldWaypoints(world.provider.dimensionId, cxScreen, cyScreen, mouseX, mouseY);

            // 7. Player Marker
            double pSx = worldToScreenX(player.posX, cxScreen);
            double pSy = worldToScreenY(player.posZ, cyScreen);
            drawPlayerChevron(pSx, pSy, player.rotationYaw - 180.0f);

            // 8. Left-side GTNH Navigator Layer Toggle Toolbar
            String hoveredLayerLabel = renderLayerToolbar(mouseX, mouseY);

            // 9. Top translucent Xaero-style HUD Bar
            renderTopBar(world, mouseX, mouseY);

            // 10. Search Bar (when VisualProspecting / searchable Navigator layer is active)
            if (gtnhBridge.hasActiveSearchBar() && searchField != null) {
                drawRect(searchField.xPosition - 4, searchField.yPosition - 4, searchField.xPosition + searchField.width + 4, searchField.yPosition + searchField.height + 4, 0xCC0E1218);
                searchField.drawTextBox();
                if (searchField.getText().isEmpty() && !searchField.isFocused()) {
                    this.fontRendererObj.drawStringWithShadow("Search GTNH ores / fluids...", searchField.xPosition + 4, searchField.yPosition + 5, 0xFF64748B);
                }
            }

            // 11. Standard top buttons
            super.drawScreen(mouseX, mouseY, partialTicks);

            // 12. Right-click Context Menu
            if (contextMenuOpen) {
                renderContextMenu(mouseX, mouseY);
            } else {
                // 13. Tooltips (Layer toolbar, Waypoints, or Navigator steps!)
                if (hoveredLayerLabel != null) {
                    drawHoveringText(java.util.Collections.singletonList(hoveredLayerLabel), mouseX, mouseY, this.fontRendererObj);
                } else if (hoveredWp != null) {
                    List<String> tip = java.util.Arrays.asList(
                        "\u00a7f\u00a7l" + hoveredWp.name,
                        String.format("\u00a77X: %d  Y: %d  Z: %d (Dim %d)", hoveredWp.x, hoveredWp.y, hoveredWp.z, hoveredWp.dimensionId)
                    );
                    drawHoveringText(tip, mouseX, mouseY, this.fontRendererObj);
                } else {
                    List<String> navTip = gtnhBridge.getHoveredTooltip();
                    if (!navTip.isEmpty()) {
                        drawHoveringText(navTip, mouseX, mouseY, this.fontRendererObj);
                    }
                    gtnhBridge.drawHoveredCustomTooltip(this.fontRendererObj, mouseX, mouseY, this.width, this.height);
                }
            }
        }
    }

    private void renderMapRegions(double cxScreen, double cyScreen) {
        double minWorldX = screenToWorldX(0);
        double maxWorldX = screenToWorldX(this.width);
        double minWorldZ = screenToWorldZ(0);
        double maxWorldZ = screenToWorldZ(this.height);

        int minRx = Math.floorDiv(MathHelper.floor_double(minWorldX), MapRegion.SIZE);
        int maxRx = Math.floorDiv(MathHelper.floor_double(maxWorldX), MapRegion.SIZE);
        int minRz = Math.floorDiv(MathHelper.floor_double(minWorldZ), MapRegion.SIZE);
        int maxRz = Math.floorDiv(MathHelper.floor_double(maxWorldZ), MapRegion.SIZE);

        boolean linear = currentZoom < 1.2;
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);

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

        GL11.glDisable(GL11.GL_TEXTURE_2D);

        // Filled chunk tints only when zoomed close enough to stay readable & fast
        if (currentZoom >= 0.4 && (cfg.showSlimeChunks || (cfg.showOreVeinGrid && cfg.highlightOreVeinCenter))) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                for (int cx = minCx; cx <= maxCx; cx++) {
                    double sx1 = worldToScreenX(cx << 4, cxScreen);
                    double sy1 = worldToScreenY(cz << 4, cyScreen);
                    double sx2 = sx1 + 16.0 * currentZoom;
                    double sy2 = sy1 + 16.0 * currentZoom;
                    if (cfg.showSlimeChunks && ChunkScanner.isSlimeChunk(world, cx, cz)) {
                        drawQuad(sx1, sy1, sx2, sy2, cfg.slimeChunkColor);
                    }
                    if (cfg.showOreVeinGrid && cfg.highlightOreVeinCenter && ChunkScanner.isOreVeinCenterChunk(cx, cz)) {
                        drawQuad(sx1, sy1, sx2, sy2, cfg.oreVeinCenterColor);
                    }
                }
            }
        }

        if (cfg.showChunkGrid && currentZoom >= 0.5) {
            GL11.glLineWidth(1.0f);
            setColor(cfg.chunkGridColor);
            GL11.glBegin(GL11.GL_LINES);
            for (int cx = minCx; cx <= maxCx + 1; cx++) {
                double sx = worldToScreenX(cx << 4, cxScreen);
                GL11.glVertex2d(sx, 0);
                GL11.glVertex2d(sx, this.height);
            }
            for (int cz = minCz; cz <= maxCz + 1; cz++) {
                double sy = worldToScreenY(cz << 4, cyScreen);
                GL11.glVertex2d(0, sy);
                GL11.glVertex2d(this.width, sy);
            }
            GL11.glEnd();
        }

        if (cfg.showOreVeinGrid && currentZoom >= 0.25) {
            GL11.glLineWidth(1.5f);
            setColor(cfg.oreVeinGridColor);
            GL11.glBegin(GL11.GL_LINES);
            for (int cx = minCx; cx <= maxCx + 1; cx++) {
                if (!ChunkScanner.isOreVeinBorderChunk(cx)) continue;
                double sx = worldToScreenX(cx << 4, cxScreen);
                GL11.glVertex2d(sx, 0);
                GL11.glVertex2d(sx, this.height);
            }
            for (int cz = minCz; cz <= maxCz + 1; cz++) {
                if (!ChunkScanner.isOreVeinBorderChunk(cz)) continue;
                double sy = worldToScreenY(cz << 4, cyScreen);
                GL11.glVertex2d(0, sy);
                GL11.glVertex2d(this.width, sy);
            }
            GL11.glEnd();
            GL11.glLineWidth(1.0f);
        }

        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    private void renderWorldEntities(EntityClientPlayerMP player, double cxScreen, double cyScreen, float partialTicks) {
        if (!MapConfig.get().radarEnabled) return;
        List<RadarEntity> entities = radarManager.collectEntities(partialTicks);
        for (int i = 0; i < entities.size(); i++) {
            RadarEntity re = entities.get(i);
            double sx = worldToScreenX(re.x, cxScreen);
            double sy = worldToScreenY(re.z, cyScreen);
            if (sx < -24 || sx > this.width + 24 || sy < -24 || sy > this.height + 24) continue;
            EntityIconRenderer.renderEntity(re, sx, sy, player.posY, 1.15f);
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

            GL11.glDisable(GL11.GL_TEXTURE_2D);
            drawQuad(sx - 7.5, sy - 7.5, sx + 7.5, sy + 7.5, 0xEE0D1117);
            drawQuad(sx - 6.0, sy - 6.0, sx + 6.0, sy + 6.0, wp.getArgb(240));
            GL11.glEnable(GL11.GL_TEXTURE_2D);

            String init = wp.initials != null ? wp.initials : "W";
            int iw = this.fontRendererObj.getStringWidth(init);
            this.fontRendererObj.drawStringWithShadow(init, (int) sx - iw / 2, (int) sy - 4, 0xFFFFFFFF);

            if (Math.abs(mouseX - sx) <= 8 && Math.abs(mouseY - sy) <= 8) {
                hovered = wp;
            }
        }
        return hovered;
    }

    private String renderLayerToolbar(int mouseX, int mouseY) {
        List<IGtnhLayerBridge.LayerButtonInfo> buttons = gtnhBridge.getLayerButtons();
        if (buttons.isEmpty()) return null;

        int startX = 8;
        int startY = 40;
        int btnSize = 22;
        int gap = 4;
        String hoveredText = null;

        for (int i = 0; i < buttons.size(); i++) {
            IGtnhLayerBridge.LayerButtonInfo b = buttons.get(i);
            int by = startY + i * (btnSize + gap);
            boolean hover = mouseX >= startX && mouseX <= startX + btnSize && mouseY >= by && mouseY <= by + btnSize;

            int bg = b.active ? 0xDD1D4ED8 : (hover ? 0xDD253042 : 0xCC0E1218);
            int border = b.active ? 0xFF60A5FA : 0x66475569;

            drawRect(startX - 1, by - 1, startX + btnSize + 1, by + btnSize + 1, border);
            drawRect(startX, by, startX + btnSize, by + btnSize, bg);

            if (b.icon != null) {
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                GL11.glColor4f(1.0f, 1.0f, 1.0f, b.active ? 1.0f : 0.7f);
                Minecraft.getMinecraft().getTextureManager().bindTexture(b.icon);
                Tessellator t = Tessellator.instance;
                t.startDrawingQuads();
                t.addVertexWithUV(startX + 3, by + btnSize - 3, 0, 0, 1);
                t.addVertexWithUV(startX + btnSize - 3, by + btnSize - 3, 0, 1, 1);
                t.addVertexWithUV(startX + btnSize - 3, by + 3, 0, 1, 0);
                t.addVertexWithUV(startX + 3, by + 3, 0, 0, 0);
                t.draw();
            } else {
                String ch = b.label.isEmpty() ? "L" : b.label.substring(0, 1).toUpperCase();
                drawCenteredString(this.fontRendererObj, ch, startX + btnSize / 2, by + 7, 0xFFFFFFFF);
            }

            if (hover) {
                hoveredText = b.label + (b.active ? " \u00a7a(ON)" : " \u00a77(OFF)");
            }
        }
        return hoveredText;
    }

    private boolean handleLayerToolbarClick(int mouseX, int mouseY) {
        List<IGtnhLayerBridge.LayerButtonInfo> buttons = gtnhBridge.getLayerButtons();
        int startX = 8;
        int startY = 40;
        int btnSize = 22;
        int gap = 4;

        for (int i = 0; i < buttons.size(); i++) {
            int by = startY + i * (btnSize + gap);
            if (mouseX >= startX && mouseX <= startX + btnSize && mouseY >= by && mouseY <= by + btnSize) {
                buttons.get(i).toggle.run();
                return true;
            }
        }
        return false;
    }

    private void renderTopBar(World world, int mouseX, int mouseY) {
        drawRect(0, 0, this.width, 30, 0xD80B0F15);
        drawRect(0, 29, this.width, 30, 0x883A7BD5);

        int bx = MathHelper.floor_double(screenToWorldX(mouseX));
        int bz = MathHelper.floor_double(screenToWorldZ(mouseY));
        int cx = bx >> 4;
        int cz = bz >> 4;
        int vx = Math.floorDiv(cx, 3);
        int vz = Math.floorDiv(cz, 3);

        String info = String.format(
            "\u00a7bSaifun's Map\u00a7r  |  Dim %d  |  X: %d  Z: %d  (Chunk %d, %d | Vein %d, %d)  |  Zoom %.2fx",
            world.provider.dimensionId, bx, bz, cx, cz, vx, vz, currentZoom
        );
        this.fontRendererObj.drawStringWithShadow(info, 10, 11, 0xFFE2E8F0);
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
        drawRect(contextMenuX - 1, contextMenuY - 1, contextMenuX + w + 1, contextMenuY + h + 1, 0xFF3A7BD5);
        drawRect(contextMenuX, contextMenuY, contextMenuX + w, contextMenuY + h, 0xF00E1218);

        for (int i = 0; i < items.length; i++) {
            int iy = contextMenuY + 3 + i * 18;
            boolean hover = mouseX >= contextMenuX && mouseX <= contextMenuX + w && mouseY >= iy && mouseY < iy + 18;
            if (hover) {
                drawRect(contextMenuX + 2, iy, contextMenuX + w - 2, iy + 17, 0xCC2563EB);
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
                    centerX = mc.thePlayer.posX;
                    centerZ = mc.thePlayer.posZ;
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
        GL11.glDisable(GL11.GL_TEXTURE_2D);

        setColor(0xEE0B0E14);
        GL11.glBegin(GL11.GL_TRIANGLES);
        GL11.glVertex2d(0.0, -6.5);
        GL11.glVertex2d(-5.0, 5.0);
        GL11.glVertex2d(0.0, 2.5);
        GL11.glVertex2d(0.0, -6.5);
        GL11.glVertex2d(0.0, 2.5);
        GL11.glVertex2d(5.0, 5.0);
        GL11.glEnd();

        setColor(0xFFFFFFFF);
        GL11.glBegin(GL11.GL_TRIANGLES);
        GL11.glVertex2d(0.0, -5.0);
        GL11.glVertex2d(-3.8, 3.8);
        GL11.glVertex2d(0.0, 1.5);
        GL11.glVertex2d(0.0, -5.0);
        GL11.glVertex2d(0.0, 1.5);
        GL11.glVertex2d(3.8, 3.8);
        GL11.glEnd();

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glPopMatrix();
    }

    private static void drawQuad(double x1, double y1, double x2, double y2, int argb) {
        setColor(argb);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2d(x1, y2);
        GL11.glVertex2d(x2, y2);
        GL11.glVertex2d(x2, y1);
        GL11.glVertex2d(x1, y1);
        GL11.glEnd();
    }

    private static void setColor(int argb) {
        float a = ((argb >>> 24) & 0xFF) / 255.0f;
        float r = ((argb >>> 16) & 0xFF) / 255.0f;
        float g = ((argb >>> 8) & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;
        GL11.glColor4f(r, g, b, a);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}

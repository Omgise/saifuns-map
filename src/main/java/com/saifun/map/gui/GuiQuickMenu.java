package com.saifun.map.gui;

import com.saifun.map.compat.IGtnhLayerBridge;
import com.saifun.map.config.MapConfig;
import com.saifun.map.engine.ChunkScanner;
import com.saifun.map.engine.MapWorldCache;
import com.saifun.map.input.MapKeybinds;
import com.saifun.map.radar.EntityRadarManager;
import com.saifun.map.render.GLDraw;
import com.saifun.map.render.MinimapRenderer;
import com.saifun.map.util.Lang;
import com.saifun.map.waypoints.WaypointManager;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.MathHelper;
import org.lwjgl.input.Keyboard;

/**
 * Compact single-key Quick Actions popup menu for toggling every map control
 * without occupying extra keyboard bindings, with live minimap preview in the corner.
 */
public class GuiQuickMenu extends GuiScreen {

    private static final int ID_MINIMAP = 1;
    private static final int ID_SHAPE = 2;
    private static final int ID_CAVE = 3;
    private static final int ID_ENLARGE = 4;
    private static final int ID_ORE_GRID = 5;
    private static final int ID_CHUNK_GRID = 6;
    private static final int ID_RADAR = 7;
    private static final int ID_SLIME = 8;
    private static final int ID_WAYPOINTS_3D = 9;
    private static final int ID_LOCK_NORTH = 10;

    private static final int ID_OPEN_WORLDMAP = 20;
    private static final int ID_OPEN_WAYPOINTS = 21;
    private static final int ID_ADD_WAYPOINT = 22;
    private static final int ID_OPEN_SETTINGS = 23;
    private static final int ID_CLOSE = 24;

    private final MapWorldCache worldCache;
    private final ChunkScanner chunkScanner;
    private final EntityRadarManager radarManager;
    private final WaypointManager waypointManager;
    private final IGtnhLayerBridge gtnhBridge;
    private final MinimapRenderer minimapRenderer;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    public GuiQuickMenu(
        MapWorldCache worldCache,
        ChunkScanner chunkScanner,
        EntityRadarManager radarManager,
        WaypointManager waypointManager,
        IGtnhLayerBridge gtnhBridge,
        MinimapRenderer minimapRenderer
    ) {
        this.worldCache = worldCache;
        this.chunkScanner = chunkScanner;
        this.radarManager = radarManager;
        this.waypointManager = waypointManager;
        this.gtnhBridge = gtnhBridge;
        this.minimapRenderer = minimapRenderer;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void initGui() {
        super.initGui();
        rebuildButtons();
    }

    @SuppressWarnings("unchecked")
    private void rebuildButtons() {
        this.buttonList.clear();
        MapConfig cfg = MapConfig.get();

        panelW = Math.min(340, this.width - 24);
        panelH = 222;
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        int pad = 12;
        int gap = 8;
        int colW = (panelW - pad * 2 - gap) / 2;
        int leftX = panelX + pad;
        int rightX = leftX + colW + gap;
        int rowH = 20;
        int rowStep = 24;
        int y = panelY + 32;

        // Row 1: Minimap HUD | Shape
        addToggle(ID_MINIMAP, leftX, y, colW, rowH,
            Lang.tr("saifunsmap.settings.minimap_enabled") + ": " + Lang.onOff(cfg.minimapEnabled),
            cfg.minimapEnabled);
        addToggle(ID_SHAPE, rightX, y, colW, rowH,
            Lang.tr("saifunsmap.settings.shape") + ": " + Lang.tr(cfg.circularMinimap ? "saifunsmap.settings.shape.circle" : "saifunsmap.settings.shape.square"),
            cfg.circularMinimap);
        y += rowStep;

        // Row 2: Cave Mode | Enlarge Minimap
        String caveKey = cfg.caveMode == MapConfig.CaveMode.ON
            ? "saifunsmap.settings.cave_mode.on"
            : (cfg.caveMode == MapConfig.CaveMode.OFF ? "saifunsmap.settings.cave_mode.off" : "saifunsmap.settings.cave_mode.auto");
        addToggle(ID_CAVE, leftX, y, colW, rowH,
            Lang.tr("saifunsmap.settings.cave_mode") + ": " + Lang.tr(caveKey),
            cfg.caveMode == MapConfig.CaveMode.ON);
        addToggle(ID_ENLARGE, rightX, y, colW, rowH,
            Lang.tr("saifunsmap.quickmenu.enlarge") + ": " + Lang.onOff(minimapRenderer.isEnlarged()),
            minimapRenderer.isEnlarged());
        y += rowStep;

        // Row 3: GT Ore Vein Grid | Chunk Grid
        addToggle(ID_ORE_GRID, leftX, y, colW, rowH,
            Lang.tr("saifunsmap.settings.ore_grid") + ": " + Lang.onOff(cfg.showOreVeinGrid),
            cfg.showOreVeinGrid);
        addToggle(ID_CHUNK_GRID, rightX, y, colW, rowH,
            Lang.tr("saifunsmap.settings.chunk_grid") + ": " + Lang.onOff(cfg.showChunkGrid),
            cfg.showChunkGrid);
        y += rowStep;

        // Row 4: Entity Radar | Slime Chunks
        addToggle(ID_RADAR, leftX, y, colW, rowH,
            Lang.tr("saifunsmap.settings.radar_enabled") + ": " + Lang.onOff(cfg.radarEnabled),
            cfg.radarEnabled);
        addToggle(ID_SLIME, rightX, y, colW, rowH,
            Lang.tr("saifunsmap.settings.slime_chunks") + ": " + Lang.onOff(cfg.showSlimeChunks),
            cfg.showSlimeChunks);
        y += rowStep;

        // Row 5: 3D Waypoints | Lock North
        addToggle(ID_WAYPOINTS_3D, leftX, y, colW, rowH,
            Lang.tr("saifunsmap.settings.waypoints_world") + ": " + Lang.onOff(cfg.showWaypointsInWorld),
            cfg.showWaypointsInWorld);
        addToggle(ID_LOCK_NORTH, rightX, y, colW, rowH,
            Lang.tr("saifunsmap.settings.lock_north") + ": " + Lang.onOff(cfg.lockNorth),
            cfg.lockNorth);
        y += rowStep + 6;

        // Navigation row (2x2 clean action bar)
        int actionY = y;
        this.buttonList.add(new ModernButton(ID_OPEN_WORLDMAP, leftX, actionY, colW, rowH,
            Lang.tr("saifunsmap.quickmenu.worldmap", keyLabel(MapKeybinds.KEY_WORLD_MAP.getKeyCode()))).setAccent(true));
        this.buttonList.add(new ModernButton(ID_OPEN_SETTINGS, rightX, actionY, colW, rowH,
            Lang.tr("saifunsmap.quickmenu.settings")));

        actionY += rowStep;
        int thirdGap = 6;
        int navW = (panelW - pad * 2 - thirdGap * 2) / 3;
        this.buttonList.add(new ModernButton(ID_OPEN_WAYPOINTS, leftX, actionY, navW, rowH,
            Lang.tr("saifunsmap.quickmenu.waypoints", keyLabel(MapKeybinds.KEY_WAYPOINTS_MENU.getKeyCode()))));
        this.buttonList.add(new ModernButton(ID_ADD_WAYPOINT, leftX + navW + thirdGap, actionY, navW, rowH,
            Lang.tr("saifunsmap.quickmenu.add_wp", keyLabel(MapKeybinds.KEY_ADD_WAYPOINT.getKeyCode()))));
        this.buttonList.add(new ModernButton(ID_CLOSE, leftX + (navW + thirdGap) * 2, actionY,
            panelW - pad * 2 - (navW + thirdGap) * 2, rowH, Lang.tr("saifunsmap.common.close")));
    }

    @SuppressWarnings("unchecked")
    private void addToggle(int id, int x, int y, int w, int h, String label, boolean active) {
        this.buttonList.add(new ModernButton(id, x, y, w, h, label).setAccent(active));
    }

    private static String keyLabel(int keyCode) {
        if (keyCode == Keyboard.KEY_NONE) return "-";
        String name = Keyboard.getKeyName(keyCode);
        return name != null ? name : "-";
    }

    @Override
    protected void actionPerformed(GuiButton btn) {
        MapConfig cfg = MapConfig.get();
        switch (btn.id) {
            case ID_MINIMAP:
                cfg.minimapEnabled = !cfg.minimapEnabled;
                MapConfig.save();
                rebuildButtons();
                break;
            case ID_SHAPE:
                cfg.circularMinimap = !cfg.circularMinimap;
                MapConfig.save();
                rebuildButtons();
                break;
            case ID_CAVE:
                MapConfig.CaveMode[] vals = MapConfig.CaveMode.values();
                cfg.caveMode = vals[(cfg.caveMode.ordinal() + 1) % vals.length];
                MapConfig.save();
                if (cfg.caveMode == MapConfig.CaveMode.ON) {
                    chunkScanner.triggerCaveRescan();
                }
                rebuildButtons();
                break;
            case ID_ENLARGE:
                minimapRenderer.toggleEnlarged();
                rebuildButtons();
                break;
            case ID_ORE_GRID:
                cfg.showOreVeinGrid = !cfg.showOreVeinGrid;
                MapConfig.save();
                rebuildButtons();
                break;
            case ID_CHUNK_GRID:
                cfg.showChunkGrid = !cfg.showChunkGrid;
                MapConfig.save();
                rebuildButtons();
                break;
            case ID_RADAR:
                cfg.radarEnabled = !cfg.radarEnabled;
                MapConfig.save();
                rebuildButtons();
                break;
            case ID_SLIME:
                cfg.showSlimeChunks = !cfg.showSlimeChunks;
                MapConfig.save();
                rebuildButtons();
                break;
            case ID_WAYPOINTS_3D:
                cfg.showWaypointsInWorld = !cfg.showWaypointsInWorld;
                MapConfig.save();
                rebuildButtons();
                break;
            case ID_LOCK_NORTH:
                cfg.lockNorth = !cfg.lockNorth;
                MapConfig.save();
                rebuildButtons();
                break;
            case ID_OPEN_WORLDMAP:
                mc.displayGuiScreen(new GuiWorldMap(worldCache, chunkScanner, radarManager, waypointManager, gtnhBridge));
                break;
            case ID_OPEN_WAYPOINTS:
                mc.displayGuiScreen(new GuiWaypoints(null, waypointManager));
                break;
            case ID_ADD_WAYPOINT:
                if (mc.thePlayer != null && mc.theWorld != null) {
                    mc.displayGuiScreen(new GuiWaypointEdit(
                        null,
                        waypointManager,
                        null,
                        MathHelper.floor_double(mc.thePlayer.posX),
                        MathHelper.floor_double(mc.thePlayer.posY),
                        MathHelper.floor_double(mc.thePlayer.posZ),
                        mc.theWorld.provider.dimensionId
                    ));
                }
                break;
            case ID_OPEN_SETTINGS:
                mc.displayGuiScreen(new GuiMapSettings(this));
                break;
            case ID_CLOSE:
                mc.displayGuiScreen(null);
                break;
            default:
                break;
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        int quickKey = MapKeybinds.KEY_QUICK_MENU.getKeyCode();
        if (keyCode == Keyboard.KEY_ESCAPE || (quickKey != Keyboard.KEY_NONE && keyCode == quickKey)) {
            mc.displayGuiScreen(null);
            return;
        }
        int mapKey = MapKeybinds.KEY_WORLD_MAP.getKeyCode();
        if (mapKey != Keyboard.KEY_NONE && keyCode == mapKey) {
            mc.displayGuiScreen(new GuiWorldMap(worldCache, chunkScanner, radarManager, waypointManager, gtnhBridge));
            return;
        }
        int wpKey = MapKeybinds.KEY_WAYPOINTS_MENU.getKeyCode();
        if (wpKey != Keyboard.KEY_NONE && keyCode == wpKey) {
            mc.displayGuiScreen(new GuiWaypoints(null, waypointManager));
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // Subtle floating panel without full-screen darkening so the live minimap stays crisp
        GLDraw.rect(panelX - 4, panelY - 4, panelX + panelW + 4, panelY + panelH + 4, 0x55000000);
        GLDraw.rect(panelX, panelY, panelX + panelW, panelY + panelH, 0xEC0C1119);
        GLDraw.rect(panelX, panelY, panelX + panelW, panelY + 24, 0xFF131B28);
        GLDraw.rect(panelX, panelY + 24, panelX + panelW, panelY + 25, 0x993B82F6);
        GLDraw.rectOutline(panelX, panelY, panelX + panelW, panelY + panelH, 1.0, 0xAA334155);

        // Divider above navigation buttons
        int divY = panelY + 153;
        GLDraw.rect(panelX + 12, divY, panelX + panelW - 12, divY + 1, 0x55334155);

        this.fontRendererObj.drawStringWithShadow(
            Lang.tr("saifunsmap.quickmenu.title"),
            panelX + 12,
            panelY + 8,
            0xFFFFFFFF
        );
        String hint = keyLabel(MapKeybinds.KEY_QUICK_MENU.getKeyCode()) + " / ESC";
        int hw = this.fontRendererObj.getStringWidth(hint);
        this.fontRendererObj.drawStringWithShadow(hint, panelX + panelW - hw - 10, panelY + 8, 0xFF64748B);

        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}

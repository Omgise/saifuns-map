package com.saifun.map.gui;

import com.saifun.map.ClientProxy;
import com.saifun.map.SaifunsMapMod;
import com.saifun.map.config.MapConfig;
import com.saifun.map.engine.BlockColorResolver;
import com.saifun.map.engine.MapPngExporter;
import com.saifun.map.render.GLDraw;
import com.saifun.map.util.Lang;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ChatComponentText;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.Supplier;

/**
 * Modern translucent settings GUI with smooth interactive sliders, tabbed categories,
 * full RU/EN localization, and real-time minimap HUD preview behind the panel.
 */
public class GuiMapSettings extends GuiScreen {

    private enum Tab {
        MINIMAP("saifunsmap.settings.tab.minimap"),
        ENGINE("saifunsmap.settings.tab.engine"),
        RADAR("saifunsmap.settings.tab.radar"),
        OVERLAYS("saifunsmap.settings.tab.overlays");

        final String langKey;
        Tab(String langKey) { this.langKey = langKey; }
    }

    private interface SettingItem {
        GuiButton build(int id, int x, int y, int w, int h);
        default void onClick() {}
    }

    private static final class ToggleItem implements SettingItem {
        final String labelKey;
        final Supplier<String> valueSupplier;
        final Runnable onClick;
        final boolean danger;

        ToggleItem(String labelKey, Supplier<String> valueSupplier, Runnable onClick, boolean danger) {
            this.labelKey = labelKey;
            this.valueSupplier = valueSupplier;
            this.onClick = onClick;
            this.danger = danger;
        }

        @Override
        public GuiButton build(int id, int x, int y, int w, int h) {
            String val = valueSupplier != null ? valueSupplier.get() : null;
            String text = (val != null && !val.isEmpty())
                ? (Lang.tr(labelKey) + ": " + val)
                : Lang.tr(labelKey);
            ModernButton btn = new ModernButton(id, x, y, w, h, text);
            if (danger) btn.setAccent(true);
            return btn;
        }

        @Override
        public void onClick() {
            if (onClick != null) onClick.run();
        }
    }

    private static final class SliderItem implements SettingItem {
        final String labelKey;
        final double min, max, step;
        final Supplier<Double> getter;
        final DoubleFunction<String> formatter;
        final DoubleConsumer onChange;
        final Runnable onRelease;

        SliderItem(
            String labelKey,
            double min, double max, double step,
            Supplier<Double> getter,
            DoubleFunction<String> formatter,
            DoubleConsumer onChange,
            Runnable onRelease
        ) {
            this.labelKey = labelKey;
            this.min = min;
            this.max = max;
            this.step = step;
            this.getter = getter;
            this.formatter = formatter;
            this.onChange = onChange;
            this.onRelease = onRelease;
        }

        @Override
        public GuiButton build(int id, int x, int y, int w, int h) {
            return new ModernSlider(
                id, x, y, w, h,
                Lang.tr(labelKey),
                min, max, step,
                getter.get(),
                formatter,
                onChange,
                onRelease
            );
        }
    }

    private final GuiScreen parent;
    private Tab activeTab = Tab.MINIMAP;
    private final List<SettingItem> currentItems = new ArrayList<>();
    private boolean confirmClearMap = false;
    private boolean confirmResetDefaults = false;

    public GuiMapSettings() {
        this(null);
    }

    public GuiMapSettings(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        rebuildButtons();
    }

    private int computePanelX(int panelW) {
        int centered = (this.width - panelW) / 2;
        // On narrower screens, nudge the panel slightly away from the minimap corner so live preview stays unobstructed!
        MapConfig cfg = MapConfig.get();
        if (activeTab == Tab.MINIMAP && cfg.minimapEnabled) {
            int clearMargin = cfg.minimapSize + cfg.offsetX + 18;
            if (cfg.minimapCorner == MapConfig.Corner.TOP_RIGHT || cfg.minimapCorner == MapConfig.Corner.BOTTOM_RIGHT) {
                if (centered + panelW > this.width - clearMargin) {
                    return Math.max(8, this.width - clearMargin - panelW);
                }
            } else {
                if (centered < clearMargin) {
                    return Math.min(this.width - panelW - 8, clearMargin);
                }
            }
        }
        return centered;
    }

    private void rebuildButtons() {
        this.buttonList.clear();
        this.currentItems.clear();

        int panelW = 356;
        int panelX = computePanelX(panelW);
        int panelY = Math.max(18, (this.height - 248) / 2);

        Tab[] tabs = Tab.values();
        int tabW = panelW / tabs.length;
        for (int i = 0; i < tabs.length; i++) {
            boolean isCurrent = tabs[i] == activeTab;
            ModernButton tb = new ModernButton(100 + i, panelX + i * tabW, panelY + 24, tabW - 2, 18, Lang.tr(tabs[i].langKey))
                .setSelected(isCurrent);
            tb.enabled = !isCurrent;
            this.buttonList.add(tb);
        }

        populateItems();

        int cols = 2;
        int colW = (panelW - 14) / cols;
        int startY = panelY + 50;
        for (int i = 0; i < currentItems.size(); i++) {
            SettingItem item = currentItems.get(i);
            int col = i % cols;
            int row = i / cols;
            int bx = panelX + 4 + col * (colW + 6);
            int by = startY + row * 23;
            this.buttonList.add(item.build(200 + i, bx, by, colW, 19));
        }

        // Footer buttons: Reset Defaults + Done & Save
        String resetLabel = confirmResetDefaults
            ? Lang.tr("saifunsmap.settings.reset_confirm")
            : Lang.tr("saifunsmap.settings.reset_defaults");
        this.buttonList.add(new ModernButton(98, panelX + 4, panelY + 222, 134, 20, resetLabel));
        this.buttonList.add(new ModernButton(99, panelX + panelW - 134, panelY + 222, 130, 20, Lang.tr("saifunsmap.common.done")).setAccent(true));
    }

    private void populateItems() {
        MapConfig cfg = MapConfig.get();

        switch (activeTab) {
            case MINIMAP:
                addToggle("saifunsmap.settings.minimap_enabled", () -> Lang.onOff(cfg.minimapEnabled),
                    () -> cfg.minimapEnabled = !cfg.minimapEnabled);
                addToggle("saifunsmap.settings.shape",
                    () -> Lang.tr(cfg.circularMinimap ? "saifunsmap.settings.shape.circle" : "saifunsmap.settings.shape.square"),
                    () -> cfg.circularMinimap = !cfg.circularMinimap);

                addSlider("saifunsmap.settings.size", 56, 220, 4,
                    () -> (double) cfg.minimapSize,
                    v -> ((int) Math.round(v)) + "px",
                    v -> cfg.minimapSize = (int) Math.round(v),
                    MapConfig::save);
                addSlider("saifunsmap.settings.zoom", 0.5, 4.0, 0.05,
                    () -> (double) cfg.minimapZoom,
                    v -> String.format("%.2fx", v),
                    v -> cfg.minimapZoom = (float) v,
                    MapConfig::save);

                addToggle("saifunsmap.settings.corner", () -> formatCorner(cfg.minimapCorner), () -> {
                    MapConfig.Corner[] vals = MapConfig.Corner.values();
                    cfg.minimapCorner = vals[(cfg.minimapCorner.ordinal() + 1) % vals.length];
                });
                addSlider("saifunsmap.settings.opacity", 0.35, 1.0, 0.05,
                    () -> (double) cfg.minimapOpacity,
                    v -> ((int) Math.round(v * 100.0)) + "%",
                    v -> cfg.minimapOpacity = (float) v,
                    MapConfig::save);

                addToggle("saifunsmap.settings.lock_north", () -> Lang.onOff(cfg.lockNorth),
                    () -> cfg.lockNorth = !cfg.lockNorth);
                addToggle("saifunsmap.settings.frame", () -> formatFrame(cfg.frameStyle), () -> {
                    MapConfig.FrameStyle[] vals = MapConfig.FrameStyle.values();
                    cfg.frameStyle = vals[(cfg.frameStyle.ordinal() + 1) % vals.length];
                });

                addToggle("saifunsmap.settings.compass", () -> Lang.onOff(cfg.showCompass),
                    () -> cfg.showCompass = !cfg.showCompass);
                addToggle("saifunsmap.settings.info_bar", () -> Lang.onOff(cfg.showInfoBar),
                    () -> cfg.showInfoBar = !cfg.showInfoBar);
                addToggle("saifunsmap.settings.info_bg", () -> Lang.onOff(cfg.showInfoBarBackground),
                    () -> cfg.showInfoBarBackground = !cfg.showInfoBarBackground);
                addToggle("saifunsmap.settings.biome_info", () -> Lang.onOff(cfg.showBiome),
                    () -> cfg.showBiome = !cfg.showBiome);
                addToggle("saifunsmap.settings.time_light", () -> Lang.onOff(cfg.showTimeAndLight),
                    () -> cfg.showTimeAndLight = !cfg.showTimeAndLight);
                break;

            case ENGINE:
                addToggle("saifunsmap.settings.cave_mode", () -> formatCaveMode(cfg.caveMode), () -> {
                    MapConfig.CaveMode[] vals = MapConfig.CaveMode.values();
                    cfg.caveMode = vals[(cfg.caveMode.ordinal() + 1) % vals.length];
                    if (cfg.caveMode == MapConfig.CaveMode.ON && SaifunsMapMod.proxy instanceof ClientProxy) {
                        ((ClientProxy) SaifunsMapMod.proxy).getChunkScanner().triggerCaveRescan();
                    }
                });
                addSlider("saifunsmap.settings.cave_slice", 2, 16, 1,
                    () -> (double) cfg.caveSliceHeight,
                    v -> "+" + ((int) Math.round(v)),
                    v -> cfg.caveSliceHeight = (int) Math.round(v),
                    MapConfig::save);

                addToggle("saifunsmap.settings.terrain_shading", () -> Lang.onOff(cfg.terrainShading), () -> {
                    cfg.terrainShading = !cfg.terrainShading;
                    triggerReshade();
                });
                addToggle("saifunsmap.settings.biome_colors", () -> Lang.onOff(cfg.biomeColors), () -> {
                    cfg.biomeColors = !cfg.biomeColors;
                    BlockColorResolver.clearCache();
                });
                addToggle("saifunsmap.settings.water_trans", () -> Lang.onOff(cfg.waterTransparency), () -> {
                    cfg.waterTransparency = !cfg.waterTransparency;
                    triggerReshade();
                });
                addSlider("saifunsmap.settings.scan_budget", 2, 24, 2,
                    () -> (double) cfg.chunksScannedPerTick,
                    v -> ((int) Math.round(v)) + "/t",
                    v -> cfg.chunksScannedPerTick = (int) Math.round(v),
                    MapConfig::save);

                addToggle("saifunsmap.settings.waypoints_world", () -> Lang.onOff(cfg.showWaypointsInWorld),
                    () -> cfg.showWaypointsInWorld = !cfg.showWaypointsInWorld);
                addSlider("saifunsmap.settings.waypoint_scale", 0.5, 1.8, 0.05,
                    () -> (double) cfg.waypointScale,
                    v -> String.format("%.2fx", v),
                    v -> cfg.waypointScale = (float) v,
                    MapConfig::save);

                addToggle("saifunsmap.settings.waypoint_names", () -> Lang.onOff(cfg.alwaysShowWaypointNames),
                    () -> cfg.alwaysShowWaypointNames = !cfg.alwaysShowWaypointNames);
                addToggle("saifunsmap.settings.waypoint_beams", () -> Lang.onOff(cfg.showWaypointBeam),
                    () -> cfg.showWaypointBeam = !cfg.showWaypointBeam);
                addToggle("saifunsmap.settings.auto_death", () -> Lang.onOff(cfg.autoDeathWaypoints),
                    () -> cfg.autoDeathWaypoints = !cfg.autoDeathWaypoints);
                addToggle("saifunsmap.settings.disk_cache", () -> Lang.onOff(cfg.diskCacheEnabled),
                    () -> cfg.diskCacheEnabled = !cfg.diskCacheEnabled);

                currentItems.add(new ToggleItem(
                    confirmClearMap ? "saifunsmap.settings.clear_map_confirm" : "saifunsmap.settings.clear_map",
                    null,
                    () -> {
                        if (!confirmClearMap) {
                            confirmClearMap = true;
                        } else {
                            confirmClearMap = false;
                            if (SaifunsMapMod.proxy instanceof ClientProxy) {
                                ClientProxy cp = (ClientProxy) SaifunsMapMod.proxy;
                                cp.getWorldCache().clearCurrentDimensionMapData();
                                cp.getChunkScanner().reset();
                                if (mc.thePlayer != null) {
                                    mc.thePlayer.addChatMessage(new ChatComponentText(
                                        "\u00a7b[Saifun's Map]\u00a7r " + Lang.tr("saifunsmap.chat.cleared_map")
                                    ));
                                }
                            }
                        }
                    },
                    confirmClearMap
                ));
                currentItems.add(new ToggleItem(
                    "saifunsmap.settings.export_png",
                    null,
                    () -> {
                        if (SaifunsMapMod.proxy instanceof ClientProxy) {
                            MapPngExporter.exportCurrentDimensionAsync(((ClientProxy) SaifunsMapMod.proxy).getWorldCache());
                        }
                    },
                    false
                ));
                break;

            case RADAR:
                addToggle("saifunsmap.settings.radar_enabled", () -> Lang.onOff(cfg.radarEnabled),
                    () -> cfg.radarEnabled = !cfg.radarEnabled);
                addToggle("saifunsmap.settings.radar_style", () -> formatRadarStyle(cfg.radarIconStyle), () -> {
                    MapConfig.RadarIconStyle[] vals = MapConfig.RadarIconStyle.values();
                    cfg.radarIconStyle = vals[(cfg.radarIconStyle.ordinal() + 1) % vals.length];
                });
                addSlider("saifunsmap.settings.radar_scale", 0.6, 1.8, 0.05,
                    () -> (double) cfg.radarIconScale,
                    v -> String.format("%.2fx", v),
                    v -> cfg.radarIconScale = (float) v,
                    MapConfig::save);
                addSlider("saifunsmap.settings.radar_max", 32, 256, 16,
                    () -> (double) cfg.maxRadarEntities,
                    v -> String.valueOf((int) Math.round(v)),
                    v -> cfg.maxRadarEntities = (int) Math.round(v),
                    MapConfig::save);

                addToggle("saifunsmap.settings.radar_players", () -> Lang.onOff(cfg.showPlayers),
                    () -> cfg.showPlayers = !cfg.showPlayers);
                addToggle("saifunsmap.settings.radar_player_names", () -> Lang.onOff(cfg.showPlayerNames),
                    () -> cfg.showPlayerNames = !cfg.showPlayerNames);
                addToggle("saifunsmap.settings.radar_hostiles", () -> Lang.onOff(cfg.showHostileMobs),
                    () -> cfg.showHostileMobs = !cfg.showHostileMobs);
                addToggle("saifunsmap.settings.radar_passives", () -> Lang.onOff(cfg.showPassiveMobs),
                    () -> cfg.showPassiveMobs = !cfg.showPassiveMobs);
                addToggle("saifunsmap.settings.radar_tamed", () -> Lang.onOff(cfg.showTamedMobs),
                    () -> cfg.showTamedMobs = !cfg.showTamedMobs);
                addToggle("saifunsmap.settings.radar_bosses", () -> Lang.onOff(cfg.showBossMobs),
                    () -> cfg.showBossMobs = !cfg.showBossMobs);
                addToggle("saifunsmap.settings.radar_items", () -> Lang.onOff(cfg.showItems),
                    () -> cfg.showItems = !cfg.showItems);
                addToggle("saifunsmap.settings.radar_height", () -> Lang.onOff(cfg.showHeightIndicator),
                    () -> cfg.showHeightIndicator = !cfg.showHeightIndicator);
                break;

            case OVERLAYS:
                addToggle("saifunsmap.settings.nav_bridge", () -> Lang.onOff(cfg.navigatorEnabled),
                    () -> cfg.navigatorEnabled = !cfg.navigatorEnabled);
                addToggle("saifunsmap.settings.nav_minimap", () -> Lang.onOff(cfg.showNavigatorOnMinimap),
                    () -> cfg.showNavigatorOnMinimap = !cfg.showNavigatorOnMinimap);
                addToggle("saifunsmap.settings.nav_worldmap", () -> Lang.onOff(cfg.showNavigatorOnWorldMap),
                    () -> cfg.showNavigatorOnWorldMap = !cfg.showNavigatorOnWorldMap);
                addToggle("saifunsmap.settings.ore_grid", () -> Lang.onOff(cfg.showOreVeinGrid),
                    () -> cfg.showOreVeinGrid = !cfg.showOreVeinGrid);
                addToggle("saifunsmap.settings.ore_center", () -> Lang.onOff(cfg.highlightOreVeinCenter),
                    () -> cfg.highlightOreVeinCenter = !cfg.highlightOreVeinCenter);
                addToggle("saifunsmap.settings.chunk_grid", () -> Lang.onOff(cfg.showChunkGrid),
                    () -> cfg.showChunkGrid = !cfg.showChunkGrid);
                addToggle("saifunsmap.settings.slime_chunks", () -> Lang.onOff(cfg.showSlimeChunks),
                    () -> cfg.showSlimeChunks = !cfg.showSlimeChunks);
                addToggle("saifunsmap.settings.wp_minimap", () -> Lang.onOff(cfg.showWaypointsOnMinimap),
                    () -> cfg.showWaypointsOnMinimap = !cfg.showWaypointsOnMinimap);
                addToggle("saifunsmap.settings.wp_worldmap", () -> Lang.onOff(cfg.showWaypointsOnWorldMap),
                    () -> cfg.showWaypointsOnWorldMap = !cfg.showWaypointsOnWorldMap);
                break;
        }
    }

    private void addToggle(String labelKey, Supplier<String> val, Runnable onClick) {
        currentItems.add(new ToggleItem(labelKey, val, onClick, false));
    }

    private void addSlider(
        String labelKey,
        double min, double max, double step,
        Supplier<Double> getter,
        DoubleFunction<String> formatter,
        DoubleConsumer onChange,
        Runnable onRelease
    ) {
        currentItems.add(new SliderItem(labelKey, min, max, step, getter, formatter, onChange, onRelease));
    }

    private static String formatCorner(MapConfig.Corner c) {
        switch (c) {
            case TOP_LEFT: return Lang.tr("saifunsmap.settings.corner.top_left");
            case BOTTOM_RIGHT: return Lang.tr("saifunsmap.settings.corner.bottom_right");
            case BOTTOM_LEFT: return Lang.tr("saifunsmap.settings.corner.bottom_left");
            default: return Lang.tr("saifunsmap.settings.corner.top_right");
        }
    }

    private static String formatFrame(MapConfig.FrameStyle f) {
        switch (f) {
            case MINIMAL: return Lang.tr("saifunsmap.settings.frame.minimal");
            case NONE: return Lang.tr("saifunsmap.settings.frame.none");
            default: return Lang.tr("saifunsmap.settings.frame.modern");
        }
    }

    private static String formatCaveMode(MapConfig.CaveMode m) {
        switch (m) {
            case ON: return Lang.tr("saifunsmap.settings.cave_mode.on");
            case OFF: return Lang.tr("saifunsmap.settings.cave_mode.off");
            default: return Lang.tr("saifunsmap.settings.cave_mode.auto");
        }
    }

    private static String formatRadarStyle(MapConfig.RadarIconStyle s) {
        switch (s) {
            case DIAMONDS: return Lang.tr("saifunsmap.settings.radar_style.diamonds");
            case DOTS: return Lang.tr("saifunsmap.settings.radar_style.dots");
            default: return Lang.tr("saifunsmap.settings.radar_style.heads");
        }
    }

    private static void triggerReshade() {
        if (SaifunsMapMod.proxy instanceof ClientProxy) {
            ((ClientProxy) SaifunsMapMod.proxy).getWorldCache().reshadeAllLoaded();
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button instanceof ModernSlider) {
            return;
        }
        if (button.id == 99) {
            MapConfig.save();
            this.mc.displayGuiScreen(parent);
            return;
        }
        if (button.id == 98) {
            confirmClearMap = false;
            if (!confirmResetDefaults) {
                confirmResetDefaults = true;
                rebuildButtons();
                return;
            }
            confirmResetDefaults = false;
            MapConfig.resetToDefaults();
            BlockColorResolver.clearCache();
            triggerReshade();
            if (mc.thePlayer != null) {
                mc.thePlayer.addChatMessage(new ChatComponentText(
                    "\u00a7b[Saifun's Map]\u00a7r " + Lang.tr("saifunsmap.chat.reset_defaults")
                ));
            }
            rebuildButtons();
            return;
        }
        if (button.id >= 100 && button.id < 100 + Tab.values().length) {
            confirmClearMap = false;
            confirmResetDefaults = false;
            activeTab = Tab.values()[button.id - 100];
            rebuildButtons();
            return;
        }
        int idx = button.id - 200;
        if (idx >= 0 && idx < currentItems.size()) {
            confirmResetDefaults = false;
            SettingItem item = currentItems.get(idx);
            if (!(item instanceof ToggleItem && ("saifunsmap.settings.clear_map".equals(((ToggleItem) item).labelKey)
                || "saifunsmap.settings.clear_map_confirm".equals(((ToggleItem) item).labelKey)))) {
                confirmClearMap = false;
            }
            item.onClick();
            MapConfig.save();
            rebuildButtons();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        int panelW = 356;
        int panelH = 248;
        int panelX = computePanelX(panelW);
        int panelY = Math.max(18, (this.height - panelH) / 2);

        // Translucent modern panel leaving the live minimap HUD visible behind!
        GLDraw.ensureBlend();
        GLDraw.rect(panelX - 6, panelY - 4, panelX + panelW + 6, panelY + panelH + 4, 0xDF0B0F16);
        GLDraw.rectOutline(panelX - 6, panelY - 4, panelX + panelW + 6, panelY + panelH + 4, 1.0, 0x88334155);
        GLDraw.rect(panelX - 6, panelY - 4, panelX + panelW + 6, panelY - 2, 0xFF3B82F6);

        drawCenteredString(this.fontRendererObj, Lang.tr("saifunsmap.settings.title"), panelX + panelW / 2, panelY + 6, 0xFFF8FAFC);

        if (activeTab == Tab.MINIMAP && MapConfig.get().minimapEnabled) {
            String hint = "\u00a7a\u25cf \u00a77" + Lang.tr("saifunsmap.settings.preview_hint");
            GLDraw.drawCenteredText(this.fontRendererObj, hint, panelX + panelW / 2.0, panelY + 211, 0.72f, 0xFF94A3B8);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == org.lwjgl.input.Keyboard.KEY_ESCAPE) {
            MapConfig.save();
            this.mc.displayGuiScreen(parent);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}

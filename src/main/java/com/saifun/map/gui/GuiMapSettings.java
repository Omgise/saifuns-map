package com.saifun.map.gui;

import com.saifun.map.ClientProxy;
import com.saifun.map.SaifunsMapMod;
import com.saifun.map.config.MapConfig;
import com.saifun.map.render.GLDraw;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import java.util.ArrayList;
import java.util.List;

/**
 * Modern translucent Xaero-style settings GUI with tabbed categories and live HUD preview.
 */
public class GuiMapSettings extends GuiScreen {

    private enum Tab {
        MINIMAP("Minimap"),
        ENGINE("Engine & Cave"),
        RADAR("Entity Radar"),
        OVERLAYS("GTNH Overlays");

        final String title;
        Tab(String title) { this.title = title; }
    }

    private static class OptionEntry {
        final String label;
        final java.util.function.Supplier<String> valueSupplier;
        final Runnable onClick;

        OptionEntry(String label, java.util.function.Supplier<String> valueSupplier, Runnable onClick) {
            this.label = label;
            this.valueSupplier = valueSupplier;
            this.onClick = onClick;
        }
    }

    private Tab activeTab = Tab.MINIMAP;
    private final List<OptionEntry> currentOptions = new ArrayList<>();

    @Override
    public void initGui() {
        rebuildButtons();
    }

    private void rebuildButtons() {
        this.buttonList.clear();
        this.currentOptions.clear();

        int panelW = 340;
        int panelX = (this.width - panelW) / 2;
        int panelY = Math.max(24, (this.height - 230) / 2);

        // Top tab bar (sleek ModernButtons)
        Tab[] tabs = Tab.values();
        int tabW = panelW / tabs.length;
        for (int i = 0; i < tabs.length; i++) {
            boolean isCurrent = tabs[i] == activeTab;
            ModernButton tb = new ModernButton(100 + i, panelX + i * tabW, panelY + 24, tabW - 2, 18, tabs[i].title)
                .setSelected(isCurrent);
            tb.enabled = !isCurrent;
            this.buttonList.add(tb);
        }

        populateOptions();

        int cols = 2;
        int colW = (panelW - 14) / cols;
        int startY = panelY + 50;
        for (int i = 0; i < currentOptions.size(); i++) {
            OptionEntry opt = currentOptions.get(i);
            int col = i % cols;
            int row = i / cols;
            int bx = panelX + 4 + col * (colW + 6);
            int by = startY + row * 23;
            this.buttonList.add(new ModernButton(200 + i, bx, by, colW, 19, opt.label + ": " + opt.valueSupplier.get()));
        }

        // Done button
        this.buttonList.add(new ModernButton(999, panelX + panelW / 2 - 64, panelY + 206, 128, 20, "Done & Save").setAccent(true));
    }

    private void populateOptions() {
        MapConfig cfg = MapConfig.get();
        switch (activeTab) {
            case MINIMAP:
                addBool("Minimap HUD", () -> cfg.minimapEnabled, () -> cfg.minimapEnabled = !cfg.minimapEnabled);
                addOption("Shape", () -> cfg.circularMinimap ? "Circle" : "Square", () -> cfg.circularMinimap = !cfg.circularMinimap);
                addOption("Corner", () -> cfg.minimapCorner.name(), () -> {
                    MapConfig.Corner[] vals = MapConfig.Corner.values();
                    cfg.minimapCorner = vals[(cfg.minimapCorner.ordinal() + 1) % vals.length];
                });
                addOption("Size", () -> cfg.minimapSize + "px", () -> {
                    int[] sizes = { 64, 80, 96, 112, 128, 148, 176, 208 };
                    int next = sizes[0];
                    for (int s : sizes) {
                        if (s > cfg.minimapSize) {
                            next = s;
                            break;
                        }
                    }
                    cfg.minimapSize = next;
                });
                addOption("Zoom", () -> String.format("%.1fx", cfg.minimapZoom), () -> {
                    cfg.minimapZoom = cfg.minimapZoom >= 6.0f ? 1.0f : cfg.minimapZoom + 0.5f;
                });
                addBool("Lock North", () -> cfg.lockNorth, () -> cfg.lockNorth = !cfg.lockNorth);
                addOption("Frame", () -> cfg.frameStyle.name(), () -> {
                    MapConfig.FrameStyle[] vals = MapConfig.FrameStyle.values();
                    cfg.frameStyle = vals[(cfg.frameStyle.ordinal() + 1) % vals.length];
                });
                addBool("Compass", () -> cfg.showCompass, () -> cfg.showCompass = !cfg.showCompass);
                addBool("Info Bar", () -> cfg.showInfoBar, () -> cfg.showInfoBar = !cfg.showInfoBar);
                addBool("Info Bar Box", () -> cfg.showInfoBarBackground, () -> cfg.showInfoBarBackground = !cfg.showInfoBarBackground);
                addBool("Biome & Time", () -> cfg.showBiome, () -> {
                    cfg.showBiome = !cfg.showBiome;
                    cfg.showTimeAndLight = cfg.showBiome;
                });
                break;

            case ENGINE:
                addOption("Cave Mode", () -> cfg.caveMode.name(), () -> {
                    MapConfig.CaveMode[] vals = MapConfig.CaveMode.values();
                    cfg.caveMode = vals[(cfg.caveMode.ordinal() + 1) % vals.length];
                });
                addBool("Terrain Shading", () -> cfg.terrainShading, () -> {
                    cfg.terrainShading = !cfg.terrainShading;
                    triggerReshade();
                });
                addBool("Biome Colors", () -> cfg.biomeColors, () -> cfg.biomeColors = !cfg.biomeColors);
                addBool("Water Depth", () -> cfg.waterTransparency, () -> {
                    cfg.waterTransparency = !cfg.waterTransparency;
                    triggerReshade();
                });
                addOption("Chunks / Tick", () -> String.valueOf(cfg.chunksScannedPerTick), () -> {
                    cfg.chunksScannedPerTick = cfg.chunksScannedPerTick >= 16 ? 2 : cfg.chunksScannedPerTick + 2;
                });
                addBool("Disk Region Cache", () -> cfg.diskCacheEnabled, () -> cfg.diskCacheEnabled = !cfg.diskCacheEnabled);
                break;

            case RADAR:
                addBool("Entity Radar", () -> cfg.radarEnabled, () -> cfg.radarEnabled = !cfg.radarEnabled);
                addOption("Icon Style", () -> cfg.radarIconStyle.name(), () -> {
                    MapConfig.RadarIconStyle[] vals = MapConfig.RadarIconStyle.values();
                    cfg.radarIconStyle = vals[(cfg.radarIconStyle.ordinal() + 1) % vals.length];
                });
                addBool("Players (Always)", () -> cfg.showPlayers, () -> cfg.showPlayers = !cfg.showPlayers);
                addBool("Player Names", () -> cfg.showPlayerNames, () -> cfg.showPlayerNames = !cfg.showPlayerNames);
                addBool("Hostile Mobs", () -> cfg.showHostileMobs, () -> cfg.showHostileMobs = !cfg.showHostileMobs);
                addBool("Passive Mobs", () -> cfg.showPassiveMobs, () -> cfg.showPassiveMobs = !cfg.showPassiveMobs);
                addBool("Tamed Mobs", () -> cfg.showTamedMobs, () -> cfg.showTamedMobs = !cfg.showTamedMobs);
                addBool("Boss Mobs", () -> cfg.showBossMobs, () -> cfg.showBossMobs = !cfg.showBossMobs);
                addBool("Items", () -> cfg.showItems, () -> cfg.showItems = !cfg.showItems);
                addBool("Height Carets", () -> cfg.showHeightIndicator, () -> cfg.showHeightIndicator = !cfg.showHeightIndicator);
                addOption("Icon Scale", () -> String.format("%.2fx", cfg.radarIconScale), () -> {
                    cfg.radarIconScale = cfg.radarIconScale >= 1.5f ? 0.75f : cfg.radarIconScale + 0.25f;
                });
                addBool("Server Long-Range", () -> cfg.serverPlayerSyncEnabled, () -> cfg.serverPlayerSyncEnabled = !cfg.serverPlayerSyncEnabled);
                break;

            case OVERLAYS:
                addBool("GT Ore Vein Grid (3x3)", () -> cfg.showOreVeinGrid, () -> cfg.showOreVeinGrid = !cfg.showOreVeinGrid);
                addBool("Vein Center Tint", () -> cfg.highlightOreVeinCenter, () -> cfg.highlightOreVeinCenter = !cfg.highlightOreVeinCenter);
                addBool("Chunk Grid (16x16)", () -> cfg.showChunkGrid, () -> cfg.showChunkGrid = !cfg.showChunkGrid);
                addBool("Slime Chunks", () -> cfg.showSlimeChunks, () -> cfg.showSlimeChunks = !cfg.showSlimeChunks);
                addBool("GTNH Navigator API", () -> cfg.navigatorEnabled, () -> cfg.navigatorEnabled = !cfg.navigatorEnabled);
                addBool("Navigator on Minimap", () -> cfg.showNavigatorOnMinimap, () -> cfg.showNavigatorOnMinimap = !cfg.showNavigatorOnMinimap);
                addBool("Navigator on WorldMap", () -> cfg.showNavigatorOnWorldMap, () -> cfg.showNavigatorOnWorldMap = !cfg.showNavigatorOnWorldMap);
                addBool("3D World Waypoints", () -> cfg.showWaypointsInWorld, () -> cfg.showWaypointsInWorld = !cfg.showWaypointsInWorld);
                addBool("Always WP Names", () -> cfg.alwaysShowWaypointNames, () -> cfg.alwaysShowWaypointNames = !cfg.alwaysShowWaypointNames);
                addOption("3D WP Scale", () -> String.format("%.2fx", cfg.waypointScale), () -> {
                    cfg.waypointScale = cfg.waypointScale >= 1.5f ? 0.75f : cfg.waypointScale + 0.25f;
                });
                addBool("Waypoint Beams", () -> cfg.showWaypointBeam, () -> cfg.showWaypointBeam = !cfg.showWaypointBeam);
                addBool("Auto Death Points", () -> cfg.autoDeathWaypoints, () -> cfg.autoDeathWaypoints = !cfg.autoDeathWaypoints);
                break;
        }
    }

    private void addBool(String label, java.util.function.BooleanSupplier getter, Runnable toggle) {
        currentOptions.add(new OptionEntry(label, () -> getter.getAsBoolean() ? "ON" : "OFF", toggle));
    }

    private void addOption(String label, java.util.function.Supplier<String> val, Runnable toggle) {
        currentOptions.add(new OptionEntry(label, val, toggle));
    }

    private void triggerReshade() {
        if (SaifunsMapMod.proxy instanceof ClientProxy) {
            ((ClientProxy) SaifunsMapMod.proxy).getWorldCache().reshadeAllLoaded();
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 999) {
            MapConfig.save();
            this.mc.displayGuiScreen(null);
            return;
        }
        if (button.id >= 100 && button.id < 110) {
            int idx = button.id - 100;
            Tab[] tabs = Tab.values();
            if (idx >= 0 && idx < tabs.length) {
                this.activeTab = tabs[idx];
                rebuildButtons();
            }
            return;
        }
        if (button.id >= 200) {
            int idx = button.id - 200;
            if (idx >= 0 && idx < currentOptions.size()) {
                currentOptions.get(idx).onClick.run();
                MapConfig.save();
                rebuildButtons();
            }
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        int panelW = 340;
        int panelH = 234;
        int panelX = (this.width - panelW) / 2;
        int panelY = Math.max(24, (this.height - 230) / 2);

        // Translucent sleek Xaero-style panel (leaving minimap visible live behind!)
        GLDraw.ensureBlend();
        GLDraw.rect(panelX - 6, panelY - 4, panelX + panelW + 6, panelY + panelH, 0xE20C1017);
        GLDraw.rectOutline(panelX - 6, panelY - 4, panelX + panelW + 6, panelY + panelH, 1.0, 0x88334155);
        GLDraw.rect(panelX - 6, panelY - 4, panelX + panelW + 6, panelY - 2, 0xFF3B82F6);
        drawCenteredString(this.fontRendererObj, "Saifun's Map — Settings", this.width / 2, panelY + 6, 0xFFF8FAFC);

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void onGuiClosed() {
        MapConfig.save();
    }
}

package com.saifun.map.compat;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;

import java.util.Collections;
import java.util.List;

public final class NoOpGtnhBridge implements IGtnhLayerBridge {

    public static final NoOpGtnhBridge INSTANCE = new NoOpGtnhBridge();

    private NoOpGtnhBridge() {}

    @Override
    public boolean isAvailable() { return false; }

    @Override
    public void onWorldMapOpened() {}

    @Override
    public void onWorldMapClosed() {}

    @Override
    public List<LayerButtonInfo> getLayerButtons() { return Collections.emptyList(); }

    @Override
    public boolean hasActiveSearchBar() { return false; }

    @Override
    public void onSearchChanged(String query) {}

    @Override
    public void renderFullscreenLayers(
        GuiScreen screen, double centerBlockX, double centerBlockZ,
        double centerScreenX, double centerScreenY, double pixelsPerBlock,
        int screenWidth, int screenHeight
    ) {}

    @Override
    public void renderMinimapLayers(
        double playerX, double playerZ, double pixelsPerBlock,
        float mapRotationDeg, double worldRadiusBlocks
    ) {}

    @Override
    public void onMouseMove(int mouseX, int mouseY) {}

    @Override
    public boolean onMapClick(boolean isDoubleClick, int mouseX, int mouseY, int blockX, int blockZ) { return false; }

    @Override
    public boolean onKeyPressed(int keyCode) { return false; }

    @Override
    public List<String> getHoveredTooltip() { return Collections.emptyList(); }

    @Override
    public void drawHoveredCustomTooltip(FontRenderer fr, int mouseX, int mouseY, int screenW, int screenH) {}

    @Override
    public com.saifun.map.waypoints.Waypoint getHoveredShareWaypoint() { return null; }
}

package com.saifun.map.compat;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ResourceLocation;

import java.util.List;

/**
 * Abstraction over optional GTNH map layer integrations (Navigator -> VisualProspecting, ServerUtilities, etc.).
 * Keeps all Navigator classes isolated so Saifun's Map runs cleanly both inside and outside GTNH.
 */
public interface IGtnhLayerBridge {

    final class LayerButtonInfo {
        public final String label;
        public final ResourceLocation icon;
        public final boolean active;
        public final Runnable toggle;

        public LayerButtonInfo(String label, ResourceLocation icon, boolean active, Runnable toggle) {
            this.label = label;
            this.icon = icon;
            this.active = active;
            this.toggle = toggle;
        }
    }

    final class ContextAction {
        public final String label;
        public final boolean danger;
        public final Runnable run;

        public ContextAction(String label, Runnable run) {
            this(label, false, run);
        }

        public ContextAction(String label, boolean danger, Runnable run) {
            this.label = label;
            this.danger = danger;
            this.run = run;
        }
    }

    boolean isAvailable();

    boolean hasActiveLayers();

    void onWorldMapOpened();

    void onWorldMapClosed();

    List<LayerButtonInfo> getLayerButtons();

    boolean hasActiveSearchBar();

    void onSearchChanged(String query);

    void renderFullscreenLayers(
        GuiScreen screen,
        double centerBlockX,
        double centerBlockZ,
        double centerScreenX,
        double centerScreenY,
        double pixelsPerBlock,
        int screenWidth,
        int screenHeight
    );

    void renderMinimapLayers(
        double playerX,
        double playerZ,
        double pixelsPerBlock,
        float mapRotationDeg,
        double worldRadiusBlocks
    );

    void onMouseMove(int mouseX, int mouseY);

    boolean onMapClick(boolean isDoubleClick, int mouseX, int mouseY, int blockX, int blockZ);

    boolean onKeyPressed(int keyCode);

    List<String> getHoveredTooltip();

    void drawHoveredCustomTooltip(FontRenderer fontRenderer, int mouseX, int mouseY, int screenWidth, int screenHeight);

    /**
     * Returns a shareable Waypoint for the currently hovered Navigator interactable element
     * (e.g. VisualProspecting Ore Vein or Thaumcraft Node), or null if none is hovered.
     */
    com.saifun.map.waypoints.Waypoint getHoveredShareWaypoint();

    /**
     * Returns right-click context menu actions for the hovered GTNH Navigator element or active layer
     * (e.g. Track/Untrack Vein, Toggle Depleted, Claim Chunk, Toggle Chunk Load, Unclaim Chunk).
     */
    List<ContextAction> getContextActions(int mouseX, int mouseY, int blockX, int blockZ);

    /**
     * Clears the active tracked Navigator waypoint for the given layer tagKey (e.g. "OreVeinLayerManager"),
     * synchronizing Navigator's own layer state when untracked from our waypoint context menu.
     */
    void clearActiveWaypoint(String navTagKey);
}

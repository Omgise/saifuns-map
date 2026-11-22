package com.saifun.map.compat;

import com.gtnewhorizons.navigator.api.NavigatorApi;
import com.gtnewhorizons.navigator.api.model.SupportedMods;
import com.gtnewhorizons.navigator.api.model.buttons.ButtonManager;
import com.gtnewhorizons.navigator.api.model.layers.InteractableLayer;
import com.gtnewhorizons.navigator.api.model.layers.InteractableLayerManager;
import com.gtnewhorizons.navigator.api.model.layers.LayerManager;
import com.gtnewhorizons.navigator.api.model.layers.LayerRenderer;
import com.gtnewhorizons.navigator.api.model.layers.UniversalLayerRenderer;
import com.gtnewhorizons.navigator.api.model.locations.ILocationProvider;
import com.gtnewhorizons.navigator.api.model.steps.UniversalRenderStep;
import com.gtnewhorizons.navigator.api.model.waypoints.Waypoint;
import com.gtnewhorizons.navigator.api.model.waypoints.WaypointManager;
import com.saifun.map.SaifunsMapMod;
import com.saifun.map.config.MapConfig;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Native bridge into GTNewHorizons/Navigator 1.1.x.
 * Automatically activates and renders ALL registered Navigator layers:
 * - VisualProspecting (GregTech Ore Veins, Underground Fluids, Thaumcraft Nodes, Search, Depletion, Waypoints)
 * - ServerUtilities (Claimed & Chunkloaded Chunks, interactive claiming)
 * - Dirty Chunks and any other NavigatorApi-compatible GTNH mod!
 * Works with ZERO mixins and ZERO dependency on JourneyMap being installed.
 */
public class NavigatorBridge implements IGtnhLayerBridge {

    private static final double LN2 = Math.log(2.0);

    private final com.saifun.map.waypoints.WaypointManager ourWaypoints;
    private final Map<LayerManager, UniversalLayerRenderer> boundRenderers = new IdentityHashMap<>();
    private final List<LayerManager> orderedManagers = new ArrayList<>();

    private Method addLayerRendererMethod;
    private Method addInteractableRendererMethod;
    private Method refreshDimCacheMethod;
    private Field layerRendererMapField;
    private Field waypointManagersMapField;
    private boolean reflectionReady = false;
    private boolean bootstrapped = false;

    public NavigatorBridge(com.saifun.map.waypoints.WaypointManager ourWaypoints) {
        this.ourWaypoints = ourWaypoints;
        initReflection();
    }

    private void initReflection() {
        try {
            addLayerRendererMethod = LayerManager.class.getDeclaredMethod(
                "addLayerRenderer", LayerManager.class, SupportedMods.class
            );
            addLayerRendererMethod.setAccessible(true);

            addInteractableRendererMethod = InteractableLayerManager.class.getDeclaredMethod(
                "addLayerRenderer", InteractableLayerManager.class, SupportedMods.class
            );
            addInteractableRendererMethod.setAccessible(true);

            refreshDimCacheMethod = LayerManager.class.getDeclaredMethod("refreshDimCache");
            refreshDimCacheMethod.setAccessible(true);

            layerRendererMapField = LayerManager.class.getDeclaredField("layerRenderer");
            layerRendererMapField.setAccessible(true);

            waypointManagersMapField = InteractableLayerManager.class.getDeclaredField("waypointManagers");
            waypointManagersMapField.setAccessible(true);

            reflectionReady = true;
        } catch (Throwable t) {
            SaifunsMapMod.LOG.warn("Navigator reflection init failed: {}", t.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private void ensureBootstrapped() {
        if (!reflectionReady) return;
        List<LayerManager> all = NavigatorApi.layerManagers;
        if (all == null || all.isEmpty()) return;

        // Re-check if any new layers were registered since last check
        if (bootstrapped && orderedManagers.size() == all.size()) {
            return;
        }

        orderedManagers.clear();
        for (LayerManager lm : all) {
            if (lm == null) continue;
            orderedManagers.add(lm);
            bindLayer(lm);
        }
        bootstrapped = true;
    }

    @SuppressWarnings("unchecked")
    private void bindLayer(LayerManager lm) {
        if (boundRenderers.containsKey(lm)) return;
        try {
            // Check if Navigator already created a UniversalLayerRenderer for JourneyMap
            LayerRenderer existing = lm.getLayerRenderer(SupportedMods.JourneyMap);
            UniversalLayerRenderer ulr = null;
            if (existing instanceof UniversalLayerRenderer) {
                ulr = (UniversalLayerRenderer) existing;
            } else {
                // Bootstrap UniversalLayerRenderer even when JourneyMap is completely uninstalled!
                Object created = (lm instanceof InteractableLayerManager)
                    ? addInteractableRendererMethod.invoke(lm, lm, SupportedMods.JourneyMap)
                    : addLayerRendererMethod.invoke(lm, lm, SupportedMods.JourneyMap);
                if (created instanceof UniversalLayerRenderer) {
                    ulr = (UniversalLayerRenderer) created;
                    Map<SupportedMods, LayerRenderer> map = (Map<SupportedMods, LayerRenderer>) layerRendererMapField.get(lm);
                    if (map != null) {
                        map.put(SupportedMods.JourneyMap, ulr);
                    }
                    // Ensure currentDimSteps is initialized on the newly bound renderer!
                    refreshDimCacheMethod.invoke(lm);
                    lm.forceRefresh();
                }
            }

            if (ulr != null) {
                boundRenderers.put(lm, ulr);
            }

            // Bind our WaypointManager into InteractableLayerManager so double-clicking veins/nodes creates Saifun's Map waypoints!
            if (lm instanceof InteractableLayerManager) {
                InteractableLayerManager ilm = (InteractableLayerManager) lm;
                Map<SupportedMods, WaypointManager> wpMap = (Map<SupportedMods, WaypointManager>) waypointManagersMapField.get(ilm);
                if (wpMap != null && !wpMap.containsKey(SupportedMods.JourneyMap)) {
                    wpMap.put(SupportedMods.JourneyMap, new SaifunsNavigatorWaypointManager(ilm, ourWaypoints));
                }
            }
        } catch (Throwable t) {
            SaifunsMapMod.LOG.debug("Could not bind Navigator layer {}: {}", lm.getClass().getSimpleName(), t.toString());
        }
    }

    @Override
    public boolean isAvailable() {
        return reflectionReady && MapConfig.get().navigatorEnabled;
    }

    @Override
    public void onWorldMapOpened() {
        if (!isAvailable()) return;
        ensureBootstrapped();
        for (LayerManager lm : orderedManagers) {
            try {
                lm.onGuiOpened(SupportedMods.JourneyMap);
                lm.forceRefresh();
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onWorldMapClosed() {
        if (!isAvailable()) return;
        for (LayerManager lm : orderedManagers) {
            try {
                lm.onGuiClosed(SupportedMods.JourneyMap);
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public List<LayerButtonInfo> getLayerButtons() {
        List<LayerButtonInfo> out = new ArrayList<>();
        if (!isAvailable()) return out;
        ensureBootstrapped();

        try {
            List<ButtonManager> buttons = NavigatorApi.getDistinctButtons();
            if (buttons == null) return out;
            for (ButtonManager bm : buttons) {
                if (bm == null) continue;
                ResourceLocation icon = null;
                try {
                    icon = bm.getIcon(SupportedMods.JourneyMap, "");
                } catch (Throwable ignored) {}
                String text = "";
                try {
                    text = bm.getButtonText();
                } catch (Throwable ignored) {}
                out.add(new LayerButtonInfo(text != null ? text : "Layer", icon, bm.isActive(), () -> {
                    bm.toggle();
                    for (LayerManager lm : orderedManagers) {
                        if (lm.getButtonManager() == bm) {
                            lm.forceRefresh();
                        }
                    }
                }));
            }
        } catch (Throwable ignored) {}
        return out;
    }

    @Override
    public boolean hasActiveSearchBar() {
        if (!isAvailable()) return false;
        ensureBootstrapped();
        for (LayerManager lm : orderedManagers) {
            if (lm.isLayerActive() && lm.hasSearchField()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onSearchChanged(String query) {
        if (!isAvailable()) return;
        ensureBootstrapped();
        String q = query != null ? query : "";
        for (LayerManager lm : orderedManagers) {
            if (lm.isLayerActive() && lm.hasSearchField()) {
                try {
                    lm.onSearch(q);
                    lm.forceRefresh();
                } catch (Throwable ignored) {}
            }
        }
    }

    @Override
    public void renderFullscreenLayers(
        GuiScreen screen,
        double centerBlockX,
        double centerBlockZ,
        double centerScreenX,
        double centerScreenY,
        double pixelsPerBlock,
        int screenWidth,
        int screenHeight
    ) {
        if (!isAvailable() || !MapConfig.get().showNavigatorOnWorldMap) return;
        ensureBootstrapped();

        int visibleBlocksW = Math.max(64, (int) Math.ceil(screenWidth / Math.max(0.05, pixelsPerBlock)) + 64);
        int visibleBlocksH = Math.max(64, (int) Math.ceil(screenHeight / Math.max(0.05, pixelsPerBlock)) + 64);
        int cX = MathHelper.floor_double(centerBlockX);
        int cZ = MathHelper.floor_double(centerBlockZ);
        double jmZoom = Math.log(Math.max(0.0625, pixelsPerBlock)) / LN2;

        List<UniversalLayerRenderer> active = collectActiveRenderersSorted();
        for (UniversalLayerRenderer ulr : active) {
            LayerManager lm = findManagerFor(ulr);
            if (lm == null) continue;
            try {
                lm.recacheFullscreenMap(cX, cZ, visibleBlocksW, visibleBlocksH);
                List<UniversalRenderStep<?>> steps = ulr.getRenderSteps();
                if (steps == null || steps.isEmpty()) continue;

                for (int i = 0; i < steps.size(); i++) {
                    UniversalRenderStep<?> step = steps.get(i);
                    if (step == null) continue;
                    ILocationProvider loc = step.getLocation();
                    if (loc == null) continue;

                    double sx = centerScreenX + (loc.getBlockX() - centerBlockX) * pixelsPerBlock;
                    double sy = centerScreenY + (loc.getBlockZ() - centerBlockZ) * pixelsPerBlock;

                    // Quick frustum cull
                    if (sx < -128 || sx > screenWidth + 128 || sy < -128 || sy > screenHeight + 128) {
                        continue;
                    }

                    GL11.glPushMatrix();
                    step.drawJourneyMap(sx, sy, 1.0f, jmZoom, pixelsPerBlock, 1.0, 0.0);
                    GL11.glPopMatrix();
                }
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void renderMinimapLayers(
        double playerX,
        double playerZ,
        double pixelsPerBlock,
        float mapRotationDeg,
        double worldRadiusBlocks
    ) {
        if (!isAvailable() || !MapConfig.get().showNavigatorOnMinimap) return;
        ensureBootstrapped();

        int pX = MathHelper.floor_double(playerX);
        int pZ = MathHelper.floor_double(playerZ);
        int radius = Math.max(32, (int) Math.ceil(worldRadiusBlocks) + 32);
        double jmZoom = Math.log(Math.max(0.0625, pixelsPerBlock)) / LN2;

        List<UniversalLayerRenderer> active = collectActiveRenderersSorted();
        for (UniversalLayerRenderer ulr : active) {
            LayerManager lm = findManagerFor(ulr);
            if (lm == null) continue;
            try {
                lm.recacheMiniMap(pX, pZ, radius * 2);
                List<UniversalRenderStep<?>> steps = ulr.getRenderSteps();
                if (steps == null || steps.isEmpty()) continue;

                for (int i = 0; i < steps.size(); i++) {
                    UniversalRenderStep<?> step = steps.get(i);
                    if (step == null) continue;
                    ILocationProvider loc = step.getLocation();
                    if (loc == null) continue;

                    double dx = (loc.getBlockX() - playerX) * pixelsPerBlock;
                    double dz = (loc.getBlockZ() - playerZ) * pixelsPerBlock;

                    GL11.glPushMatrix();
                    step.drawJourneyMap(dx, dz, 0.72f, jmZoom, pixelsPerBlock, 0.75, -mapRotationDeg);
                    GL11.glPopMatrix();
                }
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onMouseMove(int mouseX, int mouseY) {
        if (!isAvailable() || !MapConfig.get().showNavigatorOnWorldMap) return;
        for (UniversalLayerRenderer ulr : collectActiveRenderersSorted()) {
            if (ulr instanceof InteractableLayer) {
                try {
                    ((InteractableLayer) ulr).onMouseMove(mouseX, mouseY);
                } catch (Throwable ignored) {}
            }
        }
    }

    @Override
    public boolean onMapClick(boolean isDoubleClick, int mouseX, int mouseY, int blockX, int blockZ) {
        if (!isAvailable() || !MapConfig.get().showNavigatorOnWorldMap) return false;
        List<UniversalLayerRenderer> active = collectActiveRenderersSorted();
        for (int i = active.size() - 1; i >= 0; i--) {
            UniversalLayerRenderer ulr = active.get(i);
            if (ulr instanceof InteractableLayer) {
                try {
                    if (((InteractableLayer) ulr).onMapClick(isDoubleClick, mouseX, mouseY, blockX, blockZ)) {
                        return true;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    @Override
    public boolean onKeyPressed(int keyCode) {
        if (!isAvailable() || !MapConfig.get().showNavigatorOnWorldMap) return false;
        List<UniversalLayerRenderer> active = collectActiveRenderersSorted();
        for (int i = active.size() - 1; i >= 0; i--) {
            UniversalLayerRenderer ulr = active.get(i);
            if (ulr instanceof InteractableLayer) {
                try {
                    if (((InteractableLayer) ulr).onKeyPressed(keyCode)) {
                        return true;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    @Override
    public List<String> getHoveredTooltip() {
        List<String> lines = new ArrayList<>();
        if (!isAvailable() || !MapConfig.get().showNavigatorOnWorldMap) return lines;
        for (UniversalLayerRenderer ulr : collectActiveRenderersSorted()) {
            if (ulr instanceof InteractableLayer) {
                try {
                    List<String> tip = ((InteractableLayer) ulr).getTooltip();
                    if (tip != null && !tip.isEmpty()) {
                        lines.addAll(tip);
                    }
                } catch (Throwable ignored) {}
            }
        }
        return lines;
    }

    @Override
    public void drawHoveredCustomTooltip(FontRenderer fontRenderer, int mouseX, int mouseY, int screenWidth, int screenHeight) {
        if (!isAvailable() || !MapConfig.get().showNavigatorOnWorldMap) return;
        for (UniversalLayerRenderer ulr : collectActiveRenderersSorted()) {
            if (ulr instanceof InteractableLayer) {
                try {
                    ((InteractableLayer) ulr).drawCustomTooltip(fontRenderer, mouseX, mouseY, screenWidth, screenHeight);
                } catch (Throwable ignored) {}
            }
        }
    }

    private List<UniversalLayerRenderer> collectActiveRenderersSorted() {
        List<UniversalLayerRenderer> active = new ArrayList<>();
        for (LayerManager lm : orderedManagers) {
            if (!lm.isLayerActive()) continue;
            UniversalLayerRenderer ulr = boundRenderers.get(lm);
            if (ulr != null) {
                active.add(ulr);
            }
        }
        active.sort(Comparator.comparingInt(UniversalLayerRenderer::getRenderPriority));
        return active;
    }

    private LayerManager findManagerFor(UniversalLayerRenderer target) {
        for (Map.Entry<LayerManager, UniversalLayerRenderer> e : boundRenderers.entrySet()) {
            if (e.getValue() == target) return e.getKey();
        }
        return null;
    }

    /**
     * Bridges Navigator active waypoints (e.g. double-clicking an Ore Vein or Thaumcraft Node)
     * directly into Saifun's Map WaypointManager!
     */
    private static final class SaifunsNavigatorWaypointManager extends WaypointManager {
        private final com.saifun.map.waypoints.WaypointManager ourManager;
        private final String tagKey;

        SaifunsNavigatorWaypointManager(
            InteractableLayerManager manager,
            com.saifun.map.waypoints.WaypointManager ourManager
        ) {
            super(manager, SupportedMods.JourneyMap);
            this.ourManager = ourManager;
            this.tagKey = manager.getClass().getSimpleName();
        }

        @Override
        public void clearActiveWaypoint() {
            ourManager.clearNavigatorWaypoints(tagKey);
        }

        @Override
        public void updateActiveWaypoint(Waypoint waypoint) {
            if (waypoint == null) {
                clearActiveWaypoint();
                return;
            }
            ourManager.setNavigatorWaypoint(
                tagKey,
                waypoint.label != null ? waypoint.label : "Target",
                waypoint.blockX,
                waypoint.blockY > 0 ? waypoint.blockY : 70,
                waypoint.blockZ,
                waypoint.dimensionId,
                waypoint.color & 0xFFFFFF
            );
        }

        @Override
        public boolean hasWaypoint() {
            return ourManager.hasNavigatorWaypoint(tagKey);
        }
    }
}

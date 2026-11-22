package com.saifun.map.compat;

import com.gtnewhorizons.navigator.api.NavigatorApi;
import com.gtnewhorizons.navigator.api.model.SupportedMods;
import com.gtnewhorizons.navigator.api.model.buttons.ButtonManager;
import com.gtnewhorizons.navigator.api.model.layers.InteractableLayer;
import com.gtnewhorizons.navigator.api.model.layers.InteractableLayerManager;
import com.gtnewhorizons.navigator.api.model.layers.LayerManager;
import com.gtnewhorizons.navigator.api.model.layers.LayerRenderer;
import com.gtnewhorizons.navigator.api.model.layers.UniversalInteractableRenderer;
import com.gtnewhorizons.navigator.api.model.layers.UniversalLayerRenderer;
import com.gtnewhorizons.navigator.api.model.locations.ILocationProvider;
import com.gtnewhorizons.navigator.api.model.locations.IWaypointAndLocationProvider;
import com.gtnewhorizons.navigator.api.model.steps.UniversalLocationInteractableStep;
import com.gtnewhorizons.navigator.api.model.steps.UniversalRenderStep;
import com.gtnewhorizons.navigator.api.model.waypoints.Waypoint;
import com.gtnewhorizons.navigator.api.model.waypoints.WaypointManager;
import com.saifun.map.SaifunsMapMod;
import com.saifun.map.config.MapConfig;
import com.saifun.map.render.GLDraw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;
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
 * Renders all registered Navigator layers (VisualProspecting Ore Veins, Underground Fluids,
 * Thaumcraft Nodes, ServerUtilities Claims, Dirty Chunks) in 1:1 raw framebuffer pixel space
 * (matching JourneyMap 5's exact coordinate system so icons, fonts, and anti-overlap labels
 * scale identically and razor-sharp at 1080p/1440p/4K).
 */
public class NavigatorBridge implements IGtnhLayerBridge {

    private static final double LN2 = Math.log(2.0);
    private static final Comparator<UniversalLayerRenderer> PRIORITY_COMP =
        Comparator.comparingInt(UniversalLayerRenderer::getRenderPriority);

    private final com.saifun.map.waypoints.WaypointManager ourWaypoints;
    private final Map<LayerManager, UniversalLayerRenderer> boundRenderers = new IdentityHashMap<>();
    private final Map<UniversalLayerRenderer, LayerManager> rendererToManager = new IdentityHashMap<>();
    private final List<LayerManager> orderedManagers = new ArrayList<>();
    private final List<UniversalLayerRenderer> activeRenderersCache = new ArrayList<>();
    private boolean activeCacheDirty = true;

    private List<LayerButtonInfo> cachedButtons = null;
    private int cachedDispW = -1;
    private int cachedDispH = -1;
    private int cachedGuiScaleSetting = -1;
    private int cachedScaleFactor = 1;

    private Method addLayerRendererMethod;
    private Method addInteractableRendererMethod;
    private Method refreshDimCacheMethod;
    private Field layerRendererMapField;
    private Field waypointManagersMapField;
    private Field hoveredStepField;
    private Field stepXField;
    private boolean reflectionReady = false;
    private boolean bootstrapped = false;

    // Recache throttling so dragging at 150 FPS NEVER triggers per-frame database rescans!
    private int lastFullDim = Integer.MIN_VALUE;
    private int lastFullCx = Integer.MIN_VALUE;
    private int lastFullCz = Integer.MIN_VALUE;
    private int lastFullW = -1;
    private int lastFullH = -1;
    private long lastFullRecacheMs = 0L;

    private int lastMiniDim = Integer.MIN_VALUE;
    private int lastMiniPx = Integer.MIN_VALUE;
    private int lastMiniPz = Integer.MIN_VALUE;
    private long lastMiniRecacheMs = 0L;

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

            try {
                hoveredStepField = UniversalInteractableRenderer.class.getDeclaredField("hoveredLocationRenderStep");
                hoveredStepField.setAccessible(true);
            } catch (Throwable ignored) {}

            try {
                stepXField = UniversalRenderStep.class.getDeclaredField("x");
                stepXField.setAccessible(true);
            } catch (Throwable ignored) {}

            reflectionReady = true;
        } catch (Throwable t) {
            SaifunsMapMod.LOG.warn("Navigator reflection init failed: {}", t.toString());
        }
    }

    private void ensureBootstrapped() {
        if (!reflectionReady) return;
        List<LayerManager> all = NavigatorApi.layerManagers;
        if (all == null || all.isEmpty()) return;

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
            LayerRenderer existing = lm.getLayerRenderer(SupportedMods.JourneyMap);
            UniversalLayerRenderer ulr = null;
            if (existing instanceof UniversalLayerRenderer) {
                ulr = (UniversalLayerRenderer) existing;
            } else {
                Object created = (lm instanceof InteractableLayerManager)
                    ? addInteractableRendererMethod.invoke(lm, lm, SupportedMods.JourneyMap)
                    : addLayerRendererMethod.invoke(lm, lm, SupportedMods.JourneyMap);
                if (created instanceof UniversalLayerRenderer) {
                    ulr = (UniversalLayerRenderer) created;
                    Map<SupportedMods, LayerRenderer> map = (Map<SupportedMods, LayerRenderer>) layerRendererMapField.get(lm);
                    if (map != null) {
                        map.put(SupportedMods.JourneyMap, ulr);
                    }
                    refreshDimCacheMethod.invoke(lm);
                    lm.forceRefresh();
                }
            }

            if (ulr != null) {
                boundRenderers.put(lm, ulr);
                rendererToManager.put(ulr, lm);
                activeCacheDirty = true;
            }

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

    private int getGuiScaleFactor() {
        Minecraft mc = Minecraft.getMinecraft();
        int dw = mc.displayWidth;
        int dh = mc.displayHeight;
        int gs = mc.gameSettings != null ? mc.gameSettings.guiScale : 0;
        if (dw != cachedDispW || dh != cachedDispH || gs != cachedGuiScaleSetting) {
            cachedDispW = dw;
            cachedDispH = dh;
            cachedGuiScaleSetting = gs;
            cachedScaleFactor = Math.max(1, new ScaledResolution(mc, dw, dh).getScaleFactor());
        }
        return cachedScaleFactor;
    }

    @Override
    public boolean isAvailable() {
        return reflectionReady && MapConfig.get().navigatorEnabled;
    }

    @Override
    public void onWorldMapOpened() {
        if (!isAvailable()) return;
        ensureBootstrapped();
        activeCacheDirty = true;
        cachedButtons = null;
        lastFullRecacheMs = 0L;
        lastFullDim = Integer.MIN_VALUE;
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
        cachedButtons = null;
        lastMiniRecacheMs = 0L;
        lastMiniDim = Integer.MIN_VALUE;
        for (LayerManager lm : orderedManagers) {
            try {
                lm.onGuiClosed(SupportedMods.JourneyMap);
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public List<LayerButtonInfo> getLayerButtons() {
        if (!isAvailable()) return java.util.Collections.emptyList();
        ensureBootstrapped();
        if (cachedButtons != null) return cachedButtons;

        List<LayerButtonInfo> out = new ArrayList<>();
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
                    activeCacheDirty = true;
                    cachedButtons = null;
                    for (LayerManager lm : orderedManagers) {
                        if (lm.getButtonManager() == bm) {
                            lm.forceRefresh();
                        }
                    }
                }));
            }
        } catch (Throwable ignored) {}
        cachedButtons = out;
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

        int scaleFactor = getGuiScaleFactor();
        double invScale = 1.0 / scaleFactor;

        int visibleBlocksW = Math.max(64, (int) Math.ceil(screenWidth / Math.max(0.05, pixelsPerBlock)) + 96);
        int visibleBlocksH = Math.max(64, (int) Math.ceil(screenHeight / Math.max(0.05, pixelsPerBlock)) + 96);
        int cX = MathHelper.floor_double(centerBlockX);
        int cZ = MathHelper.floor_double(centerBlockZ);

        // JourneyMap 5 operates in native framebuffer pixels: blockSizeRaw = pixelsPerBlock * scaleFactor
        double blockSizeRaw = pixelsPerBlock * scaleFactor;
        double jmZoom = Math.max(0.0, Math.min(5.0, Math.log(Math.max(1.0, blockSizeRaw)) / LN2));
        float drawScale = (float) Math.max(0.55, Math.min(1.15, blockSizeRaw / 4.0));
        double fontScale = Math.max(0.75, Math.min(1.15, scaleFactor * 0.5));

        double rawCenterX = centerScreenX * scaleFactor;
        double rawCenterY = centerScreenY * scaleFactor;
        double rawW = screenWidth * (double) scaleFactor;
        double rawH = screenHeight * (double) scaleFactor;

        List<UniversalLayerRenderer> active = collectActiveRenderersSorted();
        if (active.isEmpty()) return;

        GL11.glPushMatrix();
        GL11.glScaled(invScale, invScale, 1.0);

        long nowMs = System.currentTimeMillis();
        int curDim = Minecraft.getMinecraft().theWorld != null ? Minecraft.getMinecraft().theWorld.provider.dimensionId : Integer.MIN_VALUE;
        boolean dimChanged = curDim != lastFullDim;
        boolean movedFar = dimChanged || Math.abs(cX - lastFullCx) >= 24 || Math.abs(cZ - lastFullCz) >= 24
            || Math.abs(visibleBlocksW - lastFullW) >= 48 || Math.abs(visibleBlocksH - lastFullH) >= 48;
        boolean shouldRecache = dimChanged || (movedFar && (nowMs - lastFullRecacheMs >= 80L)) || (nowMs - lastFullRecacheMs >= 350L);
        if (shouldRecache) {
            lastFullDim = curDim;
            lastFullCx = cX;
            lastFullCz = cZ;
            lastFullW = visibleBlocksW;
            lastFullH = visibleBlocksH;
            lastFullRecacheMs = nowMs;
        }

        for (UniversalLayerRenderer ulr : active) {
            LayerManager lm = findManagerFor(ulr);
            if (lm == null) continue;
            try {
                if (shouldRecache || lm.forceRefresh) {
                    lm.recacheFullscreenMap(cX, cZ, visibleBlocksW, visibleBlocksH);
                }
                List<UniversalRenderStep<?>> steps = ulr.getRenderSteps();
                if (steps == null || steps.isEmpty()) continue;

                for (int i = 0; i < steps.size(); i++) {
                    UniversalRenderStep<?> step = steps.get(i);
                    if (step == null) continue;
                    ILocationProvider loc = step.getLocation();
                    if (loc == null) continue;

                    double sxRaw = rawCenterX + (loc.getBlockX() - centerBlockX) * blockSizeRaw;
                    double syRaw = rawCenterY + (loc.getBlockZ() - centerBlockZ) * blockSizeRaw;

                    if (sxRaw < -256 || sxRaw > rawW + 256 || syRaw < -256 || syRaw > rawH + 256) {
                        // Move culled step's cached screen X off-screen so it never leaves a ghost hover hitbox!
                        if (stepXField != null) {
                            try {
                                stepXField.setDouble(step, -1.0e6);
                            } catch (Throwable ignored) {}
                        }
                        continue;
                    }

                    GL11.glPushMatrix();
                    GLDraw.ensureBlend();
                    GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
                    step.drawJourneyMap(sxRaw, syRaw, drawScale, jmZoom, blockSizeRaw, fontScale, 0.0);
                    GL11.glPopMatrix();
                }
            } catch (Throwable ignored) {}
        }

        GL11.glPopMatrix();
        GLDraw.ensureBlend();
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
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

        int scaleFactor = getGuiScaleFactor();
        double invScale = 1.0 / scaleFactor;

        int pX = MathHelper.floor_double(playerX);
        int pZ = MathHelper.floor_double(playerZ);
        int radius = Math.max(32, (int) Math.ceil(worldRadiusBlocks) + 32);

        double blockSizeRaw = pixelsPerBlock * scaleFactor;
        double jmZoom = Math.max(0.0, Math.min(5.0, Math.log(Math.max(1.0, blockSizeRaw)) / LN2));
        float drawScale = 0.65f;

        List<UniversalLayerRenderer> active = collectActiveRenderersSorted();
        if (active.isEmpty()) return;

        GL11.glPushMatrix();
        GL11.glScaled(invScale, invScale, 1.0);

        long nowMs = System.currentTimeMillis();
        int curDim = Minecraft.getMinecraft().theWorld != null ? Minecraft.getMinecraft().theWorld.provider.dimensionId : Integer.MIN_VALUE;
        boolean dimChanged = curDim != lastMiniDim;
        boolean miniShouldRecache = dimChanged || Math.abs(pX - lastMiniPx) >= 12 || Math.abs(pZ - lastMiniPz) >= 12
            || (nowMs - lastMiniRecacheMs >= 250L);
        if (miniShouldRecache) {
            lastMiniDim = curDim;
            lastMiniPx = pX;
            lastMiniPz = pZ;
            lastMiniRecacheMs = nowMs;
        }

        for (UniversalLayerRenderer ulr : active) {
            LayerManager lm = findManagerFor(ulr);
            if (lm == null) continue;
            try {
                if (miniShouldRecache || lm.forceRefresh) {
                    lm.recacheMiniMap(pX, pZ, radius * 2);
                }
                List<UniversalRenderStep<?>> steps = ulr.getRenderSteps();
                if (steps == null || steps.isEmpty()) continue;

                for (int i = 0; i < steps.size(); i++) {
                    UniversalRenderStep<?> step = steps.get(i);
                    if (step == null) continue;
                    ILocationProvider loc = step.getLocation();
                    if (loc == null) continue;

                    double dxRaw = (loc.getBlockX() - playerX) * blockSizeRaw;
                    double dzRaw = (loc.getBlockZ() - playerZ) * blockSizeRaw;

                    GL11.glPushMatrix();
                    GLDraw.ensureBlend();
                    GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
                    step.drawJourneyMap(dxRaw, dzRaw, drawScale, jmZoom, blockSizeRaw, 0.7, -mapRotationDeg);
                    GL11.glPopMatrix();
                }
            } catch (Throwable ignored) {}
        }

        GL11.glPopMatrix();
        GLDraw.ensureBlend();
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
    }

    @Override
    public void onMouseMove(int mouseX, int mouseY) {
        if (!isAvailable() || !MapConfig.get().showNavigatorOnWorldMap) return;
        int sf = getGuiScaleFactor();
        int rawX = mouseX * sf;
        int rawY = mouseY * sf;
        for (UniversalLayerRenderer ulr : collectActiveRenderersSorted()) {
            if (ulr instanceof InteractableLayer) {
                try {
                    ((InteractableLayer) ulr).onMouseMove(rawX, rawY);
                } catch (Throwable ignored) {}
            }
        }
    }

    @Override
    public boolean onMapClick(boolean isDoubleClick, int mouseX, int mouseY, int blockX, int blockZ) {
        if (!isAvailable() || !MapConfig.get().showNavigatorOnWorldMap) return false;
        int sf = getGuiScaleFactor();
        int rawX = mouseX * sf;
        int rawY = mouseY * sf;
        List<UniversalLayerRenderer> active = collectActiveRenderersSorted();
        for (int i = active.size() - 1; i >= 0; i--) {
            UniversalLayerRenderer ulr = active.get(i);
            if (ulr instanceof InteractableLayer) {
                try {
                    if (((InteractableLayer) ulr).onMapClick(isDoubleClick, rawX, rawY, blockX, blockZ)) {
                        LayerManager lm = findManagerFor(ulr);
                        if (lm != null) lm.forceRefresh();
                        lastFullRecacheMs = 0L;
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
        boolean isActionKey = keyCode == Keyboard.KEY_DELETE
            || (NavigatorApi.ACTION_KEY != null && NavigatorApi.ACTION_KEY.getKeyCode() > 0
                && keyCode == NavigatorApi.ACTION_KEY.getKeyCode());

        List<UniversalLayerRenderer> active = collectActiveRenderersSorted();
        for (int i = active.size() - 1; i >= 0; i--) {
            UniversalLayerRenderer ulr = active.get(i);
            if (ulr instanceof InteractableLayer) {
                try {
                    if (((InteractableLayer) ulr).onKeyPressed(keyCode)) {
                        LayerManager lm = findManagerFor(ulr);
                        if (lm != null) lm.forceRefresh();
                        lastFullRecacheMs = 0L;
                        return true;
                    }
                    // Rock-solid fallback for Delete / Navigator Action Key in case Util.isKeyPressed misses
                    // an LWJGL3 key state or the player presses Delete when ACTION_KEY was rebound/unbound.
                    if (isActionKey && ulr instanceof UniversalInteractableRenderer && hoveredStepField != null) {
                        Object hoveredObj = hoveredStepField.get(ulr);
                        if (hoveredObj instanceof UniversalLocationInteractableStep) {
                            ((UniversalLocationInteractableStep<?>) hoveredObj).onActionKeyPressed();
                            LayerManager lm = findManagerFor(ulr);
                            if (lm != null) lm.forceRefresh();
                            lastFullRecacheMs = 0L;
                            return true;
                        }
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
                    GLDraw.ensureBlend();
                    ((InteractableLayer) ulr).drawCustomTooltip(fontRenderer, mouseX, mouseY, screenWidth, screenHeight);
                } catch (Throwable ignored) {}
            }
        }
    }

    @Override
    public com.saifun.map.waypoints.Waypoint getHoveredShareWaypoint() {
        if (!isAvailable() || !MapConfig.get().showNavigatorOnWorldMap || hoveredStepField == null) return null;
        List<UniversalLayerRenderer> active = collectActiveRenderersSorted();
        for (int i = active.size() - 1; i >= 0; i--) {
            UniversalLayerRenderer ulr = active.get(i);
            if (!(ulr instanceof UniversalInteractableRenderer)) continue;
            try {
                Object hoveredObj = hoveredStepField.get(ulr);
                if (hoveredObj instanceof UniversalLocationInteractableStep) {
                    ILocationProvider loc = ((UniversalLocationInteractableStep<?>) hoveredObj).getLocation();
                    if (loc instanceof IWaypointAndLocationProvider) {
                        Waypoint navWp = ((IWaypointAndLocationProvider) loc).toWaypoint();
                        if (navWp != null) {
                            return new com.saifun.map.waypoints.Waypoint(
                                navWp.label != null ? navWp.label : "Shared Target",
                                null,
                                navWp.blockX,
                                navWp.blockY > 0 ? navWp.blockY : 70,
                                navWp.blockZ,
                                navWp.dimensionId,
                                navWp.color & 0xFFFFFF,
                                com.saifun.map.waypoints.Waypoint.Type.NORMAL
                            );
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private List<UniversalLayerRenderer> collectActiveRenderersSorted() {
        if (!activeCacheDirty) {
            return activeRenderersCache;
        }
        activeRenderersCache.clear();
        for (int i = 0; i < orderedManagers.size(); i++) {
            LayerManager lm = orderedManagers.get(i);
            if (!lm.isLayerActive()) continue;
            UniversalLayerRenderer ulr = boundRenderers.get(lm);
            if (ulr != null) {
                activeRenderersCache.add(ulr);
            }
        }
        activeRenderersCache.sort(PRIORITY_COMP);
        activeCacheDirty = false;
        return activeRenderersCache;
    }

    private LayerManager findManagerFor(UniversalLayerRenderer target) {
        return rendererToManager.get(target);
    }

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

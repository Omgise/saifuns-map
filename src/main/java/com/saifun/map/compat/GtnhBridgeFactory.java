package com.saifun.map.compat;

import com.saifun.map.SaifunsMapMod;
import com.saifun.map.waypoints.WaypointManager;
import cpw.mods.fml.common.Loader;

public final class GtnhBridgeFactory {

    private GtnhBridgeFactory() {}

    public static IGtnhLayerBridge create(WaypointManager waypointManager) {
        if (!Loader.isModLoaded("navigator")) {
            SaifunsMapMod.LOG.info("GTNH Navigator not detected; running without GTNH layer bridge.");
            return NoOpGtnhBridge.INSTANCE;
        }
        try {
            Class<?> clazz = Class.forName("com.saifun.map.compat.NavigatorBridge");
            IGtnhLayerBridge bridge = (IGtnhLayerBridge) clazz
                .getConstructor(WaypointManager.class)
                .newInstance(waypointManager);
            SaifunsMapMod.LOG.info("GTNH Navigator bridge initialized (VisualProspecting / ServerUtilities layers ready).");
            return bridge;
        } catch (Throwable t) {
            SaifunsMapMod.LOG.warn("Failed to initialize GTNH Navigator bridge: {}", t.toString());
            return NoOpGtnhBridge.INSTANCE;
        }
    }
}

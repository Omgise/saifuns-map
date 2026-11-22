package com.saifun.map;

import com.saifun.map.compat.GtnhBridgeFactory;
import com.saifun.map.compat.IGtnhLayerBridge;
import com.saifun.map.config.MapConfig;
import com.saifun.map.engine.BlockColorResolver;
import com.saifun.map.engine.ChunkScanner;
import com.saifun.map.engine.MapWorldCache;
import com.saifun.map.input.MapKeybinds;
import com.saifun.map.radar.EntityIconRenderer;
import com.saifun.map.radar.EntityRadarManager;
import com.saifun.map.render.MinimapRenderer;
import com.saifun.map.waypoints.WaypointManager;
import com.saifun.map.waypoints.WaypointWorldRenderer;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraftforge.common.MinecraftForge;

import java.io.File;

public class ClientProxy extends CommonProxy {

    private MapWorldCache worldCache;
    private ChunkScanner chunkScanner;
    private EntityRadarManager radarManager;
    private WaypointManager waypointManager;
    private WaypointWorldRenderer waypointWorldRenderer;
    private IGtnhLayerBridge gtnhBridge;
    private MinimapRenderer minimapRenderer;

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
        File configFile = new File(event.getModConfigurationDirectory(), SaifunsMapMod.MODID + ".json");
        MapConfig.load(configFile);
    }

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);

        MapKeybinds.register();

        File dataRoot = new File(Minecraft.getMinecraft().mcDataDir, SaifunsMapMod.MODID);
        this.worldCache = new MapWorldCache(dataRoot);
        this.chunkScanner = new ChunkScanner(this.worldCache);
        this.radarManager = new EntityRadarManager();
        this.waypointManager = new WaypointManager(this.worldCache);
        this.waypointWorldRenderer = new WaypointWorldRenderer(this.waypointManager);
        this.gtnhBridge = GtnhBridgeFactory.create(this.waypointManager);
        this.minimapRenderer = new MinimapRenderer(
            this.worldCache,
            this.chunkScanner,
            this.radarManager,
            this.waypointManager,
            this.gtnhBridge
        );

        ClientEventHandler eventHandler = new ClientEventHandler(
            this.worldCache,
            this.chunkScanner,
            this.radarManager,
            this.waypointManager,
            this.waypointWorldRenderer,
            this.gtnhBridge,
            this.minimapRenderer
        );
        FMLCommonHandler.instance().bus().register(eventHandler);
        MinecraftForge.EVENT_BUS.register(eventHandler);
    }

    @Override
    public void postInit(FMLPostInitializationEvent event) {
        super.postInit(event);
        if (Minecraft.getMinecraft().getResourceManager() instanceof IReloadableResourceManager) {
            ((IReloadableResourceManager) Minecraft.getMinecraft().getResourceManager())
                .registerReloadListener(rm -> {
                    BlockColorResolver.clearCache();
                    EntityIconRenderer.clearCache();
                });
        }
        SaifunsMapMod.LOG.info("{} {} client initialized (GTNH Navigator bridge: {}).",
            SaifunsMapMod.NAME, Tags.VERSION, gtnhBridge != null && gtnhBridge.isAvailable() ? "ACTIVE" : "inactive");
    }

    public MapWorldCache getWorldCache() {
        return worldCache;
    }

    public ChunkScanner getChunkScanner() {
        return chunkScanner;
    }

    public EntityRadarManager getRadarManager() {
        return radarManager;
    }

    public WaypointManager getWaypointManager() {
        return waypointManager;
    }

    public IGtnhLayerBridge getGtnhBridge() {
        return gtnhBridge;
    }

    public MinimapRenderer getMinimapRenderer() {
        return minimapRenderer;
    }
}

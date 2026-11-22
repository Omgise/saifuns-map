package com.saifun.map;

import com.saifun.map.config.MapConfig;
import com.saifun.map.engine.ChunkScanner;
import com.saifun.map.engine.MapWorldCache;
import com.saifun.map.gui.GuiMapSettings;
import com.saifun.map.input.MapKeybinds;
import com.saifun.map.radar.EntityRadarManager;
import com.saifun.map.render.MinimapRenderer;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;

public class ClientEventHandler {

    private final MapWorldCache worldCache;
    private final ChunkScanner chunkScanner;
    private final EntityRadarManager radarManager;
    private final MinimapRenderer minimapRenderer;

    public ClientEventHandler(
        MapWorldCache worldCache,
        ChunkScanner chunkScanner,
        EntityRadarManager radarManager,
        MinimapRenderer minimapRenderer
    ) {
        this.worldCache = worldCache;
        this.chunkScanner = chunkScanner;
        this.radarManager = radarManager;
        this.minimapRenderer = minimapRenderer;
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type == RenderGameOverlayEvent.ElementType.ALL) {
            minimapRenderer.render(event.partialTicks);
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        chunkScanner.onClientTick();
    }

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (event.world == null || !event.world.isRemote || event.getChunk() == null) return;
        chunkScanner.enqueueChunk(event.getChunk().xPosition, event.getChunk().zPosition);
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.world == null || !event.world.isRemote) return;
        worldCache.flushAndClear();
        chunkScanner.reset();
        radarManager.clear();
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null) return;

        MapConfig cfg = MapConfig.get();

        if (MapKeybinds.KEY_SETTINGS.isPressed()) {
            mc.displayGuiScreen(new GuiMapSettings());
        } else if (MapKeybinds.KEY_TOGGLE_MINIMAP.isPressed()) {
            cfg.minimapEnabled = !cfg.minimapEnabled;
            MapConfig.save();
        } else if (MapKeybinds.KEY_ZOOM_IN.isPressed()) {
            cfg.minimapZoom = Math.min(8.0f, cfg.minimapZoom * 1.25f);
            MapConfig.save();
        } else if (MapKeybinds.KEY_ZOOM_OUT.isPressed()) {
            cfg.minimapZoom = Math.max(0.5f, cfg.minimapZoom / 1.25f);
            MapConfig.save();
        } else if (MapKeybinds.KEY_TOGGLE_CAVE_MODE.isPressed()) {
            MapConfig.CaveMode[] vals = MapConfig.CaveMode.values();
            cfg.caveMode = vals[(cfg.caveMode.ordinal() + 1) % vals.length];
            MapConfig.save();
            notifyPlayer("Cave Mode: " + cfg.caveMode.name());
        } else if (MapKeybinds.KEY_TOGGLE_ORE_GRID.isPressed()) {
            cfg.showOreVeinGrid = !cfg.showOreVeinGrid;
            MapConfig.save();
            notifyPlayer("GT Ore Vein Grid: " + (cfg.showOreVeinGrid ? "ON" : "OFF"));
        } else if (MapKeybinds.KEY_TOGGLE_CHUNK_GRID.isPressed()) {
            cfg.showChunkGrid = !cfg.showChunkGrid;
            MapConfig.save();
            notifyPlayer("Chunk Grid: " + (cfg.showChunkGrid ? "ON" : "OFF"));
        } else if (MapKeybinds.KEY_TOGGLE_SHAPE.isPressed()) {
            cfg.circularMinimap = !cfg.circularMinimap;
            MapConfig.save();
        } else if (MapKeybinds.KEY_WORLD_MAP.isPressed()) {
            notifyPlayer("World Map GUI is coming in Stage 2!");
        }
    }

    private static void notifyPlayer(String msg) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText("\u00a7b[Saifun's Map]\u00a7r " + msg));
        }
    }
}

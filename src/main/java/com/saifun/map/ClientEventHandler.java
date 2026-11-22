package com.saifun.map;

import com.saifun.map.compat.IGtnhLayerBridge;
import com.saifun.map.compat.ShareWhereIAmBridge;
import com.saifun.map.config.MapConfig;
import com.saifun.map.engine.ChunkScanner;
import com.saifun.map.engine.MapWorldCache;
import com.saifun.map.gui.GuiMapSettings;
import com.saifun.map.gui.GuiWaypointEdit;
import com.saifun.map.gui.GuiWaypoints;
import com.saifun.map.gui.GuiWorldMap;
import com.saifun.map.input.MapKeybinds;
import com.saifun.map.radar.EntityRadarManager;
import com.saifun.map.render.MinimapRenderer;
import com.saifun.map.util.Lang;
import com.saifun.map.waypoints.Waypoint;
import com.saifun.map.waypoints.WaypointManager;
import com.saifun.map.waypoints.WaypointWorldRenderer;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MathHelper;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;
import org.lwjgl.input.Keyboard;

public class ClientEventHandler {

    private static volatile GuiScreen pendingScreenToOpen = null;

    public static void scheduleOpenScreen(GuiScreen screen) {
        pendingScreenToOpen = screen;
    }

    private final MapWorldCache worldCache;
    private final ChunkScanner chunkScanner;
    private final EntityRadarManager radarManager;
    private final WaypointManager waypointManager;
    private final WaypointWorldRenderer waypointWorldRenderer;
    private final IGtnhLayerBridge gtnhBridge;
    private final MinimapRenderer minimapRenderer;

    public ClientEventHandler(
        MapWorldCache worldCache,
        ChunkScanner chunkScanner,
        EntityRadarManager radarManager,
        WaypointManager waypointManager,
        WaypointWorldRenderer waypointWorldRenderer,
        IGtnhLayerBridge gtnhBridge,
        MinimapRenderer minimapRenderer
    ) {
        this.worldCache = worldCache;
        this.chunkScanner = chunkScanner;
        this.radarManager = radarManager;
        this.waypointManager = waypointManager;
        this.waypointWorldRenderer = waypointWorldRenderer;
        this.gtnhBridge = gtnhBridge;
        this.minimapRenderer = minimapRenderer;
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type == RenderGameOverlayEvent.ElementType.ALL) {
            minimapRenderer.render(event.partialTicks);
        }
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        waypointWorldRenderer.renderInWorld(event.partialTicks);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (pendingScreenToOpen != null) {
            GuiScreen next = pendingScreenToOpen;
            pendingScreenToOpen = null;
            Minecraft.getMinecraft().displayGuiScreen(next);
        }
        chunkScanner.onClientTick();
        waypointManager.onClientTick();
        ShareWhereIAmBridge.onClientTick();
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
        waypointManager.clearSession();
        com.saifun.map.radar.EntityIconRenderer.clearEntityIdCache();
    }

    @SubscribeEvent
    public void onChatReceived(ClientChatReceivedEvent event) {
        if (event.message == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) return;

        // Parse plain-text shared JourneyMap waypoints from chat and present clickable [Save] [Edit] [Beacon] buttons!
        String raw = event.message.getUnformattedText();
        if (raw.startsWith("[Saifun's Map]")) return;
        Waypoint shared = WaypointManager.tryParseChatWaypoint(raw, mc.theWorld.provider.dimensionId);
        if (shared != null) {
            mc.func_152344_a(() -> WaypointManager.postClickableShareChat(null, shared, null));
        }
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || mc.theWorld == null || mc.thePlayer == null) return;

        // Support GTNH ShareWhereIAm in-world Insert hotkey even when JourneyMap is disabled
        if (ShareWhereIAmBridge.isAvailable() && !ShareWhereIAmBridge.isJourneyMapLoaded()
            && Keyboard.getEventKeyState() && !Keyboard.isRepeatEvent()
            && Keyboard.getEventKey() == ShareWhereIAmBridge.getShareKeyCode()) {
            ShareWhereIAmBridge.handleInWorldShareHotkey();
            return;
        }

        MapConfig cfg = MapConfig.get();

        if (MapKeybinds.KEY_WORLD_MAP.isPressed()) {
            mc.displayGuiScreen(new GuiWorldMap(worldCache, chunkScanner, radarManager, waypointManager, gtnhBridge));
        } else if (MapKeybinds.KEY_WAYPOINTS_MENU.isPressed()) {
            mc.displayGuiScreen(new GuiWaypoints(null, waypointManager));
        } else if (MapKeybinds.KEY_ADD_WAYPOINT.isPressed()) {
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
        } else if (MapKeybinds.KEY_SETTINGS.isPressed()) {
            mc.displayGuiScreen(new GuiMapSettings());
        } else if (MapKeybinds.KEY_TOGGLE_MINIMAP.isPressed()) {
            cfg.minimapEnabled = !cfg.minimapEnabled;
            MapConfig.save();
        } else if (MapKeybinds.KEY_ENLARGE_MINIMAP.isPressed()) {
            minimapRenderer.toggleEnlarged();
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
            if (cfg.caveMode == MapConfig.CaveMode.ON) {
                chunkScanner.triggerCaveRescan();
            }
            String modeKey = cfg.caveMode == MapConfig.CaveMode.ON
                ? "saifunsmap.settings.cave_mode.on"
                : (cfg.caveMode == MapConfig.CaveMode.OFF ? "saifunsmap.settings.cave_mode.off" : "saifunsmap.settings.cave_mode.auto");
            notifyPlayer(Lang.tr("saifunsmap.chat.cave_mode", Lang.tr(modeKey)));
        } else if (MapKeybinds.KEY_TOGGLE_ORE_GRID.isPressed()) {
            cfg.showOreVeinGrid = !cfg.showOreVeinGrid;
            MapConfig.save();
            notifyPlayer(Lang.tr("saifunsmap.chat.ore_grid", Lang.onOff(cfg.showOreVeinGrid)));
        } else if (MapKeybinds.KEY_TOGGLE_CHUNK_GRID.isPressed()) {
            cfg.showChunkGrid = !cfg.showChunkGrid;
            MapConfig.save();
            notifyPlayer(Lang.tr("saifunsmap.chat.chunk_grid", Lang.onOff(cfg.showChunkGrid)));
        } else if (MapKeybinds.KEY_TOGGLE_SHAPE.isPressed()) {
            cfg.circularMinimap = !cfg.circularMinimap;
            MapConfig.save();
        }
    }

    private static void notifyPlayer(String msg) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText("\u00a7b[Saifun's Map]\u00a7r " + msg));
        }
    }
}

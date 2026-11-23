package com.saifun.map.compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.saifun.map.SaifunsMapMod;
import com.saifun.map.waypoints.Waypoint;
import com.saifun.map.waypoints.WaypointManager;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.network.FMLEmbeddedChannel;
import cpw.mods.fml.common.network.FMLIndexedMessageToMessageCodec;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleIndexedCodec;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import org.lwjgl.input.Keyboard;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * Full bidirectional zero-Mixin bridge into GTNH's ShareWhereIAm mod (sharewhereiam 2.x).
 * - Outgoing: sends native MessageShareWaypoint / MessageMarkWaypoint packets so teammates on JourneyMap
 *   receive clickable [JourneyMap] [Save] [Edit] [Toggle] cards and 3D world pings.
 * - Incoming: taps the client-side "sharewhereiam" Netty channel so Saifun's Map users (with JourneyMap
 *   disabled) receive clickable [Saifun's Map] [Save] [Edit] [Beacon] chat cards and 15s 3D world pings!
 */
public final class ShareWhereIAmBridge {

    private static final String CHANNEL_NAME = "sharewhereiam";
    private static final String HANDLER_NAME = "saifunsmap_sharewhereiam_bridge";
    private static final long PING_DURATION_MS = 15_000L;

    private static boolean initialized = false;
    private static boolean available = false;
    private static boolean journeyMapLoaded = false;

    private static SimpleNetworkWrapper network;
    private static Class<?> clsSharedWaypoint;
    private static Constructor<?> ctorSharedWaypoint;
    private static Class<?> clsPlayerWaypoint;
    private static Constructor<?> ctorPlayerWaypoint;
    private static Class<?> clsMsgShare;
    private static Constructor<?> ctorMsgShare;
    private static Field fldSharePlayerName;
    private static Field fldShareJson;
    private static Field fldShareExtra;
    private static Class<?> clsMsgMark;
    private static Constructor<?> ctorMsgMark;
    private static Field fldMarkPlayerName;
    private static Field fldMarkJson;
    private static KeyBinding shareKeyBinding;
    private static WaypointManager cachedWaypointManager;
    private static boolean pipelineVerified = false;
    private static volatile Waypoint pendingFallbackWp = null;
    private static volatile long pendingFallbackAtMs = 0L;

    private ShareWhereIAmBridge() {}

    public static void init(WaypointManager waypointManager) {
        if (initialized) return;
        initialized = true;
        journeyMapLoaded = Loader.isModLoaded("journeymap");

        if (!Loader.isModLoaded("sharewhereiam")) {
            return;
        }

        try {
            Class<?> clsNetworkHandler = Class.forName("com.muxiu1997.sharewhereiam.network.NetworkHandler");
            Field netField = clsNetworkHandler.getField("network");
            network = (SimpleNetworkWrapper) netField.get(null);

            clsSharedWaypoint = Class.forName("com.muxiu1997.sharewhereiam.model.SharedWaypoint");
            ctorSharedWaypoint = clsSharedWaypoint.getConstructor(
                String.class, int.class, int.class, int.class, int.class, int.class
            );

            clsPlayerWaypoint = Class.forName("com.muxiu1997.sharewhereiam.util.WaypointUtil$PlayerWaypoint");
            ctorPlayerWaypoint = clsPlayerWaypoint.getConstructor(EntityPlayer.class, clsSharedWaypoint);

            clsMsgShare = Class.forName("com.muxiu1997.sharewhereiam.network.MessageShareWaypoint");
            ctorMsgShare = clsMsgShare.getConstructor(clsPlayerWaypoint, String.class);
            fldSharePlayerName = clsMsgShare.getField("playerName");
            fldShareJson = clsMsgShare.getField("waypointJson");
            fldShareExtra = clsMsgShare.getField("additionalInformation");

            clsMsgMark = Class.forName("com.muxiu1997.sharewhereiam.network.MessageMarkWaypoint");
            ctorMsgMark = clsMsgMark.getConstructor(clsPlayerWaypoint);
            fldMarkPlayerName = clsMsgMark.getDeclaredField("playerName");
            fldMarkPlayerName.setAccessible(true);
            fldMarkJson = clsMsgMark.getDeclaredField("waypointJson");
            fldMarkJson.setAccessible(true);

            try {
                Class<?> clsKeys = Class.forName("com.muxiu1997.sharewhereiam.client.key.KeyBindings");
                Field kbField = clsKeys.getField("WaypointShare");
                shareKeyBinding = (KeyBinding) kbField.get(null);
            } catch (Throwable ignored) {}

            // NOTE: ShareWhereIAm registers its own packet discriminators (0..3) in ShareWhereIAm.postInit()
            // even when JourneyMap is absent (Mods.isEnabled() returns true without JourneyMap).
            // Never invoke NetworkLoader.load() manually here, or discriminators shift to 4..7 and cause
            // server-side DecoderException kicks!
            cachedWaypointManager = waypointManager;
            ensurePipelineHooked();

            available = network != null;
            SaifunsMapMod.LOG.info("ShareWhereIAm bridge initialized (channel hook active, JourneyMap loaded: {}).", journeyMapLoaded);
        } catch (Throwable t) {
            available = false;
            SaifunsMapMod.LOG.warn("ShareWhereIAm bridge init failed: {}", t.toString());
        }
    }

    public static boolean isAvailable() {
        return available;
    }

    public static boolean isJourneyMapLoaded() {
        return journeyMapLoaded;
    }

    public static int getShareKeyCode() {
        if (shareKeyBinding != null && shareKeyBinding.getKeyCode() > 0) {
            return shareKeyBinding.getKeyCode();
        }
        return Keyboard.KEY_INSERT;
    }

    /**
     * Shares a waypoint via GTNH's ShareWhereIAm packet protocol so both JourneyMap and Saifun's Map
     * players receive native interactive chat buttons. Returns false if ShareWhereIAm is unavailable.
     */
    public static boolean shareWaypoint(Waypoint wp, String extraInfo) {
        if (!available || wp == null) return false;
        Minecraft mc = Minecraft.getMinecraft();
        EntityClientPlayerMP player = mc.thePlayer;
        if (player == null) return false;

        try {
            Object sw = ctorSharedWaypoint.newInstance(
                wp.name != null ? wp.name : "Waypoint",
                wp.x,
                wp.y,
                wp.z,
                wp.color & 0xFFFFFF,
                wp.dimensionId
            );
            Object pw = ctorPlayerWaypoint.newInstance(player, sw);
            Object msg = ctorMsgShare.newInstance(pw, extraInfo != null ? extraInfo : "");
            pendingFallbackWp = wp;
            pendingFallbackAtMs = System.currentTimeMillis() + 650L;
            network.sendToServer((cpw.mods.fml.common.network.simpleimpl.IMessage) msg);
            return true;
        } catch (Throwable t) {
            pendingFallbackWp = null;
            SaifunsMapMod.LOG.warn("ShareWhereIAm shareWaypoint failed, falling back to chat: {}", t.toString());
            return false;
        }
    }

    private static void ensurePipelineHooked() {
        if (cachedWaypointManager == null) return;
        try {
            FMLEmbeddedChannel channel = NetworkRegistry.INSTANCE.getChannel(CHANNEL_NAME, Side.CLIENT);
            if (channel == null) return;
            String codecName = null;
            for (java.util.Map.Entry<String, ChannelHandler> e : channel.pipeline()) {
                if (e.getValue() instanceof FMLIndexedMessageToMessageCodec || e.getValue() instanceof SimpleIndexedCodec) {
                    codecName = e.getKey();
                    break;
                }
            }
            if (channel.pipeline().get(HANDLER_NAME) != null) {
                channel.pipeline().remove(HANDLER_NAME);
            }
            ChannelHandler interceptor = new IncomingShareInterceptor(cachedWaypointManager);
            if (codecName != null) {
                channel.pipeline().addAfter(codecName, HANDLER_NAME, interceptor);
            } else {
                channel.pipeline().addFirst(HANDLER_NAME, interceptor);
            }
        } catch (Throwable t) {
            SaifunsMapMod.LOG.debug("ShareWhereIAm pipeline hook retry failed: {}", t.toString());
        }
    }

    /**
     * Called each client tick:
     * 1. Ensures our Netty interceptor sits immediately after the codec (even if ShareWhereIAm loaded after us!).
     * 2. If a server does not echo back MessageShareWaypoint within 0.65s, cleanly falls back to standard chat
     *    format (which ClientEventHandler immediately transforms in-place into a single clickable card!).
     */
    public static void onClientTick() {
        if (available && !pipelineVerified) {
            pipelineVerified = true;
            ensurePipelineHooked();
        }
        Waypoint fb = pendingFallbackWp;
        if (fb != null && System.currentTimeMillis() >= pendingFallbackAtMs) {
            pendingFallbackWp = null;
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.thePlayer != null) {
                mc.thePlayer.sendChatMessage(fb.formatChatShare());
            }
        }
    }

    /**
     * Sends a temporary 3D world ping via ShareWhereIAm (MessageMarkWaypoint).
     */
    public static boolean markLocation(String name, int x, int y, int z, int dim, int color) {
        if (!available) return false;
        Minecraft mc = Minecraft.getMinecraft();
        EntityClientPlayerMP player = mc.thePlayer;
        if (player == null) return false;

        try {
            Object sw = ctorSharedWaypoint.newInstance(
                name != null && !name.isEmpty() ? name : player.getDisplayName(),
                x, y, z, color & 0xFFFFFF, dim
            );
            Object pw = ctorPlayerWaypoint.newInstance(player, sw);
            Object msg = ctorMsgMark.newInstance(pw);
            network.sendToServer((cpw.mods.fml.common.network.simpleimpl.IMessage) msg);
            return true;
        } catch (Throwable t) {
            SaifunsMapMod.LOG.debug("ShareWhereIAm markLocation failed: {}", t.toString());
            return false;
        }
    }

    /**
     * Handles ShareWhereIAm's in-world Insert hotkey when JourneyMap is disabled:
     * - Insert: 3D ping at crosshair block (128 blocks)
     * - Shift + Insert: 3D ping at player's feet
     * - Ctrl + Insert: share crosshair block in chat
     * - Ctrl + Shift + Insert: share player's location in chat
     */
    public static void handleInWorldShareHotkey() {
        if (!available || journeyMapLoaded) return;
        Minecraft mc = Minecraft.getMinecraft();
        EntityClientPlayerMP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) return;

        boolean shift = Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
        boolean ctrl = Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);

        int x, y, z;
        if (shift) {
            x = MathHelper.floor_double(player.posX);
            y = MathHelper.floor_double(player.posY);
            z = MathHelper.floor_double(player.posZ);
        } else {
            MovingObjectPosition mop = player.rayTrace(128.0, 1.0f);
            if (mop == null || mop.typeOfHit == MovingObjectPosition.MovingObjectType.MISS) {
                x = MathHelper.floor_double(player.posX);
                y = MathHelper.floor_double(player.posY);
                z = MathHelper.floor_double(player.posZ);
            } else {
                x = mop.blockX;
                y = mop.blockY;
                z = mop.blockZ;
            }
        }
        int dim = mc.theWorld.provider.dimensionId;
        String label = player.getDisplayName();

        if (ctrl) {
            Waypoint wp = new Waypoint(label, null, x, y, z, dim, 0x3B82F6, Waypoint.Type.NORMAL);
            shareWaypoint(wp, "");
        } else {
            markLocation(label, x, y, z, dim, 0xF59E0B);
        }
    }

    private static Waypoint parseSharedWaypointJson(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JsonObject obj = new JsonParser().parse(json).getAsJsonObject();
            String name = obj.has("name") && !obj.get("name").isJsonNull() ? obj.get("name").getAsString() : "Shared Waypoint";
            int rawX = obj.has("x") ? obj.get("x").getAsInt() : 0;
            int y = obj.has("y") ? obj.get("y").getAsInt() : 70;
            int rawZ = obj.has("z") ? obj.get("z").getAsInt() : 0;

            int dim = 0;
            if (obj.has("primaryDimension") && !obj.get("primaryDimension").isJsonNull()) {
                dim = obj.get("primaryDimension").getAsInt();
            } else if (obj.has("dimensions") && obj.get("dimensions").isJsonArray()) {
                JsonArray arr = obj.getAsJsonArray("dimensions");
                if (arr.size() > 0) {
                    JsonElement first = arr.get(0);
                    if (first != null && !first.isJsonNull()) {
                        dim = first.getAsInt();
                    }
                }
            }

            // JourneyMap / ShareWhereIAm stores Nether (-1) coordinates multiplied by 8 inside JSON!
            int x = (dim == -1) ? (rawX / 8) : rawX;
            int z = (dim == -1) ? (rawZ / 8) : rawZ;

            int r = obj.has("r") ? obj.get("r").getAsInt() : 59;
            int g = obj.has("g") ? obj.get("g").getAsInt() : 130;
            int b = obj.has("b") ? obj.get("b").getAsInt() : 246;
            int color = ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
            if (color == 0) color = 0x3B82F6;

            return new Waypoint(name, null, x, y, z, dim, color, Waypoint.Type.NORMAL);
        } catch (Throwable t) {
            SaifunsMapMod.LOG.debug("Failed to parse SharedWaypoint JSON: {}", t.toString());
            return null;
        }
    }

    @ChannelHandler.Sharable
    private static final class IncomingShareInterceptor extends ChannelInboundHandlerAdapter {
        private final WaypointManager waypointManager;

        IncomingShareInterceptor(WaypointManager waypointManager) {
            this.waypointManager = waypointManager;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            boolean consumed = false;
            if (!journeyMapLoaded && msg != null) {
                try {
                    if (clsMsgShare != null && clsMsgShare.isInstance(msg)) {
                        consumed = true;
                        pendingFallbackWp = null; // Server echoed the packet — no chat fallback needed!
                        final String sender = (String) fldSharePlayerName.get(msg);
                        final String json = (String) fldShareJson.get(msg);
                        final String extra = (String) fldShareExtra.get(msg);
                        final Waypoint wp = parseSharedWaypointJson(json);
                        if (wp != null) {
                            Minecraft.getMinecraft().func_152344_a(() ->
                                WaypointManager.postClickableShareChat(sender, wp, extra)
                            );
                        }
                    } else if (clsMsgMark != null && clsMsgMark.isInstance(msg)) {
                        consumed = true;
                        final String sender = (String) fldMarkPlayerName.get(msg);
                        final String json = (String) fldMarkJson.get(msg);
                        final Waypoint wp = parseSharedWaypointJson(json);
                        if (wp != null) {
                            Minecraft.getMinecraft().func_152344_a(() ->
                                waypointManager.addTransientPing(
                                    sender,
                                    wp.name,
                                    wp.x, wp.y, wp.z,
                                    wp.dimensionId,
                                    wp.color,
                                    PING_DURATION_MS
                                )
                            );
                        }
                    }
                } catch (Throwable t) {
                    SaifunsMapMod.LOG.debug("Error intercepting ShareWhereIAm packet: {}", t.toString());
                }
            }
            // When JourneyMap is absent, consume Share/Mark packets so ShareWhereIAm's JourneyMap-only
            // client handler does not run downstream (which would call missing JourneyMap classes).
            if (!consumed) {
                ctx.fireChannelRead(msg);
            }
        }
    }
}

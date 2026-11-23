package com.saifun.map.network;

import com.saifun.map.ClientProxy;
import com.saifun.map.SaifunsMapMod;
import com.saifun.map.compat.ShareWhereIAmBridge;
import com.saifun.map.waypoints.Waypoint;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;

import java.util.ArrayList;
import java.util.List;

public final class MapNetwork {

    public static SimpleNetworkWrapper INSTANCE;
    private static volatile boolean serverModPresent = false;

    private MapNetwork() {}

    public static void init() {
        INSTANCE = NetworkRegistry.INSTANCE.newSimpleChannel(SaifunsMapMod.MODID);
        INSTANCE.registerMessage(PacketPlayerPositions.Handler.class, PacketPlayerPositions.class, 0, Side.CLIENT);
        INSTANCE.registerMessage(PacketServerHello.Handler.class, PacketServerHello.class, 1, Side.CLIENT);
        INSTANCE.registerMessage(PacketQuickPing.C2SHandler.class, PacketQuickPing.C2S.class, 2, Side.SERVER);
        INSTANCE.registerMessage(PacketQuickPing.S2CHandler.class, PacketQuickPing.S2C.class, 3, Side.CLIENT);
        INSTANCE.registerMessage(PacketShareWaypointGroup.C2SHandler.class, PacketShareWaypointGroup.C2S.class, 4, Side.SERVER);
        INSTANCE.registerMessage(PacketShareWaypointGroup.S2CHandler.class, PacketShareWaypointGroup.S2C.class, 5, Side.CLIENT);
    }

    public static boolean isServerModPresent() {
        return serverModPresent;
    }

    public static void setServerModPresent(boolean present) {
        serverModPresent = present;
    }

    /**
     * Hybrid 3D Quick Ping sender:
     * - If Saifun's Map is installed server-side: broadcasts native PacketQuickPing to all players in the dimension.
     * - Otherwise: bridges through GTNH ShareWhereIAm if available, and always displays the animated 3D ping locally.
     */
    public static void sendQuickPing(int x, int y, int z, int dimId, String customLabel) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityClientPlayerMP player = mc.thePlayer;
        if (player == null) return;

        String sender = player.getCommandSenderName();
        String label = (customLabel != null && !customLabel.trim().isEmpty()) ? customLabel.trim() : sender;
        int color = 0x38BDF8;

        if (serverModPresent && INSTANCE != null) {
            try {
                INSTANCE.sendToServer(new PacketQuickPing.C2S(sender, label, x, y, z, dimId, color));
                return;
            } catch (Throwable ignored) {}
        }

        // Client-only server fallback: use ShareWhereIAm if present + show immediate local animated 3D ping
        if (ShareWhereIAmBridge.isAvailable()) {
            ShareWhereIAmBridge.markLocation(label, x, y, z, dimId, color);
        }
        if (SaifunsMapMod.proxy instanceof ClientProxy) {
            ClientProxy client = (ClientProxy) SaifunsMapMod.proxy;
            if (client.getWaypointManager() != null) {
                client.getWaypointManager().addTransientPing(
                    sender, label, x, y, z, dimId, color, PacketQuickPing.PING_DURATION_MS
                );
            }
        }
    }

    /**
     * Shares an entire waypoint group via server protocol (only available when server has Saifun's Map).
     */
    public static boolean sendShareGroup(String groupName, List<Waypoint> waypoints) {
        if (!serverModPresent || INSTANCE == null || waypoints == null || waypoints.isEmpty()) return false;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return false;

        List<PacketShareWaypointGroup.SharedEntry> entries = new ArrayList<>(waypoints.size());
        for (Waypoint wp : waypoints) {
            if (wp == null || wp.temporary) continue;
            entries.add(new PacketShareWaypointGroup.SharedEntry(
                wp.name, wp.x, wp.y, wp.z, wp.dimensionId, wp.color, wp.getEffectiveIcon().ordinal()
            ));
        }
        if (entries.isEmpty()) return false;

        try {
            INSTANCE.sendToServer(new PacketShareWaypointGroup.C2S(mc.thePlayer.getCommandSenderName(), groupName, entries));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}

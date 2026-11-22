package com.saifun.map.network;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional server-side tracker: broadcasts player positions per dimension every 10 ticks
 * so players running Saifun's Map see everyone across the entire world (no FairPlay limits).
 */
public class ServerRadarTracker {

    private int tickCounter = 0;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        tickCounter++;
        if (tickCounter < 10) return;
        tickCounter = 0;

        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null) return;

        @SuppressWarnings("unchecked")
        List<EntityPlayerMP> allPlayers = server.getConfigurationManager().playerEntityList;
        if (allPlayers == null || allPlayers.size() <= 1) return;

        Map<Integer, List<EntityPlayerMP>> byDimension = new HashMap<>();
        for (EntityPlayerMP p : allPlayers) {
            if (p == null || p.isDead) continue;
            byDimension.computeIfAbsent(p.dimension, k -> new ArrayList<>()).add(p);
        }

        for (Map.Entry<Integer, List<EntityPlayerMP>> entry : byDimension.entrySet()) {
            int dim = entry.getKey();
            List<EntityPlayerMP> dimPlayers = entry.getValue();
            if (dimPlayers.size() <= 1) continue;

            List<PacketPlayerPositions.PlayerPos> positions = new ArrayList<>(dimPlayers.size());
            for (EntityPlayerMP p : dimPlayers) {
                positions.add(new PacketPlayerPositions.PlayerPos(
                    p.getUniqueID(),
                    p.getCommandSenderName(),
                    p.posX,
                    p.posY,
                    p.posZ,
                    p.rotationYaw
                ));
            }

            PacketPlayerPositions packet = new PacketPlayerPositions(dim, positions);
            for (EntityPlayerMP recipient : dimPlayers) {
                try {
                    MapNetwork.INSTANCE.sendTo(packet, recipient);
                } catch (Exception ignored) {
                    // Client may not have Saifun's Map installed — ignore silently.
                }
            }
        }
    }
}

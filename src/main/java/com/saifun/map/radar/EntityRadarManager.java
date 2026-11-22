package com.saifun.map.radar;

import com.saifun.map.config.MapConfig;
import com.saifun.map.network.PacketPlayerPositions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.IEntityOwnable;
import net.minecraft.entity.boss.IBossDisplayData;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.passive.EntityAmbientCreature;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.passive.EntityWaterMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Collects all visible entities for the minimap and world map radar.
 * Deliberately implements ZERO FairPlay or server-side radar suppression:
 * all players and mobs known to the client (plus optional server-synced long-range players)
 * are always visible according to the user's own settings.
 */
public class EntityRadarManager {

    private static final long REMOTE_PLAYER_TTL_MS = 5000L;

    private volatile int remoteDimension = Integer.MIN_VALUE;
    private volatile long lastRemoteUpdateMs = 0L;
    private volatile List<PacketPlayerPositions.PlayerPos> remotePlayers = Collections.emptyList();

    private final List<RadarEntity> snapshot = new ArrayList<>();

    public void updateRemotePlayers(int dimensionId, List<PacketPlayerPositions.PlayerPos> players) {
        this.remoteDimension = dimensionId;
        this.remotePlayers = players != null ? new ArrayList<>(players) : Collections.emptyList();
        this.lastRemoteUpdateMs = System.currentTimeMillis();
    }

    public void clear() {
        this.remotePlayers = Collections.emptyList();
        this.lastRemoteUpdateMs = 0L;
        this.snapshot.clear();
    }

    public List<RadarEntity> collectEntities(float partialTicks) {
        snapshot.clear();
        MapConfig cfg = MapConfig.get();
        if (!cfg.radarEnabled) return snapshot;

        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.theWorld;
        EntityClientPlayerMP self = mc.thePlayer;
        if (world == null || self == null) return snapshot;

        Set<UUID> seenPlayers = new HashSet<>();
        seenPlayers.add(self.getUniqueID());

        @SuppressWarnings("unchecked")
        List<Entity> loaded = world.loadedEntityList;
        for (int i = 0; i < loaded.size(); i++) {
            Entity entity = loaded.get(i);
            if (entity == null || entity == self || entity.isDead) continue;

            EntityCategory category = classify(entity);
            if (!isCategoryEnabled(cfg, category)) continue;

            double ex = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks;
            double ey = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks;
            double ez = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks;

            String name = entity instanceof EntityPlayer
                ? ((EntityPlayer) entity).getCommandSenderName()
                : entity.getCommandSenderName();

            if (entity instanceof EntityPlayer) {
                seenPlayers.add(entity.getUniqueID());
            }

            snapshot.add(new RadarEntity(
                entity.getUniqueID(),
                name,
                ex,
                ey,
                ez,
                entity.rotationYaw,
                category,
                entity,
                false
            ));
        }

        // Merge optional long-range server-synced players
        if (cfg.showPlayers && cfg.serverPlayerSyncEnabled) {
            long now = System.currentTimeMillis();
            if (now - lastRemoteUpdateMs <= REMOTE_PLAYER_TTL_MS && remoteDimension == world.provider.dimensionId) {
                List<PacketPlayerPositions.PlayerPos> remotes = this.remotePlayers;
                for (int i = 0; i < remotes.size(); i++) {
                    PacketPlayerPositions.PlayerPos rp = remotes.get(i);
                    if (seenPlayers.contains(rp.uuid)) continue;
                    seenPlayers.add(rp.uuid);
                    snapshot.add(new RadarEntity(
                        rp.uuid,
                        rp.name,
                        rp.x,
                        rp.y,
                        rp.z,
                        rp.yaw,
                        EntityCategory.PLAYER,
                        null,
                        true
                    ));
                }
            }
        }

        // Sort: items/passive drawn first, hostile next, bosses & players on top!
        if (snapshot.size() > cfg.maxRadarEntities) {
            final double px = self.posX;
            final double pz = self.posZ;
            snapshot.sort(Comparator.comparingDouble(e -> {
                if (e.category == EntityCategory.PLAYER || e.category == EntityCategory.BOSS) {
                    return -1e12; // Always keep players & bosses
                }
                double dx = e.x - px;
                double dz = e.z - pz;
                return dx * dx + dz * dz;
            }));
            if (snapshot.size() > cfg.maxRadarEntities) {
                snapshot.subList(cfg.maxRadarEntities, snapshot.size()).clear();
            }
        }

        snapshot.sort(Comparator.comparingInt(e -> priority(e.category)));
        return snapshot;
    }

    private static int priority(EntityCategory c) {
        switch (c) {
            case ITEM: return 0;
            case OTHER: return 1;
            case PASSIVE: return 2;
            case TAMED: return 3;
            case HOSTILE: return 4;
            case BOSS: return 5;
            case PLAYER: return 6;
            default: return 1;
        }
    }

    public static EntityCategory classify(Entity entity) {
        if (entity instanceof EntityPlayer) return EntityCategory.PLAYER;
        if (entity instanceof IBossDisplayData) return EntityCategory.BOSS;
        if (entity instanceof EntityItem) return EntityCategory.ITEM;
        if (entity instanceof IEntityOwnable) {
            String owner = ((IEntityOwnable) entity).func_152113_b();
            if (owner != null && !owner.isEmpty()) {
                return EntityCategory.TAMED;
            }
        }
        if (entity instanceof IMob) return EntityCategory.HOSTILE;
        if (entity instanceof EntityAnimal
            || entity instanceof EntityVillager
            || entity instanceof EntityWaterMob
            || entity instanceof EntityAmbientCreature) {
            return EntityCategory.PASSIVE;
        }
        if (entity instanceof EntityLivingBase) return EntityCategory.OTHER;
        return EntityCategory.OTHER;
    }

    private static boolean isCategoryEnabled(MapConfig cfg, EntityCategory cat) {
        switch (cat) {
            case PLAYER: return cfg.showPlayers;
            case BOSS: return cfg.showBossMobs;
            case HOSTILE: return cfg.showHostileMobs;
            case TAMED: return cfg.showTamedMobs;
            case PASSIVE: return cfg.showPassiveMobs;
            case ITEM: return cfg.showItems;
            default: return false;
        }
    }
}

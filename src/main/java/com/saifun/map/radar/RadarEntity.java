package com.saifun.map.radar;

import net.minecraft.entity.Entity;

import java.util.UUID;

public class RadarEntity {

    public final UUID uuid;
    public final String name;
    public final double x;
    public final double y;
    public final double z;
    public final float yaw;
    public final EntityCategory category;
    public final Entity entity; // null for long-range server-synced players
    public final boolean remote;

    public RadarEntity(
        UUID uuid,
        String name,
        double x,
        double y,
        double z,
        float yaw,
        EntityCategory category,
        Entity entity,
        boolean remote
    ) {
        this.uuid = uuid;
        this.name = name;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.category = category;
        this.entity = entity;
        this.remote = remote;
    }
}

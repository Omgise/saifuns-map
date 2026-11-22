package com.saifun.map.network;

import com.saifun.map.SaifunsMapMod;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

public final class MapNetwork {

    public static SimpleNetworkWrapper INSTANCE;

    private MapNetwork() {}

    public static void init() {
        INSTANCE = NetworkRegistry.INSTANCE.newSimpleChannel(SaifunsMapMod.MODID);
        INSTANCE.registerMessage(PacketPlayerPositions.Handler.class, PacketPlayerPositions.class, 0, Side.CLIENT);
    }
}

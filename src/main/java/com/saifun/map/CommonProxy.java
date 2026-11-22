package com.saifun.map;

import com.saifun.map.network.MapNetwork;
import com.saifun.map.network.ServerRadarTracker;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        MapNetwork.init();
    }

    public void init(FMLInitializationEvent event) {
        FMLCommonHandler.instance().bus().register(new ServerRadarTracker());
    }

    public void postInit(FMLPostInitializationEvent event) {
        SaifunsMapMod.LOG.info("{} {} initialized (server/common).", SaifunsMapMod.NAME, Tags.VERSION);
    }
}

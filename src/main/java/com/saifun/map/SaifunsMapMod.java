package com.saifun.map;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(
    modid = SaifunsMapMod.MODID,
    name = SaifunsMapMod.NAME,
    version = Tags.VERSION,
    acceptableRemoteVersions = "*"
)
public class SaifunsMapMod {

    public static final String MODID = "saifunsmap";
    public static final String NAME = "Saifun's Map";
    public static final Logger LOG = LogManager.getLogger(MODID);

    @Mod.Instance(MODID)
    public static SaifunsMapMod instance;

    @SidedProxy(
        clientSide = "com.saifun.map.ClientProxy",
        serverSide = "com.saifun.map.CommonProxy"
    )
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }
}

package com.saifun.map.util;

import net.minecraft.client.resources.I18n;
import net.minecraft.util.StatCollector;

/**
 * Lightweight client localization helper wrapping Minecraft 1.7.10 I18n.
 */
public final class Lang {

    private Lang() {}

    public static String tr(String key, Object... args) {
        try {
            return I18n.format(key, args);
        } catch (Throwable ignored) {
            return args.length == 0
                ? StatCollector.translateToLocal(key)
                : StatCollector.translateToLocalFormatted(key, args);
        }
    }

    public static String onOff(boolean value) {
        return tr(value ? "saifunsmap.common.on" : "saifunsmap.common.off");
    }
}

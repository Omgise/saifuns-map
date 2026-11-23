package com.saifun.map.input;

import cpw.mods.fml.client.registry.ClientRegistry;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;

public final class MapKeybinds {

    private static final String CATEGORY = "key.categories.saifunsmap";

    public static KeyBinding KEY_QUICK_MENU;
    public static KeyBinding KEY_SETTINGS;
    public static KeyBinding KEY_WORLD_MAP;
    public static KeyBinding KEY_TOGGLE_MINIMAP;
    public static KeyBinding KEY_ENLARGE_MINIMAP;
    public static KeyBinding KEY_ZOOM_IN;
    public static KeyBinding KEY_ZOOM_OUT;
    public static KeyBinding KEY_TOGGLE_CAVE_MODE;
    public static KeyBinding KEY_TOGGLE_ORE_GRID;
    public static KeyBinding KEY_TOGGLE_CHUNK_GRID;
    public static KeyBinding KEY_TOGGLE_SHAPE;
    public static KeyBinding KEY_ADD_WAYPOINT;
    public static KeyBinding KEY_WAYPOINTS_MENU;
    public static KeyBinding KEY_QUICK_PING;

    private MapKeybinds() {}

    public static void register() {
        // Essential default keys only; all toggle binds remain available in Controls unbound by default
        KEY_QUICK_MENU = reg("key.saifunsmap.quick_menu", Keyboard.KEY_Y);
        KEY_WORLD_MAP = reg("key.saifunsmap.worldmap", Keyboard.KEY_J);
        KEY_ADD_WAYPOINT = reg("key.saifunsmap.add_waypoint", Keyboard.KEY_B);
        KEY_WAYPOINTS_MENU = reg("key.saifunsmap.waypoints", Keyboard.KEY_U);
        KEY_ENLARGE_MINIMAP = reg("key.saifunsmap.enlarge_minimap", Keyboard.KEY_Z);
        KEY_ZOOM_IN = reg("key.saifunsmap.zoom_in", Keyboard.KEY_EQUALS);
        KEY_ZOOM_OUT = reg("key.saifunsmap.zoom_out", Keyboard.KEY_MINUS);

        // Unbound by default (accessible via Quick Menu / World Map, or freely bindable in Controls)
        KEY_QUICK_PING = reg("key.saifunsmap.quick_ping", Keyboard.KEY_NONE);
        KEY_SETTINGS = reg("key.saifunsmap.settings", Keyboard.KEY_NONE);
        KEY_TOGGLE_MINIMAP = reg("key.saifunsmap.toggle_minimap", Keyboard.KEY_NONE);
        KEY_TOGGLE_CAVE_MODE = reg("key.saifunsmap.toggle_cave", Keyboard.KEY_NONE);
        KEY_TOGGLE_ORE_GRID = reg("key.saifunsmap.toggle_ore_grid", Keyboard.KEY_NONE);
        KEY_TOGGLE_CHUNK_GRID = reg("key.saifunsmap.toggle_chunk_grid", Keyboard.KEY_NONE);
        KEY_TOGGLE_SHAPE = reg("key.saifunsmap.toggle_shape", Keyboard.KEY_NONE);
    }

    private static KeyBinding reg(String desc, int keyCode) {
        KeyBinding kb = new KeyBinding(desc, keyCode, CATEGORY);
        ClientRegistry.registerKeyBinding(kb);
        return kb;
    }
}

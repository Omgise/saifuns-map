package com.saifun.map.render;

import com.saifun.map.waypoints.Waypoint;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.nio.ByteBuffer;

/**
 * Crisp 16x16 pixel-art waypoint icon atlas renderer (GL_NEAREST, zero external PNGs).
 * Uses a two-layer atlas so every icon gets a true black 1px pixel outline + white highlights
 * while its body smoothly adopts any custom waypoint color!
 * Also delegates tracked GTNH Navigator waypoints (Ore Veins, Thaumcraft Nodes, etc.)
 * to their native fullscreen overlay icon via Waypoint.customIconDrawer.
 */
public final class WaypointIconRenderer {

    private static final int ICON_SIZE = 16;
    private static final Waypoint.IconType[] ICONS = Waypoint.IconType.values();
    private static final int ATLAS_W = ICON_SIZE * ICONS.length;
    private static final int ATLAS_H = ICON_SIZE * 2; // top row: tinted body, bottom row: crisp outline & fixed accents

    private static int atlasTextureId = 0;

    private WaypointIconRenderer() {}

    /**
     * Draws a waypoint's icon centered at (cx, cy) with visual size {@code size}.
     * If the waypoint carries a native GTNH Navigator custom icon drawer (ore vein, node, etc.),
     * renders that exact icon inside a crisp tactical frame; otherwise renders the pixel-art icon.
     */
    public static void drawWaypointIcon(
        Waypoint wp,
        double cx,
        double cy,
        double size,
        float alpha,
        int overrideColor
    ) {
        if (wp == null) return;
        if (wp.customIconDrawer != null) {
            try {
                if (wp.customIconDrawer.draw(cx, cy, size, alpha)) {
                    return;
                }
            } catch (Throwable ignored) {}
        }
        drawIcon(wp.getEffectiveIcon(), cx, cy, size, overrideColor, alpha);
    }

    /**
     * Draws one of the 12 built-in 16x16 pixel-art icons centered at (cx, cy).
     */
    public static void drawIcon(
        Waypoint.IconType type,
        double cx,
        double cy,
        double size,
        int rgbColor,
        float alpha
    ) {
        if (type == null) type = Waypoint.IconType.PIN;
        ensureAtlas();
        if (atlasTextureId == 0) return;

        int idx = type.ordinal();
        double u1 = (idx * (double) ICON_SIZE) / ATLAS_W;
        double u2 = ((idx + 1) * (double) ICON_SIZE) / ATLAS_W;

        double half = size * 0.5;
        double x1 = cx - half;
        double y1 = cy - half;
        double x2 = cx + half;
        double y2 = cy + half;

        GLDraw.ensureBlend();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlasTextureId);

        // Pass 1: Tinted colored body (top half of atlas: v = 0.0 .. 0.5)
        float r = ((rgbColor >>> 16) & 0xFF) / 255.0f;
        float g = ((rgbColor >>> 8) & 0xFF) / 255.0f;
        float b = (rgbColor & 0xFF) / 255.0f;
        GL11.glColor4f(r, g, b, alpha);
        GLDraw.texturedQuad(x1, y1, x2, y2, u1, 0.0, u2, 0.5);

        // Pass 2: Crisp 1px black outline, white highlights & fixed accents (bottom half: v = 0.5 .. 1.0)
        GL11.glColor4f(1.0f, 1.0f, 1.0f, alpha);
        GLDraw.texturedQuad(x1, y1, x2, y2, u1, 0.5, u2, 1.0);

        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private static void ensureAtlas() {
        if (atlasTextureId != 0) return;

        int[] pixels = new int[ATLAS_W * ATLAS_H];

        for (int i = 0; i < ICONS.length; i++) {
            String[] pattern = getPattern(ICONS[i]);
            int xOff = i * ICON_SIZE;
            for (int y = 0; y < ICON_SIZE; y++) {
                String row = y < pattern.length ? pattern[y] : "................";
                for (int x = 0; x < ICON_SIZE; x++) {
                    char c = x < row.length() ? row.charAt(x) : '.';
                    int tintArgb = 0;
                    int fixedArgb = 0;
                    switch (c) {
                        case 'H': // Bright tinted highlight + soft glossy white sheen
                            tintArgb = 0xFFFFFFFF;
                            fixedArgb = 0x5AFFFFFF;
                            break;
                        case 'B': // Rich base waypoint color
                            tintArgb = 0xFFE6E6E6;
                            break;
                        case 'S': // Shaded waypoint color
                            tintArgb = 0xFF9E9E9E;
                            break;
                        case 'D': // Deep shaded waypoint color
                            tintArgb = 0xFF5C5C5C;
                            break;
                        case '#': // Crisp pixel-art dark outline
                            fixedArgb = 0xFF0A0D14;
                            break;
                        case 'W': // Pure crisp white (skull, blade, gleam)
                            fixedArgb = 0xFFF8FAFC;
                            break;
                        case 'G': // Cool light gray (metal, skull shadow)
                            fixedArgb = 0xFFCBD5E1;
                            break;
                        case 'M': // Medium slate gray (stone, obsidian edge)
                            fixedArgb = 0xFF64748B;
                            break;
                        case 'Y': // Warm gold accent (window light, clasp, crown)
                            fixedArgb = 0xFFFACC15;
                            break;
                        case 'R': // Crimson ruby accent
                            fixedArgb = 0xFFEF4444;
                            break;
                        case 'K': // Warm wood / soil brown
                            fixedArgb = 0xFF8B5A2B;
                            break;
                        case 'k': // Dark wood / soil shadow
                            fixedArgb = 0xFF523217;
                            break;
                        case 'P': // Obsidian dark violet-black
                            fixedArgb = 0xFF1E162B;
                            break;
                        default:
                            break;
                    }
                    pixels[y * ATLAS_W + (xOff + x)] = tintArgb;
                    pixels[(y + ICON_SIZE) * ATLAS_W + (xOff + x)] = fixedArgb;
                }
            }
        }

        ByteBuffer buf = BufferUtils.createByteBuffer(ATLAS_W * ATLAS_H * 4);
        for (int argb : pixels) {
            buf.put((byte) ((argb >>> 16) & 0xFF));
            buf.put((byte) ((argb >>> 8) & 0xFF));
            buf.put((byte) (argb & 0xFF));
            buf.put((byte) ((argb >>> 24) & 0xFF));
        }
        buf.flip();

        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
            ATLAS_W, ATLAS_H, 0,
            GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf
        );
        atlasTextureId = id;
    }

    private static String[] getPattern(Waypoint.IconType type) {
        switch (type) {
            case PIN:
                // Classic crisp map push-pin with glossy specular dot and sharp pointer tip
                return new String[] {
                    ".....######.....",
                    "...##HHBBBB##...",
                    "..#HHWBBBBBB##..",
                    ".#HHWWBBBBBBBS#.",
                    ".#HBBBB##BBBBS#.",
                    ".#BBBB#WW#BBBS#.",
                    ".#BBBB#WG#BBSS#.",
                    ".#BBBB##BBBBSS#.",
                    "..#BBBBBBBBSS#..",
                    "..#BBBBBBBSSS#..",
                    "...#BBBBBSSS#...",
                    "....#BBBSSS#....",
                    ".....#BBSS#.....",
                    "......#BS#......",
                    ".......##.......",
                    "................"
                };

            case HOME:
                // Cozy pixel cottage / base with tinted roof, chimney, door & warm lit window
                return new String[] {
                    ".......##..##...",
                    "......#HB##M#...",
                    ".....#HHBB#M#...",
                    "....#HHBBBBM#...",
                    "...#HHBBBBBB##..",
                    "..#HHBBBBBBBB##.",
                    ".##HBBBBBBBBBB##",
                    ".#..#WWWWWWWW#..",
                    "....#W##WW##W#..",
                    "....#W#Y#W#K#W..",
                    "....#W##WW#K#W..",
                    "....#WWWWW#k#W..",
                    "....#GGGGGGkGW..",
                    "....##########..",
                    "................",
                    "................"
                };

            case MINE:
                // Crisp diagonal pixel pickaxe with tinted head and wooden handle
                return new String[] {
                    "................",
                    "....######......",
                    "...#HHBBBB##....",
                    "..#HBBBBBBBB#...",
                    "..##SSS##BBBB#..",
                    "....###.#K#BBS#.",
                    "........#K##BS#.",
                    ".......#Kk#.#S#.",
                    "......#Kk#...##.",
                    ".....#Kk#.......",
                    "....#Kk#........",
                    "...#Kk#.........",
                    "..#Kk#..........",
                    "..#k#...........",
                    "...#............",
                    "................"
                };

            case DUNGEON:
                // Crossed swords for dungeons / combat POIs
                return new String[] {
                    "................",
                    ".###........###.",
                    ".#WW#......#WW#.",
                    ".#WWG#....#GWW#.",
                    "..#WWG#..#GWW#..",
                    "...#WWG##GWW#...",
                    "....#WWGGWW#....",
                    ".....#WWWW#.....",
                    "....#GWWWWG#....",
                    "...#H#WGGW#H#...",
                    "..#BH##..##HB#..",
                    "..#K#......#K#..",
                    ".#Y#........#Y#.",
                    "..#..........#..",
                    "................",
                    "................"
                };

            case PORTAL:
                // Nether/dimensional obsidian portal frame with glowing tinted vortex inside
                return new String[] {
                    "................",
                    "..############..",
                    "..#PPMMMMMMPP#..",
                    "..#P#HBBBBH#P#..",
                    "..#P#BHHHBB#P#..",
                    "..#P#BHWWHB#P#..",
                    "..#P#BBWWBB#P#..",
                    "..#P#BBHHBS#P#..",
                    "..#P#BHHBBS#P#..",
                    "..#P#HBBBSS#P#..",
                    "..#P#BBBSSS#P#..",
                    "..#P#BSSSSS#P#..",
                    "..#PPMMMMMMPP#..",
                    "..############..",
                    "................",
                    "................"
                };

            case VILLAGE:
                // Pennant flag on golden-tipped mast (villages, towns, landmarks)
                return new String[] {
                    "................",
                    "...##...........",
                    "..#YY#..........",
                    "...##.#######...",
                    "...#G#HHBBBBB#..",
                    "...#G#HBBBBBB#..",
                    "...#G#BBBBBB#...",
                    "...#G#BBBBB#....",
                    "...#G#BBBBSS#...",
                    "...#G#BBBSSSS#..",
                    "...#G########...",
                    "...#G#..........",
                    "...#G#..........",
                    "..#MMM#.........",
                    "..#####.........",
                    "................"
                };

            case STAR:
                // Radiant 4/8-point pixel star for favorites & highlights
                return new String[] {
                    ".......##.......",
                    "......#HH#......",
                    "......#HH#......",
                    ".....#HBBH#.....",
                    "..###HBBBBH###..",
                    ".#HHHBBWWBBHHH#.",
                    ".#HBBBWWWWBBBS#.",
                    "..#BBBWWWWBBS#..",
                    "...#BBBWWBBS#...",
                    "...#BBBBBBSS#...",
                    "..#BBBB##BBSS#..",
                    "..#BBB#..#BSS#..",
                    ".#BBS#....#SSS#.",
                    ".####......####.",
                    "................",
                    "................"
                };

            case CHEST:
                // Treasure / loot chest with tinted wood & bright golden clasp
                return new String[] {
                    "................",
                    "................",
                    ".##############.",
                    ".#HHHHHHHHHHHH#.",
                    ".#HBBBBBBBBBBH#.",
                    ".#BBBBBBBBBBBS#.",
                    ".######YY######.",
                    ".#SSSS#YY#SSSS#.",
                    ".#BBBB#YY#BBBS#.",
                    ".#BBBB####BBBS#.",
                    ".#BBBBBBBBBBSS#.",
                    ".#BBSSSSSSSSSS#.",
                    ".#DDDDDDDDDDDD#.",
                    ".##############.",
                    "................",
                    "................"
                };

            case FARM:
                // Sprout / leaf emblem for farms, bees & crops
                return new String[] {
                    "................",
                    "................",
                    "..####....####..",
                    ".#HHBB#..#BBHH#.",
                    ".#HBBBB##BBBBH#.",
                    "..#BBBB##BBBS#..",
                    "...#BB#HH#SS#...",
                    "....###BB###....",
                    "......#BB#......",
                    "......#BS#......",
                    "......#BS#......",
                    "...####BS####...",
                    "..#KKKKKKKkkk#..",
                    "..############..",
                    "................",
                    "................"
                };

            case BOSS:
                // Royal crown with crimson center gem for bosses & endgame targets
                return new String[] {
                    "................",
                    "................",
                    ".##....##....##.",
                    ".#Y#..#YY#..#Y#.",
                    ".#H#..#HH#..#H#.",
                    ".#HB##HBBH##BH#.",
                    ".#HBBBBBBBBBBH#.",
                    ".#HBBB#RR#BBBS#.",
                    ".#BBBB#RR#BBBS#.",
                    ".#BBBBBBBBBBSS#.",
                    ".#YYYYYYYYYYYY#.",
                    ".#SSSSSSSSSSSS#.",
                    ".##############.",
                    "................",
                    "................",
                    "................"
                };

            case CRYSTAL:
                // Faceted arcane crystal (Thaumcraft, altars, shrines, obelisks)
                return new String[] {
                    ".......##.......",
                    "......#HH#......",
                    ".....#HWWB#.....",
                    "....#HHWWBB#....",
                    "...#HHWWBBBB#...",
                    "...#HWWBBBBBS#..",
                    "..#HHWBBBBBBSS#.",
                    "..#HBBBBBBBBSS#.",
                    "..#HBBBBBBBBSS#.",
                    "...#BBBBBBBSS#..",
                    "...#BBBBBBSSS#..",
                    "....#BBBBSSS#...",
                    ".....#BBSSS#....",
                    "......#BSS#.....",
                    ".......##.......",
                    "................"
                };

            case DEATH:
            default:
                // Iconic pixel skull with tinted crimson/custom rim & stark black eyes/teeth
                return new String[] {
                    "....########....",
                    "..##HHHHHHHH##..",
                    ".#HHWWWWWWWWBB#.",
                    ".#HWWWWWWWWWWB#.",
                    "#HWWWWWWWWWWWWB#",
                    "#HWW###WW###WWB#",
                    "#HWW###WW###WWB#",
                    "#BWW###WW###WWS#",
                    "#BWWWWWWWWWWWWS#",
                    ".#BWWWW##WWWWS#.",
                    ".#SBGGWWWWGGSS#.",
                    "..##GWWWWWWG##..",
                    "...#W#W##W#W#...",
                    "...#W#W##W#W#...",
                    "....########....",
                    "................"
                };
        }
    }
}

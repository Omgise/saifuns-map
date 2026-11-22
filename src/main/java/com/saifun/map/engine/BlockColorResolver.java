package com.saifun.map.engine;

import com.saifun.map.config.MapConfig;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBush;
import net.minecraft.block.BlockDoublePlant;
import net.minecraft.block.BlockFlower;
import net.minecraft.block.BlockGrass;
import net.minecraft.block.BlockLeavesBase;
import net.minecraft.block.BlockLilyPad;
import net.minecraft.block.BlockRailBase;
import net.minecraft.block.BlockTallGrass;
import net.minecraft.block.BlockVine;
import net.minecraft.block.material.MapColor;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.init.Blocks;
import net.minecraft.util.IIcon;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Calibrated block & biome color resolver for Minecraft 1.7.10 + GTNH mods (Angelica/LWJGL3ify safe).
 * - Samples actual top-face block PNG textures directly via Minecraft's IResourceManager so colors match
 *   what the player sees in-world even when Angelica frees CPU-side TextureAtlasSprite pixel arrays!
 * - Fixes vanilla MapColor mismatches (e.g. Cyan Stained Clay rendering sky-blue instead of smooth grey,
 *   or Biomes O' Plenty Ash Block rendering cream-yellow instead of dark charcoal).
 * - Preserves colorful modded foliage (e.g. Cherry Blossom, Autumn, Jacaranda) while biome-tinting grayscale leaves.
 */
public final class BlockColorResolver {

    private static final int SENTINEL_FAILED = -1;

    private static final Map<Integer, Integer> BASE_COLOR_CACHE = new ConcurrentHashMap<>(2048);
    private static final Map<Integer, Boolean> GRAYSCALE_CACHE = new ConcurrentHashMap<>(2048);
    private static final Map<String, Integer> ICON_COLOR_CACHE = new ConcurrentHashMap<>(2048);

    private BlockColorResolver() {}

    public static void clearCache() {
        BASE_COLOR_CACHE.clear();
        GRAYSCALE_CACHE.clear();
        ICON_COLOR_CACHE.clear();
    }

    public static int getBlockColor(World world, Block block, int meta, int x, int y, int z, BiomeGenBase biome) {
        if (block == null || block == Blocks.air) return 0;

        int key = (Block.getIdFromBlock(block) << 4) | (meta & 0xF);
        Integer cached = BASE_COLOR_CACHE.get(key);
        int baseRgb;
        boolean grayscale;
        if (cached != null) {
            baseRgb = cached;
            grayscale = Boolean.TRUE.equals(GRAYSCALE_CACHE.get(key));
        } else {
            baseRgb = computeBaseColor(world, block, meta, x, y, z);
            grayscale = isGrayscaleRgb(baseRgb);
            BASE_COLOR_CACHE.put(key, baseRgb);
            GRAYSCALE_CACHE.put(key, grayscale);
        }

        if (!MapConfig.get().biomeColors || biome == null) {
            return baseRgb;
        }

        try {
            return applyBiomeTint(block, meta, baseRgb, grayscale, x, z, biome);
        } catch (Throwable ignored) {
            return baseRgb;
        }
    }

    public static boolean isWaterLike(Block block) {
        return block != null && block.getMaterial() == Material.water;
    }

    /**
     * Blocks completely skipped during surface height/color scan.
     */
    public static boolean isTransparentForSurface(Block block) {
        if (block == null || block == Blocks.air) return true;
        Material mat = block.getMaterial();
        if (mat == Material.air || mat == Material.fire) return true;
        if (block == Blocks.torch || block == Blocks.redstone_torch || block == Blocks.unlit_redstone_torch
            || block == Blocks.tripwire || block == Blocks.tripwire_hook || block == Blocks.lever
            || block == Blocks.wooden_button || block == Blocks.stone_button
            || block == Blocks.tallgrass || block instanceof BlockTallGrass
            || block instanceof BlockDoublePlant) {
            return true;
        }
        return false;
    }

    /**
     * Flat decorative overlays (flowers, rails, snow carpet, lily pads):
     * they contribute a subtle color accent WITHOUT increasing terrain elevation (preventing noise!).
     */
    public static boolean isFlatAccentBlock(Block block) {
        if (block == null) return false;
        return block instanceof BlockFlower
            || block == Blocks.yellow_flower
            || block == Blocks.red_flower
            || block instanceof BlockLilyPad
            || block instanceof BlockRailBase
            || block == Blocks.snow_layer
            || (block instanceof BlockBush && !(block instanceof BlockTallGrass) && !(block instanceof BlockDoublePlant));
    }

    public static boolean isFoliageBlock(Block block) {
        return block instanceof BlockLeavesBase || block instanceof BlockVine;
    }

    private static int computeBaseColor(World world, Block block, int meta, int x, int y, int z) {
        // Rich natural water base
        if (block.getMaterial() == Material.water) {
            return 0x3866D6;
        }
        if (block.getMaterial() == Material.lava) {
            return 0xEB5E1A;
        }
        if (block == Blocks.snow_layer || block == Blocks.snow) {
            return 0xEDF4FA;
        }
        if (block == Blocks.ice || block == Blocks.packed_ice) {
            return 0x8EBDF5;
        }
        // Vanilla grass top & vanilla leaves are grayscale masks in 1.7.10; use clean bright neutral luminance
        // so biome color multiplication produces authentic, smooth natural greens!
        if (block == Blocks.grass) {
            return 0xC8D0C2;
        }
        if (block == Blocks.leaves || block == Blocks.leaves2) {
            return 0x8FA08A;
        }

        int sampled = sampleIconColor(world, block, meta, x, y, z);
        if (sampled != SENTINEL_FAILED) {
            // Never return 0x000000 since 0 is reserved for unexplored pixels in MapRegion
            return ensureNonZeroRgb(sampled);
        }

        try {
            MapColor mc = block.getMapColor(meta);
            if (mc != null && mc.colorValue != 0) {
                return ensureNonZeroRgb(mc.colorValue & 0xFFFFFF);
            }
        } catch (Throwable ignored) {}

        return 0x7D828A;
    }

    private static int sampleIconColor(World world, Block block, int meta, int x, int y, int z) {
        IIcon icon = resolveTopIcon(world, block, meta, x, y, z);
        if (icon == null) return SENTINEL_FAILED;

        // Tier 1: Read actual texture PNG via IResourceManager (Angelica-safe, resource-pack-aware, cached by icon name!)
        try {
            String iconName = icon.getIconName();
            if (iconName != null && !iconName.isEmpty() && !"missingno".equalsIgnoreCase(iconName)) {
                Integer cached = ICON_COLOR_CACHE.get(iconName);
                if (cached != null) {
                    if (cached != SENTINEL_FAILED) {
                        return cached;
                    }
                } else {
                    int pngRgb = sampleResourcePng(iconName);
                    ICON_COLOR_CACHE.put(iconName, pngRgb);
                    if (pngRgb != SENTINEL_FAILED) {
                        return pngRgb;
                    }
                }
            }
        } catch (Throwable ignored) {}

        // Tier 2: Fallback to in-memory TextureAtlasSprite pixel array (for runtime/procedural sprites)
        try {
            if (icon instanceof TextureAtlasSprite) {
                TextureAtlasSprite sprite = (TextureAtlasSprite) icon;
                if (sprite.getFrameCount() > 0) {
                    int[][] frameData = sprite.getFrameTextureData(0);
                    if (frameData != null && frameData.length > 0 && frameData[0] != null) {
                        int[] pixels = frameData[0];
                        long sumR = 0, sumG = 0, sumB = 0;
                        int count = 0;
                        for (int i = 0; i < pixels.length; i++) {
                            int argb = pixels[i];
                            int a = (argb >>> 24) & 0xFF;
                            if (a < 80) continue;
                            sumR += (argb >>> 16) & 0xFF;
                            sumG += (argb >>> 8) & 0xFF;
                            sumB += argb & 0xFF;
                            count++;
                        }
                        if (count > 0) {
                            int r = (int) (sumR / count);
                            int g = (int) (sumG / count);
                            int b = (int) (sumB / count);
                            return (r << 16) | (g << 8) | b;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        return SENTINEL_FAILED;
    }

    private static IIcon resolveTopIcon(World world, Block block, int meta, int x, int y, int z) {
        try {
            IIcon icon = block.getIcon(1, meta);
            if (icon != null) return icon;
        } catch (Throwable ignored) {}
        if (world != null) {
            try {
                IIcon icon = block.getIcon(world, x, y, z, 1);
                if (icon != null) return icon;
            } catch (Throwable ignored) {}
        }
        try {
            IIcon icon = block.getIcon(0, meta);
            if (icon != null) return icon;
        } catch (Throwable ignored) {}
        try {
            return block.getIcon(2, meta);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int sampleResourcePng(String iconName) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return SENTINEL_FAILED;
        IResourceManager rm = mc.getResourceManager();
        if (rm == null) return SENTINEL_FAILED;

        String domain = "minecraft";
        String path = iconName;
        int colon = iconName.indexOf(':');
        if (colon >= 0) {
            if (colon > 0) {
                domain = iconName.substring(0, colon);
            }
            path = iconName.substring(colon + 1);
        }
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.endsWith(".png")) {
            path = path.substring(0, path.length() - 4);
        }
        if (path.isEmpty()) return SENTINEL_FAILED;

        // Primary standard MC 1.7.10 block texture path
        int rgb = readPngAverageRgb(rm, new ResourceLocation(domain, "textures/blocks/" + path + ".png"));
        if (rgb != SENTINEL_FAILED) return rgb;

        // Secondary fallback if mod already prefixed path with "blocks/" or custom subfolder under textures/
        return readPngAverageRgb(rm, new ResourceLocation(domain, "textures/" + path + ".png"));
    }

    private static int readPngAverageRgb(IResourceManager rm, ResourceLocation loc) {
        try {
            IResource res = rm.getResource(loc);
            if (res == null) return SENTINEL_FAILED;
            try (InputStream is = res.getInputStream()) {
                BufferedImage img = ImageIO.read(is);
                if (img == null) return SENTINEL_FAILED;
                int w = img.getWidth();
                int h = img.getHeight();
                if (w <= 0 || h <= 0) return SENTINEL_FAILED;
                // For vertical animated texture strips, sample only the first square frame
                int sampleH = (h > w && (h % w) == 0) ? w : h;

                long sumR = 0, sumG = 0, sumB = 0;
                int count = 0;
                for (int py = 0; py < sampleH; py++) {
                    for (int px = 0; px < w; px++) {
                        int argb = img.getRGB(px, py);
                        int a = (argb >>> 24) & 0xFF;
                        if (a < 80) continue;
                        sumR += (argb >>> 16) & 0xFF;
                        sumG += (argb >>> 8) & 0xFF;
                        sumB += argb & 0xFF;
                        count++;
                    }
                }
                if (count == 0) return SENTINEL_FAILED;
                int r = (int) (sumR / count);
                int g = (int) (sumG / count);
                int b = (int) (sumB / count);
                return (r << 16) | (g << 8) | b;
            }
        } catch (Throwable ignored) {
            return SENTINEL_FAILED;
        }
    }

    private static int applyBiomeTint(Block block, int meta, int baseRgb, boolean grayscale, int x, int z, BiomeGenBase biome) {
        if (block == Blocks.grass || (block instanceof BlockGrass && grayscale)) {
            int grass = biome.getBiomeGrassColor(x, 64, z);
            int mask = (block == Blocks.grass) ? baseRgb : 0xC8D0C2;
            int tinted = multiplyColor(mask, grass);
            return blendColor(tinted, 0x7A9A58, 0.14f);
        }
        if (block instanceof BlockLeavesBase || block instanceof BlockVine) {
            if (block == Blocks.leaves && (meta & 3) == 1) {
                return multiplyColor(baseRgb, 0x588A56); // Spruce
            }
            if (block == Blocks.leaves && (meta & 3) == 2) {
                return multiplyColor(baseRgb, 0x7EA452); // Birch
            }
            if (block == Blocks.leaves || block == Blocks.leaves2 || block instanceof BlockVine) {
                return multiplyColor(baseRgb, biome.getBiomeFoliageColor(x, 64, z));
            }
            // Modded leaves (BoP, Forestry, Twilight Forest, Natura):
            // Only apply render/biome tint if the texture is actually grayscale!
            // Pre-colored textures (Cherry Blossom, Autumn, Jacaranda, Hellbark) stay true to their real colors.
            if (grayscale) {
                int renderColor = safeRenderColor(block, meta);
                int tint = (renderColor != 0xFFFFFF && renderColor != 0)
                    ? renderColor
                    : biome.getBiomeFoliageColor(x, 64, z);
                return multiplyColor(0xA4B2A0, tint);
            }
            return baseRgb;
        }
        if (block instanceof BlockLilyPad) {
            return 0x2F8542;
        }
        if (block.getMaterial() == Material.water) {
            int waterTint = biome.waterColorMultiplier;
            if (waterTint != 0xFFFFFF && waterTint != 0) {
                return blendColor(baseRgb, multiplyColor(0x5282E8, waterTint), 0.45f);
            }
            return baseRgb;
        }
        int renderColor = safeRenderColor(block, meta);
        if (renderColor != 0xFFFFFF && renderColor != 0) {
            return multiplyColor(baseRgb, renderColor);
        }
        return baseRgb;
    }

    private static int safeRenderColor(Block block, int meta) {
        try {
            return block.getRenderColor(meta) & 0xFFFFFF;
        } catch (Throwable ignored) {
            return 0xFFFFFF;
        }
    }

    private static boolean isGrayscaleRgb(int rgb) {
        int r = (rgb >>> 16) & 0xFF;
        int g = (rgb >>> 8) & 0xFF;
        int b = rgb & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        return (max - min) <= 18;
    }

    private static int ensureNonZeroRgb(int rgb) {
        int clean = rgb & 0xFFFFFF;
        if (clean == 0) return 0x0A0A0A;
        return clean;
    }

    public static int multiplyColor(int rgbA, int rgbB) {
        int r = (((rgbA >>> 16) & 0xFF) * ((rgbB >>> 16) & 0xFF)) / 255;
        int g = (((rgbA >>> 8) & 0xFF) * ((rgbB >>> 8) & 0xFF)) / 255;
        int b = ((rgbA & 0xFF) * (rgbB & 0xFF)) / 255;
        return ensureNonZeroRgb((r << 16) | (g << 8) | b);
    }

    public static int blendColor(int bottomRgb, int topRgb, float topAlpha) {
        float inv = 1.0f - topAlpha;
        int r = (int) (((bottomRgb >>> 16) & 0xFF) * inv + ((topRgb >>> 16) & 0xFF) * topAlpha);
        int g = (int) (((bottomRgb >>> 8) & 0xFF) * inv + ((topRgb >>> 8) & 0xFF) * topAlpha);
        int b = (int) ((bottomRgb & 0xFF) * inv + (topRgb & 0xFF) * topAlpha);
        return (clampByte(r) << 16) | (clampByte(g) << 8) | clampByte(b);
    }

    public static int shadeColor(int rgb, float factor) {
        int r = clampByte((int) (((rgb >>> 16) & 0xFF) * factor));
        int g = clampByte((int) (((rgb >>> 8) & 0xFF) * factor));
        int b = clampByte((int) ((rgb & 0xFF) * factor));
        return (r << 16) | (g << 8) | b;
    }

    private static int clampByte(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}

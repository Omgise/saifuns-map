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
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.init.Blocks;
import net.minecraft.util.IIcon;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Calibrated block & biome color resolver for Minecraft 1.7.10 + GTNH mods.
 * - Normalizes grayscale biome-tinted textures (grass, leaves) so meadows look natural & lush, not neon/dark.
 * - Gives rivers/oceans rich sapphire-blue hues instead of muddy grey.
 * - Distinguishes flat floral/grass overlays so flowers appear as charming dots without adding fake height noise.
 */
public final class BlockColorResolver {

    private static final Map<Integer, Integer> BASE_COLOR_CACHE = new ConcurrentHashMap<>(2048);

    private BlockColorResolver() {}

    public static void clearCache() {
        BASE_COLOR_CACHE.clear();
    }

    public static int getBlockColor(World world, Block block, int meta, int x, int y, int z, BiomeGenBase biome) {
        if (block == null || block == Blocks.air) return 0;

        int key = (Block.getIdFromBlock(block) << 4) | (meta & 0xF);
        Integer cached = BASE_COLOR_CACHE.get(key);
        int baseRgb;
        if (cached != null) {
            baseRgb = cached;
        } else {
            baseRgb = computeBaseColor(block, meta);
            BASE_COLOR_CACHE.put(key, baseRgb);
        }

        if (!MapConfig.get().biomeColors || biome == null) {
            return baseRgb;
        }

        try {
            return applyBiomeTint(block, meta, baseRgb, x, z, biome);
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

    private static int computeBaseColor(Block block, int meta) {
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
        // Vanilla grass top & leaves are grayscale masks in 1.7.10; use clean bright neutral luminance
        // so biome color multiplication produces authentic, smooth natural greens!
        if (block instanceof BlockGrass) {
            return 0xC8D0C2;
        }
        if (block instanceof BlockLeavesBase) {
            return 0x8FA08A;
        }

        int sampled = sampleIconColor(block, meta);
        if (sampled != 0) {
            return sampled;
        }

        try {
            MapColor mc = block.getMapColor(meta);
            if (mc != null && mc.colorValue != 0) {
                return mc.colorValue & 0xFFFFFF;
            }
        } catch (Throwable ignored) {}

        return 0x7D828A;
    }

    private static int sampleIconColor(Block block, int meta) {
        try {
            IIcon icon = block.getIcon(1, meta);
            if (!(icon instanceof TextureAtlasSprite)) return 0;
            TextureAtlasSprite sprite = (TextureAtlasSprite) icon;
            if (sprite.getFrameCount() <= 0) return 0;
            int[][] frameData = sprite.getFrameTextureData(0);
            if (frameData == null || frameData.length == 0 || frameData[0] == null) return 0;
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
            if (count == 0) return 0;
            int r = (int) (sumR / count);
            int g = (int) (sumG / count);
            int b = (int) (sumB / count);
            return (r << 16) | (g << 8) | b;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int applyBiomeTint(Block block, int meta, int baseRgb, int x, int z, BiomeGenBase biome) {
        if (block instanceof BlockGrass) {
            int grass = biome.getBiomeGrassColor(x, 64, z);
            // Slightly soften extreme saturation for natural painterly grass
            int tinted = multiplyColor(baseRgb, grass);
            return blendColor(tinted, 0x7A9A58, 0.14f);
        }
        if (block instanceof BlockLeavesBase || block instanceof BlockVine) {
            int foliage;
            if (block == Blocks.leaves && (meta & 3) == 1) {
                foliage = 0x588A56; // Spruce
            } else if (block == Blocks.leaves && (meta & 3) == 2) {
                foliage = 0x7EA452; // Birch
            } else {
                foliage = biome.getBiomeFoliageColor(x, 64, z);
            }
            return multiplyColor(baseRgb, foliage);
        }
        if (block instanceof BlockLilyPad) {
            return 0x2F8542;
        }
        if (block.getMaterial() == Material.water) {
            int waterTint = biome.waterColorMultiplier;
            if (waterTint != 0xFFFFFF && waterTint != 0) {
                // Blend gently with biome tint so rivers always stay distinctly blue/teal, never muddy grey
                return blendColor(baseRgb, multiplyColor(0x5282E8, waterTint), 0.45f);
            }
            return baseRgb;
        }
        try {
            int renderColor = block.getRenderColor(meta);
            if (renderColor != 0xFFFFFF && renderColor != 0) {
                return multiplyColor(baseRgb, renderColor);
            }
        } catch (Throwable ignored) {}
        return baseRgb;
    }

    public static int multiplyColor(int rgbA, int rgbB) {
        int r = (((rgbA >>> 16) & 0xFF) * ((rgbB >>> 16) & 0xFF)) / 255;
        int g = (((rgbA >>> 8) & 0xFF) * ((rgbB >>> 8) & 0xFF)) / 255;
        int b = ((rgbA & 0xFF) * (rgbB & 0xFF)) / 255;
        return (r << 16) | (g << 8) | b;
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

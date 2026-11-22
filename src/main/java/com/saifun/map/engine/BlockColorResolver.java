package com.saifun.map.engine;

import com.saifun.map.config.MapConfig;
import net.minecraft.block.Block;
import net.minecraft.block.BlockDoublePlant;
import net.minecraft.block.BlockGrass;
import net.minecraft.block.BlockLeavesBase;
import net.minecraft.block.BlockLilyPad;
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
 * Resolves rich, Xaero-style RGB colors for vanilla and modded GTNH blocks.
 * Samples the block's loaded TextureAtlasSprite top face directly in CPU memory (zero GL stalls,
 * Angelica-safe), caches results by (blockId, meta), and applies accurate 1.7.10 biome tinting.
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

        return applyBiomeTint(block, meta, baseRgb, x, z, biome);
    }

    public static boolean isWaterLike(Block block) {
        if (block == null) return false;
        Material mat = block.getMaterial();
        return mat == Material.water;
    }

    public static boolean isTransparentForSurface(Block block) {
        if (block == null || block == Blocks.air) return true;
        Material mat = block.getMaterial();
        if (mat == Material.air || mat == Material.fire) return true;
        // Skip thin/non-occluding plants & torches so terrain underneath stays crisp
        if (block == Blocks.torch || block == Blocks.redstone_torch || block == Blocks.unlit_redstone_torch
            || block == Blocks.tripwire || block == Blocks.tripwire_hook || block == Blocks.lever
            || block == Blocks.wooden_button || block == Blocks.stone_button) {
            return true;
        }
        return false;
    }

    private static int computeBaseColor(Block block, int meta) {
        // Water has a clean deep base tinted by biome
        if (block.getMaterial() == Material.water) {
            return 0x2F5FD0;
        }
        if (block.getMaterial() == Material.lava) {
            return 0xE85A19;
        }
        if (block == Blocks.snow_layer || block == Blocks.snow) {
            return 0xF0F6FA;
        }
        if (block == Blocks.ice || block == Blocks.packed_ice) {
            return 0x8CB8F0;
        }

        // Sample top-face TextureAtlasSprite directly from CPU memory
        int sampled = sampleIconColor(block, meta);
        if (sampled != 0) {
            return sampled;
        }

        // Fallback to MapColor
        try {
            MapColor mc = block.getMapColor(meta);
            if (mc != null && mc.colorValue != 0) {
                return mc.colorValue & 0xFFFFFF;
            }
        } catch (Throwable ignored) {
            // Some modded blocks throw on invalid meta
        }

        return 0x7A7A7A;
    }

    private static int sampleIconColor(Block block, int meta) {
        try {
            IIcon icon = block.getIcon(1, meta); // Side 1 = TOP
            if (!(icon instanceof TextureAtlasSprite)) {
                return 0;
            }
            TextureAtlasSprite sprite = (TextureAtlasSprite) icon;
            if (sprite.getFrameCount() <= 0) {
                return 0;
            }
            int[][] frameData = sprite.getFrameTextureData(0);
            if (frameData == null || frameData.length == 0 || frameData[0] == null) {
                return 0;
            }
            int[] pixels = frameData[0];
            long sumR = 0, sumG = 0, sumB = 0;
            int count = 0;
            for (int i = 0; i < pixels.length; i++) {
                int argb = pixels[i];
                int a = (argb >>> 24) & 0xFF;
                if (a < 64) continue;
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
        if (block instanceof BlockGrass || block instanceof BlockTallGrass || block instanceof BlockDoublePlant) {
            int grass = biome.getBiomeGrassColor(x, 64, z);
            return multiplyColor(baseRgb, grass);
        }
        if (block instanceof BlockLeavesBase || block instanceof BlockVine) {
            // Birch & spruce leaves have fixed tints in 1.7.10
            if (block == Blocks.leaves && (meta & 3) == 1) {
                return multiplyColor(baseRgb, 0x619961);
            }
            if (block == Blocks.leaves && (meta & 3) == 2) {
                return multiplyColor(baseRgb, 0x80A755);
            }
            int foliage = biome.getBiomeFoliageColor(x, 64, z);
            return multiplyColor(baseRgb, foliage);
        }
        if (block instanceof BlockLilyPad) {
            return multiplyColor(baseRgb, 0x208030);
        }
        if (block.getMaterial() == Material.water) {
            int waterTint = biome.waterColorMultiplier;
            if (waterTint != 0xFFFFFF && waterTint != 0) {
                return multiplyColor(baseRgb, waterTint);
            }
            return baseRgb;
        }
        // Fallback for modded grass/foliage blocks that return non-white render color
        try {
            int renderColor = block.getRenderColor(meta);
            if (renderColor != 0xFFFFFF && renderColor != 0) {
                return multiplyColor(baseRgb, renderColor);
            }
        } catch (Throwable ignored) {
            // Ignore buggy modded blocks
        }
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

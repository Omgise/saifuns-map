package com.saifun.map.radar;

import com.saifun.map.config.MapConfig;
import com.saifun.map.render.GLDraw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.boss.EntityDragon;
import net.minecraft.entity.boss.EntityWither;
import net.minecraft.entity.monster.EntityBlaze;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.entity.monster.EntityEnderman;
import net.minecraft.entity.monster.EntityGhast;
import net.minecraft.entity.monster.EntityIronGolem;
import net.minecraft.entity.monster.EntityPigZombie;
import net.minecraft.entity.monster.EntitySkeleton;
import net.minecraft.entity.monster.EntitySlime;
import net.minecraft.entity.monster.EntitySpider;
import net.minecraft.entity.monster.EntityWitch;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.passive.EntityBat;
import net.minecraft.entity.passive.EntityChicken;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.passive.EntityOcelot;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.passive.EntitySheep;
import net.minecraft.entity.passive.EntitySquid;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.passive.EntityWolf;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compact, crisp modern entity icon renderer.
 * Supports:
 * - Compact player & mob heads (default, crisp silhouette outline so icons never pile up),
 * - Sleek colored diamonds,
 * - Clean colored dots,
 * - Resource-pack / GTNH 2D entity icon overrides + dynamic Render texture head UV fallback.
 */
public final class EntityIconRenderer {

    private static final class HeadUV {
        final float u1, v1, u2, v2;
        final boolean hasOverlay;
        final float ou1, ov1, ou2, ov2;

        HeadUV(float x, float y, float w, float h, float texW, float texH) {
            this.u1 = x / texW;
            this.v1 = y / texH;
            this.u2 = (x + w) / texW;
            this.v2 = (y + h) / texH;
            this.hasOverlay = false;
            this.ou1 = this.ov1 = this.ou2 = this.ov2 = 0f;
        }

        HeadUV(
            float x, float y, float w, float h,
            float ox, float oy, float ow, float oh,
            float texW, float texH
        ) {
            this.u1 = x / texW;
            this.v1 = y / texH;
            this.u2 = (x + w) / texW;
            this.v2 = (y + h) / texH;
            this.hasOverlay = true;
            this.ou1 = ox / texW;
            this.ov1 = oy / texH;
            this.ou2 = (ox + ow) / texW;
            this.ov2 = (oy + oh) / texH;
        }
    }

    private static final HeadUV UV_PLAYER_32 = new HeadUV(8, 8, 8, 8, 40, 8, 8, 8, 64, 32);
    private static final HeadUV UV_BIPED_64 = new HeadUV(8, 8, 8, 8, 40, 8, 8, 8, 64, 64);
    private static final HeadUV UV_BIPED_32 = new HeadUV(8, 8, 8, 8, 64, 32);
    private static final HeadUV UV_VILLAGER = new HeadUV(8, 8, 8, 10, 64, 64);
    private static final HeadUV UV_SPIDER = new HeadUV(40, 12, 8, 8, 64, 32);
    private static final HeadUV UV_SLIME = new HeadUV(8, 8, 8, 8, 64, 32);
    private static final HeadUV UV_GHAST = new HeadUV(16, 16, 16, 16, 64, 32);
    private static final HeadUV UV_COW = new HeadUV(6, 6, 8, 8, 64, 32);
    private static final HeadUV UV_PIG = new HeadUV(8, 8, 8, 8, 64, 32);
    private static final HeadUV UV_SHEEP = new HeadUV(8, 8, 6, 6, 64, 32);
    private static final HeadUV UV_CHICKEN = new HeadUV(2, 3, 4, 6, 64, 32);
    private static final HeadUV UV_WOLF = new HeadUV(4, 4, 6, 6, 64, 32);
    private static final HeadUV UV_OCELOT = new HeadUV(4, 4, 5, 4, 64, 32);
    private static final HeadUV UV_SQUID = new HeadUV(12, 12, 12, 16, 64, 32);
    private static final HeadUV UV_GOLEM = new HeadUV(8, 8, 8, 10, 128, 128);
    private static final HeadUV UV_HORSE = new HeadUV(7, 7, 7, 7, 128, 128);
    private static final HeadUV UV_BAT = new HeadUV(6, 6, 6, 6, 64, 64);
    private static final HeadUV UV_WITHER = new HeadUV(8, 8, 8, 8, 64, 64);
    private static final HeadUV UV_DRAGON = new HeadUV(128, 46, 16, 16, 256, 256);
    private static final HeadUV UV_FULL = new HeadUV(0, 0, 1, 1, 1, 1);

    private static final Map<Class<?>, Method> TEXTURE_METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, ResourceLocation> CLASS_TEXTURE_CACHE = new ConcurrentHashMap<>();
    private static final Map<Integer, ResourceLocation> ENTITY_ID_TEXTURE_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, ResourceLocation> CUSTOM_ICON_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, ResourceLocation> REMOTE_SKIN_CACHE = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, SkinFormatEntry> SKIN_FORMAT_CACHE = new ConcurrentHashMap<>();
    private static final ResourceLocation MISSING_ICON = new ResourceLocation("saifunsmap", "__missing__");

    private static final class SkinFormatEntry {
        final boolean isSquare64;
        final long checkedAtMs;

        SkinFormatEntry(boolean isSquare64, long checkedAtMs) {
            this.isSquare64 = isSquare64;
            this.checkedAtMs = checkedAtMs;
        }
    }

    private EntityIconRenderer() {}

    public static void clearCache() {
        CUSTOM_ICON_CACHE.clear();
        CLASS_TEXTURE_CACHE.clear();
        ENTITY_ID_TEXTURE_CACHE.clear();
        REMOTE_SKIN_CACHE.clear();
        SKIN_FORMAT_CACHE.clear();
    }

    public static void clearEntityIdCache() {
        ENTITY_ID_TEXTURE_CACHE.clear();
    }

    public static void renderEntity(
        RadarEntity re,
        double screenX,
        double screenY,
        double playerY,
        float scaleMultiplier
    ) {
        renderEntity(re, screenX, screenY, playerY, scaleMultiplier, 1.0f);
    }

    public static void renderEntity(
        RadarEntity re,
        double screenX,
        double screenY,
        double playerY,
        float scaleMultiplier,
        float alphaMultiplier
    ) {
        if (alphaMultiplier <= 0.05f) return;

        MapConfig cfg = MapConfig.get();
        double dy = re.y - playerY;
        float alpha = computeVerticalAlpha(dy, re.category) * alphaMultiplier;

        // Compact crisp icon proportions: clean and non-crowding
        float basePx = re.category == EntityCategory.PLAYER ? 6.8f : 4.8f;
        float size = basePx * re.category.defaultScale * cfg.radarIconScale * scaleMultiplier;
        float half = size * 0.5f;

        GL11.glPushMatrix();
        GL11.glTranslated(screenX, screenY, 0.0);
        GLDraw.ensureBlend();

        if (re.category == EntityCategory.PLAYER) {
            // Players always render crisp skin heads regardless of mob dot/diamond setting
            if (cfg.radarIconStyle == MapConfig.RadarIconStyle.DOTS) {
                renderDot(re.category.color, half * 0.72f, alpha);
            } else {
                renderPlayerHead(re, half, alpha);
            }
        } else if (re.category == EntityCategory.ITEM || cfg.radarIconStyle == MapConfig.RadarIconStyle.DOTS) {
            renderDot(re.category.color, Math.max(1.8f, half * 0.65f), alpha);
        } else if (cfg.radarIconStyle == MapConfig.RadarIconStyle.DIAMONDS) {
            renderDiamond(re.category.color, Math.max(2.2f, half * 0.85f), alpha);
        } else {
            // HEADS (default): compact crisp mob heads
            if (!renderMobHead(re, half, alpha)) {
                renderDiamond(re.category.color, Math.max(2.1f, half * 0.80f), alpha);
            }
        }

        if (cfg.showHeightIndicator && Math.abs(dy) >= 5.0 && alpha > 0.35f) {
            renderHeightIndicator(dy > 0, half, alpha);
        }

        if (re.category == EntityCategory.PLAYER && cfg.showPlayerNames && re.name != null) {
            renderPlayerName(re.name, half, alpha);
        }

        GL11.glPopMatrix();
    }

    private static float computeVerticalAlpha(double dy, EntityCategory cat) {
        if (cat == EntityCategory.PLAYER || cat == EntityCategory.BOSS) {
            return Math.abs(dy) > 28.0 ? 0.88f : 1.0f;
        }
        double abs = Math.abs(dy);
        if (abs <= 8.0) return 1.0f;
        if (abs >= 28.0) return 0.42f;
        return (float) (1.0 - ((abs - 8.0) / 20.0) * 0.58);
    }

    private static void renderPlayerHead(RadarEntity re, float half, float alpha) {
        ResourceLocation skin = null;
        if (re.entity instanceof AbstractClientPlayer) {
            skin = ((AbstractClientPlayer) re.entity).getLocationSkin();
        }
        if (skin == null && re.name != null) {
            skin = REMOTE_SKIN_CACHE.get(re.name);
            if (skin == null) {
                if (REMOTE_SKIN_CACHE.size() > 256) {
                    REMOTE_SKIN_CACHE.clear();
                }
                skin = AbstractClientPlayer.getLocationSkin(re.name);
                if (skin != null) {
                    REMOTE_SKIN_CACHE.put(re.name, skin);
                }
            }
        }
        if (skin == null) {
            skin = AbstractClientPlayer.locationStevePng;
        }

        GLDraw.rect(-half - 0.9, -half - 0.9, half + 0.9, half + 0.9, GLDraw.withAlpha(0x0A0D12, alpha * 0.90f));
        GLDraw.rect(-half - 0.45, -half - 0.45, half + 0.45, half + 0.45, GLDraw.withAlpha(re.category.color, alpha * 0.85f));

        GLDraw.ensureBlend();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0f, 1.0f, 1.0f, alpha);
        Minecraft.getMinecraft().getTextureManager().bindTexture(skin);
        HeadUV uv = resolvePlayerSkinUV(skin);
        GLDraw.texturedQuad(-half, -half, half, half, uv.u1, uv.v1, uv.u2, uv.v2);
        GLDraw.texturedQuad(
            -half - 0.25, -half - 0.25, half + 0.25, half + 0.25,
            uv.ou1, uv.ov1, uv.ou2, uv.ov2
        );
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
    }

    /**
     * Detects whether the currently bound player skin texture is modern square 64x64 (or HD 128x128+,
     * used by SkinPort / CustomSkinLoader / Mojang 1.8+ skins) or legacy 1.7.10 64x32 (2:1 aspect).
     * Re-checks legacy entries every 2s in case an async ThreadDownloadImageData finishes loading.
     */
    private static HeadUV resolvePlayerSkinUV(ResourceLocation skin) {
        long now = System.currentTimeMillis();
        SkinFormatEntry cached = SKIN_FORMAT_CACHE.get(skin);
        if (cached != null && (cached.isSquare64 || (now - cached.checkedAtMs) < 2000L)) {
            return cached.isSquare64 ? UV_BIPED_64 : UV_PLAYER_32;
        }
        int texW = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int texH = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        boolean isSquare = texW > 0 && texH > 0 && texW == texH;
        if (texW > 0 && texH > 0) {
            if (SKIN_FORMAT_CACHE.size() > 256) {
                SKIN_FORMAT_CACHE.clear();
            }
            SKIN_FORMAT_CACHE.put(skin, new SkinFormatEntry(isSquare, now));
        }
        return isSquare ? UV_BIPED_64 : UV_PLAYER_32;
    }

    private static boolean renderMobHead(RadarEntity re, float half, float alpha) {
        Entity entity = re.entity;
        if (!(entity instanceof EntityLivingBase)) return false;

        ResourceLocation custom = findCustomIcon(entity);
        if (custom != null) {
            GLDraw.rect(-half - 0.6, -half - 0.6, half + 0.6, half + 0.6, GLDraw.withAlpha(0x0A0D12, alpha * 0.85f));
            GLDraw.ensureBlend();
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glColor4f(1.0f, 1.0f, 1.0f, alpha);
            Minecraft.getMinecraft().getTextureManager().bindTexture(custom);
            GLDraw.texturedQuad(-half, -half, half, half, UV_FULL.u1, UV_FULL.v1, UV_FULL.u2, UV_FULL.v2);
            GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
            return true;
        }

        ResourceLocation tex = resolveEntityTexture(entity);
        if (tex == null) return false;

        HeadUV uv = resolveMobHeadUV(entity);

        // Crisp hairline dark frame (clean & minimal, no thick boxes!)
        GLDraw.rect(-half - 0.65, -half - 0.65, half + 0.65, half + 0.65, GLDraw.withAlpha(0x0A0D12, alpha * 0.88f));

        GLDraw.ensureBlend();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0f, 1.0f, 1.0f, alpha);
        Minecraft.getMinecraft().getTextureManager().bindTexture(tex);
        GLDraw.texturedQuad(-half, -half, half, half, uv.u1, uv.v1, uv.u2, uv.v2);
        if (uv.hasOverlay) {
            GLDraw.texturedQuad(-half - 0.15, -half - 0.15, half + 0.15, half + 0.15, uv.ou1, uv.ov1, uv.ou2, uv.ov2);
        }
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
        return true;
    }

    private static ResourceLocation findCustomIcon(Entity entity) {
        String regName = EntityList.getEntityString(entity);
        if (regName == null || regName.isEmpty()) return null;

        ResourceLocation cached = CUSTOM_ICON_CACHE.get(regName);
        if (cached != null) {
            return cached == MISSING_ICON ? null : cached;
        }

        String lower = regName.toLowerCase().replace(':', '/').replace('.', '/');
        String simple = lower.contains("/") ? lower.substring(lower.lastIndexOf('/') + 1) : lower;

        ResourceLocation[] candidates = new ResourceLocation[] {
            new ResourceLocation("saifunsmap", "textures/entity_icons/" + lower + ".png"),
            new ResourceLocation("saifunsmap", "textures/entity_icons/" + simple + ".png"),
            new ResourceLocation("journeymap", "icon/entity/2D/" + simple + ".png"),
            new ResourceLocation("journeymap", "icon/entity/2D/" + simple + "/" + simple + ".png")
        };

        for (ResourceLocation cand : candidates) {
            try {
                if (Minecraft.getMinecraft().getResourceManager().getResource(cand) != null) {
                    CUSTOM_ICON_CACHE.put(regName, cand);
                    return cand;
                }
            } catch (Exception ignored) {}
        }

        CUSTOM_ICON_CACHE.put(regName, MISSING_ICON);
        return null;
    }

    private static boolean hasVariantTextures(Entity entity) {
        return entity instanceof EntityHorse
            || entity instanceof EntityVillager
            || entity instanceof EntityWolf
            || entity instanceof EntityOcelot
            || entity instanceof EntitySkeleton
            || entity instanceof EntitySheep;
    }

    private static ResourceLocation resolveEntityTexture(Entity entity) {
        Class<?> clazz = entity.getClass();
        boolean variant = hasVariantTextures(entity);
        if (!variant) {
            ResourceLocation cached = CLASS_TEXTURE_CACHE.get(clazz);
            if (cached != null) {
                return cached == MISSING_ICON ? null : cached;
            }
        } else {
            ResourceLocation cached = ENTITY_ID_TEXTURE_CACHE.get(entity.getEntityId());
            if (cached != null) {
                return cached == MISSING_ICON ? null : cached;
            }
        }

        ResourceLocation resolved = null;
        try {
            Render render = RenderManager.instance.getEntityRenderObject(entity);
            if (render != null) {
                Method m = getEntityTextureMethod(render.getClass());
                if (m != null) {
                    Object res = m.invoke(render, entity);
                    if (res instanceof ResourceLocation) {
                        resolved = (ResourceLocation) res;
                    }
                }
            }
        } catch (Throwable ignored) {}

        ResourceLocation toStore = resolved != null ? resolved : MISSING_ICON;
        if (!variant) {
            CLASS_TEXTURE_CACHE.put(clazz, toStore);
        } else {
            if (ENTITY_ID_TEXTURE_CACHE.size() > 512) {
                ENTITY_ID_TEXTURE_CACHE.clear();
            }
            ENTITY_ID_TEXTURE_CACHE.put(entity.getEntityId(), toStore);
        }
        return resolved;
    }

    private static Method getEntityTextureMethod(Class<?> renderClass) {
        Method cached = TEXTURE_METHOD_CACHE.get(renderClass);
        if (cached != null) return cached;

        Class<?> cur = renderClass;
        while (cur != null && cur != Object.class) {
            for (Method m : cur.getDeclaredMethods()) {
                String name = m.getName();
                if ((name.equals("getEntityTexture") || name.equals("func_110775_a"))
                    && m.getParameterCount() == 1
                    && ResourceLocation.class.isAssignableFrom(m.getReturnType())) {
                    m.setAccessible(true);
                    TEXTURE_METHOD_CACHE.put(renderClass, m);
                    return m;
                }
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    private static HeadUV resolveMobHeadUV(Entity entity) {
        if (entity instanceof EntityZombie || entity instanceof EntityPigZombie) return UV_BIPED_64;
        if (entity instanceof EntitySkeleton || entity instanceof EntityCreeper || entity instanceof EntityEnderman) {
            return UV_BIPED_32;
        }
        if (entity instanceof EntityVillager || entity instanceof EntityWitch) return UV_VILLAGER;
        if (entity instanceof EntitySpider) return UV_SPIDER;
        if (entity instanceof EntitySlime) return UV_SLIME;
        if (entity instanceof EntityGhast) return UV_GHAST;
        if (entity instanceof EntityCow) return UV_COW;
        if (entity instanceof EntityPig) return UV_PIG;
        if (entity instanceof EntitySheep) return UV_SHEEP;
        if (entity instanceof EntityChicken) return UV_CHICKEN;
        if (entity instanceof EntityWolf) return UV_WOLF;
        if (entity instanceof EntityOcelot) return UV_OCELOT;
        if (entity instanceof EntitySquid) return UV_SQUID;
        if (entity instanceof EntityBlaze) return UV_BIPED_32;
        if (entity instanceof EntityIronGolem) return UV_GOLEM;
        if (entity instanceof EntityHorse) return UV_HORSE;
        if (entity instanceof EntityBat) return UV_BAT;
        if (entity instanceof EntityWither) return UV_WITHER;
        if (entity instanceof EntityDragon) return UV_DRAGON;
        return UV_BIPED_32;
    }

    private static void renderDiamond(int argb, float radius, float alpha) {
        GLDraw.diamond(0, 0, radius + 0.8, GLDraw.withAlpha(0x0A0D12, alpha * 0.92f));
        GLDraw.diamond(0, 0, radius, GLDraw.withAlpha(argb, alpha));
    }

    private static void renderDot(int argb, float radius, float alpha) {
        GLDraw.circle(0, 0, radius + 0.75, 0.0, GLDraw.withAlpha(0x0A0D12, alpha * 0.90f), 12);
        GLDraw.circle(0, 0, radius, 0.0, GLDraw.withAlpha(argb, alpha), 12);
    }

    private static void renderHeightIndicator(boolean above, float half, float alpha) {
        GLDraw.ensureBlend();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        int fill = above ? GLDraw.withAlpha(0xFFFFFF, alpha * 0.90f) : GLDraw.withAlpha(0x94A3B8, alpha * 0.85f);
        float tipY = above ? (-half - 2.6f) : (half + 2.6f);
        float baseY = above ? (-half - 0.9f) : (half + 0.9f);
        float w = 1.6f;

        GLDraw.setColor(fill);
        GL11.glBegin(GL11.GL_TRIANGLES);
        GL11.glVertex2f(0.0f, tipY);
        GL11.glVertex2f(-w, baseY);
        GL11.glVertex2f(w, baseY);
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private static void renderPlayerName(String name, float half, float alpha) {
        FontRenderer fr = Minecraft.getMinecraft().fontRenderer;
        if (fr == null) return;
        float textScale = 0.62f;
        int width = fr.getStringWidth(name);
        double bw = (width * textScale) * 0.5 + 2.0;
        double ty = half + 2.2;
        GLDraw.rect(-bw, ty - 0.8, bw, ty + fr.FONT_HEIGHT * textScale + 0.8, GLDraw.withAlpha(0x0A0D12, alpha * 0.72f));
        GLDraw.drawCenteredText(fr, name, 0.0, ty, textScale, GLDraw.withAlpha(0xE0F2FE, Math.max(0.25f, alpha)));
    }
}

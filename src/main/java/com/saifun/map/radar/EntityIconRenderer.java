package com.saifun.map.radar;

import com.saifun.map.config.MapConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.Tessellator;
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
 * Modern Xaero-style entity icon renderer.
 * - Players: crisp face + hat layer directly from the player's skin.
 * - Mobs (vanilla & all GTNH mods): checks custom 2D resource-pack icons first,
 *   then extracts the exact face UV region from the entity's active Render texture.
 * - Falls back smoothly to styled category dots for items/unknown entities.
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
    private static final Map<String, ResourceLocation> CUSTOM_ICON_CACHE = new ConcurrentHashMap<>();
    private static final ResourceLocation MISSING_ICON = new ResourceLocation("saifunsmap", "__missing__");

    private EntityIconRenderer() {}

    public static void clearCache() {
        CUSTOM_ICON_CACHE.clear();
    }

    public static void renderEntity(
        RadarEntity re,
        double screenX,
        double screenY,
        double playerY,
        float scaleMultiplier
    ) {
        MapConfig cfg = MapConfig.get();
        double dy = re.y - playerY;
        float alpha = computeVerticalAlpha(dy, re.category);

        float baseSize = 8.0f * re.category.defaultScale * cfg.radarIconScale * scaleMultiplier;
        float half = baseSize * 0.5f;

        boolean drawHead = cfg.radarIconStyle == MapConfig.RadarIconStyle.HEADS
            && re.category != EntityCategory.ITEM;

        GL11.glPushMatrix();
        GL11.glTranslated(screenX, screenY, 0.0);

        if (drawHead && re.category == EntityCategory.PLAYER) {
            renderPlayerHead(re, half, alpha);
        } else if (drawHead && re.entity instanceof EntityLivingBase) {
            if (!renderMobHead(re, half, alpha)) {
                renderDot(re.category.color, half * 0.72f, alpha);
            }
        } else {
            renderDot(re.category.color, half * 0.72f, alpha);
        }

        // Height difference caret (▲ above, ▼ below) like modern Xaero
        if (cfg.showHeightIndicator && Math.abs(dy) >= 4.0) {
            renderHeightIndicator(dy > 0, half, alpha);
        }

        // Player username label
        if (re.category == EntityCategory.PLAYER && cfg.showPlayerNames && re.name != null) {
            renderPlayerName(re.name, half, alpha);
        }

        GL11.glPopMatrix();
    }

    private static float computeVerticalAlpha(double dy, EntityCategory cat) {
        if (cat == EntityCategory.PLAYER || cat == EntityCategory.BOSS) {
            return Math.abs(dy) > 24.0 ? 0.85f : 1.0f;
        }
        double abs = Math.abs(dy);
        if (abs <= 8.0) return 1.0f;
        if (abs >= 32.0) return 0.45f;
        return (float) (1.0 - ((abs - 8.0) / 24.0) * 0.55);
    }

    private static void renderPlayerHead(RadarEntity re, float half, float alpha) {
        ResourceLocation skin = null;
        if (re.entity instanceof AbstractClientPlayer) {
            skin = ((AbstractClientPlayer) re.entity).getLocationSkin();
        }
        if (skin == null && re.name != null) {
            skin = AbstractClientPlayer.getLocationSkin(re.name);
        }
        if (skin == null) {
            skin = AbstractClientPlayer.locationStevePng;
        }

        // Crisp dark + cyan border frame
        drawRect(-half - 1.2f, -half - 1.2f, half + 1.2f, half + 1.2f, withAlpha(0x000D1117, alpha * 0.92f));
        drawRect(-half - 0.6f, -half - 0.6f, half + 0.6f, half + 0.6f, withAlpha(re.category.color, alpha * 0.85f));

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0f, 1.0f, 1.0f, alpha);
        Minecraft.getMinecraft().getTextureManager().bindTexture(skin);
        drawTexturedQuad(-half, -half, half, half, UV_PLAYER_32.u1, UV_PLAYER_32.v1, UV_PLAYER_32.u2, UV_PLAYER_32.v2);
        // Hat overlay layer
        drawTexturedQuad(
            -half - 0.35f, -half - 0.35f, half + 0.35f, half + 0.35f,
            UV_PLAYER_32.ou1, UV_PLAYER_32.ov1, UV_PLAYER_32.ou2, UV_PLAYER_32.ov2
        );
    }

    private static boolean renderMobHead(RadarEntity re, float half, float alpha) {
        Entity entity = re.entity;
        if (entity == null) return false;

        // 1. Check custom resource-pack / GTNH 2D icon overrides first
        ResourceLocation custom = findCustomIcon(entity);
        if (custom != null) {
            drawRect(-half - 0.9f, -half - 0.9f, half + 0.9f, half + 0.9f, withAlpha(0x000D1117, alpha * 0.80f));
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glColor4f(1.0f, 1.0f, 1.0f, alpha);
            Minecraft.getMinecraft().getTextureManager().bindTexture(custom);
            drawTexturedQuad(-half, -half, half, half, UV_FULL.u1, UV_FULL.v1, UV_FULL.u2, UV_FULL.v2);
            return true;
        }

        // 2. Dynamically sample head UV from the entity's registered Render texture
        ResourceLocation tex = resolveEntityTexture(entity);
        if (tex == null) return false;

        HeadUV uv = resolveMobHeadUV(entity);

        // Subtle category rim around head icon
        drawRect(-half - 1.0f, -half - 1.0f, half + 1.0f, half + 1.0f, withAlpha(0x000D1117, alpha * 0.85f));
        drawRect(-half - 0.5f, -half - 0.5f, half + 0.5f, half + 0.5f, withAlpha(re.category.color, alpha * 0.70f));

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0f, 1.0f, 1.0f, alpha);
        Minecraft.getMinecraft().getTextureManager().bindTexture(tex);
        drawTexturedQuad(-half, -half, half, half, uv.u1, uv.v1, uv.u2, uv.v2);
        if (uv.hasOverlay) {
            drawTexturedQuad(-half - 0.25f, -half - 0.25f, half + 0.25f, half + 0.25f, uv.ou1, uv.ov1, uv.ou2, uv.ov2);
        }
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
            } catch (Exception ignored) {
                // Resource not present in active packs
            }
        }

        CUSTOM_ICON_CACHE.put(regName, MISSING_ICON);
        return null;
    }

    private static ResourceLocation resolveEntityTexture(Entity entity) {
        try {
            Render render = RenderManager.instance.getEntityRenderObject(entity);
            if (render == null) return null;
            Method m = getEntityTextureMethod(render.getClass());
            if (m == null) return null;
            Object res = m.invoke(render, entity);
            return res instanceof ResourceLocation ? (ResourceLocation) res : null;
        } catch (Throwable ignored) {
            return null;
        }
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
        // Standard Minecraft humanoid/mob head UV fits the vast majority of modded GTNH mobs too
        return UV_BIPED_32;
    }

    private static void renderDot(int argb, float radius, float alpha) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        // Outer dark ring
        drawCircle(0, 0, radius + 0.9f, withAlpha(0x000D1117, alpha * 0.90f), 14);
        // Inner colored dot
        drawCircle(0, 0, radius, withAlpha(argb, alpha), 14);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    private static void renderHeightIndicator(boolean above, float half, float alpha) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        int fill = above ? withAlpha(0x00FFFFFF, alpha * 0.95f) : withAlpha(0x00B0BEC5, alpha * 0.90f);
        float tipY = above ? (-half - 3.6f) : (half + 3.6f);
        float baseY = above ? (-half - 1.2f) : (half + 1.2f);
        float w = 2.4f;

        setColor(fill);
        GL11.glBegin(GL11.GL_TRIANGLES);
        GL11.glVertex2f(0.0f, tipY);
        GL11.glVertex2f(-w, baseY);
        GL11.glVertex2f(w, baseY);
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    private static void renderPlayerName(String name, float half, float alpha) {
        FontRenderer fr = Minecraft.getMinecraft().fontRenderer;
        if (fr == null) return;
        float textScale = 0.65f;
        GL11.glPushMatrix();
        GL11.glTranslatef(0.0f, half + 2.5f, 0.0f);
        GL11.glScalef(textScale, textScale, 1.0f);
        int width = fr.getStringWidth(name);
        int pad = 2;
        drawRect(-width * 0.5f - pad, -1.0f, width * 0.5f + pad, fr.FONT_HEIGHT + 1.0f, withAlpha(0x000B0E14, alpha * 0.72f));
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        int color = withAlpha(0x00E3F2FD, Math.max(0.25f, alpha));
        fr.drawStringWithShadow(name, -width / 2, 0, color);
        GL11.glPopMatrix();
    }

    private static void drawCircle(float cx, float cy, float r, int argb, int segments) {
        setColor(argb);
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glVertex2f(cx, cy);
        for (int i = 0; i <= segments; i++) {
            double ang = (Math.PI * 2.0 * i) / segments;
            GL11.glVertex2f((float) (cx + Math.cos(ang) * r), (float) (cy + Math.sin(ang) * r));
        }
        GL11.glEnd();
    }

    private static void drawRect(float x1, float y1, float x2, float y2, int argb) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        setColor(argb);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x1, y2);
        GL11.glVertex2f(x2, y2);
        GL11.glVertex2f(x2, y1);
        GL11.glVertex2f(x1, y1);
        GL11.glEnd();
    }

    private static void drawTexturedQuad(
        float x1, float y1, float x2, float y2,
        float u1, float v1, float u2, float v2
    ) {
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.addVertexWithUV(x1, y2, 0.0, u1, v2);
        t.addVertexWithUV(x2, y2, 0.0, u2, v2);
        t.addVertexWithUV(x2, y1, 0.0, u2, v1);
        t.addVertexWithUV(x1, y1, 0.0, u1, v1);
        t.draw();
    }

    private static void setColor(int argb) {
        float a = ((argb >>> 24) & 0xFF) / 255.0f;
        float r = ((argb >>> 16) & 0xFF) / 255.0f;
        float g = ((argb >>> 8) & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;
        GL11.glColor4f(r, g, b, a);
    }

    private static int withAlpha(int rgb, float alpha) {
        int a = Math.max(0, Math.min(255, (int) (alpha * 255.0f)));
        return (a << 24) | (rgb & 0x00FFFFFF);
    }
}

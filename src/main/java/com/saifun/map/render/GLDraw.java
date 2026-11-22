package com.saifun.map.render;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.Tessellator;
import org.lwjgl.opengl.GL11;

/**
 * Angelica-safe 2D OpenGL drawing helper that NEVER disables GL_BLEND
 * (unlike Minecraft 1.7.10's Gui.drawRect which corrupts alpha blending).
 */
public final class GLDraw {

    private GLDraw() {}

    public static void setColor(int argb) {
        float a = ((argb >>> 24) & 0xFF) / 255.0f;
        float r = ((argb >>> 16) & 0xFF) / 255.0f;
        float g = ((argb >>> 8) & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;
        GL11.glColor4f(r, g, b, a);
    }

    public static int withAlpha(int rgb, float alpha) {
        int a = Math.max(0, Math.min(255, (int) (alpha * 255.0f)));
        return (a << 24) | (rgb & 0x00FFFFFF);
    }

    public static void ensureBlend() {
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    public static void rect(double x1, double y1, double x2, double y2, int argb) {
        rectZ(x1, y1, x2, y2, 0.0, argb);
    }

    public static void rectZ(double x1, double y1, double x2, double y2, double z, int argb) {
        ensureBlend();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        setColor(argb);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex3d(x1, y2, z);
        GL11.glVertex3d(x2, y2, z);
        GL11.glVertex3d(x2, y1, z);
        GL11.glVertex3d(x1, y1, z);
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
    }

    public static void rectOutline(double x1, double y1, double x2, double y2, double thick, int argb) {
        rect(x1, y1, x2, y1 + thick, argb);
        rect(x1, y2 - thick, x2, y2, argb);
        rect(x1, y1 + thick, x1 + thick, y2 - thick, argb);
        rect(x2 - thick, y1 + thick, x2, y2 - thick, argb);
    }

    public static void diamond(double cx, double cy, double r, int argb) {
        ensureBlend();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        setColor(argb);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2d(cx, cy - r);
        GL11.glVertex2d(cx + r, cy);
        GL11.glVertex2d(cx, cy + r);
        GL11.glVertex2d(cx - r, cy);
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
    }

    public static void circle(double cx, double cy, double r, double z, int argb, int segments) {
        ensureBlend();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        setColor(argb);
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glVertex3d(cx, cy, z);
        for (int i = 0; i <= segments; i++) {
            double ang = (Math.PI * 2.0 * i) / segments;
            GL11.glVertex3d(cx + Math.cos(ang) * r, cy + Math.sin(ang) * r, z);
        }
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
    }

    public static void ring(double cx, double cy, double innerR, double outerR, int argb, int segments) {
        ensureBlend();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        setColor(argb);
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        for (int i = 0; i <= segments; i++) {
            double ang = (Math.PI * 2.0 * i) / segments;
            double cos = Math.cos(ang);
            double sin = Math.sin(ang);
            GL11.glVertex2d(cx + cos * outerR, cy + sin * outerR);
            GL11.glVertex2d(cx + cos * innerR, cy + sin * innerR);
        }
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
    }

    public static void texturedQuad(
        double x1, double y1, double x2, double y2,
        double u1, double v1, double u2, double v2
    ) {
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.addVertexWithUV(x1, y2, 0.0, u1, v2);
        t.addVertexWithUV(x2, y2, 0.0, u2, v2);
        t.addVertexWithUV(x2, y1, 0.0, u2, v1);
        t.addVertexWithUV(x1, y1, 0.0, u1, v1);
        t.draw();
    }

    public static void drawCenteredText(FontRenderer fr, String text, double cx, double y, float scale, int argb) {
        if (fr == null || text == null || text.isEmpty()) return;
        GL11.glPushMatrix();
        GL11.glTranslated(cx, y, 0.0);
        GL11.glScalef(scale, scale, 1.0f);
        ensureBlend();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        int w = fr.getStringWidth(text);
        fr.drawStringWithShadow(text, -w / 2, 0, argb);
        GL11.glPopMatrix();
    }
}

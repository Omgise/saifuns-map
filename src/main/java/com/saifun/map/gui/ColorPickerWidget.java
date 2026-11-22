package com.saifun.map.gui;

import com.saifun.map.render.GLDraw;
import org.lwjgl.opengl.GL11;

import java.awt.Color;

/**
 * Interactive HSV Color Picker widget:
 * - 2D Saturation / Value gradient square (smooth click & drag)
 * - Vertical Rainbow Hue bar (smooth click & drag)
 * - Live RGB/Hex output for picking any custom 24-bit color.
 */
public class ColorPickerWidget {

    private final int x;
    private final int y;
    private final int svSize;
    private final int hueWidth;
    private final int gap = 6;

    private float hue = 0.6f; // 0..1
    private float sat = 0.78f; // 0..1
    private float val = 0.96f; // 0..1

    private boolean draggingSV = false;
    private boolean draggingHue = false;

    public ColorPickerWidget(int x, int y, int svSize, int hueWidth, int initialRgb) {
        this.x = x;
        this.y = y;
        this.svSize = svSize;
        this.hueWidth = hueWidth;
        setRgb(initialRgb);
    }

    public int getTotalWidth() {
        return svSize + gap + hueWidth;
    }

    public int getHeight() {
        return svSize;
    }

    public void setRgb(int rgb) {
        int r = (rgb >>> 16) & 0xFF;
        int g = (rgb >>> 8) & 0xFF;
        int b = rgb & 0xFF;
        float[] hsb = Color.RGBtoHSB(r, g, b, null);
        this.hue = hsb[0];
        this.sat = hsb[1];
        this.val = hsb[2];
    }

    public int getRgb() {
        return Color.HSBtoRGB(hue, sat, val) & 0x00FFFFFF;
    }

    public String getHexString() {
        return String.format("#%06X", getRgb());
    }

    public boolean mouseClicked(int mouseX, int mouseY, int button) {
        if (button != 0) return false;
        if (mouseX >= x && mouseX <= x + svSize && mouseY >= y && mouseY <= y + svSize) {
            draggingSV = true;
            updateSV(mouseX, mouseY);
            return true;
        }
        int hx = x + svSize + gap;
        if (mouseX >= hx && mouseX <= hx + hueWidth && mouseY >= y && mouseY <= y + svSize) {
            draggingHue = true;
            updateHue(mouseY);
            return true;
        }
        return false;
    }

    public boolean mouseDragged(int mouseX, int mouseY) {
        if (draggingSV) {
            updateSV(mouseX, mouseY);
            return true;
        }
        if (draggingHue) {
            updateHue(mouseY);
            return true;
        }
        return false;
    }

    public void mouseReleased() {
        draggingSV = false;
        draggingHue = false;
    }

    private void updateSV(int mouseX, int mouseY) {
        sat = clamp01((mouseX - x) / (float) svSize);
        val = clamp01(1.0f - (mouseY - y) / (float) svSize);
    }

    private void updateHue(int mouseY) {
        hue = clamp01((mouseY - y) / (float) svSize);
    }

    public void draw() {
        GLDraw.ensureBlend();

        // 1. Border around SV square
        GLDraw.rectOutline(x - 1, y - 1, x + svSize + 1, y + svSize + 1, 1.0, 0xAA475569);

        // 2. Smooth 2D Saturation-Value gradient (hardware Gouraud shaded!)
        int pureHueRgb = Color.HSBtoRGB(hue, 1.0f, 1.0f) & 0x00FFFFFF;
        float hr = ((pureHueRgb >>> 16) & 0xFF) / 255.0f;
        float hg = ((pureHueRgb >>> 8) & 0xFF) / 255.0f;
        float hb = (pureHueRgb & 0xFF) / 255.0f;

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glShadeModel(GL11.GL_SMOOTH);

        // Layer 1: Left=White -> Right=Pure Hue
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
        GL11.glVertex2d(x, y);
        GL11.glVertex2d(x, y + svSize);
        GL11.glColor4f(hr, hg, hb, 1.0f);
        GL11.glVertex2d(x + svSize, y + svSize);
        GL11.glVertex2d(x + svSize, y);
        GL11.glEnd();

        // Layer 2: Top=Transparent -> Bottom=Black
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glColor4f(0.0f, 0.0f, 0.0f, 0.0f);
        GL11.glVertex2d(x, y);
        GL11.glVertex2d(x + svSize, y);
        GL11.glColor4f(0.0f, 0.0f, 0.0f, 1.0f);
        GL11.glVertex2d(x + svSize, y + svSize);
        GL11.glVertex2d(x, y + svSize);
        GL11.glEnd();

        // 3. Vertical Rainbow Hue bar (6 smooth color stops)
        int hx = x + svSize + gap;
        GLDraw.rectOutline(hx - 1, y - 1, hx + hueWidth + 1, y + svSize + 1, 1.0, 0xAA475569);

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glShadeModel(GL11.GL_SMOOTH);
        int[] hueStops = { 0xFF0000, 0xFFFF00, 0x00FF00, 0x00FFFF, 0x0000FF, 0xFF00FF, 0xFF0000 };
        double segH = svSize / 6.0;
        for (int i = 0; i < 6; i++) {
            double sy1 = y + i * segH;
            double sy2 = y + (i + 1) * segH;
            int c1 = hueStops[i];
            int c2 = hueStops[i + 1];
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glColor4f(((c1 >>> 16) & 0xFF) / 255f, ((c1 >>> 8) & 0xFF) / 255f, (c1 & 0xFF) / 255f, 1.0f);
            GL11.glVertex2d(hx, sy1);
            GL11.glVertex2d(hx + hueWidth, sy1);
            GL11.glColor4f(((c2 >>> 16) & 0xFF) / 255f, ((c2 >>> 8) & 0xFF) / 255f, (c2 & 0xFF) / 255f, 1.0f);
            GL11.glVertex2d(hx + hueWidth, sy2);
            GL11.glVertex2d(hx, sy2);
            GL11.glEnd();
        }

        GL11.glShadeModel(GL11.GL_FLAT);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);

        // 4. Crisp SV cursor ring
        double curX = x + sat * svSize;
        double curY = y + (1.0 - val) * svSize;
        GLDraw.ring(curX, curY, 2.8, 4.2, 0xFF0A0D12, 16);
        GLDraw.ring(curX, curY, 1.8, 3.0, 0xFFFFFFFF, 16);

        // 5. Crisp Hue bar slider handle
        double hy = y + hue * svSize;
        GLDraw.rect(hx - 2, hy - 1.5, hx + hueWidth + 2, hy + 1.5, 0xFF0A0D12);
        GLDraw.rect(hx - 1, hy - 0.8, hx + hueWidth + 1, hy + 0.8, 0xFFFFFFFF);
    }

    private static float clamp01(float v) {
        return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
    }
}

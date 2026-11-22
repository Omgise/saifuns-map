package com.saifun.map.gui;

import com.saifun.map.render.GLDraw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import org.lwjgl.opengl.GL11;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/**
 * Sleek translucent horizontal slider matching ModernButton's visual language.
 * Updates target values live while dragging and invokes onRelease on mouse release for saving.
 */
public class ModernSlider extends GuiButton {

    private final String label;
    private final double min;
    private final double max;
    private final double step;
    private final DoubleFunction<String> formatter;
    private final DoubleConsumer onChange;
    private final Runnable onRelease;

    private double value;
    private boolean draggingSlider = false;

    public ModernSlider(
        int id,
        int x, int y,
        int width, int height,
        String label,
        double min, double max, double step,
        double initialValue,
        DoubleFunction<String> formatter,
        DoubleConsumer onChange,
        Runnable onRelease
    ) {
        super(id, x, y, width, height, "");
        this.label = label;
        this.min = min;
        this.max = max;
        this.step = step;
        this.formatter = formatter;
        this.onChange = onChange;
        this.onRelease = onRelease;
        setValue(initialValue, false);
    }

    public double getValue() {
        return value;
    }

    public void setValue(double newVal, boolean notify) {
        double clamped = Math.max(min, Math.min(max, newVal));
        if (step > 0.0) {
            clamped = min + Math.round((clamped - min) / step) * step;
            clamped = Math.max(min, Math.min(max, clamped));
        }
        boolean changed = Math.abs(clamped - this.value) > 1e-6;
        this.value = clamped;
        this.displayString = label + ": " + formatter.apply(this.value);
        if (notify && changed && onChange != null) {
            onChange.accept(this.value);
        }
    }

    private void updateFromMouse(int mouseX) {
        double pad = 3.0;
        double span = Math.max(1.0, this.width - pad * 2.0);
        double t = Math.max(0.0, Math.min(1.0, (mouseX - (this.xPosition + pad)) / span));
        setValue(min + t * (max - min), true);
    }

    @Override
    public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
        if (super.mousePressed(mc, mouseX, mouseY)) {
            this.draggingSlider = true;
            updateFromMouse(mouseX);
            return true;
        }
        return false;
    }

    @Override
    protected void mouseDragged(Minecraft mc, int mouseX, int mouseY) {
        if (this.visible && this.draggingSlider) {
            updateFromMouse(mouseX);
        }
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY) {
        if (this.draggingSlider) {
            this.draggingSlider = false;
            if (onRelease != null) {
                onRelease.run();
            }
        }
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!this.visible) return;
        this.field_146123_n = mouseX >= this.xPosition && mouseY >= this.yPosition
            && mouseX < this.xPosition + this.width && mouseY < this.yPosition + this.height;

        if (this.draggingSlider) {
            updateFromMouse(mouseX);
        }

        double x1 = this.xPosition;
        double y1 = this.yPosition;
        double x2 = x1 + this.width;
        double y2 = y1 + this.height;

        double t = (max > min) ? Math.max(0.0, Math.min(1.0, (value - min) / (max - min))) : 0.0;
        boolean active = this.draggingSlider || this.field_146123_n;

        GLDraw.ensureBlend();

        // Dark track background
        int bg = active ? 0xDD172030 : 0xCC101621;
        GLDraw.rect(x1, y1, x2, y2, bg);

        // Filled progress bar
        double innerLeft = x1 + 1.5;
        double innerRight = x2 - 1.5;
        double fillEnd = innerLeft + (innerRight - innerLeft) * t;
        int fillCol = this.draggingSlider ? 0xBB2563EB : (this.field_146123_n ? 0x992563EB : 0x771D4ED8);
        if (fillEnd > innerLeft + 0.5) {
            GLDraw.rect(innerLeft, y1 + 1.5, fillEnd, y2 - 1.5, fillCol);
        }

        // Crisp border
        int border = this.draggingSlider ? 0xFF38BDF8 : (this.field_146123_n ? 0xCC60A5FA : 0x77334155);
        GLDraw.rectOutline(x1, y1, x2, y2, 1.0, border);

        // Sleek thumb indicator
        double thumbX = Math.max(x1 + 3.0, Math.min(x2 - 3.0, fillEnd));
        GLDraw.rect(thumbX - 2.0, y1 + 1.0, thumbX + 2.0, y2 - 1.0, active ? 0xFFF8FAFC : 0xCCCBD5E1);

        // Centered crisp text
        FontRenderer fr = mc.fontRenderer;
        int textColor = active ? 0xFFFFFFFF : 0xFFE2E8F0;
        int tx = this.xPosition + (this.width - fr.getStringWidth(this.displayString)) / 2;
        int ty = this.yPosition + (this.height - 8) / 2;
        fr.drawStringWithShadow(this.displayString, tx, ty, textColor);

        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
    }
}

package com.saifun.map.gui;

import com.saifun.map.render.GLDraw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;

/**
 * Clean modern dark flat button replacing legacy gray stone Minecraft GuiButtons.
 */
public class ModernButton extends GuiButton {

    private boolean accent = false;
    private boolean selected = false;

    public ModernButton(int id, int x, int y, int width, int height, String text) {
        super(id, x, y, width, height, text);
    }

    public ModernButton setAccent(boolean accent) {
        this.accent = accent;
        return this;
    }

    public ModernButton setSelected(boolean selected) {
        this.selected = selected;
        return this;
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!this.visible) return;

        this.field_146123_n = mouseX >= this.xPosition
            && mouseY >= this.yPosition
            && mouseX < this.xPosition + this.width
            && mouseY < this.yPosition + this.height;

        int bg;
        int border;
        int textCol;

        if (!this.enabled) {
            if (selected) {
                bg = 0xDD2563EB;
                border = 0xFF60A5FA;
                textCol = 0xFFFFFFFF;
            } else {
                bg = 0x6611161F;
                border = 0x33334155;
                textCol = 0xFF64748B;
            }
        } else if (accent) {
            bg = this.field_146123_n ? 0xEE3B82F6 : 0xDD2563EB;
            border = 0xFF93C5FD;
            textCol = 0xFFFFFFFF;
        } else if (this.field_146123_n) {
            bg = 0xEE222C3D;
            border = 0xCC4F8FF7;
            textCol = 0xFFFFFFFF;
        } else {
            bg = 0xCC151B25;
            border = 0x88334155;
            textCol = 0xFFE2E8F0;
        }

        GLDraw.rect(this.xPosition, this.yPosition, this.xPosition + this.width, this.yPosition + this.height, bg);
        GLDraw.rectOutline(this.xPosition, this.yPosition, this.xPosition + this.width, this.yPosition + this.height, 1.0, border);

        int textY = this.yPosition + (this.height - 8) / 2;
        this.drawCenteredString(mc.fontRenderer, this.displayString, this.xPosition + this.width / 2, textY, textCol);
        GLDraw.ensureBlend();
    }
}

package com.saifun.map.gui;

import com.saifun.map.render.GLDraw;
import com.saifun.map.waypoints.Waypoint;
import com.saifun.map.waypoints.WaypointManager;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.lwjgl.input.Keyboard;

/**
 * Sleek modern Add / Edit Waypoint dialog featuring a full interactive HSV Color Picker,
 * editable #RRGGBB hex code, live badge preview, 12 quick swatches, and ModernButtons.
 */
public class GuiWaypointEdit extends GuiScreen {

    private final GuiScreen parent;
    private final WaypointManager manager;
    private final Waypoint editing;
    private final int defaultX, defaultY, defaultZ, defaultDim;

    private GuiTextField nameField;
    private GuiTextField initialsField;
    private GuiTextField xField;
    private GuiTextField yField;
    private GuiTextField zField;
    private GuiTextField hexField;
    private ColorPickerWidget colorPicker;
    private int currentColor;
    private boolean enabled;

    public GuiWaypointEdit(
        GuiScreen parent,
        WaypointManager manager,
        Waypoint editing,
        int x, int y, int z, int dim
    ) {
        this.parent = parent;
        this.manager = manager;
        this.editing = editing;
        this.defaultX = editing != null ? editing.x : x;
        this.defaultY = editing != null ? editing.y : y;
        this.defaultZ = editing != null ? editing.z : z;
        this.defaultDim = editing != null ? editing.dimensionId : dim;
        this.enabled = editing == null || editing.enabled;
        this.currentColor = editing != null ? (editing.color & 0xFFFFFF) : WaypointManager.pickRandomColor();
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        this.buttonList.clear();

        int panelW = 292;
        int panelH = 236;
        int panelX = (this.width - panelW) / 2;
        int panelY = Math.max(16, (this.height - panelH) / 2);

        nameField = new GuiTextField(this.fontRendererObj, panelX + 12, panelY + 32, 206, 16);
        nameField.setMaxStringLength(48);
        nameField.setText(editing != null ? editing.name : "New Waypoint");
        nameField.setFocused(true);

        initialsField = new GuiTextField(this.fontRendererObj, panelX + 226, panelY + 32, 54, 16);
        initialsField.setMaxStringLength(2);
        initialsField.setText(editing != null ? editing.initials : "");

        xField = new GuiTextField(this.fontRendererObj, panelX + 12, panelY + 66, 84, 16);
        xField.setText(String.valueOf(defaultX));

        yField = new GuiTextField(this.fontRendererObj, panelX + 104, panelY + 66, 84, 16);
        yField.setText(String.valueOf(defaultY));

        zField = new GuiTextField(this.fontRendererObj, panelX + 196, panelY + 66, 84, 16);
        zField.setText(String.valueOf(defaultZ));

        // Interactive HSV Color Picker (left side) + Hex + Live Preview + Presets (right side)
        colorPicker = new ColorPickerWidget(panelX + 12, panelY + 94, 92, 14, currentColor);

        hexField = new GuiTextField(this.fontRendererObj, panelX + 132, panelY + 122, 74, 16);
        hexField.setMaxStringLength(7);
        hexField.setText(colorPicker.getHexString());

        this.buttonList.add(new ModernButton(1, panelX + 132, panelY + 168, 72, 18, enabled ? "Visible: ON" : "Visible: OFF"));
        this.buttonList.add(new ModernButton(2, panelX + 208, panelY + 168, 72, 18, "Random"));
        this.buttonList.add(new ModernButton(10, panelX + 12, panelY + 204, 130, 20, "Save").setAccent(true));
        this.buttonList.add(new ModernButton(11, panelX + 150, panelY + 204, 130, 20, "Cancel"));
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 1) {
            enabled = !enabled;
            button.displayString = enabled ? "Visible: ON" : "Visible: OFF";
        } else if (button.id == 2) {
            currentColor = WaypointManager.pickRandomColor();
            colorPicker.setRgb(currentColor);
            hexField.setText(colorPicker.getHexString());
        } else if (button.id == 10) {
            saveAndClose();
        } else if (button.id == 11) {
            this.mc.displayGuiScreen(parent);
        }
    }

    private void saveAndClose() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) name = "Waypoint";
        String initials = Waypoint.normalizeInitials(initialsField.getText(), name);
        int x = parseIntSafe(xField.getText(), defaultX);
        int y = parseIntSafe(yField.getText(), defaultY);
        int z = parseIntSafe(zField.getText(), defaultZ);

        if (editing != null) {
            editing.name = name;
            editing.initials = initials;
            editing.x = x;
            editing.y = y;
            editing.z = z;
            editing.color = currentColor;
            editing.enabled = enabled;
            manager.save();
        } else {
            Waypoint created = new Waypoint(name, initials, x, y, z, defaultDim, currentColor, Waypoint.Type.NORMAL);
            created.enabled = enabled;
            manager.addWaypoint(created);
        }
        this.mc.displayGuiScreen(parent);
    }

    private static int parseIntSafe(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            this.mc.displayGuiScreen(parent);
            return;
        }
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            saveAndClose();
            return;
        }
        nameField.textboxKeyTyped(typedChar, keyCode);
        initialsField.textboxKeyTyped(typedChar, keyCode);
        xField.textboxKeyTyped(typedChar, keyCode);
        yField.textboxKeyTyped(typedChar, keyCode);
        zField.textboxKeyTyped(typedChar, keyCode);

        if (hexField.textboxKeyTyped(typedChar, keyCode)) {
            String raw = hexField.getText().trim();
            if (raw.startsWith("#")) raw = raw.substring(1);
            if (raw.length() == 6) {
                try {
                    currentColor = Integer.parseInt(raw, 16) & 0xFFFFFF;
                    colorPicker.setRgb(currentColor);
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        nameField.mouseClicked(mouseX, mouseY, mouseButton);
        initialsField.mouseClicked(mouseX, mouseY, mouseButton);
        xField.mouseClicked(mouseX, mouseY, mouseButton);
        yField.mouseClicked(mouseX, mouseY, mouseButton);
        zField.mouseClicked(mouseX, mouseY, mouseButton);
        hexField.mouseClicked(mouseX, mouseY, mouseButton);

        if (colorPicker.mouseClicked(mouseX, mouseY, mouseButton)) {
            currentColor = colorPicker.getRgb();
            hexField.setText(colorPicker.getHexString());
            return;
        }

        // Quick 2x6 preset color swatches on the right side
        int panelW = 292;
        int panelH = 236;
        int panelX = (this.width - panelW) / 2;
        int panelY = Math.max(16, (this.height - panelH) / 2);
        int swatchBaseX = panelX + 132;
        int swatchBaseY = panelY + 144;
        for (int i = 0; i < WaypointManager.PALETTE.length; i++) {
            int col = i % 6;
            int row = i / 6;
            int sx = swatchBaseX + col * 25;
            int sy = swatchBaseY + row * 11;
            if (mouseX >= sx && mouseX <= sx + 22 && mouseY >= sy && mouseY <= sy + 9) {
                currentColor = WaypointManager.PALETTE[i];
                colorPicker.setRgb(currentColor);
                hexField.setText(colorPicker.getHexString());
                break;
            }
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (colorPicker.mouseDragged(mouseX, mouseY)) {
            currentColor = colorPicker.getRgb();
            hexField.setText(colorPicker.getHexString());
        }
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int state) {
        super.mouseMovedOrUp(mouseX, mouseY, state);
        if (state == 0) {
            colorPicker.mouseReleased();
        }
    }

    @Override
    public void updateScreen() {
        nameField.updateCursorCounter();
        initialsField.updateCursorCounter();
        xField.updateCursorCounter();
        yField.updateCursorCounter();
        zField.updateCursorCounter();
        hexField.updateCursorCounter();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        int panelW = 292;
        int panelH = 236;
        int panelX = (this.width - panelW) / 2;
        int panelY = Math.max(16, (this.height - panelH) / 2);

        GLDraw.ensureBlend();
        GLDraw.rect(panelX, panelY, panelX + panelW, panelY + panelH, 0xEA0C1017);
        GLDraw.rectOutline(panelX, panelY, panelX + panelW, panelY + panelH, 1.0, 0x88334155);
        GLDraw.rect(panelX, panelY, panelX + panelW, panelY + 2, 0xFF000000 | currentColor);

        String title = editing != null ? "Edit Waypoint (Dim " + defaultDim + ")" : "Add Waypoint (Dim " + defaultDim + ")";
        drawCenteredString(this.fontRendererObj, title, this.width / 2, panelY + 8, 0xFFF8FAFC);

        this.fontRendererObj.drawStringWithShadow("Name", panelX + 12, panelY + 22, 0xFF94A3B8);
        this.fontRendererObj.drawStringWithShadow("Tag", panelX + 226, panelY + 22, 0xFF94A3B8);
        this.fontRendererObj.drawStringWithShadow("X", panelX + 12, panelY + 56, 0xFF94A3B8);
        this.fontRendererObj.drawStringWithShadow("Y", panelX + 104, panelY + 56, 0xFF94A3B8);
        this.fontRendererObj.drawStringWithShadow("Z", panelX + 196, panelY + 56, 0xFF94A3B8);

        nameField.drawTextBox();
        initialsField.drawTextBox();
        xField.drawTextBox();
        yField.drawTextBox();
        zField.drawTextBox();

        // Interactive HSV Color Picker
        colorPicker.draw();

        // Live waypoint badge preview + Hex code input
        int prevX = panelX + 132;
        int prevY = panelY + 94;
        GLDraw.rect(prevX, prevY, prevX + 22, prevY + 22, 0xFF0A0D12);
        GLDraw.rect(prevX + 2, prevY + 2, prevX + 20, prevY + 20, 0xFF000000 | currentColor);
        String tagPreview = Waypoint.normalizeInitials(initialsField.getText(), nameField.getText());
        GLDraw.drawCenteredText(this.fontRendererObj, tagPreview, prevX + 11.2, prevY + 7.5, 0.92f, 0xFFFFFFFF);

        this.fontRendererObj.drawStringWithShadow("Color Hex", prevX + 28, prevY + 4, 0xFF94A3B8);
        this.fontRendererObj.drawStringWithShadow(colorPicker.getHexString(), prevX + 28, prevY + 14, 0xFFE2E8F0);
        hexField.drawTextBox();

        // Compact 2x6 quick preset swatches
        int swatchBaseX = panelX + 132;
        int swatchBaseY = panelY + 144;
        for (int i = 0; i < WaypointManager.PALETTE.length; i++) {
            int col = i % 6;
            int row = i / 6;
            int sx = swatchBaseX + col * 25;
            int sy = swatchBaseY + row * 11;
            int swColor = WaypointManager.PALETTE[i];
            if (swColor == currentColor) {
                GLDraw.rectOutline(sx - 1, sy - 1, sx + 23, sy + 10, 1.0, 0xFFFFFFFF);
            }
            GLDraw.rect(sx, sy, sx + 22, sy + 9, 0xFF000000 | swColor);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}

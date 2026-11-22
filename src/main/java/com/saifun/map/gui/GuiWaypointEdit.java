package com.saifun.map.gui;

import com.saifun.map.waypoints.Waypoint;
import com.saifun.map.waypoints.WaypointManager;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.lwjgl.input.Keyboard;

/**
 * Sleek translucent Add / Edit Waypoint dialog.
 */
public class GuiWaypointEdit extends GuiScreen {

    private final GuiScreen parent;
    private final WaypointManager manager;
    private final Waypoint editing; // null when creating new
    private final int defaultX, defaultY, defaultZ, defaultDim;

    private GuiTextField nameField;
    private GuiTextField initialsField;
    private GuiTextField xField;
    private GuiTextField yField;
    private GuiTextField zField;
    private int selectedColorIndex;
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

        int initialColor = editing != null ? editing.color : WaypointManager.pickRandomColor();
        this.selectedColorIndex = 0;
        for (int i = 0; i < WaypointManager.PALETTE.length; i++) {
            if (WaypointManager.PALETTE[i] == initialColor) {
                this.selectedColorIndex = i;
                break;
            }
        }
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        this.buttonList.clear();

        int panelW = 260;
        int panelX = (this.width - panelW) / 2;
        int panelY = Math.max(24, (this.height - 196) / 2);

        nameField = new GuiTextField(this.fontRendererObj, panelX + 12, panelY + 34, 176, 18);
        nameField.setMaxStringLength(48);
        nameField.setText(editing != null ? editing.name : "New Waypoint");
        nameField.setFocused(true);

        initialsField = new GuiTextField(this.fontRendererObj, panelX + 196, panelY + 34, 52, 18);
        initialsField.setMaxStringLength(2);
        initialsField.setText(editing != null ? editing.initials : "");

        xField = new GuiTextField(this.fontRendererObj, panelX + 12, panelY + 72, 74, 18);
        xField.setText(String.valueOf(defaultX));

        yField = new GuiTextField(this.fontRendererObj, panelX + 93, panelY + 72, 74, 18);
        yField.setText(String.valueOf(defaultY));

        zField = new GuiTextField(this.fontRendererObj, panelX + 174, panelY + 72, 74, 18);
        zField.setText(String.valueOf(defaultZ));

        this.buttonList.add(new GuiButton(1, panelX + 12, panelY + 132, 114, 20, "Enabled: " + (enabled ? "ON" : "OFF")));
        this.buttonList.add(new GuiButton(2, panelX + 134, panelY + 132, 114, 20, "Random Color"));
        this.buttonList.add(new GuiButton(10, panelX + 12, panelY + 162, 114, 20, "Save"));
        this.buttonList.add(new GuiButton(11, panelX + 134, panelY + 162, 114, 20, "Cancel"));
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 1) {
            enabled = !enabled;
            button.displayString = "Enabled: " + (enabled ? "ON" : "OFF");
        } else if (button.id == 2) {
            selectedColorIndex = (selectedColorIndex + 1) % WaypointManager.PALETTE.length;
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
        int color = WaypointManager.PALETTE[selectedColorIndex];

        if (editing != null) {
            editing.name = name;
            editing.initials = initials;
            editing.x = x;
            editing.y = y;
            editing.z = z;
            editing.color = color;
            editing.enabled = enabled;
            manager.save();
        } else {
            Waypoint created = new Waypoint(name, initials, x, y, z, defaultDim, color, Waypoint.Type.NORMAL);
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
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        nameField.mouseClicked(mouseX, mouseY, mouseButton);
        initialsField.mouseClicked(mouseX, mouseY, mouseButton);
        xField.mouseClicked(mouseX, mouseY, mouseButton);
        yField.mouseClicked(mouseX, mouseY, mouseButton);
        zField.mouseClicked(mouseX, mouseY, mouseButton);

        // Color swatch clicks
        int panelW = 260;
        int panelX = (this.width - panelW) / 2;
        int panelY = Math.max(24, (this.height - 196) / 2);
        int swatchY = panelY + 104;
        for (int i = 0; i < WaypointManager.PALETTE.length; i++) {
            int sx = panelX + 12 + i * 20;
            if (mouseX >= sx && mouseX <= sx + 16 && mouseY >= swatchY && mouseY <= swatchY + 16) {
                selectedColorIndex = i;
                break;
            }
        }
    }

    @Override
    public void updateScreen() {
        nameField.updateCursorCounter();
        initialsField.updateCursorCounter();
        xField.updateCursorCounter();
        yField.updateCursorCounter();
        zField.updateCursorCounter();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        int panelW = 260;
        int panelH = 192;
        int panelX = (this.width - panelW) / 2;
        int panelY = Math.max(24, (this.height - 196) / 2);

        drawRect(panelX, panelY, panelX + panelW, panelY + panelH, 0xE60E1218);
        drawRect(panelX, panelY, panelX + panelW, panelY + 2, 0xFF000000 | WaypointManager.PALETTE[selectedColorIndex]);

        String title = editing != null ? "Edit Waypoint (Dim " + defaultDim + ")" : "Add Waypoint (Dim " + defaultDim + ")";
        drawCenteredString(this.fontRendererObj, title, this.width / 2, panelY + 8, 0xFFF1F5F9);

        this.fontRendererObj.drawStringWithShadow("Name", panelX + 12, panelY + 23, 0xFF94A3B8);
        this.fontRendererObj.drawStringWithShadow("Tag", panelX + 196, panelY + 23, 0xFF94A3B8);
        this.fontRendererObj.drawStringWithShadow("X", panelX + 12, panelY + 61, 0xFF94A3B8);
        this.fontRendererObj.drawStringWithShadow("Y", panelX + 93, panelY + 61, 0xFF94A3B8);
        this.fontRendererObj.drawStringWithShadow("Z", panelX + 174, panelY + 61, 0xFF94A3B8);

        nameField.drawTextBox();
        initialsField.drawTextBox();
        xField.drawTextBox();
        yField.drawTextBox();
        zField.drawTextBox();

        // Color palette strip
        int swatchY = panelY + 104;
        for (int i = 0; i < WaypointManager.PALETTE.length; i++) {
            int sx = panelX + 12 + i * 20;
            if (i == selectedColorIndex) {
                drawRect(sx - 2, swatchY - 2, sx + 18, swatchY + 18, 0xFFFFFFFF);
            }
            drawRect(sx, swatchY, sx + 16, swatchY + 16, 0xFF000000 | WaypointManager.PALETTE[i]);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}

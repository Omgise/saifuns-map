package com.saifun.map.gui;

import com.saifun.map.render.GLDraw;
import com.saifun.map.render.WaypointIconRenderer;
import com.saifun.map.util.Lang;
import com.saifun.map.waypoints.Waypoint;
import com.saifun.map.waypoints.WaypointManager;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.lwjgl.input.Keyboard;

import java.util.Collections;
import java.util.List;

/**
 * Clean minimalist Add / Edit Waypoint dialog featuring:
 * - Freeform user-created group input + quick pills for existing user groups
 * - 12-icon pixel-art selector
 * - Full interactive HSV Color Picker + #RRGGBB hex + 12 quick swatches
 */
public class GuiWaypointEdit extends GuiScreen {

    private static final int PANEL_W = 296;
    private static final int PANEL_H = 292;
    private static final int ICON_COLS = 8;
    private static final int ICON_CELL_W = 32;
    private static final int ICON_CELL_H = 21;
    private static final int ICON_GAP_X = 2;
    private static final int ICON_GAP_Y = 3;

    private final GuiScreen parent;
    private final WaypointManager manager;
    private final Waypoint editing;
    private final String defaultName;
    private final int defaultX, defaultY, defaultZ, defaultDim;

    private GuiTextField nameField;
    private GuiTextField groupField;
    private GuiTextField xField;
    private GuiTextField yField;
    private GuiTextField zField;
    private GuiTextField hexField;
    private ColorPickerWidget colorPicker;
    private int currentColor;
    private Waypoint.IconType currentIcon;
    private boolean enabled;
    private List<String> existingGroups = Collections.emptyList();

    public GuiWaypointEdit(
        GuiScreen parent,
        WaypointManager manager,
        Waypoint editing,
        int x, int y, int z, int dim
    ) {
        this(
            parent,
            manager,
            editing,
            editing != null ? editing.name : Lang.tr("saifunsmap.wpedit.default_name"),
            x, y, z, dim,
            editing != null ? (editing.color & 0xFFFFFF) : WaypointManager.pickRandomColor()
        );
    }

    public GuiWaypointEdit(
        GuiScreen parent,
        WaypointManager manager,
        Waypoint editing,
        String presetName,
        int x, int y, int z, int dim,
        int presetColor
    ) {
        this.parent = parent;
        this.manager = manager;
        this.editing = editing;
        this.defaultName = (editing != null ? editing.name : (presetName != null && !presetName.trim().isEmpty() ? presetName.trim() : "New Waypoint"));
        this.defaultX = editing != null ? editing.x : x;
        this.defaultY = editing != null ? editing.y : y;
        this.defaultZ = editing != null ? editing.z : z;
        this.defaultDim = editing != null ? editing.dimensionId : dim;
        this.enabled = editing == null || editing.enabled;
        this.currentColor = editing != null ? (editing.color & 0xFFFFFF) : (presetColor & 0xFFFFFF);
        this.currentIcon = editing != null ? editing.getEffectiveIcon() : Waypoint.IconType.PIN;
    }

    private boolean showGroupBar() {
        return existingGroups.size() > 1;
    }

    private int getEffectivePanelH() {
        return showGroupBar() ? PANEL_H : (PANEL_H - 18);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        this.buttonList.clear();
        this.existingGroups = manager.getAllGroupNames();

        String prevName = nameField != null ? nameField.getText() : defaultName;
        String prevGroup = groupField != null ? groupField.getText() : (editing != null ? editing.getEffectiveGroup() : Waypoint.GROUP_DEFAULT);
        String prevX = xField != null ? xField.getText() : String.valueOf(defaultX);
        String prevY = yField != null ? yField.getText() : String.valueOf(defaultY);
        String prevZ = zField != null ? zField.getText() : String.valueOf(defaultZ);

        int panelH = getEffectivePanelH();
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = Math.max(8, (this.height - panelH) / 2);
        int gOff = showGroupBar() ? 18 : 0;

        nameField = new GuiTextField(this.fontRendererObj, panelX + 12, panelY + 30, 166, 16);
        nameField.setMaxStringLength(48);
        nameField.setText(prevName);
        nameField.setFocused(true);

        groupField = new GuiTextField(this.fontRendererObj, panelX + 184, panelY + 30, 100, 16);
        groupField.setMaxStringLength(20);
        groupField.setText(prevGroup);

        xField = new GuiTextField(this.fontRendererObj, panelX + 12, panelY + 60, 86, 16);
        xField.setText(prevX);

        yField = new GuiTextField(this.fontRendererObj, panelX + 105, panelY + 60, 86, 16);
        yField.setText(prevY);

        zField = new GuiTextField(this.fontRendererObj, panelX + 198, panelY + 60, 86, 16);
        zField.setText(prevZ);

        colorPicker = new ColorPickerWidget(panelX + 12, panelY + 144 + gOff, 92, 14, currentColor);

        hexField = new GuiTextField(this.fontRendererObj, panelX + 134, panelY + 172 + gOff, 74, 16);
        hexField.setMaxStringLength(7);
        hexField.setText(colorPicker.getHexString());

        this.buttonList.add(new ModernButton(1, panelX + 134, panelY + 218 + gOff, 73, 18, Lang.tr("saifunsmap.wpedit.enabled", Lang.onOff(enabled))));
        this.buttonList.add(new ModernButton(2, panelX + 211, panelY + 218 + gOff, 73, 18, Lang.tr("saifunsmap.wpedit.random")));
        this.buttonList.add(new ModernButton(10, panelX + 12, panelY + 246 + gOff, 132, 20, Lang.tr("saifunsmap.common.save")).setAccent(true));
        this.buttonList.add(new ModernButton(11, panelX + 152, panelY + 246 + gOff, 132, 20, Lang.tr("saifunsmap.common.cancel")));
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 1) {
            enabled = !enabled;
            button.displayString = Lang.tr("saifunsmap.wpedit.enabled", Lang.onOff(enabled));
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
        String initials = Waypoint.normalizeInitials(editing != null ? editing.initials : null, name);
        Waypoint.Type wpType = editing != null ? editing.type : Waypoint.Type.NORMAL;
        String group = Waypoint.normalizeGroup(groupField.getText(), wpType);
        if (!Waypoint.GROUP_DEFAULT.equalsIgnoreCase(group) && !Waypoint.GROUP_DEATHS.equalsIgnoreCase(group)) {
            group = manager.addCustomGroup(group);
        }
        int x = parseIntSafe(xField.getText(), defaultX);
        int y = parseIntSafe(yField.getText(), defaultY);
        int z = parseIntSafe(zField.getText(), defaultZ);

        if (editing != null) {
            editing.name = name;
            editing.initials = initials;
            editing.group = group;
            editing.x = x;
            editing.y = y;
            editing.z = z;
            editing.color = currentColor;
            editing.icon = currentIcon;
            editing.enabled = enabled;
            manager.save();
        } else {
            Waypoint created = new Waypoint(name, initials, x, y, z, defaultDim, currentColor, Waypoint.Type.NORMAL);
            created.group = group;
            created.icon = currentIcon;
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
        if (keyCode == Keyboard.KEY_TAB) {
            GuiTextField[] fields = { nameField, groupField, xField, yField, zField, hexField };
            int cur = 0;
            for (int i = 0; i < fields.length; i++) {
                if (fields[i].isFocused()) {
                    cur = i;
                    fields[i].setFocused(false);
                    break;
                }
            }
            int next = (cur + (isShiftKeyDown() ? fields.length - 1 : 1)) % fields.length;
            fields[next].setFocused(true);
            return;
        }
        nameField.textboxKeyTyped(typedChar, keyCode);
        groupField.textboxKeyTyped(typedChar, keyCode);
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
        groupField.mouseClicked(mouseX, mouseY, mouseButton);
        xField.mouseClicked(mouseX, mouseY, mouseButton);
        yField.mouseClicked(mouseX, mouseY, mouseButton);
        zField.mouseClicked(mouseX, mouseY, mouseButton);
        hexField.mouseClicked(mouseX, mouseY, mouseButton);

        if (colorPicker.mouseClicked(mouseX, mouseY, mouseButton)) {
            currentColor = colorPicker.getRgb();
            hexField.setText(colorPicker.getHexString());
            return;
        }

        int panelH = getEffectivePanelH();
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = Math.max(8, (this.height - panelH) / 2);
        int gOff = showGroupBar() ? 18 : 0;

        // User-created group quick pills (only rendered when user has custom/death groups)
        if (showGroupBar()) {
            int pillY = panelY + 80;
            int pillH = 14;
            int px = panelX + 12;
            int maxR = panelX + PANEL_W - 12;
            for (int i = 0; i < existingGroups.size(); i++) {
                String g = existingGroups.get(i);
                String lbl = GuiWaypoints.localizeGroup(g);
                int pw = Math.max(30, this.fontRendererObj.getStringWidth(lbl) + 8);
                if (px + pw > maxR) break;
                if (mouseX >= px && mouseX <= px + pw && mouseY >= pillY && mouseY <= pillY + pillH) {
                    groupField.setText(g);
                    return;
                }
                px += pw + 4;
            }
        }

        // 16-icon pixel-art selector grid (2 rows x 8 columns)
        Waypoint.IconType[] icons = Waypoint.IconType.values();
        int iconBaseX = panelX + 13;
        int iconBaseY = panelY + 91 + gOff;
        for (int i = 0; i < icons.length; i++) {
            int col = i % ICON_COLS;
            int row = i / ICON_COLS;
            int ix = iconBaseX + col * (ICON_CELL_W + ICON_GAP_X);
            int iy = iconBaseY + row * (ICON_CELL_H + ICON_GAP_Y);
            if (mouseX >= ix && mouseX <= ix + ICON_CELL_W && mouseY >= iy && mouseY <= iy + ICON_CELL_H) {
                currentIcon = icons[i];
                return;
            }
        }

        // Quick 2x6 preset color swatches on the right side
        int swatchBaseX = panelX + 134;
        int swatchBaseY = panelY + 194 + gOff;
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
        groupField.updateCursorCounter();
        xField.updateCursorCounter();
        yField.updateCursorCounter();
        zField.updateCursorCounter();
        hexField.updateCursorCounter();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        int panelH = getEffectivePanelH();
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = Math.max(8, (this.height - panelH) / 2);
        int gOff = showGroupBar() ? 18 : 0;

        GLDraw.ensureBlend();
        GLDraw.rect(panelX, panelY, panelX + PANEL_W, panelY + panelH, 0xEC0C1017);
        GLDraw.rectOutline(panelX, panelY, panelX + PANEL_W, panelY + panelH, 1.0, 0x88334155);
        GLDraw.rect(panelX, panelY, panelX + PANEL_W, panelY + 2, 0xFF000000 | currentColor);

        String title = editing != null
            ? Lang.tr("saifunsmap.wpedit.title_edit", defaultDim)
            : Lang.tr("saifunsmap.wpedit.title_new", defaultDim);
        drawCenteredString(this.fontRendererObj, title, this.width / 2, panelY + 8, 0xFFF8FAFC);

        this.fontRendererObj.drawStringWithShadow(Lang.tr("saifunsmap.wpedit.name"), panelX + 12, panelY + 20, 0xFF94A3B8);
        this.fontRendererObj.drawStringWithShadow(Lang.tr("saifunsmap.wpedit.group"), panelX + 184, panelY + 20, 0xFF94A3B8);
        this.fontRendererObj.drawStringWithShadow("X", panelX + 12, panelY + 50, 0xFF94A3B8);
        this.fontRendererObj.drawStringWithShadow("Y", panelX + 105, panelY + 50, 0xFF94A3B8);
        this.fontRendererObj.drawStringWithShadow("Z", panelX + 198, panelY + 50, 0xFF94A3B8);
        this.fontRendererObj.drawStringWithShadow(Lang.tr("saifunsmap.wpedit.icon"), panelX + 12, panelY + 80 + gOff, 0xFF94A3B8);

        nameField.drawTextBox();
        groupField.drawTextBox();
        xField.drawTextBox();
        yField.drawTextBox();
        zField.drawTextBox();

        // User-created group quick pills (hidden when only Default exists!)
        if (showGroupBar()) {
            String curGrp = groupField.getText().trim();
            int pillY = panelY + 80;
            int pillH = 14;
            int px = panelX + 12;
            int maxR = panelX + PANEL_W - 12;
            for (int i = 0; i < existingGroups.size(); i++) {
                String g = existingGroups.get(i);
                String lbl = GuiWaypoints.localizeGroup(g);
                int pw = Math.max(30, this.fontRendererObj.getStringWidth(lbl) + 8);
                if (px + pw > maxR) break;
                boolean sel = g.equalsIgnoreCase(curGrp);
                boolean hov = mouseX >= px && mouseX <= px + pw && mouseY >= pillY && mouseY <= pillY + pillH;
                int bg = sel ? 0xDD2563EB : (hov ? 0xBB1E293B : 0x88111622);
                int border = sel ? 0xFF93C5FD : (hov ? 0xAA64748B : 0x55334155);
                GLDraw.rect(px, pillY, px + pw, pillY + pillH, bg);
                GLDraw.rectOutline(px, pillY, px + pw, pillY + pillH, 1.0, border);
                GLDraw.drawCenteredText(this.fontRendererObj, lbl, px + pw * 0.5, pillY + 3.5, 0.78f, sel ? 0xFFFFFFFF : 0xFFCBD5E1);
                px += pw + 4;
            }
        }

        // 16-icon pixel-art selector grid (2 rows x 8 columns)
        Waypoint.IconType[] icons = Waypoint.IconType.values();
        int iconBaseX = panelX + 13;
        int iconBaseY = panelY + 91 + gOff;
        String hoveredIconName = null;
        for (int i = 0; i < icons.length; i++) {
            Waypoint.IconType it = icons[i];
            int col = i % ICON_COLS;
            int row = i / ICON_COLS;
            int ix = iconBaseX + col * (ICON_CELL_W + ICON_GAP_X);
            int iy = iconBaseY + row * (ICON_CELL_H + ICON_GAP_Y);
            boolean sel = (it == currentIcon);
            boolean hov = mouseX >= ix && mouseX <= ix + ICON_CELL_W && mouseY >= iy && mouseY <= iy + ICON_CELL_H;
            int bg = sel ? 0xDD1D4ED8 : (hov ? 0xBB1E293B : 0x88111622);
            int border = sel ? 0xFF93C5FD : (hov ? 0xAA64748B : 0x55334155);
            GLDraw.rect(ix, iy, ix + ICON_CELL_W, iy + ICON_CELL_H, bg);
            GLDraw.rectOutline(ix, iy, ix + ICON_CELL_W, iy + ICON_CELL_H, 1.0, border);
            WaypointIconRenderer.drawIcon(it, ix + ICON_CELL_W * 0.5, iy + ICON_CELL_H * 0.5, 14.0, currentColor, 1.0f);
            if (hov) {
                hoveredIconName = Lang.tr(it.langKey);
            }
        }

        // Interactive HSV Color Picker
        colorPicker.draw();

        // Live waypoint icon preview + Hex code input
        int prevX = panelX + 134;
        int prevY = panelY + 144 + gOff;
        GLDraw.rect(prevX, prevY, prevX + 24, prevY + 24, 0xFF0A0D12);
        GLDraw.rectOutline(prevX, prevY, prevX + 24, prevY + 24, 1.0, 0x88334155);
        WaypointIconRenderer.drawIcon(currentIcon, prevX + 12.0, prevY + 12.0, 16.0, currentColor, 1.0f);

        this.fontRendererObj.drawStringWithShadow("Color Hex", prevX + 30, prevY + 4, 0xFF94A3B8);
        this.fontRendererObj.drawStringWithShadow(colorPicker.getHexString(), prevX + 30, prevY + 14, 0xFFE2E8F0);
        hexField.drawTextBox();

        // Compact 2x6 quick preset swatches
        int swatchBaseX = panelX + 134;
        int swatchBaseY = panelY + 194 + gOff;
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

        if (hoveredIconName != null) {
            drawHoveringText(Collections.singletonList(hoveredIconName), mouseX, mouseY, this.fontRendererObj);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}

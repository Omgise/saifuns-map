package com.saifun.map.gui;

import com.saifun.map.render.GLDraw;
import com.saifun.map.render.WaypointIconRenderer;
import com.saifun.map.util.Lang;
import com.saifun.map.waypoints.Waypoint;
import com.saifun.map.waypoints.WaypointImporter;
import com.saifun.map.waypoints.WaypointManager;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.MathHelper;
import org.lwjgl.input.Mouse;

import java.util.List;

/**
 * Modern translucent Waypoints Manager with quick toggle, edit, teleport, chat share,
 * and one-click JourneyMap waypoint importer.
 */
public class GuiWaypoints extends GuiScreen {

    private static final int ROW_HEIGHT = 22;

    private final GuiScreen parent;
    private final WaypointManager manager;

    private boolean showAllDimensions = false;
    private int scrollOffset = 0;
    private int selectedIndex = -1;
    private String statusMessage = "";

    private List<Waypoint> displayed;

    public GuiWaypoints(GuiScreen parent, WaypointManager manager) {
        this.parent = parent;
        this.manager = manager;
    }

    @Override
    public void initGui() {
        refreshList();
        rebuildButtons();
    }

    private void refreshList() {
        int dim = (mc.theWorld != null) ? mc.theWorld.provider.dimensionId : 0;
        displayed = showAllDimensions ? manager.getAllWaypoints() : manager.getWaypointsForDimension(dim);
        if (selectedIndex >= displayed.size()) {
            selectedIndex = displayed.isEmpty() ? -1 : displayed.size() - 1;
        }
    }

    private void rebuildButtons() {
        this.buttonList.clear();
        int panelW = 380;
        int panelX = (this.width - panelW) / 2;
        int bottomY = this.height - 54;

        boolean hasSel = selectedIndex >= 0 && selectedIndex < displayed.size();

        // Row 1: Waypoint actions (sleek ModernButtons)
        this.buttonList.add(new ModernButton(1, panelX, bottomY, 64, 19, Lang.tr("saifunsmap.waypoints.add")).setAccent(true));
        ModernButton btnEdit = new ModernButton(2, panelX + 67, bottomY, 48, 19, Lang.tr("saifunsmap.waypoints.edit"));
        ModernButton btnToggle = new ModernButton(3, panelX + 118, bottomY, 60, 19, Lang.tr("saifunsmap.waypoints.toggle"));
        ModernButton btnTp = new ModernButton(4, panelX + 181, bottomY, 64, 19, Lang.tr("saifunsmap.waypoints.teleport"));
        ModernButton btnShare = new ModernButton(5, panelX + 248, bottomY, 68, 19, Lang.tr("saifunsmap.waypoints.share"));
        ModernButton btnDel = new ModernButton(6, panelX + 319, bottomY, 61, 19, Lang.tr("saifunsmap.waypoints.delete"));

        btnEdit.enabled = hasSel;
        btnToggle.enabled = hasSel;
        btnTp.enabled = hasSel;
        btnShare.enabled = hasSel;
        btnDel.enabled = hasSel;

        this.buttonList.add(btnEdit);
        this.buttonList.add(btnToggle);
        this.buttonList.add(btnTp);
        this.buttonList.add(btnShare);
        this.buttonList.add(btnDel);

        // Row 2: Importer, Dimension filter & Close
        int row2Y = bottomY + 23;
        this.buttonList.add(new ModernButton(10, panelX, row2Y, 138, 19, Lang.tr("saifunsmap.waypoints.import_jm")));
        this.buttonList.add(new ModernButton(12, panelX + 144, row2Y, 130, 19,
            Lang.tr(showAllDimensions ? "saifunsmap.waypoints.dims_all" : "saifunsmap.waypoints.dims_current")));
        this.buttonList.add(new ModernButton(99, panelX + 280, row2Y, 100, 19, Lang.tr("saifunsmap.common.close")));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        Waypoint sel = (selectedIndex >= 0 && selectedIndex < displayed.size()) ? displayed.get(selectedIndex) : null;

        switch (button.id) {
            case 1:
                if (mc.thePlayer != null && mc.theWorld != null) {
                    mc.displayGuiScreen(new GuiWaypointEdit(
                        this, manager, null,
                        MathHelper.floor_double(mc.thePlayer.posX),
                        MathHelper.floor_double(mc.thePlayer.posY),
                        MathHelper.floor_double(mc.thePlayer.posZ),
                        mc.theWorld.provider.dimensionId
                    ));
                }
                break;
            case 2:
                if (sel != null) {
                    mc.displayGuiScreen(new GuiWaypointEdit(this, manager, sel, sel.x, sel.y, sel.z, sel.dimensionId));
                }
                break;
            case 3:
                if (sel != null) {
                    sel.enabled = !sel.enabled;
                    manager.save();
                }
                break;
            case 4:
                if (sel != null) {
                    WaypointManager.teleportTo(sel);
                    mc.displayGuiScreen(null);
                }
                break;
            case 5:
                if (sel != null) {
                    WaypointManager.shareInChat(sel);
                    statusMessage = Lang.tr("saifunsmap.waypoints.status.shared", sel.name);
                }
                break;
            case 6:
                if (sel != null) {
                    manager.removeWaypoint(sel);
                    refreshList();
                    rebuildButtons();
                }
                break;
            case 10: {
                int count = WaypointImporter.importFromJourneyMap(manager);
                statusMessage = count > 0
                    ? Lang.tr("saifunsmap.waypoints.status.imported_jm", count)
                    : Lang.tr("saifunsmap.waypoints.status.none_jm");
                refreshList();
                rebuildButtons();
                break;
            }
            case 12:
                showAllDimensions = !showAllDimensions;
                scrollOffset = 0;
                refreshList();
                rebuildButtons();
                break;
            case 99:
                mc.displayGuiScreen(parent);
                break;
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            scrollOffset -= Integer.signum(wheel);
            int maxScroll = Math.max(0, displayed.size() - getVisibleRows());
            scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset));
        }
    }

    private int getVisibleRows() {
        int listH = Math.max(44, (this.height - 62) - 42);
        return Math.max(1, listH / ROW_HEIGHT);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        int panelW = 380;
        int panelX = (this.width - panelW) / 2;
        int listTop = 42;
        int listBottom = this.height - 62;

        if (mouseX >= panelX && mouseX <= panelX + panelW && mouseY >= listTop && mouseY <= listBottom) {
            int row = (mouseY - listTop) / ROW_HEIGHT + scrollOffset;
            if (row >= 0 && row < displayed.size()) {
                selectedIndex = row;
                rebuildButtons();
            }
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        int panelW = 380;
        int panelX = (this.width - panelW) / 2;
        int listTop = 42;
        int listBottom = this.height - 62;

        GLDraw.ensureBlend();
        GLDraw.rect(panelX - 8, 14, panelX + panelW + 8, this.height - 8, 0xE60C1017);
        GLDraw.rectOutline(panelX - 8, 14, panelX + panelW + 8, this.height - 8, 1.0, 0x88334155);
        GLDraw.rect(panelX - 8, 14, panelX + panelW + 8, 16, 0xFF3B82F6);

        String title = Lang.tr("saifunsmap.waypoints.title", displayed.size());
        drawCenteredString(this.fontRendererObj, title, this.width / 2, 22, 0xFFF8FAFC);
        if (!statusMessage.isEmpty()) {
            drawCenteredString(this.fontRendererObj, statusMessage, this.width / 2, 32, 0xFF4ADE80);
        }

        GLDraw.rect(panelX, listTop, panelX + panelW, listBottom, 0x88080B10);
        GLDraw.rectOutline(panelX, listTop, panelX + panelW, listBottom, 1.0, 0x55334155);

        int visible = getVisibleRows();
        int px = mc.thePlayer != null ? MathHelper.floor_double(mc.thePlayer.posX) : 0;
        int pz = mc.thePlayer != null ? MathHelper.floor_double(mc.thePlayer.posZ) : 0;

        for (int i = 0; i < visible; i++) {
            int idx = i + scrollOffset;
            if (idx >= displayed.size()) break;
            Waypoint wp = displayed.get(idx);
            int ry = listTop + i * ROW_HEIGHT;

            boolean sel = idx == selectedIndex;
            int rowBg = sel ? 0xAA2563EB : (i % 2 == 0 ? 0x44161D29 : 0x22161D29);
            GLDraw.rect(panelX + 1, ry + 1, panelX + panelW - 1, ry + ROW_HEIGHT - 1, rowBg);

            // Crisp pixel-art waypoint icon (or native GTNH overlay icon)
            WaypointIconRenderer.drawWaypointIcon(wp, panelX + 13.0, ry + 11.0, 14.0, wp.enabled ? 1.0f : 0.40f, wp.color);

            int nameCol = wp.enabled ? 0xFFF1F5F9 : 0xFF64748B;
            this.fontRendererObj.drawStringWithShadow(wp.name, panelX + 28, ry + 7, nameCol);

            double dist = Math.hypot(wp.x - px, wp.z - pz);
            String coords = String.format("%d, %d, %d  (Dim %d)  %dm", wp.x, wp.y, wp.z, wp.dimensionId, Math.round(dist));
            int cw = this.fontRendererObj.getStringWidth(coords);
            this.fontRendererObj.drawStringWithShadow(coords, panelX + panelW - cw - 8, ry + 7, 0xFF94A3B8);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == org.lwjgl.input.Keyboard.KEY_ESCAPE) {
            this.mc.displayGuiScreen(parent);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}

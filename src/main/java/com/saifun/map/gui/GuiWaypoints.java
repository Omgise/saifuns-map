package com.saifun.map.gui;

import com.saifun.map.config.MapConfig;
import com.saifun.map.render.GLDraw;
import com.saifun.map.render.WaypointIconRenderer;
import com.saifun.map.util.Lang;
import com.saifun.map.waypoints.Waypoint;
import com.saifun.map.waypoints.WaypointImporter;
import com.saifun.map.waypoints.WaypointManager;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.MathHelper;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Advanced Waypoints Manager featuring:
 * - Preset & custom waypoint groups with 1-click group visibility toggle (RMB pill or toggle button)
 * - Instant live search across waypoint names and groups
 * - Sorting by Distance, Name, or Date (newest first)
 * - Two-click "Delete All Deaths" bulk cleanup
 * - Overworld <-> Nether (x8 / div8) portal auto-link toggle
 * - JourneyMap importer
 */
public class GuiWaypoints extends GuiScreen {

    private static final int ROW_HEIGHT = 22;
    private static final int LIST_TOP = 74;
    private static final int LIST_BOTTOM_MARGIN = 60;

    private enum SortMode {
        DISTANCE("saifunsmap.waypoints.sort.distance"),
        NAME("saifunsmap.waypoints.sort.name"),
        NEWEST("saifunsmap.waypoints.sort.newest");

        final String langKey;

        SortMode(String langKey) {
            this.langKey = langKey;
        }
    }

    private static final class GroupPill {
        final String group; // null = ALL
        final String label;
        final int x, y, w, h;

        GroupPill(String group, String label, int x, int y, int w, int h) {
            this.group = group;
            this.label = label;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        boolean hit(int mx, int my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }
    }

    private final GuiScreen parent;
    private final WaypointManager manager;

    private GuiTextField searchField;
    private String searchQuery = "";
    private SortMode sortMode = SortMode.DISTANCE;
    private String selectedGroup = null; // null = ALL
    private boolean showAllDimensions = false;
    private boolean confirmDeleteDeaths = false;

    private int scrollOffset = 0;
    private int selectedIndex = -1;
    private long lastRowClickMs = 0L;
    private int lastClickedRow = -1;
    private String statusMessage = "";

    private List<Waypoint> displayed = new ArrayList<>();
    private final List<GroupPill> groupPills = new ArrayList<>();

    public GuiWaypoints(GuiScreen parent, WaypointManager manager) {
        this.parent = parent;
        this.manager = manager;
    }

    private int getPanelW() {
        return Math.min(436, this.width - 20);
    }

    private int getPanelX() {
        return (this.width - getPanelW()) / 2;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        int panelW = getPanelW();
        int panelX = getPanelX();

        String prevSearch = searchField != null ? searchField.getText() : searchQuery;
        int searchW = Math.max(90, panelW - 198);
        searchField = new GuiTextField(this.fontRendererObj, panelX + 1, 29, searchW, 16);
        searchField.setMaxStringLength(48);
        searchField.setText(prevSearch);
        searchQuery = prevSearch;

        refreshList();
        rebuildButtons();
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    private void refreshList() {
        int dim = (mc.theWorld != null) ? mc.theWorld.provider.dimensionId : 0;
        List<Waypoint> source = manager.getEditableWaypoints(showAllDimensions, dim);

        String q = searchQuery != null ? searchQuery.trim().toLowerCase(Locale.ROOT) : "";
        List<Waypoint> filtered = new ArrayList<>(source.size());
        for (int i = 0; i < source.size(); i++) {
            Waypoint wp = source.get(i);
            String g = wp.getEffectiveGroup();
            if (selectedGroup != null && !selectedGroup.equalsIgnoreCase(g)) {
                continue;
            }
            if (!q.isEmpty()) {
                String nLow = wp.name != null ? wp.name.toLowerCase(Locale.ROOT) : "";
                String gLow = g.toLowerCase(Locale.ROOT);
                if (!nLow.contains(q) && !gLow.contains(q)) {
                    continue;
                }
            }
            filtered.add(wp);
        }

        final double px = mc.thePlayer != null ? mc.thePlayer.posX : 0.0;
        final double pz = mc.thePlayer != null ? mc.thePlayer.posZ : 0.0;

        switch (sortMode) {
            case DISTANCE:
                filtered.sort(Comparator.comparingDouble(w -> {
                    double dx = (w.x + 0.5) - px;
                    double dz = (w.z + 0.5) - pz;
                    return dx * dx + dz * dz;
                }));
                break;
            case NAME:
                filtered.sort((a, b) -> {
                    String na = a.name != null ? a.name : "";
                    String nb = b.name != null ? b.name : "";
                    return na.compareToIgnoreCase(nb);
                });
                break;
            case NEWEST:
                filtered.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
                break;
        }

        displayed = filtered;
        int maxScroll = Math.max(0, displayed.size() - getVisibleRows());
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset));
        if (selectedIndex >= displayed.size()) {
            selectedIndex = displayed.isEmpty() ? -1 : displayed.size() - 1;
        }
        rebuildGroupPills();
    }

    private void rebuildGroupPills() {
        groupPills.clear();
        int panelW = getPanelW();
        int panelX = getPanelX();
        int maxRight = panelX + panelW - (selectedGroup != null ? 88 : 2);
        int x = panelX;
        int y = 51;
        int h = 17;

        String allLabel = Lang.tr("saifunsmap.waypoints.group.all");
        int allW = Math.max(30, this.fontRendererObj.getStringWidth(allLabel) + 12);
        groupPills.add(new GroupPill(null, allLabel, x, y, allW, h));
        x += allW + 4;

        List<String> groups = manager.getAllGroupNames();
        for (int i = 0; i < groups.size(); i++) {
            String g = groups.get(i);
            String label = localizeGroup(g);
            int w = Math.max(34, this.fontRendererObj.getStringWidth(label) + 12);
            if (x + w > maxRight) break;
            groupPills.add(new GroupPill(g, label, x, y, w, h));
            x += w + 4;
        }
    }

    public static String localizeGroup(String group) {
        if (group == null || group.trim().isEmpty()) {
            return Lang.tr("saifunsmap.group.default");
        }
        String low = group.trim().toLowerCase(Locale.ROOT);
        if ("default".equals(low)) return Lang.tr("saifunsmap.group.default");
        if ("base".equals(low)) return Lang.tr("saifunsmap.group.base");
        if ("ores".equals(low)) return Lang.tr("saifunsmap.group.ores");
        if ("loot".equals(low)) return Lang.tr("saifunsmap.group.loot");
        if ("deaths".equals(low)) return Lang.tr("saifunsmap.group.deaths");
        return group.trim();
    }

    @SuppressWarnings("unchecked")
    private void rebuildButtons() {
        this.buttonList.clear();
        int panelW = getPanelW();
        int panelX = getPanelX();
        int bottomY = this.height - 52;

        // Top-right filter buttons (Sort + Dimensions)
        int dimW = 94;
        int sortW = 94;
        this.buttonList.add(new ModernButton(20, panelX + panelW - dimW - sortW - 4, 28, sortW, 18,
            Lang.tr(sortMode.langKey)));
        this.buttonList.add(new ModernButton(12, panelX + panelW - dimW, 28, dimW, 18,
            Lang.tr(showAllDimensions ? "saifunsmap.waypoints.dims_all" : "saifunsmap.waypoints.dims_current")));

        // Group visibility toggle button when a specific group is selected
        if (selectedGroup != null) {
            boolean gOn = manager.isGroupEnabled(selectedGroup);
            this.buttonList.add(new ModernButton(21, panelX + panelW - 84, 51, 84, 17,
                Lang.tr("saifunsmap.waypoints.group_toggle", Lang.onOff(gOn))).setAccent(gOn));
        }

        boolean hasSel = selectedIndex >= 0 && selectedIndex < displayed.size();
        Waypoint selWp = hasSel ? displayed.get(selectedIndex) : null;

        // Row 1: Waypoint actions
        int gap = 4;
        int bAdd = 66, bEdit = 52, bTog = 64, bTp = 70, bShare = 78;
        int bDel = panelW - (bAdd + bEdit + bTog + bTp + bShare + gap * 5);
        int x = panelX;

        this.buttonList.add(new ModernButton(1, x, bottomY, bAdd, 19, Lang.tr("saifunsmap.waypoints.add")).setAccent(true));
        x += bAdd + gap;
        ModernButton btnEdit = new ModernButton(2, x, bottomY, bEdit, 19, Lang.tr("saifunsmap.waypoints.edit"));
        x += bEdit + gap;
        ModernButton btnToggle = new ModernButton(3, x, bottomY, bTog, 19, Lang.tr("saifunsmap.waypoints.toggle"));
        x += bTog + gap;
        ModernButton btnTp = new ModernButton(4, x, bottomY, bTp, 19, Lang.tr("saifunsmap.waypoints.teleport"));
        x += bTp + gap;
        ModernButton btnShare = new ModernButton(5, x, bottomY, bShare, 19, Lang.tr("saifunsmap.waypoints.share"));
        x += bShare + gap;
        ModernButton btnDel = new ModernButton(6, x, bottomY, Math.max(48, bDel), 19, Lang.tr("saifunsmap.waypoints.delete"));

        boolean editable = hasSel && selWp != null && !selWp.temporary;
        btnEdit.enabled = editable;
        btnToggle.enabled = editable;
        btnTp.enabled = hasSel;
        btnShare.enabled = hasSel;
        btnDel.enabled = hasSel;

        this.buttonList.add(btnEdit);
        this.buttonList.add(btnToggle);
        this.buttonList.add(btnTp);
        this.buttonList.add(btnShare);
        this.buttonList.add(btnDel);

        // Row 2: JourneyMap Importer | Delete All Deaths | Nether x8 Auto-Link | Close
        int row2Y = bottomY + 23;
        int deathCount = manager.countDeaths();
        int wImp = 114;
        int wDeaths = 122;
        int wNether = 114;
        int wClose = panelW - (wImp + wDeaths + wNether + gap * 3);
        int rx = panelX;

        this.buttonList.add(new ModernButton(10, rx, row2Y, wImp, 19, Lang.tr("saifunsmap.waypoints.import_jm")));
        rx += wImp + gap;

        String delDeathsLabel = confirmDeleteDeaths
            ? Lang.tr("saifunsmap.waypoints.delete_deaths_confirm", deathCount)
            : Lang.tr("saifunsmap.waypoints.delete_deaths", deathCount);
        ModernButton btnDelDeaths = new ModernButton(13, rx, row2Y, wDeaths, 19, delDeathsLabel);
        btnDelDeaths.enabled = deathCount > 0;
        if (confirmDeleteDeaths) {
            btnDelDeaths.setAccent(true);
        }
        this.buttonList.add(btnDelDeaths);
        rx += wDeaths + gap;

        boolean autoNether = MapConfig.get().autoLinkNetherPortals;
        this.buttonList.add(new ModernButton(14, rx, row2Y, wNether, 19,
            Lang.tr("saifunsmap.waypoints.nether_link", Lang.onOff(autoNether))).setAccent(autoNether));
        rx += wNether + gap;

        this.buttonList.add(new ModernButton(99, rx, row2Y, Math.max(58, wClose), 19, Lang.tr("saifunsmap.common.close")));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id != 13 && confirmDeleteDeaths) {
            confirmDeleteDeaths = false;
        }
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
                if (sel != null && !sel.temporary) {
                    mc.displayGuiScreen(new GuiWaypointEdit(this, manager, sel, sel.x, sel.y, sel.z, sel.dimensionId));
                }
                break;
            case 3:
                if (sel != null && !sel.temporary) {
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
            case 13:
                if (!confirmDeleteDeaths) {
                    confirmDeleteDeaths = true;
                    rebuildButtons();
                } else {
                    int removed = manager.removeAllDeaths();
                    confirmDeleteDeaths = false;
                    statusMessage = Lang.tr("saifunsmap.waypoints.status.deleted_deaths", removed);
                    refreshList();
                    rebuildButtons();
                }
                break;
            case 14: {
                MapConfig cfg = MapConfig.get();
                cfg.autoLinkNetherPortals = !cfg.autoLinkNetherPortals;
                MapConfig.save();
                manager.refreshRenderCache();
                rebuildButtons();
                break;
            }
            case 20: {
                SortMode[] vals = SortMode.values();
                sortMode = vals[(sortMode.ordinal() + 1) % vals.length];
                refreshList();
                rebuildButtons();
                break;
            }
            case 21:
                if (selectedGroup != null) {
                    boolean nowOn = manager.toggleGroupEnabled(selectedGroup);
                    statusMessage = Lang.tr("saifunsmap.waypoints.status.group_toggled", localizeGroup(selectedGroup), Lang.onOff(nowOn));
                    rebuildButtons();
                }
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
        int listH = Math.max(44, (this.height - LIST_BOTTOM_MARGIN) - LIST_TOP);
        return Math.max(1, listH / ROW_HEIGHT);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (searchField != null) {
            searchField.mouseClicked(mouseX, mouseY, mouseButton);
        }

        // Group pill clicks: LMB selects filter, RMB toggles entire group visibility!
        for (int i = 0; i < groupPills.size(); i++) {
            GroupPill p = groupPills.get(i);
            if (!p.hit(mouseX, mouseY)) continue;
            if (mouseButton == 1 && p.group != null) {
                boolean nowOn = manager.toggleGroupEnabled(p.group);
                statusMessage = Lang.tr("saifunsmap.waypoints.status.group_toggled", p.label, Lang.onOff(nowOn));
                rebuildButtons();
            } else if (mouseButton == 0) {
                selectedGroup = p.group;
                scrollOffset = 0;
                selectedIndex = -1;
                refreshList();
                rebuildButtons();
            }
            return;
        }

        int panelW = getPanelW();
        int panelX = getPanelX();
        int listBottom = this.height - LIST_BOTTOM_MARGIN;

        if (mouseX >= panelX && mouseX <= panelX + panelW && mouseY >= LIST_TOP && mouseY <= listBottom) {
            int row = (mouseY - LIST_TOP) / ROW_HEIGHT + scrollOffset;
            if (row >= 0 && row < displayed.size()) {
                long now = System.currentTimeMillis();
                boolean isDbl = (mouseButton == 0 && row == lastClickedRow && (now - lastRowClickMs) <= 360L);
                lastClickedRow = row;
                lastRowClickMs = now;
                selectedIndex = row;
                rebuildButtons();
                if (isDbl) {
                    Waypoint wp = displayed.get(row);
                    if (!wp.temporary) {
                        mc.displayGuiScreen(new GuiWaypointEdit(this, manager, wp, wp.x, wp.y, wp.z, wp.dimensionId));
                    }
                }
            }
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (searchField != null && searchField.isFocused() && !searchField.getText().isEmpty()) {
                searchField.setText("");
                searchQuery = "";
                refreshList();
                rebuildButtons();
                return;
            }
            this.mc.displayGuiScreen(parent);
            return;
        }
        if (searchField != null && searchField.isFocused()) {
            if (searchField.textboxKeyTyped(typedChar, keyCode)) {
                searchQuery = searchField.getText();
                scrollOffset = 0;
                refreshList();
                rebuildButtons();
            }
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void updateScreen() {
        if (searchField != null) {
            searchField.updateCursorCounter();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        int panelW = getPanelW();
        int panelX = getPanelX();
        int listBottom = this.height - LIST_BOTTOM_MARGIN;

        GLDraw.ensureBlend();
        GLDraw.rect(panelX - 8, 8, panelX + panelW + 8, this.height - 6, 0xEC0C1017);
        GLDraw.rectOutline(panelX - 8, 8, panelX + panelW + 8, this.height - 6, 1.0, 0x88334155);
        GLDraw.rect(panelX - 8, 8, panelX + panelW + 8, 10, 0xFF3B82F6);

        String title = Lang.tr("saifunsmap.waypoints.title", displayed.size());
        this.fontRendererObj.drawStringWithShadow(title, panelX + 2, 15, 0xFFF8FAFC);
        if (!statusMessage.isEmpty()) {
            int sw = this.fontRendererObj.getStringWidth(statusMessage);
            this.fontRendererObj.drawStringWithShadow(statusMessage, panelX + panelW - sw - 2, 15, 0xFF4ADE80);
        } else {
            String hint = Lang.tr("saifunsmap.waypoints.group_rmb_hint");
            int hw = this.fontRendererObj.getStringWidth(hint);
            this.fontRendererObj.drawStringWithShadow(hint, panelX + panelW - hw - 2, 15, 0xFF64748B);
        }

        // Live search input
        if (searchField != null) {
            searchField.drawTextBox();
            GLDraw.ensureBlend();
            if (searchField.getText().isEmpty() && !searchField.isFocused()) {
                this.fontRendererObj.drawStringWithShadow(
                    Lang.tr("saifunsmap.waypoints.search_hint"),
                    searchField.xPosition + 4, searchField.yPosition + 4, 0xFF64748B
                );
            }
        }

        // Group filter / visibility pills
        for (int i = 0; i < groupPills.size(); i++) {
            GroupPill p = groupPills.get(i);
            boolean activeFilter = (p.group == null && selectedGroup == null)
                || (p.group != null && p.group.equalsIgnoreCase(selectedGroup));
            boolean groupVisible = p.group == null || manager.isGroupEnabled(p.group);
            boolean hov = p.hit(mouseX, mouseY);

            int bg;
            int border;
            int textCol;
            if (!groupVisible) {
                bg = activeFilter ? 0x99334155 : (hov ? 0x881E293B : 0x550F172A);
                border = activeFilter ? 0xAA94A3B8 : 0x55475569;
                textCol = 0xFF64748B;
            } else if (activeFilter) {
                bg = 0xDD2563EB;
                border = 0xFF93C5FD;
                textCol = 0xFFFFFFFF;
            } else if (hov) {
                bg = 0xCC1E293B;
                border = 0xAA4F8FF7;
                textCol = 0xFFF1F5F9;
            } else {
                bg = 0xAA131924;
                border = 0x66334155;
                textCol = 0xFFCBD5E1;
            }

            GLDraw.rect(p.x, p.y, p.x + p.w, p.y + p.h, bg);
            GLDraw.rectOutline(p.x, p.y, p.x + p.w, p.y + p.h, 1.0, border);
            String text = groupVisible ? p.label : ("\u00a7m" + p.label);
            drawCenteredString(this.fontRendererObj, text, p.x + p.w / 2, p.y + 4, textCol);
        }

        // Waypoints scroll list
        GLDraw.rect(panelX, LIST_TOP, panelX + panelW, listBottom, 0x88080B10);
        GLDraw.rectOutline(panelX, LIST_TOP, panelX + panelW, listBottom, 1.0, 0x55334155);

        int visible = getVisibleRows();
        int px = mc.thePlayer != null ? MathHelper.floor_double(mc.thePlayer.posX) : 0;
        int pz = mc.thePlayer != null ? MathHelper.floor_double(mc.thePlayer.posZ) : 0;

        if (displayed.isEmpty()) {
            drawCenteredString(
                this.fontRendererObj,
                Lang.tr("saifunsmap.waypoints.empty"),
                panelX + panelW / 2,
                LIST_TOP + (listBottom - LIST_TOP) / 2 - 4,
                0xFF64748B
            );
        }

        for (int i = 0; i < visible; i++) {
            int idx = i + scrollOffset;
            if (idx >= displayed.size()) break;
            Waypoint wp = displayed.get(idx);
            int ry = LIST_TOP + i * ROW_HEIGHT;

            boolean sel = idx == selectedIndex;
            boolean vis = manager.isWaypointVisible(wp);
            int rowBg = sel ? 0xAA2563EB : (i % 2 == 0 ? 0x44161D29 : 0x22161D29);
            GLDraw.rect(panelX + 1, ry + 1, panelX + panelW - 1, ry + ROW_HEIGHT - 1, rowBg);

            // Crisp pixel-art waypoint icon
            WaypointIconRenderer.drawWaypointIcon(wp, panelX + 13.0, ry + 11.0, 12.0, vis ? 1.0f : 0.35f, wp.color);

            int nameCol = vis ? 0xFFF1F5F9 : 0xFF64748B;
            this.fontRendererObj.drawStringWithShadow(wp.name, panelX + 26, ry + 7, nameCol);

            // Compact group badge + Nether x8 indicator
            int badgeX = panelX + 26 + this.fontRendererObj.getStringWidth(wp.name) + 6;
            String grpLabel = localizeGroup(wp.getEffectiveGroup());
            if (!Waypoint.GROUP_DEFAULT.equalsIgnoreCase(wp.getEffectiveGroup())) {
                int gw = this.fontRendererObj.getStringWidth(grpLabel) + 6;
                if (badgeX + gw < panelX + panelW - 140) {
                    int gBg = manager.isGroupEnabled(wp.getEffectiveGroup()) ? 0x771E293B : 0x55334155;
                    GLDraw.rect(badgeX, ry + 5, badgeX + gw, ry + 16, gBg);
                    this.fontRendererObj.drawStringWithShadow(grpLabel, badgeX + 3, ry + 7, vis ? 0xFF93C5FD : 0xFF64748B);
                    badgeX += gw + 4;
                }
            }

            boolean linked = (wp.dimensionId == 0 || wp.dimensionId == -1)
                && (wp.linkNether || (MapConfig.get().autoLinkNetherPortals && wp.getEffectiveIcon() == Waypoint.IconType.PORTAL));
            if (linked && badgeX + 20 < panelX + panelW - 132) {
                GLDraw.rect(badgeX, ry + 5, badgeX + 18, ry + 16, 0x88581C87);
                this.fontRendererObj.drawStringWithShadow("\u00d78", badgeX + 3, ry + 7, 0xFFE9D5FF);
            }

            double dist = Math.hypot(wp.x - px, wp.z - pz);
            String distStr = dist >= 1000.0 ? String.format("%.1fkm", dist / 1000.0) : (Math.round(dist) + "m");
            String coords = String.format("%d, %d, %d (%s) %s", wp.x, wp.y, wp.z, Lang.tr("saifunsmap.common.dim", wp.dimensionId), distStr);
            int cw = this.fontRendererObj.getStringWidth(coords);
            this.fontRendererObj.drawStringWithShadow(coords, panelX + panelW - cw - 6, ry + 7, 0xFF94A3B8);
        }

        // Scrollbar indicator if list overflows
        if (displayed.size() > visible) {
            int trackH = listBottom - LIST_TOP - 4;
            int thumbH = Math.max(18, (trackH * visible) / displayed.size());
            int maxScroll = Math.max(1, displayed.size() - visible);
            int thumbY = LIST_TOP + 2 + (int) (((trackH - thumbH) * (long) scrollOffset) / maxScroll);
            GLDraw.rect(panelX + panelW - 3, thumbY, panelX + panelW - 1, thumbY + thumbH, 0xAA60A5FA);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}

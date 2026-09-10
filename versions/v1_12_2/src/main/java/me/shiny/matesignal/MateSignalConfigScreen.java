package me.shiny.matesignal;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextFormatting;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * In-game configuration screen for the 1.12.2 port.
 * <p>
 * 1.12.2 has no {@code AbstractWidget}/{@code CycleButton} framework, so the
 * toggles are plain {@link GuiButton}s whose labels are refreshed every frame.
 * Option names and layout follow the modern ports so the screen feels the same.
 */
public class MateSignalConfigScreen extends GuiScreen {
    private static final int BG_COLOR = 0xFF101010;
    private static final int SCROLL_TRACK = 0xAA303030;
    private static final int SCROLL_THUMB = 0xFFE0E0E0;
    private static final int TEXT_COLOR = 0xFFFFFF;

    private static final int OUTER_PAD = 20;
    private static final int SCROLLBAR_W = 10;
    private static final int SCROLLBAR_GAP = 6;
    private static final int ROW_H = 18;
    private static final int MAIN_ROW_H = 22;
    private static final int MAIN_ROWS = 14;

    private static final String[] LABELS = {
            "Day Time Message",
            "Night Time Message",
            "Low Health Message",
            "Low Hunger Message",
            "Death Message",
            "Rain Start Message",
            "Drowning 50% Air",
            "Sleep Message",
            "Crafting Message (30%)",
            "Eat Message (30%)",
            "Kill Confirm (30%)",
            "Biome Discovery (100%)",
    };

    private final GuiScreen parent;

    /** One toggle row: the option index and the button that flips it. */
    private static final class Toggle {
        final int option;
        final GuiButton button;
        boolean value;

        Toggle(int option, GuiButton button, boolean value) {
            this.option = option;
            this.button = button;
            this.value = value;
        }
    }

    private static final class MobEntry {
        final String id;
        final GuiButton button;
        boolean value;

        MobEntry(String id, GuiButton button, boolean value) {
            this.id = id;
            this.button = button;
            this.value = value;
        }
    }

    private enum FilterMode { ALL, ACTIVE, INACTIVE }

    private final List<Toggle> toggles = new ArrayList<>();
    private final List<MobEntry> mobs = new ArrayList<>();

    private GuiButton radiusDown;
    private GuiButton radiusUp;
    private GuiButton radiusLabel;
    private GuiButton confMobsBtn;
    private GuiButton saveBtn;
    private GuiButton cancelBtn;
    private GuiButton showAllBtn;
    private GuiButton showActiveBtn;
    private GuiButton showInactiveBtn;
    private GuiButton backBtn;

    private int radius;

    private boolean mobView = false;
    private FilterMode filterMode = FilterMode.ALL;

    private int listTop;
    private int listBottom;
    private int listLeft;
    private int listRight;
    private int contentLeft;
    private int contentRight;
    private int contentWidth;

    private double scroll;
    private boolean draggingScrollbar;
    private boolean leftWasDown;

    public MateSignalConfigScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        toggles.clear();
        mobs.clear();
        radius = Config.getRadius();

        int controlW = 64;
        int listRightX = this.width / 2 + Math.min(350, this.width / 2 - OUTER_PAD) - SCROLLBAR_W - SCROLLBAR_GAP;
        int controlX = listRightX - controlW;

        radiusDown = new GuiButton(1, controlX, 0, 20, 20, "-");
        radiusUp = new GuiButton(2, controlX + controlW - 20, 0, 20, 20, "+");
        radiusLabel = new GuiButton(3, controlX - 44, 0, 40, 20, Integer.toString(radius));
        radiusLabel.enabled = false;
        buttonList.add(radiusDown);
        buttonList.add(radiusLabel);
        buttonList.add(radiusUp);

        for (int i = 0; i < LABELS.length; i++) {
            GuiButton b = new GuiButton(10 + i, controlX, 0, controlW, 18, "");
            buttonList.add(b);
            toggles.add(new Toggle(i, b, Config.get(i)));
        }

        confMobsBtn = new GuiButton(40, controlX, 0, controlW, 18, "Conf.");
        buttonList.add(confMobsBtn);

        showAllBtn = new GuiButton(50, 0, 0, 100, 20, "Show All");
        showActiveBtn = new GuiButton(51, 0, 0, 100, 20, "Show Active");
        showInactiveBtn = new GuiButton(52, 0, 0, 100, 20, "Show Unactive");
        backBtn = new GuiButton(53, 0, 0, 70, 20, "Back");
        buttonList.add(showAllBtn);
        buttonList.add(showActiveBtn);
        buttonList.add(showInactiveBtn);
        buttonList.add(backBtn);

        saveBtn = new GuiButton(60, 0, 0, 100, 20, "Save");
        cancelBtn = new GuiButton(61, 0, 0, 100, 20, "Cancel");
        buttonList.add(saveBtn);
        buttonList.add(cancelBtn);

        Set<String> enabled = new HashSet<>();
        for (String s : Config.getMobs()) {
            // 1.12.2 targets Java 8, so no String#isBlank.
            if (s != null && !s.trim().isEmpty()) {
                enabled.add(s.toLowerCase(Locale.ROOT));
            }
        }
        boolean anyExplicit = !enabled.isEmpty();

        int id = 100;
        for (ResourceLocation rl : Config.hostileMobIds()) {
            String full = rl.toString();
            String name = rl.getResourcePath();
            boolean sel = anyExplicit
                    ? (enabled.contains(full.toLowerCase(Locale.ROOT)) || enabled.contains(name.toLowerCase(Locale.ROOT)))
                    : ("minecraft:creeper".equals(full) || "minecraft:zombie".equals(full));
            GuiButton b = new GuiButton(id++, 0, 0, 64, 18, "");
            buttonList.add(b);
            mobs.add(new MobEntry(full, b, sel));
        }

        layoutForCurrentView();
        updateVisibility();
        clampScroll();
    }

    private void layoutForCurrentView() {
        int cw = Math.min(700, this.width - 2 * OUTER_PAD);
        if (cw < 300) {
            cw = this.width - 2 * OUTER_PAD;
        }
        contentWidth = cw;
        contentLeft = (this.width - contentWidth) / 2;
        contentRight = contentLeft + contentWidth;

        if (!mobView) {
            listLeft = contentLeft;
            listRight = contentRight - SCROLLBAR_W - SCROLLBAR_GAP;
            listTop = 88;
            listBottom = this.height - 60;
        } else {
            layoutMobControls();
        }
    }

    private void layoutMobControls() {
        int gap = 10;
        int totalW = 100 + gap + 100 + gap + 100;
        int sx = contentLeft + (contentWidth - totalW) / 2;
        int fy = 80;
        showAllBtn.x = sx;
        showAllBtn.y = fy;
        showActiveBtn.x = sx + 110;
        showActiveBtn.y = fy;
        showInactiveBtn.x = sx + 220;
        showInactiveBtn.y = fy;
        backBtn.x = contentLeft;
        backBtn.y = this.height - 40;
        listLeft = contentLeft;
        listRight = contentRight - SCROLLBAR_W - SCROLLBAR_GAP;
        listTop = fy + 28;
        listBottom = this.height - 60;
        clampScroll();
    }

    private boolean passesFilter(MobEntry e) {
        if (filterMode == FilterMode.ALL) return true;
        if (filterMode == FilterMode.ACTIVE) return e.value;
        return !e.value;
    }

    private int rowHeight() { return mobView ? ROW_H : MAIN_ROW_H; }
    private int contentHeight() {
        if (!mobView) return MAIN_ROWS * MAIN_ROW_H;
        int c = 0;
        for (MobEntry e : mobs) if (passesFilter(e)) c++;
        return c * ROW_H;
    }
    private int viewHeight() { return Math.max(0, listBottom - listTop); }
    private int maxScroll() { return Math.max(0, contentHeight() - viewHeight()); }

    private int thumbH() {
        int vh = viewHeight();
        int ch = contentHeight();
        if (ch <= 0) return vh;
        return Math.max(16, vh * vh / ch);
    }

    private int thumbY() {
        int ms = maxScroll();
        if (ms == 0) return listTop;
        int vh = viewHeight();
        int th = thumbH();
        return listTop + (int) ((vh - th) * (scroll / (double) ms));
    }

    private void setScrollFromThumbY(int mouseY) {
        int vh = viewHeight();
        int th = thumbH();
        int ms = maxScroll();
        if (ms == 0) {
            scroll = 0;
            return;
        }
        double rel = (mouseY - listTop - th * 0.5) / (double) Math.max(1, vh - th);
        scroll = rel * ms;
        clampScroll();
    }

    private void clampScroll() {
        if (scroll < 0) scroll = 0;
        int ms = maxScroll();
        if (scroll > ms) scroll = ms;
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            scroll -= (wheel > 0 ? 1 : -1) * rowHeight();
            clampScroll();
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        radiusLabel.displayString = Integer.toString(radius);
        for (Toggle t : toggles) {
            t.button.displayString = t.value ? TextFormatting.GREEN + "ON" : TextFormatting.RED + "OFF";
        }
        for (MobEntry m : mobs) {
            m.button.displayString = m.value ? TextFormatting.GREEN + "ON" : TextFormatting.RED + "OFF";
        }
    }

    private void updateVisibility() {
        boolean main = !mobView;
        radiusDown.visible = main;
        radiusUp.visible = main;
        radiusLabel.visible = main;
        for (Toggle t : toggles) t.button.visible = main;
        confMobsBtn.visible = main;
        saveBtn.visible = main;
        cancelBtn.visible = main;
        showAllBtn.visible = !main;
        showActiveBtn.visible = !main;
        showInactiveBtn.visible = !main;
        backBtn.visible = !main;
        for (MobEntry m : mobs) m.button.visible = !main;
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == radiusDown) {
            radius = Math.max(3, radius - 1);
            return;
        }
        if (button == radiusUp) {
            radius = Math.min(64, radius + 1);
            return;
        }
        if (button == confMobsBtn) {
            mobView = true;
            scroll = 0;
            layoutForCurrentView();
            updateVisibility();
            return;
        }
        if (button == showAllBtn) {
            filterMode = FilterMode.ALL;
            scroll = 0;
            clampScroll();
            return;
        }
        if (button == showActiveBtn) {
            filterMode = FilterMode.ACTIVE;
            scroll = 0;
            clampScroll();
            return;
        }
        if (button == showInactiveBtn) {
            filterMode = FilterMode.INACTIVE;
            scroll = 0;
            clampScroll();
            return;
        }
        if (button == backBtn) {
            mobView = false;
            scroll = 0;
            layoutForCurrentView();
            updateVisibility();
            return;
        }
        if (button == saveBtn) {
            save();
            return;
        }
        if (button == cancelBtn) {
            mc.displayGuiScreen(parent);
            return;
        }
        for (Toggle t : toggles) {
            if (t.button == button) {
                t.value = !t.value;
                return;
            }
        }
        for (MobEntry m : mobs) {
            if (m.button == button) {
                m.value = !m.value;
                return;
            }
        }
    }

    private void save() {
        Config.setRadius(radius);
        for (Toggle t : toggles) {
            Config.set(t.option, t.value);
        }
        List<String> list = new ArrayList<>();
        for (MobEntry m : mobs) {
            if (m.value) list.add(m.id);
        }
        Config.setMobs(list);
        Config.save();
        mc.displayGuiScreen(parent);
    }

    @Override
    public void onGuiClosed() {
        // Nothing to flush; Save/Cancel decide explicitly.
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        boolean leftDown = Mouse.isButtonDown(0);
        int bx = contentRight - SCROLLBAR_W;
        int th = thumbH();
        int ty = thumbY();

        if (leftDown && !leftWasDown && mouseX >= bx && mouseX <= bx + SCROLLBAR_W
                && mouseY >= listTop && mouseY <= listBottom) {
            draggingScrollbar = true;
            if (mouseY < ty || mouseY > ty + th) {
                setScrollFromThumbY(mouseY);
            }
        }
        if (draggingScrollbar && leftDown) {
            setScrollFromThumbY(mouseY);
        }
        if (draggingScrollbar && !leftDown) {
            draggingScrollbar = false;
        }
        leftWasDown = leftDown;

        Gui.drawRect(0, 0, this.width, this.height, BG_COLOR);

        if (!mobView) {
            drawMainView(bx);
        } else {
            drawMobView(bx);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawMainView(int bx) {
        layoutForCurrentView();
        int offY = -(int) scroll;

        int controlW = 64;
        int controlX = listRight - controlW;
        int yBlock = 88 + offY;
        radiusLabel.x = controlX - 44;
        radiusLabel.y = yBlock + 1;
        radiusDown.x = controlX;
        radiusDown.y = yBlock;
        radiusUp.x = controlX + controlW - 20;
        radiusUp.y = yBlock;

        int y0 = 110 + offY;
        for (int i = 0; i < toggles.size(); i++) {
            Toggle t = toggles.get(i);
            t.button.x = controlX;
            t.button.y = y0 + i * 22;
            t.button.visible = withinRow(t.button.y);
        }
        confMobsBtn.x = controlX;
        confMobsBtn.y = y0 + toggles.size() * 22;
        confMobsBtn.visible = withinRow(confMobsBtn.y);
        radiusDown.visible = withinRow(yBlock);
        radiusUp.visible = withinRow(yBlock);
        radiusLabel.visible = withinRow(yBlock);

        drawCenteredString(fontRenderer, "MateSignal", this.width / 2, 20, TEXT_COLOR);

        if (visibleRow(yBlock + 4)) {
            drawString(fontRenderer, "Block Radius", contentLeft, yBlock + 4, TEXT_COLOR);
        }
        for (int i = 0; i < LABELS.length; i++) {
            int ly = y0 + 4 + i * 22;
            if (visibleRow(ly)) {
                drawString(fontRenderer, LABELS[i], contentLeft, ly, TEXT_COLOR);
            }
        }
        if (visibleRow(y0 + 4 + LABELS.length * 22)) {
            drawString(fontRenderer, "Configure Mob Messages", contentLeft, y0 + 4 + LABELS.length * 22, TEXT_COLOR);
        }

        saveBtn.x = contentLeft;
        saveBtn.y = this.height - 40;
        cancelBtn.x = contentRight - 100;
        cancelBtn.y = this.height - 40;

        drawScrollbar(bx);
    }

    private boolean visibleRow(int y) {
        return y >= listTop - 16 && y <= listBottom;
    }

    private boolean withinRow(int y) {
        return y + 18 >= listTop && y <= listBottom;
    }

    private void drawMobView(int bx) {
        layoutMobControls();
        drawCenteredString(fontRenderer, "Mob Messages", this.width / 2, 20, TEXT_COLOR);

        int startIndex = (int) Math.floor(scroll / ROW_H);
        int y = listTop - (int) (scroll % ROW_H);
        int skipped = startIndex;

        for (MobEntry m : mobs) {
            if (!passesFilter(m)) {
                m.button.visible = false;
                continue;
            }
            if (skipped > 0) {
                skipped--;
                m.button.visible = false;
                continue;
            }
            if (y > listBottom) {
                m.button.visible = false;
                continue;
            }
            m.button.visible = true;
            m.button.x = listRight - 70;
            m.button.y = y + 1;
            m.button.width = 64;
            drawString(fontRenderer, m.id, listLeft, y + 5, TEXT_COLOR);
            y += ROW_H;
        }

        drawScrollbar(bx);
    }

    private void drawScrollbar(int bx) {
        Gui.drawRect(bx, listTop, bx + SCROLLBAR_W, listBottom, SCROLL_TRACK);
        if (maxScroll() > 0) {
            int tth = thumbH();
            int tty = thumbY();
            Gui.drawRect(bx + 1, tty, bx + SCROLLBAR_W - 1, tty + tth,
                    draggingScrollbar ? 0xFFFFFFFF : SCROLL_THUMB);
        }
    }
}

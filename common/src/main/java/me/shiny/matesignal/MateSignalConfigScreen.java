package me.shiny.matesignal;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
//#if 1.20.1
import net.minecraftforge.registries.ForgeRegistries;
//#else
import net.minecraft.core.registries.BuiltInRegistries;
//#endif

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * In-game configuration screen for the Forge 1.20.1 port.
 * <p>
 * Layout and option semantics follow MateSignal 1.1.0 so the screen looks and
 * behaves the same on every ported platform.
 */
public class MateSignalConfigScreen extends Screen {
    private enum FilterMode { ALL, ACTIVE, INACTIVE }
    private enum View { MAIN, MOBS }

    private static final int BG_COLOR = 0xFF101010;
    private static final int SCROLL_TRACK = 0xAA303030;
    private static final int SCROLL_THUMB = 0xFFE0E0E0;
    private static final int SCROLL_THUMB_ACTIVE = 0xFFFFFFFF;
    private static final int TEXT_COLOR = 0xFFFFFFFF;

    private static final int OUTER_PAD = 20;
    private static final int SCROLLBAR_W = 10;
    private static final int SCROLLBAR_GAP = 6;
    private static final int ROW_H = 18;
    private static final int MAIN_ROW_H = 22;
    /** Number of label/control rows on the main page (radius + 12 toggles + mobs). */
    private static final int MAIN_ROWS = 14;

    private final Screen parent;

    private EditBox radiusBox;
    private Button minusBtn;
    private Button plusBtn;
    private Button saveBtn;
    private Button cancelBtn;
    private CycleButton<Boolean> dayBtn;
    private CycleButton<Boolean> nightBtn;
    private CycleButton<Boolean> lowHealthBtn;
    private CycleButton<Boolean> lowHungerBtn;
    private CycleButton<Boolean> deathBtn;
    private CycleButton<Boolean> rainBtn;
    private CycleButton<Boolean> drownBtn;
    private CycleButton<Boolean> sleepBtn;
    private CycleButton<Boolean> craftBtn;
    private CycleButton<Boolean> eatBtn;
    private CycleButton<Boolean> killBtn;
    private CycleButton<Boolean> biomeBtn;
    private Button configureMobsBtn;
    private Button showAllBtn;
    private Button showActiveBtn;
    private Button showUnactiveBtn;
    private Button backBtn;

    private final List<Entry> entries = new ArrayList<>();

    private int listTop;
    private int listBottom;
    private int listLeft;
    private int listRight;
    private double scroll;
    private boolean draggingScrollbar;
    private boolean leftWasDown;

    private int contentLeft;
    private int contentRight;
    private int contentWidth;

    private FilterMode filterMode = FilterMode.ALL;
    private View view = View.MAIN;

    public MateSignalConfigScreen(Screen parent) {
        super(Component.literal("MateSignal Config"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        for (Entry e : entries) {
            removeWidget((GuiEventListener) e.toggle);
        }
        entries.clear();

        radiusBox = new EditBox(this.font, 0, 0, 40, 20, Component.literal("radius"));
        radiusBox.setValue(Integer.toString(Config.RADIUS.get()));
        radiusBox.setEditable(false);

        minusBtn = Button.builder(Component.literal("-"), b -> step(-1)).bounds(0, 0, 20, 20).build();
        plusBtn = Button.builder(Component.literal("+"), b -> step(1)).bounds(0, 0, 20, 20).build();
        addRenderableWidget(minusBtn);
        addRenderableWidget(radiusBox);
        addRenderableWidget(plusBtn);

        dayBtn = onOff(Config.DAY_MESSAGE.get());
        nightBtn = onOff(Config.NIGHT_MESSAGE.get());
        lowHealthBtn = onOff(Config.LOW_HEALTH_MESSAGE.get());
        lowHungerBtn = onOff(Config.LOW_HUNGER_MESSAGE.get());
        deathBtn = onOff(Config.DEATH_MESSAGE.get());
        rainBtn = onOff(Config.RAIN_START_MESSAGE.get());
        drownBtn = onOff(Config.DROWNING_HALF_MESSAGE.get());
        sleepBtn = onOff(Config.SLEEP_MESSAGE.get());
        craftBtn = onOff(Config.CRAFTING_MESSAGE.get());
        eatBtn = onOff(Config.EAT_MESSAGE.get());
        killBtn = onOff(Config.KILL_MESSAGE.get());
        biomeBtn = onOff(Config.BIOME_MESSAGE.get());

        for (CycleButton<Boolean> b : List.of(dayBtn, nightBtn, lowHealthBtn, lowHungerBtn, deathBtn,
                rainBtn, drownBtn, sleepBtn, craftBtn, eatBtn, killBtn, biomeBtn)) {
            addRenderableWidget(b);
        }

        configureMobsBtn = Button.builder(Component.literal("Conf."), b -> {
            view = View.MOBS;
            clampScroll();
            layoutForCurrentView();
            updateVisibility();
        }).bounds(0, 0, 64, 18).build();
        addRenderableWidget(configureMobsBtn);

        showAllBtn = Button.builder(Component.literal("Show All"), b -> {
            filterMode = FilterMode.ALL;
            scroll = 0;
            clampScroll();
        }).bounds(0, 0, 100, 20).build();
        showActiveBtn = Button.builder(Component.literal("Show Active"), b -> {
            filterMode = FilterMode.ACTIVE;
            scroll = 0;
            clampScroll();
        }).bounds(0, 0, 120, 20).build();
        showUnactiveBtn = Button.builder(Component.literal("Show Unactive"), b -> {
            filterMode = FilterMode.INACTIVE;
            scroll = 0;
            clampScroll();
        }).bounds(0, 0, 130, 20).build();
        backBtn = Button.builder(Component.literal("Back"), b -> {
            view = View.MAIN;
            clampScroll();
            layoutForCurrentView();
            updateVisibility();
        }).bounds(0, 0, 70, 20).build();
        addRenderableWidget(showAllBtn);
        addRenderableWidget(showActiveBtn);
        addRenderableWidget(showUnactiveBtn);
        addRenderableWidget(backBtn);

        // Enumerate every hostile mob type registered on this platform.
//#if 1.20.1
        List<EntityType<?>> all = new ArrayList<>(ForgeRegistries.ENTITY_TYPES.getValues());
//#else
List<EntityType<?>> all = new ArrayList<>();
        BuiltInRegistries.ENTITY_TYPE.forEach(all::add);
//#endif
        all.removeIf(t -> t.getCategory() != MobCategory.MONSTER);
        all.sort(Comparator.comparing(t -> {
            //#if 1.20.1
            ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(t);
//#else
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(t);
//#endif
            return id == null ? "" : id.toString();
        }));

        Set<String> enabled = new HashSet<>();
        List<? extends String> cfg = Config.MOBS.get();
        if (cfg != null) {
            for (String s : cfg) {
                if (s != null && !s.isBlank()) {
                    enabled.add(s.toLowerCase(Locale.ROOT));
                }
            }
        }

        for (EntityType<?> t : all) {
            //#if 1.20.1
            ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(t);
//#else
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(t);
//#endif
            if (id == null) continue;
            String full = id.toString();
            String name = id.getPath();
            boolean sel = enabled.isEmpty()
                    ? ("minecraft:creeper".equals(full) || "minecraft:zombie".equals(full))
                    : enabled.contains(full) || enabled.contains(name);
            CycleButton<Boolean> toggle = onOff(sel);
            addRenderableWidget(toggle);
            entries.add(new Entry(full, name, toggle));
        }

        saveBtn = Button.builder(Component.literal("Save"), b -> save()).bounds(0, 0, 100, 20).build();
        cancelBtn = Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(0, 0, 100, 20).build();
        addRenderableWidget(saveBtn);
        addRenderableWidget(cancelBtn);

        layoutForCurrentView();
        updateVisibility();
        clampScroll();
    }

    private static CycleButton<Boolean> onOff(boolean initial) {
        return CycleButton.onOffBuilder(initial)
                .displayOnlyValue()
                .create(0, 0, 64, 18, Component.literal(""), (b, v) -> {});
    }

    @Override
    public void resize(Minecraft mc, int w, int h) {
        super.resize(mc, w, h);
        layoutForCurrentView();
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

        if (view == View.MAIN) {
            listLeft = contentLeft;
            listRight = contentRight - SCROLLBAR_W - SCROLLBAR_GAP;
            listTop = 88;
            listBottom = this.height - 60;
        } else {
            layoutMobControls();
        }
    }

    private boolean passesFilter(Entry e) {
        boolean on = e.toggle.getValue();
        if (filterMode == FilterMode.ALL) return true;
        if (filterMode == FilterMode.ACTIVE) return on;
        return !on;
    }

    private int rowHeight() { return view == View.MOBS ? ROW_H : MAIN_ROW_H; }
    private int contentHeight() {
        return view == View.MOBS ? filteredCount() * ROW_H : MAIN_ROWS * MAIN_ROW_H;
    }
    private int filteredCount() {
        int c = 0;
        for (Entry e : entries) if (passesFilter(e)) c++;
        return c;
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

    private void step(int d) {
        int v;
        try {
            v = Integer.parseInt(radiusBox.getValue().trim());
        } catch (Exception e) {
            v = Config.RADIUS.get();
        }
        v = Math.max(3, Math.min(64, v + d));
        radiusBox.setValue(Integer.toString(v));
    }

    private void save() {
        int r;
        try {
            r = Integer.parseInt(radiusBox.getValue().trim());
        } catch (Exception e) {
            r = 10;
        }
        r = Math.max(3, Math.min(64, r));
        Config.RADIUS.set(r);

        List<String> list = new ArrayList<>();
        for (Entry e : entries) {
            if (e.toggle.getValue()) list.add(e.id);
        }
        Config.MOBS.set(list);

        Config.DAY_MESSAGE.set(dayBtn.getValue());
        Config.NIGHT_MESSAGE.set(nightBtn.getValue());
        Config.LOW_HEALTH_MESSAGE.set(lowHealthBtn.getValue());
        Config.LOW_HUNGER_MESSAGE.set(lowHungerBtn.getValue());
        Config.DEATH_MESSAGE.set(deathBtn.getValue());
        Config.RAIN_START_MESSAGE.set(rainBtn.getValue());
        Config.DROWNING_HALF_MESSAGE.set(drownBtn.getValue());
        Config.SLEEP_MESSAGE.set(sleepBtn.getValue());
        Config.CRAFTING_MESSAGE.set(craftBtn.getValue());
        Config.EAT_MESSAGE.set(eatBtn.getValue());
        Config.KILL_MESSAGE.set(killBtn.getValue());
        Config.BIOME_MESSAGE.set(biomeBtn.getValue());

        Config.SPEC.save();
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    /** 1.20.1 still uses the 3-argument scroll callback (no horizontal delta). */
    @Override
//#if 1.20.1
    public boolean mouseScrolled(double mx, double my, double dy) {
//#else
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
//#endif
        scroll -= dy * rowHeight();
        clampScroll();
        return true;
    }

    private void updateVisibility() {
        boolean main = view == View.MAIN;
        minusBtn.visible = main;
        plusBtn.visible = main;
        radiusBox.visible = main;
        dayBtn.visible = main;
        nightBtn.visible = main;
        lowHealthBtn.visible = main;
        lowHungerBtn.visible = main;
        deathBtn.visible = main;
        rainBtn.visible = main;
        drownBtn.visible = main;
        sleepBtn.visible = main;
        craftBtn.visible = main;
        eatBtn.visible = main;
        killBtn.visible = main;
        biomeBtn.visible = main;
        configureMobsBtn.visible = main;
        saveBtn.visible = main;
        cancelBtn.visible = main;
        showAllBtn.visible = !main;
        showActiveBtn.visible = !main;
        showUnactiveBtn.visible = !main;
        backBtn.visible = !main;
        for (Entry e : entries) {
            e.toggle.visible = !main;
        }
    }

    /** Positions the right-hand control column, offset by the current scroll. */
    private void layoutMainControls(int offY) {
        int controlW = 64;
        int controlX = listRight - controlW;
        int yBlock = 88 + offY;

        radiusBox.setX(controlX - 44);
        radiusBox.setY(yBlock + 1);
        minusBtn.setPosition(controlX, yBlock);
        plusBtn.setPosition(controlX + controlW - 20, yBlock);

        int y0 = 110 + offY;
        dayBtn.setPosition(controlX, y0);
        nightBtn.setPosition(controlX, y0 + 22);
        lowHealthBtn.setPosition(controlX, y0 + 44);
        lowHungerBtn.setPosition(controlX, y0 + 66);
        deathBtn.setPosition(controlX, y0 + 88);
        rainBtn.setPosition(controlX, y0 + 110);
        drownBtn.setPosition(controlX, y0 + 132);
        sleepBtn.setPosition(controlX, y0 + 154);
        craftBtn.setPosition(controlX, y0 + 176);
        eatBtn.setPosition(controlX, y0 + 198);
        killBtn.setPosition(controlX, y0 + 220);
        biomeBtn.setPosition(controlX, y0 + 242);
        configureMobsBtn.setPosition(controlX, y0 + 264);
    }

    private void applyViewportVisibilityForMain() {
        setVisibleWithin(minusBtn);
        setVisibleWithin(plusBtn);
        setVisibleWithin(radiusBox);
        setVisibleWithin(dayBtn);
        setVisibleWithin(nightBtn);
        setVisibleWithin(lowHealthBtn);
        setVisibleWithin(lowHungerBtn);
        setVisibleWithin(deathBtn);
        setVisibleWithin(rainBtn);
        setVisibleWithin(drownBtn);
        setVisibleWithin(sleepBtn);
        setVisibleWithin(craftBtn);
        setVisibleWithin(eatBtn);
        setVisibleWithin(killBtn);
        setVisibleWithin(biomeBtn);
        setVisibleWithin(configureMobsBtn);
    }

    private void setVisibleWithin(Button b) {
        b.visible = b.getY() + b.getHeight() >= listTop && b.getY() <= listBottom;
    }

    private void setVisibleWithin(EditBox e) {
        e.visible = e.getY() + e.getHeight() >= listTop && e.getY() <= listBottom;
    }

    private void setVisibleWithin(CycleButton<?> c) {
        c.visible = c.getY() + c.getHeight() >= listTop && c.getY() <= listBottom;
    }

    private void layoutMobControls() {
        int gap = 10;
        int wAll = 100, wAct = 120, wInact = 130, h = 20;
        int totalW = wAll + gap + wAct + gap + wInact;
        int sx = contentLeft + (contentWidth - totalW) / 2;
        int fy = 80;
        showAllBtn.setPosition(sx, fy);
        showActiveBtn.setPosition(sx + wAll + gap, fy);
        showUnactiveBtn.setPosition(sx + wAll + gap + wAct + gap, fy);
        backBtn.setPosition(contentLeft, this.height - 40);
        listLeft = contentLeft;
        listRight = contentRight - SCROLLBAR_W - SCROLLBAR_GAP;
        listTop = fy + 28;
        listBottom = this.height - 60;
        clampScroll();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        boolean leftDown = Minecraft.getInstance().mouseHandler.isLeftPressed();
        int bx = contentRight - SCROLLBAR_W;
        int th = thumbH();
        int ty = thumbY();

        if (leftDown && !leftWasDown && mx >= bx && mx <= bx + SCROLLBAR_W && my >= listTop && my <= listBottom) {
            draggingScrollbar = true;
            if (my < ty || my > ty + th) {
                setScrollFromThumbY(my);
            }
        }
        if (draggingScrollbar && leftDown) {
            setScrollFromThumbY(my);
        }
        if (draggingScrollbar && !leftDown) {
            draggingScrollbar = false;
        }
        leftWasDown = leftDown;

        g.fill(0, 0, this.width, this.height, BG_COLOR);

        if (view == View.MAIN) {
            renderMainView(g, mx, my, pt, bx);
        } else {
            renderMobView(g, mx, my, pt, bx);
        }
    }

    private void renderMainView(GuiGraphics g, int mx, int my, float pt, int bx) {
        layoutForCurrentView();
        int offY = -(int) scroll;
        layoutMainControls(offY);

        g.drawCenteredString(this.font, "MateSignal", this.width / 2, 20, TEXT_COLOR);

        int labelX = contentLeft;
        int yBlockLbl = 92 + offY;
        if (visibleRow(yBlockLbl)) {
            g.drawString(this.font, "Block Radius", labelX, yBlockLbl, TEXT_COLOR);
        }

        String[] labels = {
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
                "Configure Mob Messages",
        };
        int y0 = 110 + offY;
        for (int i = 0; i < labels.length; i++) {
            int ly = y0 + 4 + i * 22;
            if (visibleRow(ly)) {
                g.drawString(this.font, labels[i], labelX, ly, TEXT_COLOR);
            }
        }

        applyViewportVisibilityForMain();
        saveBtn.setPosition(labelX, this.height - 40);
        cancelBtn.setPosition(contentRight - 100, this.height - 40);

        super.render(g, mx, my, pt);
        drawScrollbar(g, bx);
    }

    private boolean visibleRow(int y) {
        return y >= listTop - 16 && y <= listBottom;
    }

    private void renderMobView(GuiGraphics g, int mx, int my, float pt, int bx) {
        layoutMobControls();
        g.drawCenteredString(this.font, "Mob Messages", this.width / 2, 20, TEXT_COLOR);

        int startIndex = (int) Math.floor(scroll / ROW_H);
        int y = listTop - (int) (scroll % ROW_H);
        int skipped = startIndex;

        for (Entry e : entries) {
            if (!passesFilter(e)) {
                e.toggle.visible = false;
                continue;
            }
            if (skipped > 0) {
                skipped--;
                e.toggle.visible = false;
                continue;
            }
            if (y > listBottom) {
                e.toggle.visible = false;
                continue;
            }
            e.toggle.visible = true;
            e.toggle.setX(listRight - 70);
            e.toggle.setY(y + 1);
            e.toggle.setWidth(64);
            g.drawString(this.font, e.name + "  (" + e.id + ")", listLeft, y + 5, TEXT_COLOR);
            y += ROW_H;
        }

        super.render(g, mx, my, pt);
        drawScrollbar(g, bx);
    }

    private void drawScrollbar(GuiGraphics g, int bx) {
        g.fill(bx, listTop, bx + SCROLLBAR_W, listBottom, SCROLL_TRACK);
        if (maxScroll() > 0) {
            int tth = thumbH();
            int tty = thumbY();
            g.fill(bx + 1, tty, bx + SCROLLBAR_W - 1, tty + tth,
                    draggingScrollbar ? SCROLL_THUMB_ACTIVE : SCROLL_THUMB);
        }
    }

    private static final class Entry {
        final String id;
        final String name;
        final CycleButton<Boolean> toggle;

        Entry(String id, String name, CycleButton<Boolean> toggle) {
            this.id = id;
            this.name = name;
            this.toggle = toggle;
        }
    }
}

package name.atlasclient.ui;

import name.atlasclient.script.Script;
import name.atlasclient.script.ScriptManager;
import name.atlasclient.script.mining.MithrilMiningScript;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.render.RenderLayer;

public class AtlasMainScreen extends Screen {
    private final Screen parent;
    private List<Script> scripts = List.of();

    private enum Page { SCRIPTS, SETTINGS }
    private static Page LAST_PAGE = Page.SCRIPTS;
    private static Tab LAST_TAB = Tab.MISC;
    private static SettingsTab LAST_SETTINGS_TAB = SettingsTab.VISUAL;
    private Page currentPage = LAST_PAGE;

    // Tabs (left nav)
    private enum Tab {
        FARMING("Farming"),
        COMBAT("Combat"),
        FORAGING("Foraging"),
        MINING("Mining"),
        FISHING("Fishing"),
        MISC("Misc");

        final String label;
        Tab(String label) { this.label = label; }
    }

    enum SettingsTab {
        VISUAL("Visual", false),
        HUD("Hud", false),
        SCRIPTING("Scripting", false),
        ANTI_STAFF("Anti-Staff", false),
        FAILSAFE("Failsafe", false),
        REWARPER("Rewarper", false),
        AUTO_DIRECTION("Rotation", false),

        DIVIDER("", true),

        FARMING("Farming", false),
        COMBAT("Combat", false),
        FORAGING("Foraging", false),
        MINING("Mining", false),
        FISHING("Fishing", false);

        final String label;
        final boolean divider;

        SettingsTab(String label, boolean divider) {
            this.label = label;
            this.divider = divider;
        }
    }

    private Tab selectedTab = LAST_TAB;

    private SettingsTab selectedSettingsTab = LAST_SETTINGS_TAB;

    // Panel layout
    private int panelX, panelY, panelW, panelH;
    private int leftAreaW;
    private static final int PADDING = 10;
    private static final int CORNER_RADIUS = 10;

    private static final Identifier ATLAS_LOGO = Identifier.of("atlas-client", "icon.png");

    // Top-right controls above the panel
    private static final int ICON_BTN_SIZE = 18;
    private static final int ICON_BTN_GAP = 6;

    // Mithril Miner config control sizing
    private static final int CONTROL_WIDTH = 104;
    private static final int CONTROL_HEIGHT = 28; // decrease to make buttons/sliders shorter

    private ButtonWidget searchButton;
    private ButtonWidget pageButton;

    // Search
    private boolean searchVisible = false;
    private TextFieldWidget searchField;

    // Mining settings widgets (Settings → Mining)
    private ButtonWidget mithrilToggleBtn;
        private AtlasToggleButton mineTitaniumBtn;
        private AtlasToggleButton debugBtn;
        private AtlasIntSlider strictnessSlider;
        private AtlasIntSlider rotationSpeedSlider;
    private AtlasToggleButton rotationTypeBtn;
    private AtlasFloatSlider bezierSpeedSlider;
    private AtlasFloatSlider randomnessSlider;

    // Script cards
    private static final int CARD_H = 34;
    private static final int CARD_GAP = 8;
    private final List<CardHitbox> cardHitboxes = new ArrayList<>();

    // Left tab hitboxes
    private final List<TabHitbox> tabHitboxes = new ArrayList<>();
    private final List<SettingsHitbox> settingsHitboxes = new ArrayList<>();

    // Colors
    private static final int TAB_NORMAL = 0xFFB0B0B0;     // gray text
    private static final int ACCENT_COLOR = 0xFF3D7294;
    private static final int TAB_SELECTED = ACCENT_COLOR;
    private static final int TAB_HOVER = 0xFFE0E0E0;


    public AtlasMainScreen(Screen parent) {
        super(Text.literal("Atlas Client"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.scripts = ScriptManager.all();

        // Preserve search query across rebuilds
        String previousQuery = (this.searchField != null) ? this.searchField.getText() : "";

        // Center panel sizing
        this.panelW = Math.min(this.width - 40, 560);
        this.panelH = Math.min(this.height - 70, 360);
        this.panelX = (this.width - this.panelW) / 2;
        this.panelY = (this.height - this.panelH) / 2;
        this.leftAreaW = Math.max(140, (int) Math.round(this.panelW * 0.15)); // slightly wider for tabs

        // Two square buttons above top-right of panel
        int iconY = this.panelY - ICON_BTN_SIZE - 6;
        if (iconY < 6) iconY = 6;
        int pageBtnW = ICON_BTN_SIZE * 2;
        int pageBtnX = this.panelX + this.panelW - pageBtnW;
        int searchBtnX = pageBtnX - ICON_BTN_GAP - ICON_BTN_SIZE;

        this.searchButton = addDrawableChild(ButtonWidget.builder(Text.literal(""), btn -> {
            this.searchVisible = !this.searchVisible;
            this.searchField.setVisible(this.searchVisible);
            if (this.searchVisible) {
                this.setFocused(this.searchField);
            }
            rebuild();
        }).dimensions(searchBtnX, iconY, ICON_BTN_SIZE, ICON_BTN_SIZE).build());

        this.pageButton = addDrawableChild(ButtonWidget.builder(Text.literal(""), btn -> {
            this.currentPage = (this.currentPage == Page.SCRIPTS) ? Page.SETTINGS : Page.SCRIPTS;
            if (this.searchField != null) this.searchField.setText("");
            rebuild();
        }).dimensions(pageBtnX, iconY, ICON_BTN_SIZE * 2, ICON_BTN_SIZE).build());

        // Search field appears to the left of the icon buttons
        int searchW = 170;
        int searchX = searchBtnX - ICON_BTN_GAP - searchW;
        this.searchField = new TextFieldWidget(this.textRenderer, searchX, iconY, searchW, ICON_BTN_SIZE, Text.literal("Search"));
        this.searchField.setText(previousQuery);
        this.searchField.setVisible(this.searchVisible);
        this.searchField.setChangedListener(s -> rebuild());
        addDrawableChild(this.searchField);

        // Back/Close button below the panel
        addDrawableChild(ButtonWidget.builder(
                Text.literal(parent == null ? "Close" : "Back"),
                btn -> MinecraftClient.getInstance().setScreen(parent)
        ).dimensions(this.width / 2 - 45, this.panelY + this.panelH + 12, 90, 20).build());

        // Add settings widgets for the active settings tab
        if (this.currentPage == Page.SETTINGS) {
            if (this.selectedSettingsTab == SettingsTab.MINING) {
                addMiningSettingsWidgets();
            } else if (this.selectedSettingsTab == SettingsTab.AUTO_DIRECTION) {
                addRotationSettingsWidgets();
            }
        }
    }
    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // Intentionally empty: we render the background/blur explicitly at the start of render().
    }


    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Render vanilla background/blur behind the UI
        super.renderBackground(context, mouseX, mouseY, delta);

        // Dim overlay
        context.fill(0, 0, this.width, this.height, 0x88000000);

        // Panel background
        drawRoundedRect(context, this.panelX, this.panelY, this.panelW, this.panelH, CORNER_RADIUS, 0xCC101010);

        // Left nav area
        drawRoundedRect(context, this.panelX, this.panelY, this.leftAreaW, this.panelH, CORNER_RADIUS, 0xCC0B0B0B);

        // Divider
        context.fill(this.panelX + this.leftAreaW, this.panelY + 6,
                this.panelX + this.leftAreaW + 1, this.panelY + this.panelH - 6, 0x80333333);

        // Left nav
        if (this.currentPage == Page.SCRIPTS) {
            renderLeftTabs(context, mouseX, mouseY);
        } else {
            renderSettingsSidebar(context, mouseX, mouseY);
        }

        // Right area
        int rightX = this.panelX + this.leftAreaW + PADDING;
        int rightY = this.panelY + PADDING;
        int rightW = this.panelW - this.leftAreaW - (PADDING * 2);

        // Clip right-side content so large cards never render outside the Atlas panel
        context.enableScissor(rightX, this.panelY, rightX + rightW, this.panelY + this.panelH);

        if (this.currentPage == Page.SCRIPTS) {
            renderScriptsPage(context, mouseX, mouseY, rightX, rightY, rightW);
        } else {
            renderSettingsPage(context, rightX, rightY, rightW);
        }

        context.disableScissor();

        // Overlay icons on the two square buttons
        renderTopIcons(context);

        super.render(context, mouseX, mouseY, delta);
    }

    private void renderLeftTabs(DrawContext context, int mouseX, int mouseY) {
        this.tabHitboxes.clear();
        this.settingsHitboxes.clear();

        int paneX = this.panelX;
        int paneY = this.panelY;
        int paneW = this.leftAreaW;
        int paneH = this.panelH;

        // Header (logo + larger Atlas)
        int headerY = paneY + PADDING + 6;
        float titleScale = 1.6f;
        String title = "Atlas";
        int titleW = this.textRenderer.getWidth(title);
        int scaledTitleW = (int) (titleW * titleScale);
        int logoSize = 18;
        int titleGap = 6;
        int totalW = logoSize + titleGap + scaledTitleW;
        int headerX = paneX + (paneW - totalW) / 2;

        context.drawTexture(RenderLayer::getGuiTextured, ATLAS_LOGO, headerX, headerY + 1, 0f, 0f, logoSize, logoSize, logoSize, logoSize);

        // Draw scaled title anchored at the intended pixel position (prevents left drift)
        context.getMatrices().push();
        context.getMatrices().translate(headerX + logoSize + titleGap, headerY + 1 + (int)((logoSize - (this.textRenderer.fontHeight * titleScale)) / 2f), 0);
        context.getMatrices().scale(titleScale, titleScale, 1.0f);
        context.drawTextWithShadow(this.textRenderer, Text.literal(title), 0, 0, ACCENT_COLOR);
        context.getMatrices().pop();

        // Top-align list (centered only on X)
        int rowH = 16;
        int listTop = headerY + 36;
        int startY = listTop;

int hitboxX = paneX + PADDING;
        int hitboxW = paneW - (PADDING * 2);
        int textY = startY;
        int textH = 14;

        for (Tab tab : Tab.values()) {
            int labelW = this.textRenderer.getWidth(tab.label);
            int labelX = paneX + (paneW - labelW) / 2;

            boolean hovered =
                    mouseX >= hitboxX && mouseX < hitboxX + hitboxW &&
                    mouseY >= textY - 1 && mouseY < textY - 1 + textH + 2;

            int color;
            if (tab == selectedTab) color = TAB_SELECTED;
            else if (hovered) color = TAB_HOVER;
            else color = TAB_NORMAL;

            if (tab == selectedTab) {
                // Selection indicator stripe
                context.fill(hitboxX - 3, textY - 1, hitboxX - 1, textY - 1 + textH + 2, ACCENT_COLOR);
            }

            context.drawTextWithShadow(this.textRenderer, Text.literal(tab.label), labelX, textY, color);

            this.tabHitboxes.add(new TabHitbox(hitboxX, textY - 1, hitboxW, textH + 2, tab));
            textY += rowH;
        }
    }


    private void renderSettingsSidebar(DrawContext context, int mouseX, int mouseY) {
        this.tabHitboxes.clear();
        this.settingsHitboxes.clear();

        int paneX = this.panelX;
        int paneY = this.panelY;
        int paneW = this.leftAreaW;
        int paneH = this.panelH;

        // Header stays "Atlas" (not "Settings")
        int headerY = paneY + PADDING;
        float titleScale = 1.6f;
        String title = "Atlas";
        int titleW = this.textRenderer.getWidth(title);
        int scaledTitleW = (int) (titleW * titleScale);
        int logoSize = 18;
        int titleGap = 6;
        int totalW = logoSize + titleGap + scaledTitleW;
        int headerX = paneX + (paneW - totalW) / 2;

        context.drawTexture(RenderLayer::getGuiTextured, ATLAS_LOGO, headerX, headerY + 1, 0f, 0f, logoSize, logoSize, logoSize, logoSize);

        // Draw scaled title anchored at the intended pixel position (prevents left drift)
        context.getMatrices().push();
        context.getMatrices().translate(headerX + logoSize + titleGap, headerY - 1, 0);
        context.getMatrices().scale(titleScale, titleScale, 1.0f);
        context.drawTextWithShadow(this.textRenderer, Text.literal(title), 0, 0, ACCENT_COLOR);
        context.getMatrices().pop();

        // Build a flat list for layout that includes dividers
        java.util.List<SettingsTab> tabs = java.util.Arrays.asList(SettingsTab.values());

        int rowH = 16;
        int listTop = headerY + 36;
        int startY = listTop;

int hitboxX = paneX + PADDING;
        int hitboxW = paneW - (PADDING * 2);
        int y = startY;
        int textH = 14;

        for (SettingsTab tab : tabs) {
            if (tab.divider) {
                int lineY = y + 7;
                context.fill(hitboxX, lineY, hitboxX + hitboxW, lineY + 1, 0x80333333);
                y += rowH;
                continue;
            }

            int labelW = this.textRenderer.getWidth(tab.label);
            int labelX = paneX + (paneW - labelW) / 2;

            boolean hovered =
                    mouseX >= hitboxX && mouseX < hitboxX + hitboxW &&
                    mouseY >= y - 1 && mouseY < y - 1 + textH + 2;

            int color;
            if (tab == selectedSettingsTab) color = TAB_SELECTED;
            else if (hovered) color = TAB_HOVER;
            else color = TAB_NORMAL;

            if (tab == selectedSettingsTab) {
                context.fill(hitboxX - 3, y - 1, hitboxX - 1, y - 1 + textH + 2, ACCENT_COLOR);
            }

            context.drawTextWithShadow(this.textRenderer, Text.literal(tab.label), labelX, y, color);
            this.settingsHitboxes.add(new SettingsHitbox(hitboxX, y - 1, hitboxW, textH + 2, tab));

            y += rowH;
        }
    }

    private void renderScriptsPage(DrawContext context, int mouseX, int mouseY, int x, int y, int w) {
        this.cardHitboxes.clear();

        List<Script> list = filteredScripts();
        int maxCards = Math.max(1, (this.panelH - (PADDING * 2) - 26) / (CARD_H + CARD_GAP));

        int cy = y + 10;
        int shown = 0;

        for (Script script : list) {
            if (shown >= maxCards) break;

            int cardX = x;
            int cardY = cy;
            int cardW = w;
            int cardH = CARD_H;

            boolean hovered = mouseX >= cardX && mouseX < cardX + cardW && mouseY >= cardY && mouseY < cardY + cardH;

            int bg = hovered ? 0xCC1B1B1B : 0xCC151515;
            drawRoundedRect(context, cardX, cardY, cardW, cardH, 9, bg);

            // Small top highlight line
            context.fill(cardX + 8, cardY + 6, cardX + cardW - 8, cardY + 7, 0x402A2A2A);

            // Text
            String title = script.displayName();
            String sub = script.isEnabled() ? "Running (press Insert to stop)" : "Click to run (press Insert to stop)";
            context.drawTextWithShadow(this.textRenderer, Text.literal(title), cardX + 10, cardY + 9, 0xFFFFFF);
            context.drawTextWithShadow(this.textRenderer, Text.literal(sub), cardX + 10, cardY + 20, 0x909090);

            this.cardHitboxes.add(new CardHitbox(cardX, cardY, cardW, cardH, script));

            cy += CARD_H + CARD_GAP;
            shown++;
        }

        if (list.isEmpty()) {
            context.drawTextWithShadow(this.textRenderer, Text.literal("No scripts match your filters."), x, y + 12, 0x909090);
        }
    }
    
private void addMiningSettingsWidgets() {
    int rightX = this.panelX + this.leftAreaW + PADDING;
    int rightY = this.panelY + PADDING + 18;
    int rightW = this.panelW - this.leftAreaW - (PADDING * 2);

    // Match the card geometry used in renderSettingsPage()
    int cardX = rightX;
    int cardY = rightY + 24;
    int cardW = rightW;

    int pad = 10;
    int rowH = 56;
    int gap = 10;

    int x = cardX + pad;
    int w = cardW - (pad * 2);
    int y = cardY + 34;

    this.strictnessSlider = addDrawableChild(new AtlasIntSlider(
            x, y, w, rowH,
            1, 5,
            () -> MithrilMiningScript.Settings.getStrictness(),
            v -> MithrilMiningScript.Settings.setStrictness(v),
            "Strictness",
            "Higher = stricter (5 = wool only)"
    ));
    y += rowH + gap;

    this.mineTitaniumBtn = addDrawableChild(new AtlasToggleButton(
            x, y, w, rowH,
            () -> MithrilMiningScript.Settings.isMineTitanium(),
            v -> MithrilMiningScript.Settings.setMineTitanium(v),
            "ON", "OFF",
            "Mine Titanium",
            "Include titanium as a target"
    ));
    y += rowH + gap;

    this.debugBtn = addDrawableChild(new AtlasToggleButton(
            x, y, w, rowH,
            () -> MithrilMiningScript.Settings.isDebugMessages(),
            v -> MithrilMiningScript.Settings.setDebugMessages(v),
            "ON", "OFF",
            "Debug",
            "Show debug messages in chat"
    ));
}

private void addRotationSettingsWidgets() {
    int rightX = this.panelX + this.leftAreaW + PADDING;
    int rightY = this.panelY + PADDING + 18;
    int rightW = this.panelW - this.leftAreaW - (PADDING * 2);

    int cardX = rightX;
    int cardY = rightY + 24;
    int cardW = rightW;

    int pad = 10;
    int rowH = 56;
    int gap = 10;

    int x = cardX + pad;
    int w = cardW - (pad * 2);
    int y = cardY + 34;

    this.rotationTypeBtn = addDrawableChild(new AtlasToggleButton(
            x, y, w, rowH,
            () -> MithrilMiningScript.Settings.getRotationType() == MithrilMiningScript.Settings.RotationType.BEZIER,
            v -> MithrilMiningScript.Settings.setRotationType(
                    v ? MithrilMiningScript.Settings.RotationType.BEZIER : MithrilMiningScript.Settings.RotationType.LINEAR
            ),
            "Bezier", "Linear",
            "Rotation Type",
            "Type of rotation algorithm to use"
    ));
    y += rowH + gap;

    this.rotationSpeedSlider = addDrawableChild(new AtlasIntSlider(
            x, y, w, rowH,
            0, 1000,
            () -> MithrilMiningScript.Settings.getRotationSpeed(),
            v -> MithrilMiningScript.Settings.setRotationSpeed(v),
            "Speed",
            "Linear rotation speed"
    ));
    y += rowH + gap;

    this.bezierSpeedSlider = addDrawableChild(new AtlasFloatSlider(
            x, y, w, rowH,
            0.0f, 2.5f,
            () -> MithrilMiningScript.Settings.getBezierSpeed(),
            v -> MithrilMiningScript.Settings.setBezierSpeed(v),
            "Bezier Speed",
            "Bezier rotation speed multiplier"
    ));
    y += rowH + gap;

    this.randomnessSlider = addDrawableChild(new AtlasFloatSlider(
            x, y, w, rowH,
            0.0f, 1.0f,
            () -> MithrilMiningScript.Settings.getRotationRandomness(),
            v -> MithrilMiningScript.Settings.setRotationRandomness(v),
            "Randomness",
            "Stable noise applied per rotation"
    ));

    // Disable bezier speed slider when linear
    boolean bezier = MithrilMiningScript.Settings.getRotationType() == MithrilMiningScript.Settings.RotationType.BEZIER;
    this.bezierSpeedSlider.active = bezier;
}

// ---------------------------------------------------------------------
// Custom widgets (non-vanilla look & feel)
// ---------------------------------------------------------------------

    @FunctionalInterface
private interface IntGetter { int get(); }
@FunctionalInterface
private interface IntSetter { void set(int v); }
@FunctionalInterface
private interface FloatGetter { float get(); }
@FunctionalInterface
private interface FloatSetter { void set(float v); }
@FunctionalInterface
private interface BoolGetter { boolean get(); }
@FunctionalInterface
private interface BoolSetter { void set(boolean v); }
@FunctionalInterface
private interface EnabledSupplier { boolean isEnabled(); }

private static int clampInt(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
private static float clampFloat(float v, float min, float max) { return Math.max(min, Math.min(max, v)); }

private static final class AtlasToggleButton extends ClickableWidget {
    private final BoolGetter getter;
    private final BoolSetter setter;
    private final String trueLabel;
    private final String falseLabel;
    private final String title;
    private final String subtitle;

    AtlasToggleButton(int x, int y, int w, int h,
                      BoolGetter getter, BoolSetter setter,
                      String trueLabel, String falseLabel,
                      String title, String subtitle) {
        super(x, y, w, h, Text.literal(title));
        this.getter = getter;
        this.setter = setter;
        this.trueLabel = trueLabel;
        this.falseLabel = falseLabel;
        this.title = title;
        this.subtitle = subtitle;
    }

    private boolean isOnPill(double mouseX, double mouseY) {
        int pillW = 86;
        int pillH = 22;
        int px = getX() + getWidth() - pillW - 10;
        int py = getY() + (getHeight() / 2) - (pillH / 2);
        return mouseX >= px && mouseX <= px + pillW && mouseY >= py && mouseY <= py + pillH;
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        if (!active) return;
        if (!isOnPill(mouseX, mouseY)) return;
        setter.set(!getter.get());
    }

    @Override
    protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        MinecraftClient mc = MinecraftClient.getInstance();

        int bg = this.isHovered() ? 0xFF171717 : 0xFF121212;
        drawRoundedRect(context, getX(), getY(), getWidth(), getHeight(), 10, bg);

        // Title + subtitle
        context.drawTextWithShadow(mc.textRenderer, Text.literal(title),
                getX() + 10, getY() + 6, 0xFFE6E6E6);
        context.drawTextWithShadow(mc.textRenderer, Text.literal(subtitle),
                getX() + 10, getY() + 6 + mc.textRenderer.fontHeight + 1, 0xFF9A9A9A);

        // Pill on the right
        boolean val = getter.get();
        String pillText = val ? trueLabel : falseLabel;

        int pillW = 86;
        int pillH = 22;
        int px = getX() + getWidth() - pillW - 10;
        int py = getY() + (getHeight() / 2) - (pillH / 2);

        int pillBg = isOnPill(mouseX, mouseY) ? 0xFF1F1F1F : 0xFF0D0D0D;

        // Border (outer) then fill (inner)
        int border = val ? ACCENT_COLOR : 0xFF2A2A2A;
        drawRoundedRect(context, px - 1, py - 1, pillW + 2, pillH + 2, 11, border);
        drawRoundedRect(context, px, py, pillW, pillH, 10, pillBg);

        int tw = mc.textRenderer.getWidth(pillText);
        int tx = px + (pillW - tw) / 2;
        int ty = py + (pillH - mc.textRenderer.fontHeight) / 2;
        context.drawTextWithShadow(mc.textRenderer, Text.literal(pillText), tx, ty, 0xFFE6E6E6);
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
    }
}

private static final class AtlasIntSlider extends ClickableWidget {
    private final int min;
    private final int max;
    private final IntGetter getter;
    private final IntSetter setter;
    private final String title;
    private final String subtitle;
    private boolean dragging = false;

    AtlasIntSlider(int x, int y, int w, int h,
                   int min, int max,
                   IntGetter getter, IntSetter setter,
                   String title, String subtitle) {
        super(x, y, w, h, Text.literal(title));
        this.min = min;
        this.max = max;
        this.getter = getter;
        this.setter = setter;
        this.title = title;
        this.subtitle = subtitle;
    }

    private int trackX() { return getX() + 10; }
    private int trackW() { return getWidth() - 120; }
    private int trackY() { return getY() + getHeight() - 14; }

    private boolean isOnTrack(double mouseX, double mouseY) {
        int tx = trackX();
        int ty = trackY();
        int tw = trackW();
        return mouseX >= tx && mouseX <= tx + tw && mouseY >= ty - 6 && mouseY <= ty + 8;
    }

    private void updateFromMouse(double mouseX) {
        int tx = trackX();
        int tw = trackW();
        double t = (mouseX - tx) / (double) tw;
        t = Math.max(0.0, Math.min(1.0, t));
        int v = min + (int)Math.round(t * (max - min));
        setter.set(v);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        if (!active) return;
        if (!isOnTrack(mouseX, mouseY)) return;
        dragging = true;
        updateFromMouse(mouseX);
    }

    @Override
    public void onRelease(double mouseX, double mouseY) {
        dragging = false;
    }

    @Override
    protected void onDrag(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (!active) return;
        if (dragging) updateFromMouse(mouseX);
    }

    @Override
    protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        MinecraftClient mc = MinecraftClient.getInstance();

        int bg = this.isHovered() ? 0xFF171717 : 0xFF121212;
        drawRoundedRect(context, getX(), getY(), getWidth(), getHeight(), 10, bg);

        // Title + subtitle
        context.drawTextWithShadow(mc.textRenderer, Text.literal(title),
                getX() + 10, getY() + 6, 0xFFE6E6E6);
        context.drawTextWithShadow(mc.textRenderer, Text.literal(subtitle),
                getX() + 10, getY() + 6 + mc.textRenderer.fontHeight + 1, 0xFF9A9A9A);

        // Track
        int tx = trackX();
        int ty = trackY();
        int tw = trackW();
        float t = (getter.get() - min) / (float)(max - min);

        context.fill(tx, ty, tx + tw, ty + 2, 0xFF000000);
        int fill = (int)(t * tw);
        context.fill(tx, ty, tx + fill, ty + 2, ACCENT_COLOR);

        // Knob
        int knobX = tx + fill - 2;
        context.fill(knobX, ty - 4, knobX + 4, ty + 6, 0xFFE6E6E6);

        // Value right aligned
        String val = String.valueOf(getter.get());
        int vw = mc.textRenderer.getWidth(val);
        int vx = getX() + getWidth() - vw - 12;
        int vy = ty - 6;
        context.drawTextWithShadow(mc.textRenderer, Text.literal(val), vx, vy, 0xFFE6E6E6);
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
    }
}

private static final class AtlasFloatSlider extends ClickableWidget {
    private final float min;
    private final float max;
    private final FloatGetter getter;
    private final FloatSetter setter;
    private final String title;
    private final String subtitle;
    private boolean dragging = false;

    AtlasFloatSlider(int x, int y, int w, int h,
                     float min, float max,
                     FloatGetter getter, FloatSetter setter,
                     String title, String subtitle) {
        super(x, y, w, h, Text.literal(title));
        this.min = min;
        this.max = max;
        this.getter = getter;
        this.setter = setter;
        this.title = title;
        this.subtitle = subtitle;
    }

    private int trackX() { return getX() + 10; }
    private int trackW() { return getWidth() - 120; }
    private int trackY() { return getY() + getHeight() - 14; }

    private boolean isOnTrack(double mouseX, double mouseY) {
        int tx = trackX();
        int ty = trackY();
        int tw = trackW();
        return mouseX >= tx && mouseX <= tx + tw && mouseY >= ty - 6 && mouseY <= ty + 8;
    }

    private void updateFromMouse(double mouseX) {
        int tx = trackX();
        int tw = trackW();
        double t = (mouseX - tx) / (double) tw;
        t = Math.max(0.0, Math.min(1.0, t));
        float v = min + (float)(t * (max - min));
        setter.set(v);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        if (!active) return;
        if (!isOnTrack(mouseX, mouseY)) return;
        dragging = true;
        updateFromMouse(mouseX);
    }

    @Override
    public void onRelease(double mouseX, double mouseY) {
        dragging = false;
    }

    @Override
    protected void onDrag(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (!active) return;
        if (dragging) updateFromMouse(mouseX);
    }

    @Override
    protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        MinecraftClient mc = MinecraftClient.getInstance();

        int bg = this.isHovered() ? 0xFF171717 : 0xFF121212;
        drawRoundedRect(context, getX(), getY(), getWidth(), getHeight(), 10, bg);

        // Title + subtitle
        context.drawTextWithShadow(mc.textRenderer, Text.literal(title),
                getX() + 10, getY() + 6, 0xFFE6E6E6);
        context.drawTextWithShadow(mc.textRenderer, Text.literal(subtitle),
                getX() + 10, getY() + 6 + mc.textRenderer.fontHeight + 1, 0xFF9A9A9A);

        // Track
        int tx = trackX();
        int ty = trackY();
        int tw = trackW();
        float t = (getter.get() - min) / (max - min);

        context.fill(tx, ty, tx + tw, ty + 2, 0xFF000000);
        int fill = (int)(t * tw);
        context.fill(tx, ty, tx + fill, ty + 2, ACCENT_COLOR);

        // Knob
        int knobX = tx + fill - 2;
        context.fill(knobX, ty - 4, knobX + 4, ty + 6, 0xFFE6E6E6);

        // Value right aligned
        String val = String.format("%.2f", getter.get());
        int vw = mc.textRenderer.getWidth(val);
        int vx = getX() + getWidth() - vw - 12;
        int vy = ty - 6;
        context.drawTextWithShadow(mc.textRenderer, Text.literal(val), vx, vy, 0xFFE6E6E6);
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
    }
}


private void renderSettingsPage(DrawContext context, int x, int y, int w) {
        // Keep widget enable/disable in sync without requiring a full rebuild
        if (this.bezierSpeedSlider != null) {
            boolean bezier = MithrilMiningScript.Settings.getRotationType() == MithrilMiningScript.Settings.RotationType.BEZIER;
            this.bezierSpeedSlider.active = bezier;
        }

        int cardX = x;
        int cardY = y + 24;
        int cardW = w;

        if (this.selectedSettingsTab == SettingsTab.MINING) {
            int cardH = 34 + (56 + 10) * 3 + 14; // header + 3 tiles + padding
            drawRoundedRect(context, cardX, cardY, cardW, cardH, 9, 0xCC0B0B0B);
            context.drawTextWithShadow(this.textRenderer, Text.literal("Mithril Miner"), cardX + 10, cardY + 10, 0xFFFFFF);
            return;
        }

        if (this.selectedSettingsTab == SettingsTab.AUTO_DIRECTION) {
            int cardH = 34 + (56 + 10) * 4 + 14; // header + 4 tiles + padding
            drawRoundedRect(context, cardX, cardY, cardW, cardH, 9, 0xCC0B0B0B);
            context.drawTextWithShadow(this.textRenderer, Text.literal("Rotation"), cardX + 10, cardY + 10, 0xFFFFFF);
            return;
        }

        // Other settings tabs not yet implemented
        drawRoundedRect(context, cardX, cardY, cardW, 120, 9, 0xCC0B0B0B);
        context.drawTextWithShadow(this.textRenderer, Text.literal(this.selectedSettingsTab.label), cardX + 10, cardY + 10, 0xFFFFFF);
}

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // Sidebar clicks
            if (this.currentPage == Page.SCRIPTS) {
                for (TabHitbox hb : this.tabHitboxes) {
                    if (hb.contains((int) mouseX, (int) mouseY)) {
                        this.selectedTab = hb.tab;
                        rebuild();
                        return true;
                    }
                }
            } else {
                for (SettingsHitbox hb : this.settingsHitboxes) {
                    if (hb.contains((int) mouseX, (int) mouseY)) {
                        this.selectedSettingsTab = hb.tab;
                        rebuild();
                        return true;
                    }
                }
            }

            // Script card clicks
            if (this.currentPage == Page.SCRIPTS) {
                for (CardHitbox hb : this.cardHitboxes) {
                    if (hb.contains((int) mouseX, (int) mouseY)) {
                        hb.script.setEnabled(true);
                        hb.script.onEnable(MinecraftClient.getInstance());
                        MinecraftClient.getInstance().setScreen(null);
                        return true;
                    }
                }
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    private List<Script> filteredScripts() {
        // Tab filter first (only applies on Scripts page; harmless otherwise)
        String tabLabel = this.selectedTab.label;

        // Search filter
        String q = (this.searchVisible && this.searchField != null) ? this.searchField.getText().trim().toLowerCase() : "";

        return this.scripts.stream()
                .filter(s -> {
                    // category match
                    String cat = safeLower(s.category());
                    boolean tabMatch = cat.equalsIgnoreCase(tabLabel) || safeLower(tabLabel).equals(cat);
                    return tabMatch;
                })
                .filter(s -> {
                    if (q.isEmpty()) return true;
                    return safeLower(s.displayName()).contains(q) || safeLower(s.description()).contains(q);
                })
                .toList();
    }

    private static String safeLower(String s) {
        return s == null ? "" : s.toLowerCase();
    }

    private void rebuild() {
        this.clearChildren();
        this.init();
    }

    // ---- icon drawing (unchanged) ----

    private void renderTopIcons(DrawContext context) {
        if (this.searchButton != null) {
            int x = this.searchButton.getX();
            int y = this.searchButton.getY();
            drawIconButtonFrame(context, x, y, this.searchButton.getWidth(), this.searchButton.getHeight());
            drawMagnifierIcon(context, x, y);
        }

        if (this.pageButton != null) {
            int x = this.pageButton.getX();
            int y = this.pageButton.getY();
            int w = this.pageButton.getWidth();
            int h = this.pageButton.getHeight();
            drawIconButtonFrame(context, x, y, w, h);

            // Use emojis "⚙▶" (bold, slightly larger). Selected is white; non-selected is darker.
            final int inactive = 0xFF5C5C5C;
            final int selected = 0xFFFFFFFF;
            int cogColor = (this.currentPage == Page.SETTINGS) ? selected : inactive;
            int playColor = (this.currentPage == Page.SCRIPTS) ? selected : inactive;

            final String glyphs = "⚙ ▶";
            final float glyphScale = 1.25f;

            int glyphW = (int) (this.textRenderer.getWidth(glyphs) * glyphScale);
            int glyphH = (int) (this.textRenderer.fontHeight * glyphScale);
            int tx = x + (w - glyphW) / 2;
            int ty = y + (h - glyphH) / 2;

            // Draw each glyph separately so they can be tinted independently.
            int cogW = this.textRenderer.getWidth("⚙ ");

            context.getMatrices().push();
            context.getMatrices().translate(tx, ty, 0);
            context.getMatrices().scale(glyphScale, glyphScale, 1.0f);
            context.drawTextWithShadow(this.textRenderer, Text.literal("⚙ ").styled(s -> s.withBold(true)), 0, 0, cogColor);
            context.drawTextWithShadow(this.textRenderer, Text.literal("▶").styled(s -> s.withBold(true)), cogW, 0, playColor);
            context.getMatrices().pop();
        }
    }

    private static void drawIconButtonFrame(DrawContext context, int x, int y, int w, int h) {
        drawRoundedRect(context, x, y, w, h, 5, 0xCC0B0B0B);
        context.fill(x + 1, y + 1, x + w - 1, y + 2, 0x40222222);
    }

    private static void drawMagnifierIcon(DrawContext context, int x, int y) {
        int cx = x + 7;
        int cy = y + 7;
        int r = 4;
        drawCircleOutline(context, cx, cy, r, 0xFFE0E0E0);
        context.fill(cx + 3, cy + 3, cx + 7, cy + 4, 0xFFE0E0E0);
        context.fill(cx + 4, cy + 4, cx + 7, cy + 5, 0xFFE0E0E0);
    }

    private static void drawPlayIcon(DrawContext context, int x, int y) {
        int left = x + 6;
        int top = y + 5;
        int height = 8;
        for (int i = 0; i < height; i++) {
            int w = 1 + (i / 2);
            context.fill(left, top + i, left + w, top + i + 1, 0xFFE0E0E0);
        }
    }

    private static void drawCogIcon(DrawContext context, int x, int y) {
        int cx = x + 9;
        int cy = y + 9;
        drawCircleOutline(context, cx, cy, 4, 0xFFE0E0E0);
        context.fill(cx - 1, cy - 7, cx + 1, cy - 5, 0xFFE0E0E0);
        context.fill(cx - 1, cy + 5, cx + 1, cy + 7, 0xFFE0E0E0);
        context.fill(cx - 7, cy - 1, cx - 5, cy + 1, 0xFFE0E0E0);
        context.fill(cx + 5, cy - 1, cx + 7, cy + 1, 0xFFE0E0E0);
        context.fill(cx - 1, cy - 1, cx + 1, cy + 1, 0xFFE0E0E0);
    }

    private static void drawCircleOutline(DrawContext context, int cx, int cy, int r, int color) {
        int x = r;
        int y = 0;
        int err = 0;
        while (x >= y) {
            plotCirclePoints(context, cx, cy, x, y, color);
            y++;
            if (err <= 0) err += 2 * y + 1;
            else {
                x--;
                err -= 2 * x + 1;
            }
        }
    }

    private static void plotCirclePoints(DrawContext context, int cx, int cy, int x, int y, int color) {
        context.fill(cx + x, cy + y, cx + x + 1, cy + y + 1, color);
        context.fill(cx + y, cy + x, cx + y + 1, cy + x + 1, color);
        context.fill(cx - y, cy + x, cx - y + 1, cy + x + 1, color);
        context.fill(cx - x, cy + y, cx - x + 1, cy + y + 1, color);
        context.fill(cx - x, cy - y, cx - x + 1, cy - y + 1, color);
        context.fill(cx - y, cy - x, cx - y + 1, cy - x + 1, color);
        context.fill(cx + y, cy - x, cx + y + 1, cy - x + 1, color);
        context.fill(cx + x, cy - y, cx + x + 1, cy - y + 1, color);
    }


    @Override
    public void removed() {
        // Persist last-opened state so reopening the menu returns to the same page/tab
        LAST_PAGE = this.currentPage;
        LAST_TAB = this.selectedTab;
        LAST_SETTINGS_TAB = this.selectedSettingsTab;
        super.removed();
    }

    private static void drawRoundedRect(DrawContext context, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0) return;
        if (r <= 0) {
            context.fill(x, y, x + w, y + h, color);
            return;
        }

        // Clamp radius defensively
        r = Math.min(r, Math.min(w / 2, h / 2));

        // Main rectangles (center + sides)
        context.fill(x + r, y, x + w - r, y + h, color);                 // center band
        context.fill(x, y + r, x + r, y + h - r, color);                 // left band
        context.fill(x + w - r, y + r, x + w, y + h - r, color);         // right band

        // Corner pixels (quarter-circles). This avoids inverted/“flipped” corners.
        int rr = r * r;
        // top-left corner box
        for (int dy = 0; dy < r; dy++) {
            for (int dx = 0; dx < r; dx++) {
                int ox = r - 1 - dx;
                int oy = r - 1 - dy;
                if (ox * ox + oy * oy <= rr) {
                    // TL
                    context.fill(x + dx, y + dy, x + dx + 1, y + dy + 1, color);
                    // TR
                    context.fill(x + w - 1 - dx, y + dy, x + w - dx, y + dy + 1, color);
                    // BL
                    context.fill(x + dx, y + h - 1 - dy, x + dx + 1, y + h - dy, color);
                    // BR
                    context.fill(x + w - 1 - dx, y + h - 1 - dy, x + w - dx, y + h - dy, color);
                }
            }
        }
    }

    private static final class CardHitbox {
        final int x, y, w, h;
        final Script script;

        CardHitbox(int x, int y, int w, int h, Script script) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.script = script;
        }

        boolean contains(int mx, int my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }
    private static final class SettingsHitbox {
        final int x, y, w, h;
        final SettingsTab tab;

        SettingsHitbox(int x, int y, int w, int h, SettingsTab tab) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.tab = tab;
        }

        boolean contains(int mx, int my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }



    private static final class TabHitbox {
        final int x, y, w, h;
        final Tab tab;

        TabHitbox(int x, int y, int w, int h, Tab tab) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.tab = tab;
        }

        boolean contains(int mx, int my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }
}
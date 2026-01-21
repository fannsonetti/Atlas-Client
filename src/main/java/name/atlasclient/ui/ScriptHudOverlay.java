package name.atlasclient.ui;

import name.atlasclient.script.Script;
import name.atlasclient.script.ScriptManager;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;

import java.util.List;

public final class ScriptHudOverlay implements HudRenderCallback {

    private static final int PRIMARY = 0xFF3D7294; // requested
    private static final int GRAY = 0xFFB0B0B0;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int BG = 0x70000000;

    // If you want the right title to also be PRIMARY, set this to PRIMARY.
    private static final int TITLE_RIGHT = 0xFFFFAA00;

    public static void register() {
        HudRenderCallback.EVENT.register(new ScriptHudOverlay());
    }

    @Override
    public void onHudRender(DrawContext ctx, RenderTickCounter tickCounter) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.textRenderer == null) return;
        if (mc.options != null && mc.options.hudHidden) return;

        HudPanel panel = null;
        for (Script s : ScriptManager.all()) {
            if (!s.isEnabled()) continue;

            HudPanel candidate = s.buildHudPanel();
            if (candidate != null) {
                panel = candidate;
                break;
            }
        }
        if (panel == null) return;

        final int padding = 6;
        final int lineGap = 2;
        final int ruleH = 2;
        final int lineH = mc.textRenderer.fontHeight + lineGap;

        Text titleLeft = panel.titleLeft == null ? Text.empty() : panel.titleLeft;
        Text titleRight = panel.titleRight == null ? Text.empty() : panel.titleRight;
        Text subtitle = panel.subtitle;

        int maxW = 0;

        int titleLeftW = mc.textRenderer.getWidth(titleLeft);
        int titleRightW = mc.textRenderer.getWidth(titleRight);
        int titleRowW = titleLeftW + (titleRightW > 0 ? 10 : 0) + titleRightW;
        maxW = Math.max(maxW, titleRowW);

        if (subtitle != null && !subtitle.getString().isBlank()) {
            maxW = Math.max(maxW, mc.textRenderer.getWidth(subtitle));
        }

        for (HudPanel.Section sec : panel.sections) {
            maxW = Math.max(maxW, mc.textRenderer.getWidth(sec.header));
            for (Text l : sec.lines) {
                maxW = Math.max(maxW, mc.textRenderer.getWidth(l));
            }
        }

        int totalLines = 1; // title
        if (subtitle != null && !subtitle.getString().isBlank()) totalLines++;

        for (int i = 0; i < panel.sections.size(); i++) {
            HudPanel.Section sec = panel.sections.get(i);
            totalLines += 1; // header
            totalLines += sec.lines.size();
            if (i != panel.sections.size() - 1) totalLines += 1; // blank line between sections
        }

        int boxW = maxW + padding * 2;
        int boxH = ruleH + padding + totalLines * lineH + padding + ruleH;

        int rightMargin = 8;
        int topMargin = 8;

        int x0 = ctx.getScaledWindowWidth() - rightMargin - boxW;
        int y0 = topMargin;

        ctx.fill(x0, y0, x0 + boxW, y0 + boxH, BG);
        ctx.fill(x0, y0, x0 + boxW, y0 + ruleH, PRIMARY);

        int xTextLeft = x0 + padding;
        int xTextRight = x0 + boxW - padding;
        int y = y0 + ruleH + padding;

        ctx.drawTextWithShadow(mc.textRenderer, titleLeft, xTextLeft, y, PRIMARY);

        if (!titleRight.getString().isBlank()) {
            int w = mc.textRenderer.getWidth(titleRight);
            ctx.drawTextWithShadow(mc.textRenderer, titleRight, xTextRight - w, y, TITLE_RIGHT);
        }
        y += lineH;

        if (subtitle != null && !subtitle.getString().isBlank()) {
            ctx.drawTextWithShadow(mc.textRenderer, subtitle, xTextLeft, y, GRAY);
            y += lineH;
        }

        for (int si = 0; si < panel.sections.size(); si++) {
            HudPanel.Section sec = panel.sections.get(si);

            ctx.drawTextWithShadow(mc.textRenderer, sec.header, xTextLeft, y, PRIMARY);
            y += lineH;

            List<Text> lines = sec.lines;
            for (Text l : lines) {
                ctx.drawTextWithShadow(mc.textRenderer, l, xTextLeft, y, WHITE);
                y += lineH;
            }

            if (si != panel.sections.size() - 1) {
                y += lineH;
            }
        }

        ctx.fill(x0, y0 + boxH - ruleH, x0 + boxW, y0 + boxH, PRIMARY);
    }
}

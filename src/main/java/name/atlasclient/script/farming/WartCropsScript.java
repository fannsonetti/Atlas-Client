package name.atlasclient.script.farming;

import name.atlasclient.script.Script;
import name.atlasclient.script.VariantScript;
import name.atlasclient.script.ScriptVariant;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Method;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wart/Crops
 *
 * Variants:
 *  - Vertical: hold one strafe direction continuously (no W)
 *  - S-Shape: alternate strafe direction when "lane end" is detected by being blocked/stationary (no W)
 *
 * HUD (styled like Hub Foraging):
 *  - Inventory Worth (approx; offline)
 *  - Blocks/s (computed from inventory item gain rate, not block API)
 *  - Pests (scoreboard line 6, extracts number from "x8")
 *
 * Notes:
 *  - Never presses W.
 *  - No runs/lanes/direction counters.
 */
public final class WartCropsScript implements Script, VariantScript {

    // ---------------------------------------------------------------------
    // Script metadata
    // ---------------------------------------------------------------------

    @Override public String id() { return "wart_crops"; }

    @Override public String displayName() { return "Wart/Crops"; }

    @Override public String description() { return "Wart/Crops farming with Vertical + S-Shape variants, foraging-style HUD stats, no W."; }

    @Override public String category() { return "Farming"; }

    // IMPORTANT: ScriptManager should call onEnable/onDisable; do not call them from setEnabled.
    private boolean enabled = false;
    @Override public boolean isEnabled() { return enabled; }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            ACTIVE_INSTANCE = this;
            ensureHudHook();
        } else {
            if (ACTIVE_INSTANCE == this) ACTIVE_INSTANCE = null;
        }
    }

    // ---------------------------------------------------------------------
    // Variants
    // ---------------------------------------------------------------------

    private static final List<ScriptVariant> VARIANTS = List.of(
            new ScriptVariant("vertical", "Vertical"),
            new ScriptVariant("s_shape", "S-Shape")
    );

    private String selectedVariant = "vertical";

    @Override
    public List<ScriptVariant> variants() { return VARIANTS; }

    @Override
    public String selectedVariantId() { return selectedVariant; }

    @Override
    public void setSelectedVariantId(String variantId) {
        if (variantId == null) return;
        for (ScriptVariant v : VARIANTS) {
            if (v.id().equalsIgnoreCase(variantId)) {
                selectedVariant = v.id();
                return;
            }
        }
    }

    private String selectedVariantLabel() {
        for (ScriptVariant v : VARIANTS) {
            if (v.id().equalsIgnoreCase(selectedVariant)) return v.label();
        }
        return selectedVariant;
    }

    // ---------------------------------------------------------------------
    // Behavior constants
    // ---------------------------------------------------------------------

    /** If true, right-click is held instead of left-click. */
    private static final boolean USE_RIGHT_CLICK_HARVEST = false;

    /** How long we must be "not moving" before we consider it a lane end for S-Shape (seconds). */
    private static final double LANE_END_STUCK_SECONDS = 0.45;

    /** Cooldown after switching strafe direction (seconds). */
    private static final double SWITCH_COOLDOWN_SECONDS = 0.65;

    /** Minimum delta in X/Z per tick to be considered "moving". */
    private static final double MOVE_EPS_SQ = 0.0009; // ~0.03 blocks

    // ---------------------------------------------------------------------
    // HUD window (match Hub Foraging)
    // ---------------------------------------------------------------------

    /** ARGB accent for the HUD window. */
    private static final int HUD_ACCENT_ARGB = 0xFF3D7294;

    /** HUD background (semi-transparent). */
    private static final int HUD_BG_ARGB = 0xB0000000;

    /** HUD base text color. */
    private static final int HUD_TEXT_ARGB = 0xFFE6E6E6;

    /** HUD secondary text color. */
    private static final int HUD_TEXT_DIM_ARGB = 0xFFB0B0B0;

    private static final int HUD_PAD = 6;
    private static final int HUD_LINE_H = 10;

    // ---------------------------------------------------------------------
    // Pests parsing
    // ---------------------------------------------------------------------

    private static final Pattern PESTS_PATTERN = Pattern.compile("x\\s*(\\d+)");

    // ---------------------------------------------------------------------
    // State / stats
    // ---------------------------------------------------------------------

    private long startedAtMs = 0L;

    private boolean strafeLeft = true;

    /** Last observed position (used for both variants). */
    private Vec3d lastPos = null;

    // ---- Vertical variant state ----
    /** Last Y used to detect a downward step (vertical variant). */
    private double lastY = Double.NaN;

    // ---- S-Shape variant state ----
    private enum Facing { SOUTH, WEST, NORTH, EAST }
    private Facing facing = Facing.SOUTH;

    private enum SShapeState { STRAFE, FORWARD_SHIFT }
    private SShapeState sShapeState = SShapeState.STRAFE;

    /** Forward-axis coordinate at the moment we started the 5-block forward shift. */
    private double forwardShiftStartAxis = 0.0;

    private long lastMovedNs = 0L;
    private long lastSwitchNs = 0L;

    // Inventory worth (approx)
    private double currentInventoryWorth = 0.0;
    private long lastWorthUpdateMs = 0L;

    // Blocks/s: computed from inventory gain rate of relevant drops
    private int lastRelevantCount = -1;
    private long lastBpsSampleMs = 0L;
    private double blocksPerSecond = 0.0;

    // Pests
    private int pests = 0;

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    @Override
    public void onEnable(MinecraftClient mc) {
        if (mc != null) {
            mc.inGameHud.getChatHud().addMessage(
                    Text.literal("Atlas >> ").formatted(Formatting.AQUA, Formatting.BOLD)
                            .append(Text.literal("Wart/Crops ").styled(s -> s.withColor(Formatting.GREEN).withBold(false)))
                            .append(Text.literal("script activated. ").styled(s -> s.withColor(Formatting.GRAY).withBold(false)))
                            .append(Text.literal("[" + selectedVariantLabel() + "]").styled(s -> s.withColor(Formatting.DARK_AQUA).withBold(false)))
            );
        }

        startedAtMs = System.currentTimeMillis();

        strafeLeft = true;

        // Snap yaw to the nearest cardinal direction on start (do NOT change pitch).
        if (mc != null && mc.player != null) {
            snapYawToCardinal(mc.player);
            facing = facingFromYaw(mc.player.getYaw());
        } else {
            facing = Facing.SOUTH;
        }

        lastPos = null;
        lastY = Double.NaN;

        sShapeState = SShapeState.STRAFE;
        forwardShiftStartAxis = 0.0;

        lastMovedNs = System.nanoTime();
        lastSwitchNs = 0L;

        currentInventoryWorth = 0.0;
        lastWorthUpdateMs = 0L;

        lastRelevantCount = -1;
        lastBpsSampleMs = System.currentTimeMillis();
        blocksPerSecond = 0.0;

        pests = 0;

        if (mc != null) {
            releaseAll(mc);
            pressHarvest(mc, true);
            applyStrafeOnly(mc);
        }
    }

    @Override
    public void onDisable() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null) {
            releaseAll(mc);
            mc.inGameHud.getChatHud().addMessage(
                    Text.literal("Atlas >> ").formatted(Formatting.AQUA, Formatting.BOLD)
                            .append(Text.literal("Wart/Crops ").styled(s -> s.withColor(Formatting.RED).withBold(false)))
                            .append(Text.literal("script stopped.").styled(s -> s.withColor(Formatting.GRAY).withBold(false)))
            );
        }
    }

    @Override
    public void onTick(MinecraftClient mc) {
        if (!enabled) return;
        if (mc == null || mc.player == null || mc.world == null) return;

        // Pause if a GUI is open
        if (mc.currentScreen != null) {
            releaseAll(mc);
            return;
        }

        // Keep harvesting input down
        pressHarvest(mc, true);


        // Update pests (scoreboard)
        pests = Math.max(0, parsePestsFromScoreboard(mc, pests));

        // Update inventory worth + blocks/s sampling
        long nowMs = System.currentTimeMillis();

        if (nowMs - lastWorthUpdateMs >= 1000L) {
            currentInventoryWorth = computeInventoryWorthApprox(mc.player);
            lastWorthUpdateMs = nowMs;
        }

        // blocks/s from inventory gain rate (1 sample per second)
        if (nowMs - lastBpsSampleMs >= 1000L) {
            int currentCount = countRelevantDrops(mc.player);
            if (lastRelevantCount >= 0) {
                int delta = Math.max(0, currentCount - lastRelevantCount);
                double sec = Math.max(0.001, (nowMs - lastBpsSampleMs) / 1000.0);
                blocksPerSecond = delta / sec;
            }
            lastRelevantCount = currentCount;
            lastBpsSampleMs = nowMs;
        }

        // Movement
        if ("vertical".equalsIgnoreCase(selectedVariant)) {
            verticalStepLogic(mc);
        } else {
            sShapeLogic(mc);
        }
    }

    // ---------------------------------------------------------------------
    // Movement (no W ever)
    // ---------------------------------------------------------------------

    
    private static void snapYawToCardinal(ClientPlayerEntity player) {
        if (player == null) return;

        float yaw = normalizeYaw(player.getYaw());
        Facing f = facingFromYaw(yaw);

        float snapped;
        switch (f) {
            case SOUTH -> snapped = 0.0f;
            case WEST -> snapped = 90.0f;
            case NORTH -> snapped = 180.0f;
            case EAST -> snapped = -90.0f;
            default -> snapped = 0.0f;
        }

        // Do NOT change pitch.
        player.setYaw(snapped);
    }

    private static float normalizeYaw(float yaw) {
        float y = yaw;
        while (y <= -180.0f) y += 360.0f;
        while (y > 180.0f) y -= 360.0f;
        return y;
    }

    private static Facing facingFromYaw(float yaw) {
        float y = normalizeYaw(yaw);

        // Minecraft convention (common): 0=S, 90=W, 180=N, -90=E
        if (y >= -45.0f && y < 45.0f) return Facing.SOUTH;
        if (y >= 45.0f && y < 135.0f) return Facing.WEST;
        if (y >= 135.0f || y < -135.0f) return Facing.NORTH;
        return Facing.EAST;
    }

    private void applyStrafeOnly(MinecraftClient mc) {
        if (mc == null) return;

        mc.options.forwardKey.setPressed(false);

        mc.options.leftKey.setPressed(strafeLeft);
        mc.options.rightKey.setPressed(!strafeLeft);
    }

    private void applyForwardOnly(MinecraftClient mc) {
        if (mc == null) return;

        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);

        mc.options.forwardKey.setPressed(true);
    }

    /**
     * Vertical variant:
     *  - Hold one strafe direction continuously.
     *  - Swap strafe direction when Y decreases (step down).
     *  - Never change pitch.
     */
    private void verticalStepLogic(MinecraftClient mc) {
        if (mc == null || mc.player == null) return;

        Vec3d p = mc.player.getPos();

        if (Double.isNaN(lastY)) {
            lastY = p.y;
        } else {
            // Swap when we go DOWN (tolerance to ignore minor jitter).
            if (p.y < lastY - 0.01) {
                strafeLeft = !strafeLeft;
            }
            lastY = p.y;
        }

        applyStrafeOnly(mc);
        lastPos = p;
    }

    /**
     * S-Shape variant:
     *  - Strafe left/right while harvesting.
     *  - When lane end is detected (stuck), stop strafing and go forward for 5 blocks.
     *  - After moving forward 5 blocks, swap strafe direction and continue.
     *  - Axis selection depends on facing:
     *      * Facing NORTH/SOUTH -> forward axis is Z
     *      * Facing EAST/WEST   -> forward axis is X
     */
    private void sShapeLogic(MinecraftClient mc) {
        if (mc == null || mc.player == null) return;

        long nowNs = System.nanoTime();
        Vec3d p = mc.player.getPos();

        if (lastPos == null) {
            lastPos = p;
            lastMovedNs = nowNs;
            sShapeState = SShapeState.STRAFE;
            applyStrafeOnly(mc);
            return;
        }

        // Track movement to detect "stuck"
        double dx = p.x - lastPos.x;
        double dz = p.z - lastPos.z;
        double distSq = dx * dx + dz * dz;

        if (distSq > MOVE_EPS_SQ) {
            lastMovedNs = nowNs;
        }

        double stuckSec = (nowNs - lastMovedNs) / 1_000_000_000.0;
        double sinceSwitchSec = (lastSwitchNs == 0L) ? 9999.0 : (nowNs - lastSwitchNs) / 1_000_000_000.0;

        double forwardAxis = (facing == Facing.EAST || facing == Facing.WEST) ? p.x : p.z;

        double lastForwardAxis = (facing == Facing.EAST || facing == Facing.WEST) ? lastPos.x : lastPos.z;
        double forwardDelta = Math.abs(forwardAxis - lastForwardAxis);

        if (sShapeState == SShapeState.STRAFE) {
            // Lane end => start forward shift
            if ((stuckSec >= LANE_END_STUCK_SECONDS || forwardDelta >= 0.20) && sinceSwitchSec >= SWITCH_COOLDOWN_SECONDS) {
                sShapeState = SShapeState.FORWARD_SHIFT;
                forwardShiftStartAxis = forwardAxis;

                // Reset timers to avoid immediate re-trigger
                lastSwitchNs = nowNs;
                lastMovedNs = nowNs;

                applyForwardOnly(mc);
            } else {
                applyStrafeOnly(mc);
            }
        } else { // FORWARD_SHIFT
            // Move forward 5 blocks, then swap strafe direction and resume strafing.
            double moved = Math.abs(forwardAxis - forwardShiftStartAxis);

            if (moved >= 5.0) {
                mc.options.forwardKey.setPressed(false);

                strafeLeft = !strafeLeft;
                sShapeState = SShapeState.STRAFE;

                // Reset movement timers after the turn
                lastMovedNs = nowNs;

                applyStrafeOnly(mc);
            } else {
                applyForwardOnly(mc);
            }
        }

        lastPos = p;
    }
private static void pressHarvest(MinecraftClient mc, boolean down) {
        if (mc == null) return;
        if (USE_RIGHT_CLICK_HARVEST) {
            mc.options.useKey.setPressed(down);
            mc.options.attackKey.setPressed(false);
        } else {
            mc.options.attackKey.setPressed(down);
            mc.options.useKey.setPressed(false);
        }
    }

    private static void releaseAll(MinecraftClient mc) {
        if (mc == null) return;
        mc.options.forwardKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.attackKey.setPressed(false);
        mc.options.useKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
    }

    // ---------------------------------------------------------------------
    // Inventory Worth (approximation)
    // ---------------------------------------------------------------------

    private double computeInventoryWorthApprox(ClientPlayerEntity player) {
        if (player == null) return 0.0;

        double worth = 0.0;
        var inv = player.getInventory();
        int limit = Math.min(36, inv.size());

        for (int i = 0; i < limit; i++) {
            ItemStack st = inv.getStack(i);
            if (st == null || st.isEmpty()) continue;

            String name = stripFormatting(st.getName().getString());
            int count = st.getCount();

            // Vanilla items
            if (st.getItem() == Items.NETHER_WART) { worth += count * 3.0; continue; }
            if (st.getItem() == Items.WHEAT)       { worth += count * 2.0; continue; }
            if (st.getItem() == Items.CARROT)      { worth += count * 2.0; continue; }
            if (st.getItem() == Items.POTATO)      { worth += count * 2.0; continue; }
            if (st.getItem() == Items.BEETROOT)    { worth += count * 2.0; continue; }
            if (st.getItem() == Items.SUGAR_CANE)  { worth += count * 2.0; continue; }
            if (st.getItem() == Items.COCOA_BEANS) { worth += count * 2.0; continue; }
            if (st.getItem() == Items.MELON_SLICE) { worth += count * 1.0; continue; }
            if (st.getItem() == Items.PUMPKIN)     { worth += count * 4.0; continue; }

            // SkyBlock custom names (enchanted forms) - placeholder unit values
            if ("Enchanted Nether Wart".equalsIgnoreCase(name)) { worth += count * 160.0; continue; }
            if ("Enchanted Bread".equalsIgnoreCase(name))       { worth += count * 120.0; continue; }
            if ("Enchanted Carrot".equalsIgnoreCase(name))      { worth += count * 160.0; continue; }
            if ("Enchanted Potato".equalsIgnoreCase(name))      { worth += count * 160.0; continue; }
            if ("Enchanted Sugar".equalsIgnoreCase(name))       { worth += count * 160.0; continue; }
            if ("Enchanted Cocoa Bean".equalsIgnoreCase(name))  { worth += count * 160.0; continue; }
            if ("Enchanted Melon".equalsIgnoreCase(name))       { worth += count * 160.0; continue; }
            if ("Enchanted Pumpkin".equalsIgnoreCase(name))     { worth += count * 160.0; continue; }
        }

        return worth;
    }

    /**
     * Counts "relevant drops" in the main inventory used for blocks/s estimation.
     * This is intentionally coarse: it counts the raw crop items.
     */
    private int countRelevantDrops(ClientPlayerEntity player) {
        if (player == null) return 0;

        int total = 0;
        var inv = player.getInventory();
        int limit = Math.min(36, inv.size());

        for (int i = 0; i < limit; i++) {
            ItemStack st = inv.getStack(i);
            if (st == null || st.isEmpty()) continue;

            // Count raw farm drops (these track "blocks harvested" closely enough)
            if (st.getItem() == Items.NETHER_WART
                    || st.getItem() == Items.WHEAT
                    || st.getItem() == Items.CARROT
                    || st.getItem() == Items.POTATO
                    || st.getItem() == Items.BEETROOT
                    || st.getItem() == Items.SUGAR_CANE
                    || st.getItem() == Items.COCOA_BEANS
                    || st.getItem() == Items.MELON_SLICE
                    || st.getItem() == Items.PUMPKIN) {
                total += st.getCount();
            }
        }

        return total;
    }

    // ---------------------------------------------------------------------
    // Pests from scoreboard line 6
    // ---------------------------------------------------------------------

    private int parsePestsFromScoreboard(MinecraftClient mc, int fallback) {
        if (mc == null || mc.world == null) return fallback;

        try {
            Scoreboard sb = mc.world.getScoreboard();
            if (sb == null) return fallback;

            ScoreboardObjective obj = sb.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
            if (obj == null) return fallback;

            // We intentionally avoid mapping-specific score classes.
            // Use reflection to get an objective score list, then sort by score desc.
            List<?> rawScores = tryGetObjectiveScores(sb, obj);
            if (rawScores == null || rawScores.size() <= 5) return fallback;

            rawScores.sort((a, b) -> Integer.compare(extractScore(b), extractScore(a)));

            String line = extractEntryName(rawScores.get(5));
            line = stripFormatting(line);

            Matcher m = PESTS_PATTERN.matcher(line);
            if (m.find()) return Integer.parseInt(m.group(1));

            return fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static List<?> tryGetObjectiveScores(Scoreboard sb, ScoreboardObjective obj) {
        Object result;

        result = invoke(sb, "getAllPlayerScores", new Class<?>[]{ScoreboardObjective.class}, new Object[]{obj});
        if (result instanceof List<?> l1) return l1;

        result = invoke(sb, "getPlayerScoreList", new Class<?>[]{ScoreboardObjective.class}, new Object[]{obj});
        if (result instanceof List<?> l2) return l2;

        result = invoke(sb, "getScores", new Class<?>[]{ScoreboardObjective.class}, new Object[]{obj});
        if (result instanceof List<?> l3) return l3;

        return null;
    }

    private static Object invoke(Object target, String name, Class<?>[] sig, Object[] args) {
        try {
            Method m = target.getClass().getMethod(name, sig);
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int extractScore(Object scoreObj) {
        if (scoreObj == null) return 0;

        Object v = invoke(scoreObj, "getScore", new Class<?>[]{}, new Object[]{});
        if (v instanceof Integer i) return i;

        v = invoke(scoreObj, "getValue", new Class<?>[]{}, new Object[]{});
        if (v instanceof Integer i2) return i2;

        v = invoke(scoreObj, "score", new Class<?>[]{}, new Object[]{});
        if (v instanceof Integer i3) return i3;

        return 0;
    }

    private static String extractEntryName(Object scoreObj) {
        if (scoreObj == null) return "";

        Object v = invoke(scoreObj, "getPlayerName", new Class<?>[]{}, new Object[]{});
        if (v instanceof String s) return s;

        v = invoke(scoreObj, "getOwner", new Class<?>[]{}, new Object[]{});
        if (v instanceof String s2) return s2;

        v = invoke(scoreObj, "getName", new Class<?>[]{}, new Object[]{});
        if (v instanceof String s3) return s3;

        return String.valueOf(scoreObj);
    }

    private static String stripFormatting(String s) {
        if (s == null) return "";
        return s.replaceAll("\\u00A7.", "").trim();
    }

    private static String formatMoney(double v) {
        if (v < 0) v = 0;
        if (v >= 1_000_000_000) return String.format("%.2fb", v / 1_000_000_000.0);
        if (v >= 1_000_000) return String.format("%.2fm", v / 1_000_000.0);
        if (v >= 1_000) return String.format("%.1fk", v / 1_000.0);
        return String.format("%.0f", v);
    }

    // ---------------------------------------------------------------------
    // HUD hook
    // ---------------------------------------------------------------------

    private static boolean HUD_HOOK_REGISTERED = false;
    private static WartCropsScript ACTIVE_INSTANCE = null;

    private static void ensureHudHook() {
        if (HUD_HOOK_REGISTERED) return;
        HUD_HOOK_REGISTERED = true;
        HudRenderCallback.EVENT.register(WartCropsScript::onHudRender);
    }

    private static void onHudRender(DrawContext ctx, RenderTickCounter tickCounter) {
        onHudRender(ctx, 0.0f);
    }

    private static void onHudRender(DrawContext ctx, float tickDelta) {
        WartCropsScript inst = ACTIVE_INSTANCE;
        if (inst == null || !inst.enabled) return;
        if (ctx == null) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer tr = (mc == null) ? null : mc.textRenderer;
        if (tr == null) return;

        inst.renderHud(ctx, tr);
    }

    private void renderHud(DrawContext ctx, TextRenderer tr) {
        long now = System.currentTimeMillis();
        long elapsedMs = Math.max(0L, now - startedAtMs);
        long elapsedS = elapsedMs / 1000L;

        String title = "Wart/Crops (" + formatDuration(elapsedS) + ")";
        String sub = "Mode: " + selectedVariantLabel();

        int y = 6;

        int w = 175;
        int screenW = MinecraftClient.getInstance().getWindow().getScaledWidth();
        int x = Math.max(6, screenW - w - 6);

        // Compute height based on rendered lines (match Hub Foraging style)
        int lines =
                1 /*title*/ +
                1 /*sub*/ +
                1 /*header*/ +
                3 /*inventory + blocks/s + pests*/;
        int h = HUD_PAD * 2 + (lines * HUD_LINE_H) + 8;

        // Background + accent header line
        ctx.fill(x, y, x + w, y + h, HUD_BG_ARGB);
        ctx.fill(x, y, x + w, y + 2, HUD_ACCENT_ARGB);

        int cx = x + HUD_PAD;
        int cy = y + HUD_PAD;

        // Title
        ctx.drawTextWithShadow(tr, title, cx, cy, HUD_ACCENT_ARGB);
        cy += HUD_LINE_H;
        ctx.drawTextWithShadow(tr, sub, cx, cy, HUD_TEXT_DIM_ARGB);
        cy += HUD_LINE_H + 4;

        // Section header
        ctx.drawTextWithShadow(tr, "Inventory Worth", cx, cy, HUD_ACCENT_ARGB);
        cy += HUD_LINE_H;

        // Values
        ctx.drawTextWithShadow(tr, "Inventory Worth: $" + formatMoney(currentInventoryWorth), cx, cy, HUD_TEXT_DIM_ARGB);
        cy += HUD_LINE_H;

        ctx.drawTextWithShadow(tr, String.format("Blocks/s: %.2f", blocksPerSecond), cx, cy, HUD_TEXT_DIM_ARGB);
        cy += HUD_LINE_H;

        ctx.drawTextWithShadow(tr, "Pests: " + pests, cx, cy, HUD_TEXT_DIM_ARGB);
    }

    private static String formatDuration(long totalSeconds) {
        long s = Math.max(0L, totalSeconds);
        long hours = s / 3600L;
        s %= 3600L;
        long minutes = s / 60L;
        s %= 60L;

        if (hours > 0) return hours + "h " + minutes + "m";
        if (minutes > 0) return minutes + "m " + s + "s";
        return s + "s";
    }
}

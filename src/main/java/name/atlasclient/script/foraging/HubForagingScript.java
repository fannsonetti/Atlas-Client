package name.atlasclient.script.foraging;

import name.atlasclient.config.Rotation;
import name.atlasclient.script.Script;
import name.atlasclient.script.misc.PathfindScript;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.Formatting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.debug.DebugRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.network.message.MessageType;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.Hand;

import com.mojang.authlib.GameProfile;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HubForagingScript
 *
 * - Uses PathfindScript ONLY to go to Travel positions.
 * - BREAK phase never pathfinds to blocks.
 * - Preselects and highlights the next break target during travel (before arrival).
 * - Mines while running: during TRAVEL, rotates toward selected target and holds attack when in reach.
 * - Oak-only detection: OAK_LOG or OAK_WOOD (per your request; stripped variants excluded).
 * - Connected highlights use radius-1 (26-neighbor) flood fill.
 */
public final class HubForagingScript implements Script {

    // ---------------------------------------------------------------------
    // Script metadata
    // ---------------------------------------------------------------------

    @Override public String id() { return "hub_foraging"; }
    @Override public String displayName() { return "Hub Foraging"; }
    @Override public String description() { return "Forages hub oak trunks using a fixed route with highlights."; }
    @Override public String category() { return "Foraging"; }

    // IMPORTANT: ScriptManager should call onEnable/onDisable; do not call them from setEnabled.
    private boolean enabled = false;
    @Override public boolean isEnabled() { return enabled; }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            ACTIVE_INSTANCE = this;
            ensureRenderHook();
            ensureHudHook();
        } else {
            if (ACTIVE_INSTANCE == this) ACTIVE_INSTANCE = null;
        }
    }

    // ---------------------------------------------------------------------
    // Route
    // ---------------------------------------------------------------------

    private static final class Step {
        final BlockPos travel;
        final List<BlockPos> breaks;
        Step(BlockPos travel, List<BlockPos> breaks) {
            this.travel = travel;
            this.breaks = breaks;
        }
    }

    private static BlockPos p(int x, int y, int z) { return new BlockPos(x, y, z); }

    private static final List<Step> ROUTE = List.of(
            new Step(p(-117, 74, -32), List.of(p(-118, 74, -35), p(-117, 74, -29))),
            new Step(p(-122, 73, -23), List.of(p(-123, 74, -21))),
            new Step(p(-129, 73, -22), List.of(p(-131, 73, -22))),
            new Step(p(-131, 73, -15), List.of(p(-134, 73, -17), p(-133, 73, -12))),
            new Step(p(-130, 72, -7),  List.of(p(-130, 72, -5))),
            new Step(p(-131, 72, 4),   List.of(p(-131, 73, 7))),
            new Step(p(-141, 69, 13),  List.of(p(-143, 70, 15))),
            new Step(p(-148, 70, 10),  List.of(p(-150, 71, 9))),
            new Step(p(-167, 72, 0),   List.of(p(-169, 73, -2))),
            new Step(p(-162, 73, -12), List.of(p(-161, 74, -14))),
            new Step(p(-162, 73, -31), List.of(p(-163, 74, -34))),
            new Step(p(-178, 75, -29), List.of(p(-180, 76, -28))),
            new Step(p(-189, 75, -29), List.of(p(-191, 76, -30))),
            new Step(p(-199, 74, -30), List.of(p(-201, 75, -30))),
            new Step(p(-194, 75, -38), List.of(p(-192, 76, -40))),
            new Step(p(-194, 75, -55), List.of(p(-194, 76, -57))),
            new Step(p(-203, 74, -73), List.of(p(-204, 75, -75))),
            new Step(p(-196, 75, -70), List.of(p(-194, 76, -69))),
            new Step(p(-182, 75, -71), List.of(p(-180, 76, -71))),
            new Step(p(-168, 75, -68), List.of(p(-166, 76, -67))),
            new Step(p(-161, 75, -60), List.of(p(-159, 76, -58))),
            new Step(p(-150, 74, -54), List.of(p(-148, 75, -53))),
            new Step(p(-145, 76, -63), List.of(p(-144, 77, -65))),
            new Step(p(-136, 73, -43), List.of(p(-133, 74, -43)))
    );

    // ---------------------------------------------------------------------
    // Visuals (match your miner hue)
    // ---------------------------------------------------------------------

    private static final float H_R = 0.35f;
    private static final float H_G = 0.85f;
    private static final float H_B = 1.00f;

    private static final float TARGET_ALPHA = 0.50f;    // 50% opacity
    private static final float CONNECTED_ALPHA = 0.15f; // 85% transparency

    // ---------------------------------------------------------------------
    // HUD window (requested: use 0xFF3D7294 instead of green)
    // ---------------------------------------------------------------------

    /** ARGB accent for the Hub Foraging HUD window. */
    private static final int HUD_ACCENT_ARGB = 0xFF3D7294;

    /** HUD background (semi-transparent). */
    private static final int HUD_BG_ARGB = 0xB0000000;

    /** HUD base text color. */
    private static final int HUD_TEXT_ARGB = 0xFFE6E6E6;

    /** HUD secondary text color. */
    private static final int HUD_TEXT_DIM_ARGB = 0xFFB0B0B0;

    private static final int HUD_PAD = 6;
    private static final int HUD_LINE_H = 10;

    private static boolean HUD_HOOK_REGISTERED = false;
    private static boolean MESSAGE_HOOK_REGISTERED = false;

    // ---------------------------------------------------------------------
    // Behavior constants
    // ---------------------------------------------------------------------

    /** Begin holding attack after this many ticks on a selected target. */
    private static final int FORCE_MINE_AFTER_TICKS = 6;

    /** Interaction reach threshold for "mine while running". */
    private static final double MINE_REACH = 4.25;

    /** Connected flood fill safety cap. */
    private static final int CONNECTED_MAX_BLOCKS = 256;

    // ---------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------

    private final PathfindScript pathfinder = new PathfindScript();

    private int stepIndex = 0;
    private int breakIndex = 0;

    private enum Phase { TRAVEL, BREAK }
    private Phase phase = Phase.TRAVEL;

    /** Selected target for highlight + rotation. Exists during TRAVEL and BREAK. */
    private BlockPos currentTarget = null;

    /** Ticks spent attempting currentTarget (used for sticky mining). */
    private int targetTicks = 0;

    /** Connected oak blocks (radius-1) for highlighting. */
    private Set<BlockPos> connectedLogs = Set.of();

    // ---------------------------------------------------------------------
    // HUD counters / stats
    // ---------------------------------------------------------------------

    /** Wall-clock start time used for the HUD timer + earned-per-hour. */
    private long startedAtMs = 0L;

    /** Full route completions (increments when step wraps from last -> first). */
    private int routesCompleted = 0;

    /** Inventory worth baseline captured on script start. */
    private double startInventoryWorth = 0.0;

    /** Current inventory worth snapshot (updated periodically). */
    private double currentInventoryWorth = 0.0;

    private long lastWorthUpdateMs = 0L;

    /** Current profit (currentInventoryWorth - startInventoryWorth). */
    private double totalProfit = 0.0;    // Parsing// Rotation planner state
    private boolean rotating = false;
    private float startYaw, startPitch;
    private float goalYaw, goalPitch;
    private float rotT = 0f;

    // Noise (applied gently; avoid first-tick flick)
    private float noiseYaw = 0f;
    private float noisePitch = 0f;

    // Render hook
    private static boolean RENDER_HOOK_REGISTERED = false;
    private static HubForagingScript ACTIVE_INSTANCE = null;

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    @Override
    public void onEnable(MinecraftClient mc) {
        if (mc != null) {
            mc.inGameHud.getChatHud().addMessage(
                    Text.literal("Atlas >> ").formatted(Formatting.AQUA, Formatting.BOLD)
                            .append(
                                    Text.literal("Hub Foraging ")
                                            .styled(s -> s.withColor(Formatting.GREEN).withBold(false))
                            )
                            .append(
                                    Text.literal("script activated.")
                                            .styled(s -> s.withColor(Formatting.GRAY).withBold(false))
                            )
            );
        }

        stepIndex = 0;
        breakIndex = 0;
        phase = Phase.TRAVEL;

        startedAtMs = System.currentTimeMillis();
        routesCompleted = 0;
        startInventoryWorth = (mc != null && mc.player != null) ? computeInventoryWorth(mc.player) : 0.0;
        currentInventoryWorth = startInventoryWorth;
        totalProfit = 0.0;

        currentTarget = null;
        targetTicks = 0;
        connectedLogs = Set.of();

        rotating = false;
        rotT = 0f;

        // Ensure pathfinder runs
        pathfinder.setEnabled(true);

        // Arrival -> BREAK, and force-refresh selection (chunks/state may change during travel)
        pathfinder.setOnArrived(() -> {
            if (!this.enabled) return;
            phase = Phase.BREAK;
            breakIndex = 0;

            // Force refresh selection on arrival
            MinecraftClient mc2 = MinecraftClient.getInstance();
            if (mc2 != null && !ROUTE.isEmpty()) {
                BlockPos first = ROUTE.get(stepIndex).breaks.get(0);
                forceSelectTarget(mc2, first);
            }
        });

        // Preselect first target immediately so highlight appears while traveling.
        preselectCurrentStepBreakTarget(mc, stepIndex, 0);

        // Start first travel.
        startTravelToCurrentStep();
    }

    @Override
    public void onDisable() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null) {
            mc.options.attackKey.setPressed(false);
            mc.inGameHud.getChatHud().addMessage(
                    Text.literal("Atlas >> ").formatted(Formatting.AQUA, Formatting.BOLD)
                            .append(
                                    Text.literal("Hub Foraging ")
                                            .styled(s -> s.withColor(Formatting.RED).withBold(false))
                            )
                            .append(
                                    Text.literal("script stopped. ")
                                            .styled(s -> s.withColor(Formatting.GRAY).withBold(false))
                            )
                            .append(
                                    Text.literal("[")
                                            .styled(s -> s.withColor(Formatting.GRAY).withBold(false))
                            )
                            .append(
                                    Text.literal("Manual")
                                            .styled(s -> s.withColor(Formatting.RED).withBold(false))
                            )
                            .append(
                                    Text.literal("]")
                                            .styled(s -> s.withColor(Formatting.GRAY).withBold(false))
                            )
            );
        }
        pathfinder.cancelNavigation();
        pathfinder.setEnabled(false);
    }

    @Override
    public void onTick(MinecraftClient mc) {
        if (!enabled) return;
        if (mc == null || mc.player == null || mc.world == null) return;
        if (ROUTE.isEmpty()) return;

        if (stepIndex >= ROUTE.size()) stepIndex = 0;

        // Tick pathfinder; only active when it has a nav target.
        pathfinder.onTick(mc);

        // Periodic telemetry updates
        updateInventoryWorth(mc);

        // Always keep next break target selected so highlight/rotation is ready early.
        if (phase == Phase.TRAVEL) {
            preselectCurrentStepBreakTarget(mc, stepIndex, 0);
        }

        if (phase == Phase.TRAVEL) {
            // Mine while running (and only rotate) when the selected target is actually in reach.
            boolean canMine = currentTarget != null
                    && isTreeBlock(mc.world, currentTarget)
                    && inReach(mc.player, currentTarget);

            if (canMine) {
                updateRotation(mc);

                // Only count ticks while we're actually in reach of a valid block
                targetTicks++;
                mc.options.attackKey.setPressed(targetTicks >= FORCE_MINE_AFTER_TICKS);
            } else {
                targetTicks = 0;
                mc.options.attackKey.setPressed(false);
            }
            return;
        }

        // ---------------- BREAK ----------------

        Step step = ROUTE.get(stepIndex);

        if (breakIndex >= step.breaks.size()) {
            // Finished this step: immediately start next travel (no delay).
            mc.options.attackKey.setPressed(false);

            int prev = stepIndex;
            stepIndex = (stepIndex + 1) % ROUTE.size();
            if (prev == ROUTE.size() - 1 && stepIndex == 0) {
                routesCompleted++;
            }
            phase = Phase.TRAVEL;

            // Preselect next step's first break target so highlighting starts instantly.
            preselectCurrentStepBreakTarget(mc, stepIndex, 0);

            startTravelToCurrentStep();
            return;
        }

        BlockPos target = step.breaks.get(breakIndex);

        // Ensure selection matches current break target
        if (!target.equals(currentTarget)) {
            forceSelectTarget(mc, target);
        }

        // If block is no longer oak trunk, advance
        if (!isTreeBlock(mc.world, target)) {
            mc.options.attackKey.setPressed(false);
            breakIndex++;
            targetTicks = 0;
            return;
        }

        targetTicks++;

        // Keep rotation alive
        if (!rotating && (targetTicks % 10 == 0)) {
            planRotationToBlock(mc, target);
        }
        updateRotation(mc);

        // Sticky mine during BREAK
        mc.options.attackKey.setPressed(targetTicks >= FORCE_MINE_AFTER_TICKS);
    }

    // ---------------------------------------------------------------------
    // Travel control
    // ---------------------------------------------------------------------

    private void startTravelToCurrentStep() {
        Step step = ROUTE.get(stepIndex);
        phase = Phase.TRAVEL;
        pathfinder.setEnabled(true);
        pathfinder.navigateTo(step.travel);
    }

    // ---------------------------------------------------------------------
    // Selection / preselection
    // ---------------------------------------------------------------------

    private void preselectCurrentStepBreakTarget(MinecraftClient mc, int stepIdx, int breakIdx0) {
        if (mc == null || mc.world == null) return;
        if (stepIdx < 0 || stepIdx >= ROUTE.size()) return;

        Step step = ROUTE.get(stepIdx);
        if (step.breaks.isEmpty()) return;

        int bi = MathHelper.clamp(breakIdx0, 0, step.breaks.size() - 1);
        BlockPos target = step.breaks.get(bi);

        if (!target.equals(currentTarget)) {
            currentTarget = target;
            targetTicks = 0;

            // Connected set might be incomplete if chunk isn't loaded yet;
            // it is forced refreshed on arrival and when starting the actual break.
            connectedLogs = floodFillRadius1(mc.world, target);

            planRotationToBlock(mc, target);
        }
    }

    private void forceSelectTarget(MinecraftClient mc, BlockPos target) {
        currentTarget = target;
        targetTicks = 0;
        connectedLogs = floodFillRadius1(mc.world, target);
        planRotationToBlock(mc, target);
    }

    // ---------------------------------------------------------------------
    // Rotation (config-driven)
    // ---------------------------------------------------------------------

    private void planRotationToBlock(MinecraftClient mc, BlockPos pos) {
        if (mc == null || mc.player == null) return;

        float[] yp = computeYawPitchToBlock(mc, pos);

        rotating = true;
        rotT = 0f;

        startYaw = mc.player.getYaw();
        startPitch = mc.player.getPitch();

        goalYaw = yp[0];
        goalPitch = yp[1];

        // Noise: computed once, applied gently to avoid first-tick flick
        float r = Rotation.getRotationRandomness();
        noiseYaw = ((float) Math.random() - 0.5f) * 2f * r * 0.35f;
        noisePitch = ((float) Math.random() - 0.5f) * 2f * r * 0.25f;
    }

    private void updateRotation(MinecraftClient mc) {
        if (!rotating || mc == null || mc.player == null) return;

        if (!Rotation.isBezierRotation()) {
            // Linear rotation
            float maxStep = linearMaxStepPerTick(Rotation.getRotationSpeed());

            float cy = mc.player.getYaw();
            float cp = mc.player.getPitch();

            float dy = MathHelper.wrapDegrees(goalYaw - cy);
            float dp = goalPitch - cp;

            float yawErr = Math.abs(dy);
            float pitchErr = Math.abs(dp);

            float stepYaw = MathHelper.clamp(maxStep, 0.8f, 16f);
            float stepPitch = MathHelper.clamp(maxStep * 0.85f, 0.8f, 16f);

            float ny = cy + clampAbs(dy, stepYaw);
            float np = cp + clampAbs(dp, stepPitch);

            // Apply minimal noise only while moving (not as an instant offset)
            if (yawErr > 3f || pitchErr > 3f) {
                ny += noiseYaw * 0.05f;
                np += noisePitch * 0.05f;
            }

            mc.player.setYaw(ny);
            mc.player.setPitch(MathHelper.clamp(np, -90f, 90f));

            if (yawErr <= 0.8f && pitchErr <= 0.8f) rotating = false;
            return;
        }

        // Bezier rotation with eased noise (prevents flick at start)
        float speed = Rotation.getRotationSpeed();   // 0..1000
        float mult = Rotation.getBezierSpeed();      // multiplier

        float base = 0.012f + (speed / 1000f) * 0.10f;
        float dt = MathHelper.clamp(base * mult, 0.010f, 0.14f);

        rotT = Math.min(1f, rotT + dt);
        float eased = easeInOutCubic(rotT);

        float dy = MathHelper.wrapDegrees(goalYaw - startYaw);
        float dp = goalPitch - startPitch;

        float ny = startYaw + dy * eased + noiseYaw * eased;
        float np = startPitch + dp * eased + noisePitch * eased;

        mc.player.setYaw(ny);
        mc.player.setPitch(MathHelper.clamp(np, -90f, 90f));

        if (rotT >= 1f) rotating = false;
    }

    private float[] computeYawPitchToBlock(MinecraftClient mc, BlockPos pos) {
        return computeYawPitchToPoint(mc, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    private float[] computeYawPitchToPoint(MinecraftClient mc, double tx, double ty, double tz) {
        ClientPlayerEntity p = mc.player;

        double px = p.getX();
        double py = p.getY() + 1.5;
        double pz = p.getZ();

        double dx = tx - px;
        double dy = ty - py;
        double dz = tz - pz;

        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double horiz = Math.sqrt(dx * dx + dz * dz);
        double pitch = -Math.toDegrees(Math.atan2(dy, horiz));

        return new float[]{ (float) yaw, (float) pitch };
    }

    private static float linearMaxStepPerTick(int speed0to1000) {
        float s = MathHelper.clamp(speed0to1000 / 1000f, 0f, 1f);
        return 1.4f + 10.0f * s;
    }

    private static float easeInOutCubic(float t) {
        t = MathHelper.clamp(t, 0f, 1f);
        if (t < 0.5f) return 4f * t * t * t;
        return 1f - (float) Math.pow(-2f * t + 2f, 3f) / 2f;
    }

    private static float clampAbs(float v, float maxAbs) {
        if (v > maxAbs) return maxAbs;
        if (v < -maxAbs) return -maxAbs;
        return v;
    }

    // ---------------------------------------------------------------------
    // Oak-only predicate + connected flood fill
    // ---------------------------------------------------------------------

    /**
     * Oak-only trunk predicate (per your request).
     * Includes oak_log and oak_wood only (no stripped variants).
     */
    private static boolean isTreeBlock(World world, BlockPos pos) {
        if (world == null || pos == null) return false;
        BlockState st = world.getBlockState(pos);
        if (st == null) return false;
        Block b = st.getBlock();
        return b == Blocks.OAK_LOG || b == Blocks.OAK_WOOD;
    }

    private static Set<BlockPos> floodFillRadius1(World world, BlockPos start) {
        if (!isTreeBlock(world, start)) return Set.of();

        Set<BlockPos> out = new HashSet<>();
        ArrayDeque<BlockPos> q = new ArrayDeque<>();

        out.add(start);
        q.add(start);

        while (!q.isEmpty() && out.size() < CONNECTED_MAX_BLOCKS) {
            BlockPos p = q.pollFirst();

            // radius 1 cube (26 neighbors): faces + edges + corners
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;

                        BlockPos n = p.add(dx, dy, dz);
                        if (out.contains(n)) continue;
                        if (!isTreeBlock(world, n)) continue;

                        out.add(n);
                        q.addLast(n);
                        if (out.size() >= CONNECTED_MAX_BLOCKS) break;
                    }
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // "Mine while running" reach check
    // ---------------------------------------------------------------------

    private static boolean inReach(ClientPlayerEntity player, BlockPos pos) {
        Vec3d eye = player.getEyePos();
        Vec3d center = Vec3d.ofCenter(pos);
        return eye.squaredDistanceTo(center) <= (MINE_REACH * MINE_REACH);
    }

    // ---------------------------------------------------------------------
    // Chat/actionbar tracking
    // ---------------------------------------------------------------------

    

    private static int safeParseInt(String v, int fallback) {
        try { return Integer.parseInt(v); } catch (Exception e) { return fallback; }
    }

    private static double safeParseDouble(String v, double fallback) {
        try { return Double.parseDouble(v); } catch (Exception e) { return fallback; }
    }

        private void onChatMessage(Text message) {
        // Profit tracking is based on inventory value; chat parsing is not required.
    }

private static long parseLongWithCommas(String v) {
        if (v == null) return 0L;
        String cleaned = v.replace(",", "").trim();
        try { return Long.parseLong(cleaned); } catch (Exception e) { return 0L; }
    }

    // ---------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------

    private static void ensureRenderHook() {
        if (RENDER_HOOK_REGISTERED) return;
        RENDER_HOOK_REGISTERED = true;
        WorldRenderEvents.LAST.register(HubForagingScript::onWorldRender);
    }

    private static void ensureHudHook() {
        if (HUD_HOOK_REGISTERED) return;
        HUD_HOOK_REGISTERED = true;
        HudRenderCallback.EVENT.register(HubForagingScript::onHudRender);
    }

    
    private static void onHudRender(DrawContext ctx, RenderTickCounter tickCounter) {
        // This project version of RenderTickCounter does not expose a tick-delta accessor.
        // The HUD rendering here does not require tickDelta, so we safely forward 0f.
        onHudRender(ctx, 0.0f);
    }

    private static void onHudRender(DrawContext ctx, float tickDelta) {
        HubForagingScript inst = ACTIVE_INSTANCE;
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

        double elapsedHours = elapsedMs / 3_600_000.0;
        double earnedPerHour = (elapsedHours > 0.0001) ? (totalProfit / elapsedHours) : 0.0;

        String title = "Hub Foraging (" + formatDuration(elapsedS) + ")";
        String sub = (phase == Phase.TRAVEL) ? "Traveling" : "Breaking Tree";

        int stepNow = MathHelper.clamp(stepIndex + 1, 1, ROUTE.size());
        String stepText = stepNow + "/" + ROUTE.size();

        int y = 6;

        int w = 175;
        int screenW = MinecraftClient.getInstance().getWindow().getScaledWidth();
        int x = Math.max(6, screenW - w - 6);

        // Compute height based on rendered lines
        int lines = 1 /*title*/ + 1 /*sub*/ + 2 /*route stats*/ + 4 /*inventory*/ ;
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

        // Route
        ctx.drawTextWithShadow(tr, "Step: " + stepText, cx, cy, HUD_TEXT_ARGB);
        cy += HUD_LINE_H;
        ctx.drawTextWithShadow(tr, "Routes Completed: " + routesCompleted, cx, cy, HUD_TEXT_ARGB);
        cy += HUD_LINE_H + 4;

        // Inventory Worth
        ctx.drawTextWithShadow(tr, "Inventory Worth", cx, cy, HUD_ACCENT_ARGB);
        cy += HUD_LINE_H;
        ctx.drawTextWithShadow(tr, "Inventory Worth: $" + formatMoney(currentInventoryWorth), cx, cy, HUD_TEXT_DIM_ARGB);
        cy += HUD_LINE_H;
        ctx.drawTextWithShadow(tr, "Earned Per Hour: $" + formatMoney(earnedPerHour), cx, cy, HUD_TEXT_DIM_ARGB);
        cy += HUD_LINE_H;
        ctx.drawTextWithShadow(tr, "Total Profit: $" + formatMoney(totalProfit), cx, cy, HUD_TEXT_DIM_ARGB);
        cy += HUD_LINE_H + 4;
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

    private void updateInventoryWorth(MinecraftClient mc) {
        if (mc == null || mc.player == null) return;

        long now = System.currentTimeMillis();
        if (now - lastWorthUpdateMs < 500L) return; // 2Hz is sufficient
        lastWorthUpdateMs = now;

        currentInventoryWorth = computeInventoryWorth(mc.player);
        totalProfit = currentInventoryWorth - startInventoryWorth;
    }

    private static double computeInventoryWorth(ClientPlayerEntity player) {
        if (player == null) return 0.0;

        double worth = 0.0;
        // Main inventory includes hotbar in modern versions.
        PlayerInventory inv = player.getInventory();
        int limit = Math.min(36, inv.size());
        for (int i = 0; i < limit; i++) {
            ItemStack st = inv.getStack(i);
            if (st == null || st.isEmpty()) continue;

            String name = stripFormatting(st.getName().getString());
            int count = st.getCount();

            // Hypixel SkyBlock custom items often use display names; both are oak-log based.
            if ("Enchanted Oak Log".equalsIgnoreCase(name)) {
                worth += 320.0 * count;
            } else if ("Oak Log".equalsIgnoreCase(name)) {
                worth += 2.0 * count;
            }
        }
        return worth;
    }

    private static String stripFormatting(String s) {
        if (s == null) return "";
        // Remove standard Minecraft formatting codes (section sign)
        return s.replaceAll("\\u00A7.", "").trim();
    }

    

    private static int findSlotByItem(List<Slot> slots, net.minecraft.item.Item item) {
        if (slots == null || item == null) return -1;
        for (int i = 0; i < slots.size(); i++) {
            ItemStack st = slots.get(i).getStack();
            if (st != null && !st.isEmpty() && st.getItem() == item) return i;
        }
        return -1;
    }

    private static int romanToInt(String roman) {
        if (roman == null || roman.isEmpty()) return -1;
        int sum = 0;
        int prev = 0;
        for (int i = roman.length() - 1; i >= 0; i--) {
            int v = switch (roman.charAt(i)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                case 'L' -> 50;
                case 'C' -> 100;
                case 'D' -> 500;
                case 'M' -> 1000;
                default -> 0;
            };
            if (v < prev) sum -= v;
            else { sum += v; prev = v; }
        }
        return sum;
    }

    private static String formatMoney(double v) {
        double av = Math.abs(v);
        if (av >= 1_000_000) return String.format("%.1fm", v / 1_000_000.0);
        if (av >= 1_000) return String.format("%.1fk", v / 1_000.0);
        return String.valueOf((long) Math.floor(v));
    }

    private static void onWorldRender(WorldRenderContext ctx) {
        HubForagingScript inst = ACTIVE_INSTANCE;
        if (inst == null || !inst.enabled) return;
        if (inst.currentTarget == null) return;

        MatrixStack matrices = ctx.matrixStack();
        VertexConsumerProvider consumers = ctx.consumers();
        if (matrices == null || consumers == null) return;
        if (ctx.camera() == null) return;

        Vec3d cam = ctx.camera().getPos();

        // Selected target (visible during TRAVEL and BREAK)
        Box main = new Box(inst.currentTarget).expand(0.002).offset(-cam.x, -cam.y, -cam.z);
        DebugRenderer.drawBox(matrices, consumers, main, H_R, H_G, H_B, TARGET_ALPHA);

        // Connected oak blocks
        if (inst.connectedLogs != null && !inst.connectedLogs.isEmpty()) {
            for (BlockPos p : inst.connectedLogs) {
                if (p.equals(inst.currentTarget)) continue;
                Box b = new Box(p).expand(0.002).offset(-cam.x, -cam.y, -cam.z);
                DebugRenderer.drawBox(matrices, consumers, b, H_R, H_G, H_B, CONNECTED_ALPHA);
            }
        }
    }
}
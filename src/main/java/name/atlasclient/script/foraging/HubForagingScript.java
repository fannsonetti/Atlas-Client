package name.atlasclient.script.foraging;

import name.atlasclient.config.Rotation;
import name.atlasclient.script.Script;
import name.atlasclient.script.misc.PathfindScript;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.debug.DebugRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * HubForagingScript
 *
 * - Uses PathfindScript ONLY to go to Travel positions.
 * - During BREAK phase, it NEVER pathfinds to blocks.
 * - Preselects the next break target and highlights it DURING travel (before arrival).
 * - Adds a 5-tick delay before starting the next travel pathfind after finishing a step.
 * - Recognizes oak trunks broadly (oak_log, oak_wood, stripped variants, and LOGS tag).
 * - 1-block radius (26-neighbor) flood fill for connected highlights.
 */
public final class HubForagingScript implements Script {

    @Override public String id() { return "hub_foraging"; }
    @Override public String displayName() { return "Hub Foraging"; }
    @Override public String description() { return "Forages hub logs using a fixed route with highlights."; }
    @Override public String category() { return "Foraging"; }

    // ScriptManager should call onEnable/onDisable; do not call them from setEnabled.
    private boolean enabled = false;
    @Override public boolean isEnabled() { return enabled; }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            ACTIVE_INSTANCE = this;
            ensureRenderHook();
        } else {
            if (ACTIVE_INSTANCE == this) ACTIVE_INSTANCE = null;
        }
    }

    // ---------------- Route ----------------

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

    // ---------------- Visuals ----------------

    private static final float H_R = 0.35f;
    private static final float H_G = 0.85f;
    private static final float H_B = 1.00f;

    private static final float TARGET_ALPHA = 0.50f;    // 50% opacity
    private static final float CONNECTED_ALPHA = 0.15f; // 85% transparency

    // ---------------- Behavior constants ----------------

    private static final int FORCE_MINE_AFTER_TICKS = 6;
    private static final int NEXT_TRAVEL_DELAY_TICKS = 5;
    private static final int CONNECTED_MAX_BLOCKS = 256;

    // ---------------- State ----------------

    private final PathfindScript pathfinder = new PathfindScript();

    private int stepIndex = 0;
    private int breakIndex = 0;

    private enum Phase { TRAVEL, BREAK, TRAVEL_DELAY }
    private Phase phase = Phase.TRAVEL;

    private int travelDelayTicks = 0;

    private BlockPos currentTarget = null;
    private int targetTicks = 0;
    private Set<BlockPos> connectedLogs = Set.of();

    // Rotation planner state
    private boolean rotating = false;
    private float startYaw, startPitch;
    private float goalYaw, goalPitch;
    private float rotT = 0f;

    // Noise (used gently to avoid flick)
    private float noiseYaw = 0f, noisePitch = 0f;

    private static boolean RENDER_HOOK_REGISTERED = false;
    private static HubForagingScript ACTIVE_INSTANCE = null;

    // ---------------- Lifecycle ----------------

    @Override
    public void onEnable(MinecraftClient mc) {
        if (mc != null) mc.inGameHud.getChatHud().addMessage(Text.literal("[HubForaging] Enabled"));

        stepIndex = 0;
        breakIndex = 0;
        phase = Phase.TRAVEL;
        travelDelayTicks = 0;

        currentTarget = null;
        targetTicks = 0;
        connectedLogs = Set.of();

        rotating = false;
        rotT = 0f;

        pathfinder.setEnabled(true);

        pathfinder.setOnArrived(() -> {
            if (!this.enabled) return;
            phase = Phase.BREAK;
            breakIndex = 0;

            // Force refresh selection on arrival (fixes “preselected while unloaded” issues)
            forceSelectTarget(MinecraftClient.getInstance(), ROUTE.get(stepIndex).breaks.get(0));
        });

        // Preselect so highlight appears while traveling
        preselectCurrentStepBreakTarget(mc, stepIndex, 0);

        startTravelToCurrentStep();
    }

    @Override
    public void onDisable() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null) {
            mc.options.attackKey.setPressed(false);
            mc.inGameHud.getChatHud().addMessage(Text.literal("[HubForaging] Disabled"));
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

        pathfinder.onTick(mc);

        // Keep selection/highlight active during travel and delay
        if (phase == Phase.TRAVEL || phase == Phase.TRAVEL_DELAY) {
            preselectCurrentStepBreakTarget(mc, stepIndex, 0);
        }

        if (phase == Phase.TRAVEL) {
            mc.options.attackKey.setPressed(false);
            updateRotation(mc); // rotate toward preselected target while traveling
            return;
        }

        if (phase == Phase.TRAVEL_DELAY) {
            mc.options.attackKey.setPressed(false);
            updateRotation(mc); // continue rotating during delay
            travelDelayTicks--;
            if (travelDelayTicks <= 0) startTravelToCurrentStep();
            return;
        }

        // ---------------- BREAK ----------------
        Step step = ROUTE.get(stepIndex);

        if (breakIndex >= step.breaks.size()) {
            mc.options.attackKey.setPressed(false);

            stepIndex = (stepIndex + 1) % ROUTE.size();
            phase = Phase.TRAVEL_DELAY;
            travelDelayTicks = NEXT_TRAVEL_DELAY_TICKS;

            preselectCurrentStepBreakTarget(mc, stepIndex, 0);
            return;
        }

        BlockPos target = step.breaks.get(breakIndex);

        if (!target.equals(currentTarget)) {
            forceSelectTarget(mc, target);
        }

        // If the block is no longer a trunk/log, move on
        if (!isTreeBlock(mc.world, target)) {
            mc.options.attackKey.setPressed(false);
            breakIndex++;
            return;
        }

        targetTicks++;

        // Keep rotation alive
        if (!rotating && (targetTicks % 10 == 0)) {
            planRotationToBlock(mc, target);
        }
        updateRotation(mc);

        // Sticky mine
        mc.options.attackKey.setPressed(targetTicks >= FORCE_MINE_AFTER_TICKS);
    }

    // ---------------- Travel control ----------------

    private void startTravelToCurrentStep() {
        Step step = ROUTE.get(stepIndex);
        phase = Phase.TRAVEL;
        pathfinder.setEnabled(true);
        pathfinder.navigateTo(step.travel);
    }

    // ---------------- Selection ----------------

    private void preselectCurrentStepBreakTarget(MinecraftClient mc, int stepIdx, int breakIdx) {
        if (mc == null || mc.world == null) return;
        if (stepIdx < 0 || stepIdx >= ROUTE.size()) return;

        Step step = ROUTE.get(stepIdx);
        if (step.breaks.isEmpty()) return;

        int bi = MathHelper.clamp(breakIdx, 0, step.breaks.size() - 1);
        BlockPos target = step.breaks.get(bi);

        if (!target.equals(currentTarget)) {
            // During travel, we still select even if chunk isn't loaded yet.
            // Flood fill will be recomputed on arrival via forceSelectTarget().
            currentTarget = target;
            targetTicks = 0;
            connectedLogs = floodFillRadius1(mc.world, target);
            planRotationToBlock(mc, target);
        }
    }

    /** Always recompute connected logs + rotation plan (used on arrival and when starting a break). */
    private void forceSelectTarget(MinecraftClient mc, BlockPos target) {
        currentTarget = target;
        targetTicks = 0;
        connectedLogs = floodFillRadius1(mc.world, target);
        planRotationToBlock(mc, target);
    }

    // ---------------- Rotation (config-driven) ----------------

    private void planRotationToBlock(MinecraftClient mc, BlockPos pos) {
        if (mc == null || mc.player == null) return;

        float[] yp = computeYawPitchToBlock(mc, pos);

        rotating = true;
        rotT = 0f;

        startYaw = mc.player.getYaw();
        startPitch = mc.player.getPitch();

        goalYaw = yp[0];
        goalPitch = yp[1];

        // Compute noise, but apply gently (no first-tick “flick”)
        float r = Rotation.getRotationRandomness();
        noiseYaw = ((float) Math.random() - 0.5f) * 2f * r * 0.35f;
        noisePitch = ((float) Math.random() - 0.5f) * 2f * r * 0.25f;
    }

    private void updateRotation(MinecraftClient mc) {
        if (!rotating || mc == null || mc.player == null) return;

        if (!Rotation.isBezierRotation()) {
            // Linear: step toward goal without adding an instant noise offset
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

            // Apply a *tiny* noise only after we are already moving, to avoid “flick”
            if (yawErr > 3f || pitchErr > 3f) {
                ny += noiseYaw * 0.05f;
                np += noisePitch * 0.05f;
            }

            mc.player.setYaw(ny);
            mc.player.setPitch(MathHelper.clamp(np, -90f, 90f));

            if (yawErr <= 0.8f && pitchErr <= 0.8f) rotating = false;
            return;
        }

        // Bezier: noise is multiplied by easing (starts at 0, ramps in)
        float speed = Rotation.getRotationSpeed();
        float mult = Rotation.getBezierSpeed();

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

    // ---------------- Tree detection + flood fill ----------------

    /** Broad predicate: logs tag + explicit oak variants (covers hub trunks). */
    private static boolean isTreeBlock(World world, BlockPos pos) {
        if (world == null || pos == null) return false;

        BlockState st = world.getBlockState(pos);
        if (st == null) return false;

        Block b = st.getBlock();
        return b == Blocks.OAK_LOG
                || b == Blocks.OAK_WOOD
    }

    private static Set<BlockPos> floodFillRadius1(World world, BlockPos start) {
        if (!isTreeBlock(world, start)) return Set.of();

        Set<BlockPos> out = new HashSet<>();
        ArrayDeque<BlockPos> q = new ArrayDeque<>();

        out.add(start);
        q.add(start);

        while (!q.isEmpty() && out.size() < CONNECTED_MAX_BLOCKS) {
            BlockPos p = q.pollFirst();

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

    // ---------------- Rendering ----------------

    private static void ensureRenderHook() {
        if (RENDER_HOOK_REGISTERED) return;
        RENDER_HOOK_REGISTERED = true;
        WorldRenderEvents.LAST.register(HubForagingScript::onWorldRender);
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

        Box main = new Box(inst.currentTarget).expand(0.002).offset(-cam.x, -cam.y, -cam.z);
        DebugRenderer.drawBox(matrices, consumers, main, H_R, H_G, H_B, TARGET_ALPHA);

        if (inst.connectedLogs != null && !inst.connectedLogs.isEmpty()) {
            for (BlockPos p : inst.connectedLogs) {
                if (p.equals(inst.currentTarget)) continue;
                Box b = new Box(p).expand(0.002).offset(-cam.x, -cam.y, -cam.z);
                DebugRenderer.drawBox(matrices, consumers, b, H_R, H_G, H_B, CONNECTED_ALPHA);
            }
        }
    }
}

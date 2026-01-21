package name.atlasclient.script.misc;

import name.atlasclient.config.Rotation;
import name.atlasclient.script.Script;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.debug.DebugRenderer;
import net.minecraft.client.util.math.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import org.joml.Matrix4f;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * PathfindScript
 *
 * Key fixes:
 * - Prevent path index from skipping "step-up lead-in" nodes (so jump logic still triggers).
 * - Jump logic looks ahead for next step-up and requires proximity to the step lead-in.
 * - Top-half collision blocks (e.g., upside-down stairs/top slabs) are not treated as passable.
 *
 * ASCII-only.
 */
public final class PathfindScript implements Script {
    // HubForagingScript-style path debug color (RGBA)
    private static final float PATH_R = 0.35f;
    private static final float PATH_G = 0.85f;
    private static final float PATH_B = 1.00f;
    // Default alpha for "connected" path nodes (HubForagingScript uses 0.15f)
    private static final float PATH_A = 0.15f;



    // ---------------------------------------------------------------------
    // Default destination
    // ---------------------------------------------------------------------
    private static final Vec3d DEFAULT_TARGET_CENTER = new Vec3d(24.5, 60.0, -1.5);

    @Override public String id() { return "pathfind_script"; }
    @Override public String displayName() { return "Pathfind Script"; }
    @Override public String description() { return "Macro pathing with forgiving node progress and overlays."; }
    @Override public String category() { return "Intermediary"; }

    private boolean enabled = false;
    @Override public boolean isEnabled() { return enabled; }
    @Override public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            ACTIVE_INSTANCE = this;
            ensureRenderHook();
        } else {
            if (ACTIVE_INSTANCE == this) ACTIVE_INSTANCE = null;
            releaseKeys();
        }
    }

    private static PathfindScript ACTIVE_INSTANCE = null;

    // ---------------------------------------------------------------------
    // Runtime state (restored)
    // ---------------------------------------------------------------------

    private Vec3d finalTargetCenter = null;
    private BlockPos finalTargetBlock = null;

    private BlockPos macroGoalBlock = null;
    private Vec3d macroGoalCenter = null;

    private List<BlockPos> currentPath = Collections.emptyList();
    private int pathNodeIndex = 0;

    private Vec3d currentWalkTarget = null;
    private BlockPos currentSegmentGoal = null;

    // Rotation planner state (shared Rotation.java config; same as HubForagingScript)
    private boolean rotating = false;
    private float startYaw, startPitch;
    private float goalYaw, goalPitch;
    private float rotT = 0f;

    // Noise (applied gently; avoid first-tick flick)
    private float noiseYaw = 0f;
    private float noisePitch = 0f;

    // LOS steering smoothing (reduces choppy yaw changes)
    private BlockPos cachedLosTarget = null;
    private int losRetargetCooldown = 0;

    private final List<BlockPos> routeCheckpoints = new ArrayList<>();
    private int checkpointIndex = 0;
    private boolean checkpointsReady = false;

    private int ticksSinceLastPath = 0;
    private int offPathRepathCooldown = 0;
    private boolean forceRepathNow = false;

    private Runnable onArrived = null;

    // Desired input states
    private boolean desiredForward = false;
    private boolean desiredBack = false;
    private boolean desiredLeft = false;
    private boolean desiredRight = false;
    private boolean desiredJump = false;
    private boolean desiredSneak = false;
    private boolean lastForward = false;

    // Jump state
    private int jumpHoldTicks = 0;
    private int jumpCooldownTicks = 0;
    private int jumpGraceTicks = 0;

    // Momentum control
    private int brakeTapTicksRemaining = 0;

    // AoTE (optional)
    private static final boolean ENABLE_AOTE = false;
    private static final String AOTE_NAME = "Aspect of the End";
    private static final int AOTE_COOLDOWN_TICKS = 14;
    private static final int AOTE_FORWARD_LOOKAHEAD_NODES = 18;
    private static final double AOTE_MIN_DIST_TO_USE = 9.0;
    private static final double AOTE_RAYCAST_RANGE = 13.0;
    private static final float AOTE_MAX_YAW_ERROR_DEG = 15.0f;
    private int aoteCooldownTicks = 0;

    // Step-up failure tracking
    private final Set<Long> avoidedStepUpEdges = new HashSet<>();
    private BlockPos trackedStepLead = null;
    private BlockPos trackedUpNode = null;
    private int trackedStepAttemptTimer = 0;
    private int trackedStepFailCount = 0;

    // Render hook
    private static boolean RENDER_HOOK_REGISTERED = false;

    // Visuals: match HubForagingScript highlight
    private static final float H_R = 0.35f;
    private static final float H_G = 0.85f;
    private static final float H_B = 1.00f;
    private static final float TARGET_ALPHA = 0.50f;
    private static final float CONNECTED_ALPHA = 0.15f;


    // Block highlight (matches HubForagingScript oak log highlight opacity)
    private static final float BLOCK_R = PATH_R;
    private static final float BLOCK_G = PATH_G;
    private static final float BLOCK_B = PATH_B;
    private static final float BLOCK_ALPHA = 0.50f;
    private static final float NODE_RADIUS = 0.24f;
    private static final int NODE_SEGS = 16;
    private static final double FLOOR_RIBBON_Y_OFFSET = 0.02; // visible above floor

    // Path polyline rendering: draw dense tiny boxes (DebugRenderer.drawBox) along segments.
    // This uses the same debug renderer pipeline as HubForagingScript highlights, which
    // yields consistent perceived color/opacity vs. the RenderLayer.getLines() pipeline.
    private static final float PATH_SEGMENT_ALPHA = 0.55f;
    private static final float PATH_DOT_HALF_EXTENT = 0.08f; // smaller looks like a line, not blocks
    private static final double PATH_DOT_STEP = 0.12;        // sampling density along segment

    // Camera rotation smoothing
    private static final float YAW_DEADZONE_DEG = 3.0f;
    private static final int LOS_RETARGET_COOLDOWN_TICKS = 5;

    // Step/jump classification based on walk-surface deltas
    private static final double STEP_NO_JUMP_MAX = 0.62; // vanilla step height ~0.6
    private static final double STEP_JUMP_MAX = 1.05;    // allow 1-block-ish jump, disallow 1.5

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    public void navigateTo(Vec3d targetCenter) {
        this.finalTargetCenter = targetCenter;
        this.finalTargetBlock = blockFromCenter(targetCenter);

        this.macroGoalBlock = null;
        this.macroGoalCenter = null;

        this.routeCheckpoints.clear();
        this.checkpointIndex = 0;
        this.checkpointsReady = false;

        resetPathState();
    }

    public void navigateTo(BlockPos targetBlock) {
        Vec3d center = new Vec3d(targetBlock.getX() + 0.5, targetBlock.getY(), targetBlock.getZ() + 0.5);
        navigateTo(center);
    }

    public void cancelNavigation() {
        this.finalTargetCenter = null;
        this.finalTargetBlock = null;

        this.macroGoalBlock = null;
        this.macroGoalCenter = null;

        this.routeCheckpoints.clear();
        this.checkpointIndex = 0;
        this.checkpointsReady = false;

        resetPathState();
        releaseKeys();
    }

    public boolean isNavigating() {
        return finalTargetBlock != null && finalTargetCenter != null;
    }

    public void setOnArrived(Runnable onArrived) {
        this.onArrived = onArrived;
    }

    // ---------------------------------------------------------------------
    // Settings
    // ---------------------------------------------------------------------

    // "Reached goal" threshold used only for checkpoint/segment completion (not for node stepping)
    private static final double GOAL_REACH_DIST = 1.15;


    // Tighter, height-aware checkpoint completion (prevents early stop next to ledges / corners)
    private static final double CHECKPOINT_REACH_DIST = 0.55;
    private static final double CHECKPOINT_FEET_Y_EPS = 0.30;
    // Only stop near the real goal
    private static final double STOP_GOAL_DIST = 0.95;

    // Deceleration near checkpoint/segment end.
    // SLOWDOWN_DIST_FINAL: hold crouch to add friction and reduce overshoot/circling.
    // BRAKE_DIST_FINAL: briefly tap back to bleed residual momentum if still moving.
    private static final double SLOWDOWN_DIST_FINAL = 2.25;
    private static final double SLOWDOWN_DIST_FINAL_CHECKPOINT = 1.20;
    private static final double BRAKE_DIST_FINAL = 0.0; // disabled (prevents end-point oscillation)
    private static final double BRAKE_DIST_FINAL_CHECKPOINT = 0.0;
    private static final int BRAKE_TAP_TICKS = 3;

    // Require vertical agreement before declaring "stop/arrived".
    // This prevents the macro from stopping under an elevated target (common when a ledge fall occurs).
    private static final double STOP_GOAL_FEET_Y_EPS = 0.35;

    // Repath
    // Repath cadence (requested: every 10 ticks)
    private static final int REPATH_EVERY_TICKS = 10;
    private static final int OFFPATH_REPATH_COOLDOWN_TICKS = 35;

    // A*
    private static final int MAX_ITERATIONS = 40_000;
    private static final int MAX_RANGE = 170;

    // Macro segments
    // Long-distance friendliness (requested: 100 block radius)
    private static final int MACRO_STEP = 100;
    private static final int MACRO_SNAP_SEARCH_RADIUS = 10;

    // Movement topology
    private static final boolean ALLOW_DIAGONALS = true;
    private static final boolean ALLOW_STEP_UP   = true;

    private static final boolean ALLOW_DROP_DOWN = true;
    private static final int MAX_DROP_DOWN = 4;
    private static final int DROP_ESCAPE_SEARCH_LIMIT = 200;
    private static final int DROP_ESCAPE_MAX_RADIUS = 9;

    private static final boolean AVOID_FLUIDS = true;

    // Checkpoints
    private static final int CHECKPOINT_EVERY_N_NODES = 40;
    private static final boolean CHECKPOINT_ON_TURN = true;
    private static final int MIN_NODES_BETWEEN_CHECKPOINTS = 12;
    private static final int MAX_CHECKPOINTS_CAP = 10;

    // Rendering (visible)
    private static final int RENDER_MAX_WHITE_NODES = 220;
    // Maximum number of corner marker blocks to render for a path (start/end included).
    private static final int CORNER_MARK_MAX = 64;
    // Render checkpoint boxes one block lower than the walk target.
    private static final boolean RENDER_ONE_Y_LOWER = true;

    private static final float WHITE_R = 0.0f, WHITE_G = 1.0f, WHITE_B = 1.0f, WHITE_A = 0.55f; // aqua
private static final float GOAL_R  = 0.0f, GOAL_G  = 1.0f, GOAL_B  = 1.0f, GOAL_A  = 0.80f; // aqua
private static final float DEST_R  = 0.0f, DEST_G  = 1.0f, DEST_B  = 1.0f, DEST_A  = 0.80f; // aqua

    // Visual overlay: target is ~1.01x a block (avoid 1.1x-1.2x appearance).
    // Box.expand(e) expands on both sides: final size = 1.0 + 2e.
    // 1.0 + 2e = 1.01 => e = 0.005.
    private static final double BOX_EXPAND = 0.002;

    // Jump control
    private static final int JUMP_HOLD_TICKS = 3;
    private static final int JUMP_COOLDOWN_TICKS = 10;
    private static final int JUMP_GRACE_TICKS_ON_START = 10;

    // Step-up failure handling (prevents infinite jump loops at high movement speeds)
    private static final int STEPUP_FAIL_MAX_ATTEMPTS = 3;
    private static final int STEPUP_ATTEMPT_TIMEOUT_TICKS = 26; // ~1.3s

    // Clearance for slab-ish blocks
    private static final double BODY_CLEARANCE_MIN_Y = 0.60;

    // Start snap
    private static final int START_SNAP_MAX_VERTICAL = 2;
    private static final boolean START_SNAP_ALLOW_DOWN_FIRST = true;

    // Rotation
    private static final float YAW_STEP_SMOOTH = 6.0f;
    private static final float YAW_STEP_FAST   = 18.0f;

    // LOS steering
    private static final int LOS_LOOKAHEAD_MAX_NODES = 18;
    private static final double LOS_EYE_HEIGHT = 1.62;

    // Corridor safety
    // Keep this fairly strict; too-lenient corridor acceptance causes "arrival" under/aside the goal and
    // encourages early corner cutting that can lead to falling off ledges.
    private static final double CORRIDOR_MAX_DIST_TO_PATH = 0.60;
    private static final double CORRIDOR_SAMPLE_STEP = 0.45;

    // Off-path sensitivity (less trigger-happy)
    private static final double OFFPATH_MAX_DIST = 4.5;

    // Progress window
    private static final int PATH_INDEX_FORWARD_WINDOW = 10;
 
    // ---------------------------------------------------------------------
    // Main tick entrypoint (Script runner calls this each client tick)
    // ---------------------------------------------------------------------

    // ---------------------------------------------------------------------
    // Script lifecycle compatibility (Fix A)
    // ---------------------------------------------------------------------

    @Override
    public void onEnable(MinecraftClient client) {
        // Ensure state is safe on enable; avoid jump spam right after enabling.
        jumpGraceTicks = JUMP_GRACE_TICKS_ON_START;
        ensureRenderHook();
    }

    @Override
    public void onDisable() {
        cancelNavigation();
        releaseKeys();
    }

    @Override
    public void onTick(MinecraftClient client) {
        // Atlas ScriptManager ticks scripts via onTick(...).
        // Delegate to the existing run(...) tick loop to preserve behavior.
        run(client);
    }


    // Some Atlas builds use a Script interface that does not declare run(MinecraftClient).
    // Keep the method (it is how this script is executed), but do not mark it as @Override.
    public void run(MinecraftClient client) {
        // Smooth head rotation (Rotation.java)
        if (client.player != null && Rotation.allowRotation("pathfind", client.player.age)) {
            updateRotation(client);
        }

        if (!enabled) return;
        if (client == null || client.player == null || client.world == null) {
            releaseKeys();
            return;
        }

        ClientPlayerEntity player = client.player;
        World world = client.world;

        if (finalTargetCenter == null || finalTargetBlock == null) {
            // If no destination is set, default to a configured point.
            navigateTo(DEFAULT_TARGET_CENTER);
        }

        // Cooldowns
        if (offPathRepathCooldown > 0) offPathRepathCooldown--;
        if (aoteCooldownTicks > 0) aoteCooldownTicks--;

        BlockPos startFeet = snapStartToStandable(world, feetBlock(player));
        if (startFeet == null) {
            releaseKeys();
            return;
        }

        // Segment goal (100 block radius for long distances)
        BlockPos segmentEnd = ensureMacroGoal(world, startFeet, finalTargetBlock);
        if (segmentEnd == null) {
            releaseKeys();
            return;
        }

        // Completion: only stop at true destination
        if (isAtStopGoal(player, finalTargetBlock)) {
            finishRun(client);
            return;
        }

        // Checkpoint progression
        BlockPos checkpointGoal = getCurrentCheckpointGoal(segmentEnd);
        currentSegmentGoal = checkpointGoal;

        if (checkpointGoal != null && isAtGoalXZ(world, player, checkpointGoal)) {
            if (checkpointIndex < routeCheckpoints.size()) {
                checkpointIndex++;
            } else {
                // End of current segment
                if (segmentEnd.equals(finalTargetBlock)) {
                    // We'll finish when stop-goal triggers (stricter).
                } else {
                    // Advance macro segment.
                    macroGoalBlock = null;
                    macroGoalCenter = null;
                }
                // Force fresh path/checkpoints for the next segment.
                checkpointsReady = false;
                currentPath = Collections.emptyList();
                pathNodeIndex = 0;
            }
        }

        // Repath triggers
        boolean shouldRepath = forceRepathNow;
        if (!shouldRepath) {
            ticksSinceLastPath++;
            if (ticksSinceLastPath >= REPATH_EVERY_TICKS) shouldRepath = true;
        }

        if (!shouldRepath && offPathRepathCooldown <= 0) {
            if (isOffPath(player.getPos(), currentPath, pathNodeIndex, OFFPATH_MAX_DIST)) {
                shouldRepath = true;
                offPathRepathCooldown = OFFPATH_REPATH_COOLDOWN_TICKS;
            }
        }

        if (shouldRepath) {
            forceRepathNow = false;
            ticksSinceLastPath = 0;

            // Compute fresh path to the current checkpoint goal.
            BlockPos goal = (currentSegmentGoal != null) ? currentSegmentGoal : segmentEnd;
            List<BlockPos> raw;

            // Direct-path shortcut: if the corridor is clear and walkable (common in superflat / open fields),
            // skip A* and generate a straight-line path to eliminate unnecessary diagonal-then-straight patterns.
            if (isDirectWalkable(world, startFeet, goal)) {
                raw = buildDirectLinePath(startFeet, goal);
            } else {
                raw = AStar.find(world, startFeet, goal, MAX_RANGE, MAX_ITERATIONS,
                        ALLOW_DIAGONALS, ALLOW_STEP_UP, ALLOW_DROP_DOWN, MAX_DROP_DOWN);
            }

            if (raw != null && !raw.isEmpty()) {
                currentPath = raw;
                pathNodeIndex = 0;
                currentWalkTarget = null;

                routeCheckpoints.clear();
                routeCheckpoints.addAll(generateSparseCheckpointsFromRawPath(raw, goal));
                checkpointIndex = 0;
                checkpointsReady = true;
            }
        }

        // If no path, do not press keys.
        if (currentPath == null || currentPath.isEmpty()) {
            releaseKeys();
            return;
        }

        // Progress index (for jump logic + LOS lookahead)
        updatePathIndexForgiving(player.getPos(), world);

        // Select a safe LOS target along the path (body-safe, not just eye ray), but do not
        // retarget every tick (reduces choppy yaw and unnecessary mouse movement).
        BlockPos los = getSmoothedLosTarget(world, player, currentPath, pathNodeIndex);
        if (los != null) currentWalkTarget = centerOf(los);

        // Compute desired movement keys (A/D corridor steering)
        computeDesiredMovement(client, player, startFeet, segmentEnd);

        // Apply keys
        applyKeys(client);
    }

    private void applyKeys(MinecraftClient client) {
        if (client == null || client.options == null) return;

        client.options.forwardKey.setPressed(desiredForward);
        client.options.backKey.setPressed(desiredBack);
        client.options.leftKey.setPressed(desiredLeft);
        client.options.rightKey.setPressed(desiredRight);
        client.options.sprintKey.setPressed(false);
        client.options.jumpKey.setPressed(desiredJump);
        client.options.sneakKey.setPressed(desiredSneak);

boolean prevForward = lastForward;
lastForward = desiredForward;

// If we have just started walking, claim rotation briefly to prevent other scripts from snapping us back.
if (!prevForward && desiredForward && client.player != null) {
    Rotation.tryClaimRotation("pathfind", client.player.age, 8);
}
}

    private BlockPos getSmoothedLosTarget(World world, ClientPlayerEntity player, List<BlockPos> path, int startIndex) {
        if (world == null || player == null || path == null || path.isEmpty()) return null;

        if (losRetargetCooldown > 0 && cachedLosTarget != null) {
            losRetargetCooldown--;
            return cachedLosTarget;
        }

        BlockPos fresh = selectLineOfSightTarget(world, player, path, startIndex);
        if (fresh != null) {
            cachedLosTarget = fresh;
            losRetargetCooldown = LOS_RETARGET_COOLDOWN_TICKS;
        }
        return cachedLosTarget;
    }

    private void computeDesiredMovement(MinecraftClient client, ClientPlayerEntity player, BlockPos start, BlockPos segmentEnd) {
        desiredForward = false;
        desiredBack = false;
        desiredLeft = false;
        desiredRight = false;
        desiredJump = false;
        desiredSneak = false;

        if (client == null || client.world == null) return;
        if (player == null) return;
        if (segmentEnd == null) return;

        // Determine the current stop-goal (checkpoint/segment goal). We only stop the run at the true destination.
        BlockPos stopGoal = (currentSegmentGoal != null) ? currentSegmentGoal : segmentEnd;
        Vec3d stopCenter = centerOf(stopGoal);

        // Distance to the current stop-goal (used for slowdown heuristics).
        double distToStop = distanceXZ(player.getPos(), stopCenter);

        // If we are actually at the checkpoint/segment goal (tight + height-aware), stop pressing movement keys.
        if (isAtGoalXZ(client.world, player, stopGoal)) {
            // Let the checkpoint progression logic advance on the next tick.
            desiredForward = false;
            desiredSneak = false;
            desiredJump = false;
            return;
        }

        // Prefer a body-safe LOS target along the actual path (prevents corner cutting), but
        // use the smoothed (cached) target so yaw does not jitter.
        BlockPos aimBlock = stopGoal;
        if (currentPath != null && !currentPath.isEmpty()) {
            BlockPos los = getSmoothedLosTarget(client.world, player, currentPath, pathNodeIndex);
            if (los != null) aimBlock = los;
        }

        Vec3d aim = centerOf(aimBlock);
        float desiredYaw = computeDesiredYaw(player.getPos(), aim);
        float yawError = Math.abs(MathHelper.wrapDegrees(desiredYaw - player.getYaw()));

        // If we are already aligned and moving forward, do not touch the mouse.
        // This prevents the "already straight line" choppiness and reduces visual weirdness.
        if (!(lastForward && yawError <= YAW_DEADZONE_DEG)) {
            if (yawError > YAW_DEADZONE_DEG) {
                float yawStep = adaptiveYawStep(yawError);
                if (Rotation.allowRotation("pathfind", player.age)) {
                    planRotation(desiredYaw, player.getPitch());
                }
            }
        }

        // Forward-only walking: eliminates diagonal overshoot/zigzag caused by A/D corrections.
        desiredForward = true;
        desiredLeft = false;
        desiredRight = false;

        // Turn anticipation slowdown near sharp waypoint turns.
        double turnAngle = 0.0;
        if (routeCheckpoints != null && checkpointIndex < routeCheckpoints.size()) {
            BlockPos b = routeCheckpoints.get(Math.min(routeCheckpoints.size() - 1, checkpointIndex));
            BlockPos a = start;
            if (checkpointIndex > 0) a = routeCheckpoints.get(Math.min(routeCheckpoints.size() - 1, checkpointIndex - 1));
            BlockPos c = null;
            if (checkpointIndex + 1 < routeCheckpoints.size()) c = routeCheckpoints.get(checkpointIndex + 1);
            else if (segmentEnd != null && !b.equals(segmentEnd)) c = segmentEnd;

            if (c != null) {
                Vec3d av = centerOf(a);
                Vec3d bv = centerOf(b);
                Vec3d cv = centerOf(c);
                Vec3d ab = new Vec3d(bv.x - av.x, 0.0, bv.z - av.z);
                Vec3d bc = new Vec3d(cv.x - bv.x, 0.0, cv.z - bv.z);
                if (ab.lengthSquared() > 1e-6 && bc.lengthSquared() > 1e-6) {
                    Vec3d v1 = ab.normalize();
                    Vec3d v2 = bc.normalize();
                    double dot = MathHelper.clamp(v1.dotProduct(v2), -1.0, 1.0);
                    turnAngle = Math.toDegrees(Math.acos(dot));
                }
            }

            double distToB = distanceXZ(player.getPos(), centerOf(b));
            if (turnAngle >= 55.0 && distToB <= 2.4) {
                desiredSneak = true;
            }
        }

        // Slowdown near the stop-goal to prevent overshoot. Use a smaller window for intermediate checkpoints.
        boolean stopIsFinal = (finalTargetBlock != null && stopGoal.equals(finalTargetBlock));
        double slowDist = stopIsFinal ? SLOWDOWN_DIST_FINAL : SLOWDOWN_DIST_FINAL_CHECKPOINT;
        if (distToStop <= slowDist) {
            desiredSneak = true;
        }

        // Jump: conservative (only when required) - looks ahead for upcoming step-up.
        desiredJump = computeDesiredJump(client, player);

        // If the next path edge is a drop-down, never sneak.
// Sneaking prevents stepping off ledges and can deadlock near goals (especially when the goal is below).
if (currentPath != null && pathNodeIndex + 1 < currentPath.size()) {
    BlockPos a = currentPath.get(pathNodeIndex);
    BlockPos b = currentPath.get(pathNodeIndex + 1);
    if (isDropRequiredForEdge(client.world, a, b)) {
        desiredSneak = false;
    }
}

        // AoTE (optional)
        if (ENABLE_AOTE && desiredForward) {
            tryUseAote(client, player, yawError);
        }
    }

    private void finishRun(MinecraftClient client) {
        releaseKeys();
        currentWalkTarget = null;
        currentSegmentGoal = null;

        if (client != null && client.player != null) {
            client.player.sendMessage(Text.literal("Arrived."), false);
        }

        Runnable cb = this.onArrived;
        if (cb != null) {
            try { cb.run(); } catch (Throwable ignored) {}
        }

        setEnabled(false);
    }

    // ---------------------------------------------------------------------
    // Forgiving progress index (pass-based, clamped on step-ups)
    // ---------------------------------------------------------------------

    private void updatePathIndexForgiving(Vec3d playerPos, World world) {
        if (currentPath == null || currentPath.isEmpty()) {
            pathNodeIndex = 0;
            return;
        }

        int start = MathHelper.clamp(pathNodeIndex, 0, currentPath.size() - 1);
        int end = Math.min(currentPath.size() - 1, start + PATH_INDEX_FORWARD_WINDOW);

        // Choose best index only in forward window (never regress)
        int bestIdx = start;
        double bestD2 = Double.POSITIVE_INFINITY;

        for (int i = start; i <= end; i++) {
            Vec3d c = centerOf(currentPath.get(i));
            double dx = c.x - playerPos.x;
            double dz = c.z - playerPos.z;
            double d2 = dx * dx + dz * dz;
            if (d2 < bestD2) {
                bestD2 = d2;
                bestIdx = i;
            }
        }

        if (bestIdx > pathNodeIndex) pathNodeIndex = bestIdx;

        // Pass-based advancement: if you have moved past the current node along the segment, advance.
        // BUT: do not allow skipping the lead-in of a step-up; you must approach it so jump logic can fire.
        while (pathNodeIndex + 1 < currentPath.size()) {
            int i = pathNodeIndex;

            BlockPos prev = currentPath.get(Math.max(0, i - 1));
            BlockPos cur  = currentPath.get(i);
            BlockPos next = currentPath.get(i + 1);

            double near = distanceXZ(playerPos, centerOf(cur));

            boolean stepUp = isJumpRequiredForEdge(world, cur, next);

            // IMPORTANT: never advance onto the "up" node until we are actually up.
            // Otherwise, the walker thinks it is already one block ahead and will not
            // trigger jump/step logic while still pressed against the ledge.
            if (stepUp) {
                // Require that our feet are effectively on the higher walk surface.
                // This covers both full block step-ups and half-block (slab/stair) step-ups.
                double nextFloor = getWalkSurfaceY(world, next);
                boolean actuallyUp = playerPos.y >= nextFloor - 0.05;
                if (actuallyUp && near <= 0.95) {
                    pathNodeIndex++;
                    continue;
                }
                break;
            }

            // Flat / downhill: forgiving advancement
            if (near <= 0.95) {
                pathNodeIndex++;
                continue;
            }

            if (hasPassedNodeXZ(playerPos, prev, cur, next)) {
                pathNodeIndex++;
                continue;
            }

            break;
        }

        if (pathNodeIndex >= currentPath.size()) {
            pathNodeIndex = currentPath.size() - 1;
        }
    }

    private static boolean hasPassedNodeXZ(Vec3d playerPos, BlockPos prev, BlockPos node, BlockPos next) {
        // Project player position onto segment prev->next and consider "passed" if beyond ~55% of the segment.
        Vec3d a = new Vec3d(prev.getX() + 0.5, 0.0, prev.getZ() + 0.5);
        Vec3d b = new Vec3d(next.getX() + 0.5, 0.0, next.getZ() + 0.5);
        Vec3d p = new Vec3d(playerPos.x, 0.0, playerPos.z);

        Vec3d ab = b.subtract(a);
        double abLen2 = ab.lengthSquared();
        if (abLen2 < 1e-6) return false;

        double t = (p.subtract(a).dotProduct(ab)) / abLen2;
        return t > 0.55;
    }

    // ---------------------------------------------------------------------
    // Walk surface / jump-required detection (handles slabs and stairs)
    // ---------------------------------------------------------------------

    private static double getWalkSurfaceY(World world, BlockPos airPos) {
    if (world == null || airPos == null) return 0.0;

    // If the current "air" cell actually contains a low-profile collision (bottom slab, stair tread, carpet),
    // treat that collision top as the floor surface. This is essential when the pathfinder treats slabs as passable.
    VoxelShape hereShape = world.getBlockState(airPos).getCollisionShape(world, airPos);
    if (hereShape != null && !hereShape.isEmpty()) {
        double minY = hereShape.getMin(Direction.Axis.Y);
        double maxY = hereShape.getMax(Direction.Axis.Y);

        // Accept only floor-like shapes in the lower half. Reject top slabs / upside-down stairs.
        if (minY <= 0.20 && maxY <= BODY_CLEARANCE_MIN_Y) {
            return (double) airPos.getY() + maxY;
        }
    }

    // Otherwise, classic case: player stands in air, floor is the block below.
    BlockPos support = airPos.down();
    VoxelShape supportShape = world.getBlockState(support).getCollisionShape(world, support);
    if (supportShape == null || supportShape.isEmpty()) return (double) support.getY();

    double maxY = supportShape.getMax(Direction.Axis.Y);
    return (double) support.getY() + maxY;
}

    private static boolean isJumpRequiredForEdge(World world, BlockPos fromAir, BlockPos toAir) {
        if (fromAir == null || toAir == null) return false;
        // Classic full-block step-up case.
        if (toAir.getY() > fromAir.getY()) return true;
        if (world == null) return false;

        // Slabs/stairs: same air Y but the walk surface height increases beyond step height.
        double fromFloor = getWalkSurfaceY(world, fromAir);
        double toFloor = getWalkSurfaceY(world, toAir);
        double d = toFloor - fromFloor;
        // Use surface delta rather than block Y delta so slabs/stairs are handled correctly.
        return d > STEP_NO_JUMP_MAX && d <= STEP_JUMP_MAX;
    }

    private static boolean isDropRequiredForEdge(World world, BlockPos fromAir, BlockPos toAir) {
        if (fromAir == null || toAir == null) return false;
        if (toAir.getY() < fromAir.getY()) return true;
        if (world == null) return false;

        // Slabs/stairs: same air Y but the walk surface height decreases.
        double fromFloor = getWalkSurfaceY(world, fromAir);
        double toFloor = getWalkSurfaceY(world, toAir);
        double d = toFloor - fromFloor;
        return d < -STEP_NO_JUMP_MAX && d >= -5.0;
    }

    private static boolean isNearEdgeToward(BlockPos lead, BlockPos dest, Vec3d pos, double edgeTol, double lateralTol) {
        if (lead == null || dest == null || pos == null) return false;
        BlockPos d = dest.subtract(lead);
        int sx = Integer.signum(d.getX());
        int sz = Integer.signum(d.getZ());
        if (sx != 0) {
            double edgeX = lead.getX() + (sx > 0 ? 1.0 : 0.0);
            double forwardDist = (sx > 0) ? (edgeX - pos.x) : (pos.x - edgeX);
            double lateral = Math.abs(pos.z - (lead.getZ() + 0.5));
            return (forwardDist <= edgeTol && forwardDist >= -0.55 && lateral <= lateralTol);
        } else if (sz != 0) {
            double edgeZ = lead.getZ() + (sz > 0 ? 1.0 : 0.0);
            double forwardDist = (sz > 0) ? (edgeZ - pos.z) : (pos.z - edgeZ);
            double lateral = Math.abs(pos.x - (lead.getX() + 0.5));
            return (forwardDist <= edgeTol && forwardDist >= -0.55 && lateral <= lateralTol);
        }
        return false;
    }

        private static long edgeKey(BlockPos from, BlockPos to) {
        // Deterministic 64-bit mixing of two BlockPos values
        long a = from.asLong();
        long b = to.asLong();
        long x = a ^ (b * 0x9E3779B97F4A7C15L);
        x ^= (x >>> 33);
        x *= 0xC2B2AE3D27D4EB4FL;
        x ^= (x >>> 29);
        return x;
    }
    
    private static boolean isStepUpEdgeAvoided(BlockPos from, BlockPos to) {
        PathfindScript inst = ACTIVE_INSTANCE;
        return inst != null && inst.avoidedStepUpEdges.contains(edgeKey(from, to));
    }
    
    private void clearStepUpTracking() {
        trackedStepLead = null;
        trackedUpNode = null;
        trackedStepAttemptTimer = 0;
        trackedStepFailCount = 0;
    }
    
    private void onStepUpAttemptStarted(BlockPos stepLead, BlockPos upNode) {
        // New edge -> reset attempt counter
        if (trackedStepLead == null || trackedUpNode == null
                || !trackedStepLead.equals(stepLead) || !trackedUpNode.equals(upNode)) {
            trackedStepFailCount = 0;
        }
        trackedStepLead = stepLead;
        trackedUpNode = upNode;
        trackedStepAttemptTimer = STEPUP_ATTEMPT_TIMEOUT_TICKS;
    }
    
    private void updateStepUpAttemptTracking(ClientPlayerEntity player) {
        if (trackedStepLead == null || trackedUpNode == null) return;
    
        // Success: we reached (or exceeded) the destination walk surface.
        // This covers both full-block step-ups and half-block (slab/stair) rises.
        if (player.getBoundingBox().minY >= getWalkSurfaceY(player.getWorld(), trackedUpNode) - 0.02) {
            clearStepUpTracking();
            return;
        }
    
        if (trackedStepAttemptTimer > 0) {
            trackedStepAttemptTimer--;
            return;
        }
    
        // Timer expired without gaining the step-up height: treat as failure.
        trackedStepFailCount++;
    
        if (trackedStepFailCount >= STEPUP_FAIL_MAX_ATTEMPTS) {
            avoidedStepUpEdges.add(edgeKey(trackedStepLead, trackedUpNode));
            forceRepathNow = true;
            clearStepUpTracking();
            return;
        }
    
        // Give it another chance, but avoid tight-looping: require us to re-trigger jump.
        trackedStepAttemptTimer = 0;
    }

// ---------------------------------------------------------------------
    // Jump logic (conservative, step-up aware)
    // ---------------------------------------------------------------------

    private boolean computeDesiredJump(MinecraftClient client, ClientPlayerEntity player) {
        if (client == null || client.world == null) return false;

        // Detect step-up failures (e.g., too-fast approach causing repeated missed jumps) and force a longer repath.
        updateStepUpAttemptTracking(player);

        if (jumpGraceTicks > 0) {
            jumpGraceTicks--;
            jumpHoldTicks = 0;
            return false;
        }

        if (jumpCooldownTicks > 0) jumpCooldownTicks--;

        if (jumpHoldTicks > 0) {
            jumpHoldTicks--;
            return true;
        }

        if (!player.isOnGround()) return false;
        if (jumpCooldownTicks > 0) return false;

        if (currentPath == null || currentPath.isEmpty()) return false;
        if (pathNodeIndex >= currentPath.size()) return false;

        int py = player.getBlockPos().getY();

        // Look ahead for the next edge that requires a jump (full block or slab/stair rise).
        BlockPos stepLead = null;
        BlockPos stepDest = null;
        int maxLook = Math.min(currentPath.size() - 2, pathNodeIndex + 5);

        for (int i = pathNodeIndex; i <= maxLook; i++) {
            BlockPos a = currentPath.get(i);
            BlockPos b = currentPath.get(i + 1);
            if (isJumpRequiredForEdge(client.world, a, b)) {
                stepLead = a;
                stepDest = b;
                break;
            }
        }

        if (stepLead == null || stepDest == null) return false;

                // Jump trigger should be based on being near the ledge edge in the correct direction,
        // and should work reliably on diagonals and offset approaches.
        BlockPos d = stepDest.subtract(stepLead);
        int sx = Integer.signum(d.getX());
        int sz = Integer.signum(d.getZ());
        if (sx == 0 && sz == 0) return false;

        Vec3d p = player.getPos();

        Vec3d leadCenter = new Vec3d(stepLead.getX() + 0.5, p.y, stepLead.getZ() + 0.5);

        // Direction of travel on XZ plane
        Vec3d dir = new Vec3d(sx, 0.0, sz).normalize();

        Vec3d rel = new Vec3d(p.x - leadCenter.x, 0.0, p.z - leadCenter.z);

        // forward = how far along the step direction you are from center
        double forward = rel.dotProduct(dir);

        // lateral = perpendicular distance from the travel line (cross magnitude in XZ)
        double lateral = Math.abs(rel.x * dir.z - rel.z * dir.x);

        double speedXZ = new Vec3d(player.getVelocity().x, 0.0, player.getVelocity().z).length();
        double edgeTol = 0.60 + MathHelper.clamp(speedXZ * 1.3, 0.0, 0.45);
        double lateralTol = 0.95;

        // From block center to edge is 0.5. distToEdge is how close we are to the edge in the direction of travel.
        double distToEdge = 0.5 - forward;

        boolean nearEdge = (distToEdge <= edgeTol && distToEdge >= -0.65 && lateral <= lateralTol);
        if (!nearEdge) return false;
        BlockPos upNode = stepDest;

        // If we previously failed this specific step-up multiple times, avoid it and repath.
        if (isStepUpEdgeAvoided(stepLead, upNode)) {
            forceRepathNow = true;
            return false;
        }

        World world = client.world;

        // Robust "need-to-jump" detection (full blocks + slabs/stairs):
        // - Near the lead-in
        // - Walk surface at destination is higher than current by > step height
        // - Not already at/above the destination walk surface
        // - Destination air spaces are clear (feet + head)
        double fromFloor = getWalkSurfaceY(world, stepLead);
        double toFloor = getWalkSurfaceY(world, upNode);
        double floorDelta = toFloor - fromFloor;
        if (floorDelta <= STEP_NO_JUMP_MAX || floorDelta > STEP_JUMP_MAX) return false;

        double feetY = player.getBoundingBox().minY;
        if (feetY >= toFloor - 0.02) return false;

        // Destination spaces that must be empty/passable.
        BlockPos destFeet = upNode;
        BlockPos destHead = upNode.up();

        VoxelShape destFeetShape = world.getBlockState(destFeet).getCollisionShape(world, destFeet);
        VoxelShape destHeadShape = world.getBlockState(destHead).getCollisionShape(world, destHead);

        if (!destFeetShape.isEmpty()) return false;
        if (!destHeadShape.isEmpty()) return false;
        
// Avoid jumping if we are clearly moving away from the step (use velocity, not camera yaw).
Vec3d toUp = centerOf(upNode).subtract(player.getPos());
Vec3d horiz = new Vec3d(toUp.x, 0.0, toUp.z);
if (horiz.lengthSquared() < 1e-6) return false;
horiz = horiz.normalize();
Vec3d v = player.getVelocity();
Vec3d vXZ = new Vec3d(v.x, 0.0, v.z);
if (vXZ.lengthSquared() > 1e-6) {
    vXZ = vXZ.normalize();
    if (vXZ.dotProduct(horiz) < -0.15) return false;
}

onStepUpAttemptStarted(stepLead, upNode);
        jumpHoldTicks = JUMP_HOLD_TICKS;
        jumpCooldownTicks = JUMP_COOLDOWN_TICKS;
        return true;
    }


    // ---------------------------------------------------------------------
    // Direct-path shortcut (superflat / open areas)
    // ---------------------------------------------------------------------

    private static boolean isDirectWalkable(World world, BlockPos start, BlockPos goal) {
        if (world == null || start == null || goal == null) return false;

        // Only attempt on roughly level ground; A* handles vertical terrain better.
        if (Math.abs(start.getY() - goal.getY()) > 1) return false;

        // Sample along the straight line in XZ and require standable blocks.
        Vec3d a = centerOf(start);
        Vec3d b = centerOf(goal);
        Vec3d ab = new Vec3d(b.x - a.x, 0.0, b.z - a.z);
        double len = Math.sqrt(ab.lengthSquared());
        if (len < 1e-6) return true;

        int steps = (int) Math.ceil(len / 0.45);
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / (double) steps;
            double x = a.x + (b.x - a.x) * t;
            double z = a.z + (b.z - a.z) * t;

            BlockPos p = new BlockPos(MathHelper.floor(x), start.getY(), MathHelper.floor(z));

            // Try a small vertical window to tolerate minor micro-steps.
            BlockPos stand = null;
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos cand = p.add(0, dy, 0);
                if (AStar.isStandablePublic(world, cand)) {
                    stand = cand;
                    break;
                }
            }
            if (stand == null) return false;
        }
        return true;
    }

    private static List<BlockPos> buildDirectLinePath(BlockPos start, BlockPos goal) {
        // Bresenham in XZ at the start Y (good enough for flat-ish direct routes).
        List<BlockPos> out = new ArrayList<>();
        int x0 = start.getX();
        int z0 = start.getZ();
        int x1 = goal.getX();
        int z1 = goal.getZ();
        int y = start.getY();

        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = (x0 < x1) ? 1 : -1;
        int sz = (z0 < z1) ? 1 : -1;
        int err = dx - dz;

        int x = x0;
        int z = z0;
        out.add(new BlockPos(x, y, z));

        while (x != x1 || z != z1) {
            int e2 = 2 * err;
            if (e2 > -dz) {
                err -= dz;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                z += sz;
            }
            out.add(new BlockPos(x, y, z));
        }

        // Ensure the final node matches the goal Y if it differs by 1.
        if (Math.abs(goal.getY() - y) == 1) {
            out.set(out.size() - 1, goal);
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // Macro goal
    // ---------------------------------------------------------------------

    private BlockPos ensureMacroGoal(World world, BlockPos start, BlockPos finalGoal) {
        int distMan = manhattan(start, finalGoal);

        // Always segment long distances into ~MACRO_STEP chunks to avoid large A* expansions.
        if (distMan <= MACRO_STEP) {
            macroGoalBlock = finalGoal;
            macroGoalCenter = centerOf(finalGoal);
            return finalGoal;
        }

        if (macroGoalBlock != null) {
            int d = manhattan(start, macroGoalBlock);
            if (d <= MAX_RANGE && d >= 12) return macroGoalBlock;
        }

        Vec3d s = centerOf(start);
        Vec3d g = centerOf(finalGoal);

        Vec3d dir = g.subtract(s);
        double len = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        if (len < 1e-6) return finalGoal;

        double step = (double) MACRO_STEP;
        Vec3d dir2 = new Vec3d(dir.x / len, 0.0, dir.z / len);
        Vec3d approx = s.add(dir2.multiply(step));

        BlockPos approxPos = new BlockPos(MathHelper.floor(approx.x), start.getY(), MathHelper.floor(approx.z));
        BlockPos snapped = snapToNearestStandable(world, approxPos, MACRO_SNAP_SEARCH_RADIUS, START_SNAP_MAX_VERTICAL);

        if (snapped == null) return null;
        if (manhattan(start, snapped) > MAX_RANGE) return null;

        macroGoalBlock = snapped;
        macroGoalCenter = centerOf(snapped);
        return snapped;
    }

    private static BlockPos snapToNearestStandable(World world, BlockPos around, int radiusXZ, int radiusY) {
        for (int r = 0; r <= radiusXZ; r++) {
            for (int dx = -r; dx <= r; dx++) {
                int dz1 = r - Math.abs(dx);
                int dz2 = -dz1;

                for (int dz : new int[]{dz1, dz2}) {
                    for (int dy = 0; dy <= radiusY; dy++) {
                        BlockPos cand1 = around.add(dx, -dy, dz);
                        if (AStar.isStandablePublic(world, cand1)) return cand1;

                        if (dy != 0) {
                            BlockPos cand2 = around.add(dx, dy, dz);
                            if (AStar.isStandablePublic(world, cand2)) return cand2;
                        }
                    }
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------

    private static void ensureRenderHook() {
        if (RENDER_HOOK_REGISTERED) return;
        RENDER_HOOK_REGISTERED = true;
        WorldRenderEvents.LAST.register(PathfindScript::onWorldRender);
    }

    private static void onWorldRender(WorldRenderContext ctx) {
        PathfindScript inst = ACTIVE_INSTANCE;
        if (inst == null || !inst.enabled) return;

        MatrixStack matrices = ctx.matrixStack();
        VertexConsumerProvider consumers = ctx.consumers();
        if (matrices == null || consumers == null) return;

        var camera = ctx.camera();
        if (camera == null) return;

        double cx = camera.getPos().x;
        double cy = camera.getPos().y;
        double cz = camera.getPos().z;

        // Path highlight (HubForagingScript style)
        if (inst.currentPath != null && !inst.currentPath.isEmpty()) {
            renderPathHighlights(matrices, consumers, cx, cy, cz, inst.currentPath, inst.pathNodeIndex);
        }
renderCheckpointOverlays(matrices, consumers, cx, cy, cz, inst);
    }

    /**
     * Version-compatible rendering:
     * - Avoids BufferRenderer/Tessellator/VertexFormat/GameRenderer APIs that change across MC versions.
     * - Uses RenderLayer.getLines() via VertexConsumerProvider, which is stable.
     * - Draws a single line segment between nodes.
     * - Renders clear marker blocks at corners (wireframe outline + semi-transparent fill).
     */
    private static void renderPathHighlights(MatrixStack matrices, VertexConsumerProvider consumers,
                                         double camX, double camY, double camZ,
                                         List<BlockPos> path, int nodeIndex) {
        if (path == null || path.isEmpty()) return;

        int max = Math.min(path.size(), RENDER_MAX_WHITE_NODES);
        int idx = MathHelper.clamp(nodeIndex, 0, max - 1);

        // Draw "connected" nodes
        for (int i = 0; i < max; i++) {
            BlockPos p0 = path.get(i);
            if (p0 == null) continue;

            // Path nodes are typically "air" positions where the player stands.
            // For visuals (to match HubForagingScript block highlights), draw the solid block under air nodes.
            World w = MinecraftClient.getInstance().world;
            BlockPos p = p0;
            if (w != null && w.getBlockState(p0).isAir() && !w.getBlockState(p0.down()).isAir()) {
                p = p0.down();
            }
            if (i == idx) continue;

            Box b = new Box(p).expand(BOX_EXPAND).offset(-camX, -camY, -camZ);
            DebugRenderer.drawBox(matrices, consumers, b, H_R, H_G, H_B, CONNECTED_ALPHA);
        }

        // Draw current/next node as "target"
        if (idx >= 0 && idx < max) {
            BlockPos np0 = path.get(idx);
            World w2 = MinecraftClient.getInstance().world;
            BlockPos np = np0;
            if (np0 != null && w2 != null && w2.getBlockState(np0).isAir() && !w2.getBlockState(np0.down()).isAir()) {
                np = np0.down();
            }
            if (np != null) {
                Box nb = new Box(np).expand(BOX_EXPAND).offset(-camX, -camY, -camZ);
                DebugRenderer.drawBox(matrices, consumers, nb, H_R, H_G, H_B, TARGET_ALPHA);
            }
        }
    }






    private static void renderCheckpointOverlays(MatrixStack matrices, VertexConsumerProvider consumers,
                                                 double camX, double camY, double camZ,
                                                 PathfindScript inst) {
        if (inst == null) return;

        // Boxes match HubForagingScript oak log highlight.
        final float br = BLOCK_R;
        final float bg = BLOCK_G;
        final float bb = BLOCK_B;
        // Lines keep PATH_COLOR_ARGB.
        final float lr = PATH_R;
        final float lg = PATH_G;
        final float lb = PATH_B;
        final float la = PATH_A;
        // Highlight final destination block (if present).
        if (inst.finalTargetBlock != null) {
            drawBoxAt(renderPos(inst.finalTargetBlock), matrices, consumers, camX, camY, camZ, br, bg, bb, BLOCK_ALPHA);
        }

        // Highlight current segment / checkpoint goal.
        if (inst.currentSegmentGoal != null) {
            drawBoxAt(renderPos(inst.currentSegmentGoal), matrices, consumers, camX, camY, camZ, br, bg, bb, BLOCK_ALPHA);
        }

        // Checkpoints: block highlights + a single line from each checkpoint to the next.
        if (inst.routeCheckpoints == null || inst.routeCheckpoints.isEmpty()) return;

        for (int i = 0; i < inst.routeCheckpoints.size(); i++) {
            BlockPos cp = inst.routeCheckpoints.get(i);
            if (cp == null) continue;

            boolean isNext = (i == inst.checkpointIndex);
            drawBoxAt(renderPos(cp), matrices, consumers, camX, camY, camZ, br, bg, bb, BLOCK_ALPHA);

            // Single line drawing from this checkpoint to the next checkpoint (or final goal).
            BlockPos next = null;
            if (i + 1 < inst.routeCheckpoints.size()) next = inst.routeCheckpoints.get(i + 1);
            else if (inst.finalTargetBlock != null) next = inst.finalTargetBlock;

            if (next != null) {
                // depth test toggle omitted (compat)
                try {
                    List<Vec3d> pts = buildVisibleSegmentPoints(MinecraftClient.getInstance().world, renderPos(cp), renderPos(next));
                    for (int k = 0; k + 1 < pts.size(); k++) {
                        drawLine3d(matrices, consumers, camX, camY, camZ,
                                pts.get(k), pts.get(k + 1),
                                lr, lg, lb, la);
                    }
                } finally {
                    // depth test toggle omitted (compat)
                }
            }
        }
    }

    
    /**
     * Returns a path point centered on the cell but placed on the computed walk surface (slab-aware),
     * with a tiny offset so the line never z-fights with the floor.
     */
    private static Vec3d nodePoint(World world, BlockPos cell) {
        if (cell == null) return Vec3d.ZERO;
        double y = (world == null) ? (cell.getY() + FLOOR_RIBBON_Y_OFFSET)
                : (getWalkSurfaceY(world, cell) + FLOOR_RIBBON_Y_OFFSET);
        return new Vec3d(cell.getX() + 0.5, y, cell.getZ() + 0.5);
    }

    /**
     * For vertical transitions, draw an "L-shaped" polyline that hugs the outside edge of the block,
     * instead of a 45-degree diagonal that can clip into geometry.
     */
    private static List<Vec3d> buildVisibleSegmentPoints(World world, BlockPos a, BlockPos b) {
        Vec3d p0 = nodePoint(world, renderPos(a));
        Vec3d p1 = nodePoint(world, renderPos(b));

        double dy = p1.y - p0.y;
        if (Math.abs(dy) <= 1e-4) {
            return List.of(p0, p1);
        }

        int dx = Integer.signum(b.getX() - a.getX());
        int dz = Integer.signum(b.getZ() - a.getZ());

        // If purely vertical (rare), just draw vertical.
        if (dx == 0 && dz == 0) return List.of(p0, p1);

        // Choose the dominant horizontal axis to avoid cutting corners into blocks.
        boolean useX = (Math.abs(b.getX() - a.getX()) >= Math.abs(b.getZ() - a.getZ()));

        double edgeX = a.getX() + 0.5;
        double edgeZ = a.getZ() + 0.5;

        if (useX && dx != 0) edgeX = a.getX() + (dx > 0 ? 1.0 : 0.0);
        else if (!useX && dz != 0) edgeZ = a.getZ() + (dz > 0 ? 1.0 : 0.0);
        else {
            // Fallback to any available axis.
            if (dx != 0) edgeX = a.getX() + (dx > 0 ? 1.0 : 0.0);
            if (dz != 0) edgeZ = a.getZ() + (dz > 0 ? 1.0 : 0.0);
        }

        Vec3d edgeLow = new Vec3d(edgeX, p0.y, edgeZ);
        Vec3d edgeHigh = new Vec3d(edgeX, p1.y, edgeZ);

        return List.of(p0, edgeLow, edgeHigh, p1);
    }

private static void drawCircle3d(MatrixStack matrices, VertexConsumerProvider consumers,
                                     double camX, double camY, double camZ,
                                     Vec3d center, double radius, int segs,
                                     float r, float g, float b, float a) {
        if (segs < 3) return;
        for (int i = 0; i < segs; i++) {
            double a0 = (2.0 * Math.PI * i) / (double) segs;
            double a1 = (2.0 * Math.PI * (i + 1)) / (double) segs;
            Vec3d p0 = new Vec3d(center.x + Math.cos(a0) * radius, center.y, center.z + Math.sin(a0) * radius);
            Vec3d p1 = new Vec3d(center.x + Math.cos(a1) * radius, center.y, center.z + Math.sin(a1) * radius);
            drawLine3d(matrices, consumers, camX, camY, camZ, p0, p1, r, g, b, a);
        }
    }

    private static BlockPos renderPos(BlockPos logical) {
        return RENDER_ONE_Y_LOWER ? logical.down() : logical;
    }

    private static void drawBoxAt(BlockPos pos, MatrixStack matrices, VertexConsumerProvider consumers,
                                  double camX, double camY, double camZ,
                                  float r, float g, float b, float a) {
        Box box = new Box(pos).expand(BOX_EXPAND).offset(-camX, -camY, -camZ);
        DebugRenderer.drawBox(matrices, consumers, box, r, g, b, a);
    }

    private static void drawMarkerBoxAt(BlockPos pos, MatrixStack matrices, VertexConsumerProvider consumers,
                                        double camX, double camY, double camZ,
                                        float r, float g, float b, float fillAlpha) {
        if (pos == null) return;

        // Slight expansion makes edges clearer and reduces Z-fighting.
        Box fill = new Box(pos).expand(0.002).offset(-camX, -camY, -camZ);
        DebugRenderer.drawBox(matrices, consumers, fill, r, g, b, fillAlpha);

        // Strong outline: a second pass with a slightly larger box and full alpha.
        Box outline = new Box(pos).expand(0.006).offset(-camX, -camY, -camZ);
        DebugRenderer.drawBox(matrices, consumers, outline, r, g, b, 1.0f);
    }

    /**
     * Draw a segment using the same DebugRenderer pipeline as block highlights by sampling
     * a dense sequence of tiny boxes along the line. This preserves the perceived color/opacity
     * that users see on highlight boxes (e.g., HubForagingScript).
     */
    private static void drawSegmentAsDots(MatrixStack matrices, VertexConsumerProvider consumers,
                                         double camX, double camY, double camZ,
                                         Vec3d a, Vec3d b,
                                         float r, float g, float bl, float al) {
        if (matrices == null || consumers == null || a == null || b == null) return;

        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double dz = b.z - a.z;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6) return;

        int steps = Math.max(1, (int) Math.ceil(len / PATH_DOT_STEP));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / (double) steps;
            double x = a.x + dx * t;
            double y = a.y + dy * t;
            double z = a.z + dz * t;

            Box dot = new Box(
                    x - PATH_DOT_HALF_EXTENT, y - 0.001, z - PATH_DOT_HALF_EXTENT,
                    x + PATH_DOT_HALF_EXTENT, y + 0.001, z + PATH_DOT_HALF_EXTENT
            ).offset(-camX, -camY, -camZ);

            DebugRenderer.drawBox(matrices, consumers, dot, r, g, bl, al);
        }
    }


private static void drawLine3d(MatrixStack matrices, VertexConsumerProvider consumers,
                               double camX, double camY, double camZ,
                               Vec3d a, Vec3d b,
                               float r, float g, float bl, float al) {
    VertexConsumer vc = consumers.getBuffer(RenderLayer.getLines());
    Matrix4f mat = matrices.peek().getPositionMatrix();

    float ax = (float) (a.x - camX);
    float ay = (float) (a.y - camY);
    float az = (float) (a.z - camZ);
    float bx = (float) (b.x - camX);
    float by = (float) (b.y - camY);
    float bz = (float) (b.z - camZ);

    endVertex(vc.vertex(mat, ax, ay, az).color(r, g, bl, al).normal(0f, 1f, 0f));
    endVertex(vc.vertex(mat, bx, by, bz).color(r, g, bl, al).normal(0f, 1f, 0f));
}

    // ---------------------------------------------------------------------
    // VertexConsumer terminal method compatibility
    // ---------------------------------------------------------------------

    // Across mappings/versions, the terminal method for a built vertex differs (e.g., next(), endVertex()).
    // Use reflection so the code compiles even if one of them is absent.
    private static volatile java.lang.reflect.Method VC_NEXT_METHOD;
    private static volatile java.lang.reflect.Method VC_ENDVERTEX_METHOD;

    private static void endVertex(Object maybeVertexConsumer) {
        if (!(maybeVertexConsumer instanceof VertexConsumer)) return;
        VertexConsumer vc = (VertexConsumer) maybeVertexConsumer;

        try {
            // Fast path: cached method
            if (VC_NEXT_METHOD != null) {
                VC_NEXT_METHOD.invoke(vc);
                return;
            }
            if (VC_ENDVERTEX_METHOD != null) {
                VC_ENDVERTEX_METHOD.invoke(vc);
                return;
            }

            // Resolve once
            Class<?> c = vc.getClass();
            try {
                VC_NEXT_METHOD = c.getMethod("next");
                VC_NEXT_METHOD.invoke(vc);
                return;
            } catch (NoSuchMethodException ignored) {
                // fall through
            }

            try {
                VC_ENDVERTEX_METHOD = c.getMethod("endVertex");
                VC_ENDVERTEX_METHOD.invoke(vc);
            } catch (NoSuchMethodException ignored) {
                // If neither exists, do nothing. (Some implementations may flush implicitly.)
            }
        } catch (Throwable ignored) {
            // Rendering must never crash the client.
        }
    }

    // ---------------------------------------------------------------------
    // Rotation + target selection
    // ---------------------------------------------------------------------

    private static float computeDesiredYaw(Vec3d playerPos, BlockPos target) {
        Vec3d t = centerOf(target);
        double dx = t.x - playerPos.x;
        double dz = t.z - playerPos.z;
        return (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
    }

    
private static float computeDesiredYaw(Vec3d playerPos, Vec3d target) {
    double dx = target.x - playerPos.x;
    double dz = target.z - playerPos.z;
    return (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
}

private static float adaptiveYawStep(float yawErrorDeg) {
        float t = MathHelper.clamp(yawErrorDeg / 90.0f, 0.0f, 1.0f);
        return lerp(YAW_STEP_SMOOTH, YAW_STEP_FAST, t);
    }

    
    private void planRotation(float yaw, float pitch) {
    MinecraftClient mc = MinecraftClient.getInstance();
    if (mc == null || mc.player == null) return;

    float cy = mc.player.getYaw();

    // Normalize yaw to the shortest angular target from current yaw.
    float normalizedGoalYaw = cy + MathHelper.wrapDegrees(yaw - cy);

    // If we're already rotating to basically the same yaw/pitch, do not restart the plan.
    if (rotating) {
        float yawDelta = Math.abs(MathHelper.wrapDegrees(normalizedGoalYaw - goalYaw));
        float pitchDelta = Math.abs(pitch - goalPitch);
        if (yawDelta < 1.0f && pitchDelta < 1.0f) return;
    }

    rotating = true;
    startYaw = cy;
    startPitch = mc.player.getPitch();
    goalYaw = normalizedGoalYaw;
    goalPitch = pitch;
    rotT = 0f;

    // Noise computed once per plan; kept small to avoid flick.
    float r = Rotation.getRotationRandomness();
    noiseYaw = ((float) Math.random() - 0.5f) * 2f * r * 0.35f;
    noisePitch = ((float) Math.random() - 0.5f) * 2f * r * 0.25f;
}


    private void updateRotation(MinecraftClient mc) {
        if (!rotating || mc == null || mc.player == null) return;

        if (!Rotation.isBezierRotation()) {
            float maxStep = linearMaxStepPerTick(Rotation.getRotationSpeed());

            float cy = mc.player.getYaw();
            float cp = mc.player.getPitch();

            float dy = MathHelper.wrapDegrees(goalYaw - cy);
            float dp = goalPitch - cp;

            float yawErr = Math.abs(dy);
            float pitchErr = Math.abs(dp);

            if (yawErr <= maxStep && pitchErr <= maxStep) {
                rotating = false;
                mc.player.setYaw(goalYaw + noiseYaw);
                mc.player.setPitch(goalPitch + noisePitch);
                mc.player.setHeadYaw(mc.player.getYaw());
                return;
            }

            float stepYaw = MathHelper.clamp(dy, -maxStep, maxStep);
            float stepPitch = MathHelper.clamp(dp, -maxStep, maxStep);

            float newYaw = cy + stepYaw;
            float newPitch = cp + stepPitch;

            mc.player.setYaw(newYaw);
            mc.player.setPitch(newPitch);
            mc.player.setHeadYaw(newYaw);
            return;
        }

        // Bezier / eased rotation
        float speed = bezierStepPerTick(Rotation.getRotationSpeed(), Rotation.getBezierSpeed());
        rotT = Math.min(1f, rotT + speed);

        float t = rotT;
        float ease = t * t * (3f - 2f * t); // smoothstep

        float newYaw = lerpAngle(startYaw, goalYaw, ease) + noiseYaw * 0.12f;
        float newPitch = MathHelper.lerp(ease, startPitch, goalPitch) + noisePitch * 0.12f;

        mc.player.setYaw(newYaw);
        mc.player.setPitch(newPitch);
        mc.player.setHeadYaw(newYaw);

        if (rotT >= 1f) rotating = false;
    }

    private static float lerpAngle(float a, float b, float t) {
        float d = MathHelper.wrapDegrees(b - a);
        return a + d * t;
    }

    private static float linearMaxStepPerTick(int rotationSpeed) {
        // Faster mapping than the original clamp-step to avoid sluggish/choppy head turns.
        // Treat Rotation.getRotationSpeed() as a user-configured sensitivity and boost it for per-tick use.
        int rs = MathHelper.clamp(rotationSpeed * 4, 0, 1000);
        float t = rs / 1000f;
        return 2.0f + t * 60.0f; // 2..62 deg/tick
    }

    private static float bezierStepPerTick(int rotationSpeed, float bezierMultiplier) {
        int rs = MathHelper.clamp(rotationSpeed * 4, 0, 1000);
        float t = rs / 1000f;
        // Progress per tick; higher base so eased rotation converges quickly.
        float base = 0.035f + (t * 0.140f);
        return base * MathHelper.clamp(bezierMultiplier, 0.1f, 3.0f);
    }


    // LOS steering (corridor-safe)
    private static BlockPos selectLineOfSightTarget(World world, ClientPlayerEntity player,
                                                    List<BlockPos> path, int startIndex) {
        if (world == null || player == null || path == null || path.isEmpty()) return null;

        int start = MathHelper.clamp(startIndex, 0, path.size() - 1);
        int max = Math.min(path.size() - 1, start + LOS_LOOKAHEAD_MAX_NODES);

        Vec3d playerPos = player.getPos();

        BlockPos best = path.get(start);

        for (int i = start; i <= max; i++) {
            BlockPos cand = path.get(i);

            if (!hasBodyLineOfSight(world, player, cand)) break;

            if (!isCorridorSafe(world, playerPos, cand, path, start, i)) break;

            best = cand;
        }

        return best;
    }

    private static boolean isCorridorSafe(World world, Vec3d fromPos, BlockPos cand,
                                          List<BlockPos> path, int startIdx, int candIdx) {
        Vec3d toPos = new Vec3d(cand.getX() + 0.5, fromPos.y, cand.getZ() + 0.5);

        double dx = toPos.x - fromPos.x;
        double dz = toPos.z - fromPos.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1e-6) return true;

        int samples = (int) Math.ceil(dist / CORRIDOR_SAMPLE_STEP);
        for (int s = 1; s <= samples; s++) {
            double t = (double) s / (double) samples;

            double sx = fromPos.x + dx * t;
            double sz = fromPos.z + dz * t;

            BlockPos feet = BlockPos.ofFloored(sx, fromPos.y - 0.01, sz);
            if (!AStar.isStandablePublic(world, feet)) return false;

            if (distanceToPathXZ(sx, sz, path, startIdx, candIdx) > CORRIDOR_MAX_DIST_TO_PATH) return false;
        }

        return true;
    }

    private static double distanceToPathXZ(double x, double z, List<BlockPos> path, int startIdx, int endIdx) {
        int a = MathHelper.clamp(startIdx, 0, path.size() - 1);
        int b = MathHelper.clamp(endIdx, 0, path.size() - 1);
        if (b < a) { int tmp = a; a = b; b = tmp; }

        double best = Double.MAX_VALUE;
        for (int i = a; i <= b; i++) {
            BlockPos p = path.get(i);
            double px = p.getX() + 0.5;
            double pz = p.getZ() + 0.5;
            double dx = px - x;
            double dz = pz - z;
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < best) best = d;
        }
        return best;
    }

    // Body-safe LOS: do not allow "seeing" a node if the feet/body would clip a block.
    // This prevents selecting targets that are visible from the eye but blocked at player height.
    private static boolean hasBodyLineOfSight(World world, ClientPlayerEntity player, BlockPos target) {
        if (world == null || player == null || target == null) return false;

        Vec3d base = player.getPos();
        Vec3d tgt = new Vec3d(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);

        Vec3d srcFeet = new Vec3d(base.x, base.y + 0.15, base.z);
        Vec3d srcMid  = new Vec3d(base.x, base.y + 0.95, base.z);
        Vec3d srcEye  = new Vec3d(base.x, base.y + LOS_EYE_HEIGHT, base.z);

        Vec3d dstFeet = new Vec3d(tgt.x, target.getY() + 0.15, tgt.z);
        Vec3d dstMid  = new Vec3d(tgt.x, target.getY() + 0.95, tgt.z);
        Vec3d dstEye  = new Vec3d(tgt.x, target.getY() + LOS_EYE_HEIGHT, tgt.z);

        return rayClear(world, srcFeet, dstFeet, player)
                && rayClear(world, srcMid,  dstMid,  player)
                && rayClear(world, srcEye,  dstEye,  player);
    }

    private static boolean rayClear(World world, Vec3d from, Vec3d to, ClientPlayerEntity player) {
        RaycastContext ctx = new RaycastContext(
                from, to,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                player
        );
        HitResult hit = world.raycast(ctx);
        // Strict: any hit means the segment is not body-safe.
        // A small distance tolerance here can incorrectly allow aiming "through" thin obstacles.
        return hit.getType() == HitResult.Type.MISS;
    }

    private static int indexOfNode(List<BlockPos> path, BlockPos node, int a, int b) {
        int start = Math.max(0, a);
        int end = Math.min(path.size() - 1, b);
        for (int i = start; i <= end; i++) {
            if (path.get(i).equals(node)) return i;
        }
        return -1;
    }

    // ---------------------------------------------------------------------
    // AoTE
    // ---------------------------------------------------------------------

    private void tryUseAote(MinecraftClient client, ClientPlayerEntity player, float yawErrorDeg) {
        if (client == null || player == null || client.world == null) return;
        if (client.interactionManager == null) return;
        if (aoteCooldownTicks > 0) return;
        if (Math.abs(yawErrorDeg) > AOTE_MAX_YAW_ERROR_DEG) return;

        int slot = findHotbarSlotByName(player, AOTE_NAME);
        if (slot < 0) return;

        if (currentPath == null || currentPath.isEmpty()) return;

        int lookIdx = Math.min(currentPath.size() - 1, pathNodeIndex + AOTE_FORWARD_LOOKAHEAD_NODES);
        Vec3d lookTarget = centerOf(currentPath.get(lookIdx));
        if (distanceXZ(player.getPos(), lookTarget) < AOTE_MIN_DIST_TO_USE) return;

        Vec3d eye = player.getPos().add(0.0, LOS_EYE_HEIGHT, 0.0);
        Vec3d end = eye.add(player.getRotationVec(1.0f).multiply(AOTE_RAYCAST_RANGE));

        RaycastContext ctx = new RaycastContext(
                eye, end,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                player
        );
        HitResult hit = client.world.raycast(ctx);
        if (hit.getType() == HitResult.Type.BLOCK) {
            double d2 = hit.getPos().squaredDistanceTo(eye);
            if (d2 < (3.0 * 3.0)) return;
        }

        Object inv = player.getInventory();
        int prev = InventoryReflect.getSelectedSlot(inv);
        if (prev == Integer.MIN_VALUE) return;

        if (!InventoryReflect.setSelectedSlot(inv, slot)) return;

        client.interactionManager.interactItem(player, Hand.MAIN_HAND);

        InventoryReflect.setSelectedSlot(inv, prev);
        aoteCooldownTicks = AOTE_COOLDOWN_TICKS;
    }

    private static int findHotbarSlotByName(ClientPlayerEntity player, String name) {
        if (player == null) return -1;
        for (int i = 0; i < 9; i++) {
            var stack = player.getInventory().getStack(i);
            if (stack == null || stack.isEmpty()) continue;
            String display = stack.getName().getString();
            if (display != null && display.equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    private static final class InventoryReflect {
        private static Method GET_SELECTED = null;
        private static Method SET_SELECTED = null;
        private static Field FIELD_SELECTED = null;
        private static boolean INIT = false;

        private static void init(Object inv) {
            if (INIT) return;
            INIT = true;
            Class<?> c = inv.getClass();
            try { GET_SELECTED = c.getMethod("getSelectedSlot"); } catch (Throwable ignored) {}
            try { SET_SELECTED = c.getMethod("setSelectedSlot", int.class); } catch (Throwable ignored) {}
            try {
                FIELD_SELECTED = c.getDeclaredField("selectedSlot");
                FIELD_SELECTED.setAccessible(true);
            } catch (Throwable ignored) {
                FIELD_SELECTED = null;
            }
        }

        static int getSelectedSlot(Object inv) {
            if (inv == null) return Integer.MIN_VALUE;
            init(inv);
            try {
                if (GET_SELECTED != null) {
                    Object v = GET_SELECTED.invoke(inv);
                    if (v instanceof Integer) return (Integer) v;
                }
            } catch (Throwable ignored) {}
            try {
                if (FIELD_SELECTED != null) return FIELD_SELECTED.getInt(inv);
            } catch (Throwable ignored) {}
            return Integer.MIN_VALUE;
        }

        static boolean setSelectedSlot(Object inv, int slot) {
            if (inv == null) return false;
            init(inv);
            try {
                if (SET_SELECTED != null) {
                    SET_SELECTED.invoke(inv, slot);
                    return true;
                }
            } catch (Throwable ignored) {}
            try {
                if (FIELD_SELECTED != null) {
                    FIELD_SELECTED.setInt(inv, slot);
                    return true;
                }
            } catch (Throwable ignored) {}
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Start snapping + path checks
    // ---------------------------------------------------------------------

    private static BlockPos feetBlock(ClientPlayerEntity player) {
        Vec3d p = player.getPos();
        return BlockPos.ofFloored(p.x, p.y - 0.01, p.z);
    }

    private static BlockPos snapStartToStandable(World world, BlockPos feet) {
        if (world == null) return null;

        if (START_SNAP_ALLOW_DOWN_FIRST) {
            for (int dy = 0; dy <= START_SNAP_MAX_VERTICAL; dy++) {
                BlockPos cand = feet.down(dy);
                if (AStar.isStandablePublic(world, cand)) return cand;
            }
            for (int dy = 1; dy <= START_SNAP_MAX_VERTICAL; dy++) {
                BlockPos cand = feet.up(dy);
                if (AStar.isStandablePublic(world, cand)) return cand;
            }
        } else {
            for (int dy = 0; dy <= START_SNAP_MAX_VERTICAL; dy++) {
                BlockPos cand = feet.up(dy);
                if (AStar.isStandablePublic(world, cand)) return cand;
            }
            for (int dy = 1; dy <= START_SNAP_MAX_VERTICAL; dy++) {
                BlockPos cand = feet.down(dy);
                if (AStar.isStandablePublic(world, cand)) return cand;
            }
        }
        return null;
    }

    private static boolean isAtGoalXZ(World world, ClientPlayerEntity player, BlockPos goal) {
        if (world == null || player == null || goal == null) return false;

        Vec3d pos = player.getPos();
        if (distanceXZ(pos, centerOf(goal)) > CHECKPOINT_REACH_DIST) return false;

        // Require feet height agreement with the walk surface at the goal node.
        double feetY = player.getBoundingBox().minY;
        double goalFloor = getWalkSurfaceY(world, goal);
        return Math.abs(feetY - goalFloor) <= CHECKPOINT_FEET_Y_EPS;
    }

    /**
     * Stricter "stop/arrived" check:
     * - Requires XZ proximity, AND
     * - Requires the player's feet Y to match the goal block's Y within a small epsilon.
     *
     * This prevents false "Arrived" when the player is directly under an elevated goal.
     */
    private static boolean isAtStopGoal(ClientPlayerEntity player, BlockPos goal) {
        if (player == null || goal == null) return false;
        Vec3d pos = player.getPos();
        double xz = distanceXZ(pos, centerOf(goal));
        if (xz > STOP_GOAL_DIST) return false;

        double feetY = player.getBoundingBox().minY;
        double dy = Math.abs(feetY - (double) goal.getY());
        return dy <= STOP_GOAL_FEET_Y_EPS;
    }

    /** Returns true if the path makes a sharp turn in XZ at cur (angle >= thresholdDeg). */
    private static boolean isSharpTurnXZ(BlockPos prev, BlockPos cur, BlockPos next, double thresholdDeg) {
        if (prev == null || cur == null || next == null) return false;
        Vec3d a = new Vec3d(cur.getX() - prev.getX(), 0.0, cur.getZ() - prev.getZ());
        Vec3d b = new Vec3d(next.getX() - cur.getX(), 0.0, next.getZ() - cur.getZ());
        if (a.lengthSquared() < 1e-6 || b.lengthSquared() < 1e-6) return false;
        a = a.normalize();
        b = b.normalize();
        double dot = MathHelper.clamp(a.dotProduct(b), -1.0, 1.0);
        double ang = Math.toDegrees(Math.acos(dot));
        return ang >= thresholdDeg;
    }

    private static boolean isOffPath(Vec3d playerPos, List<BlockPos> path, int idx, double maxDist) {
        if (path == null || path.isEmpty()) return true;

        int i = MathHelper.clamp(idx, 0, path.size() - 1);
        int end = Math.min(path.size() - 1, i + 8);

        double best = Double.MAX_VALUE;
        for (int k = i; k <= end; k++) {
            double d = distanceXZ(playerPos, centerOf(path.get(k)));
            if (d < best) best = d;
        }
        return best > maxDist;
    }

    // ---------------------------------------------------------------------
    // Checkpoints
    // ---------------------------------------------------------------------

    private BlockPos getCurrentCheckpointGoal(BlockPos segmentEnd) {
        if (segmentEnd == null) return null;
        if (checkpointIndex < routeCheckpoints.size()) return routeCheckpoints.get(checkpointIndex);
        return segmentEnd;
    }

    private static List<BlockPos> generateSparseCheckpointsFromRawPath(List<BlockPos> raw, BlockPos end) {
        if (raw.size() < 3) return new ArrayList<>();

        List<BlockPos> cps = new ArrayList<>();
        BlockPos prev = raw.get(0);
        int lastDx = 0, lastDy = 0, lastDz = 0;
        int nodesSinceLastCp = 0;

        for (int i = 1; i < raw.size() - 1; i++) {
            BlockPos cur = raw.get(i);

            int dx = Integer.signum(cur.getX() - prev.getX());
            int dy = Integer.signum(cur.getY() - prev.getY());
            int dz = Integer.signum(cur.getZ() - prev.getZ());

            boolean turn = CHECKPOINT_ON_TURN && (i > 1) && (dx != lastDx || dy != lastDy || dz != lastDz);
            boolean spaced = (i % CHECKPOINT_EVERY_N_NODES) == 0;

            nodesSinceLastCp++;

            if ((turn || spaced) && nodesSinceLastCp >= MIN_NODES_BETWEEN_CHECKPOINTS) {
                cps.add(cur);
                nodesSinceLastCp = 0;
            }

            lastDx = dx;
            lastDy = dy;
            lastDz = dz;
            prev = cur;
        }

        cps.removeIf(p -> p.equals(end));

        if (cps.size() > MAX_CHECKPOINTS_CAP) {
            List<BlockPos> down = new ArrayList<>(MAX_CHECKPOINTS_CAP);
            double step = (double) cps.size() / MAX_CHECKPOINTS_CAP;
            for (int i = 0; i < MAX_CHECKPOINTS_CAP; i++) {
                down.add(cps.get((int) Math.floor(i * step)));
            }
            cps = down;
        }

        return cps;
    }

    // ---------------------------------------------------------------------
    // Reset + key release
    // ---------------------------------------------------------------------

    private void resetPathState() {
        avoidedStepUpEdges.clear();
        clearStepUpTracking();
        forceRepathNow = false;
        currentPath = Collections.emptyList();
        pathNodeIndex = 0;
        currentWalkTarget = null;
        currentSegmentGoal = null;

        ticksSinceLastPath = 0;
        offPathRepathCooldown = 0;

        jumpHoldTicks = 0;
        jumpCooldownTicks = 0;

        aoteCooldownTicks = 0;
        brakeTapTicksRemaining = 0;
    }

    private static void releaseKeys() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null) return;

        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sneakKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
    }

    // ---------------------------------------------------------------------
    // Utils
    // ---------------------------------------------------------------------

    private static BlockPos blockFromCenter(Vec3d center) {
        return new BlockPos(MathHelper.floor(center.x), MathHelper.floor(center.y), MathHelper.floor(center.z));
    }

    private static Vec3d centerOf(BlockPos p) {
        return new Vec3d(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
    }

    private static double distanceXZ(Vec3d a, Vec3d b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Compatibility helper: some mappings/versions do not expose Box#squaredDistanceTo(Vec3d).
     * Returns the squared distance from a point to the box (0 if inside).
     */
    private static double squaredDistanceTo(Box box, Vec3d p) {
        double cx = MathHelper.clamp(p.x, box.minX, box.maxX);
        double cy = MathHelper.clamp(p.y, box.minY, box.maxY);
        double cz = MathHelper.clamp(p.z, box.minZ, box.maxZ);
        double dx = p.x - cx;
        double dy = p.y - cy;
        double dz = p.z - cz;
        return dx * dx + dy * dy + dz * dz;
    }

    private static int manhattan(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX())
                + Math.abs(a.getY() - b.getY())
                + Math.abs(a.getZ() - b.getZ());
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    // ---------------------------------------------------------------------
    // A* (diagonal corner checks + wall penalty)
    // ---------------------------------------------------------------------

    private static final class AStar {

        private static final class Node {
            final BlockPos pos;
            final Node parent;
            final double g;
            final double f;

            Node(BlockPos pos, Node parent, double g, double f) {
                this.pos = pos;
                this.parent = parent;
                this.g = g;
                this.f = f;
            }
        }

        private static final class Neighbor {
            final BlockPos pos;
            final double cost;
            Neighbor(BlockPos pos, double cost) { this.pos = pos; this.cost = cost; }
        }

        static List<BlockPos> find(World world,
                                   BlockPos start,
                                   BlockPos goal,
                                   int maxRange,
                                   int maxIterations,
                                   boolean diagonals,
                                   boolean stepUp,
                                   boolean allowDropDown,
                                   int maxDropDown) {

            if (world == null) return Collections.emptyList();
            if (manhattan(start, goal) > maxRange * 2) return Collections.emptyList();
            if (!isStandable(world, start) || !isStandable(world, goal)) return Collections.emptyList();

            PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
            Map<BlockPos, Node> best = new HashMap<>();
            Set<BlockPos> closed = new HashSet<>();

            Node s = new Node(start, null, 0.0, heuristic(start, goal));
            open.add(s);
            best.put(start, s);

            int it = 0;
            while (!open.isEmpty() && it++ < maxIterations) {
                Node cur = open.poll();
                if (cur.pos.equals(goal)) return reconstruct(cur);

                if (!closed.add(cur.pos)) continue;

                if (manhattan(start, cur.pos) > maxRange) continue;

                for (Neighbor nb : neighbors(world, cur.pos, diagonals, stepUp, allowDropDown, maxDropDown)) {
                    if (closed.contains(nb.pos)) continue;

                    double ng = cur.g + nb.cost + wallPenalty(world, nb.pos);

                    Node prev = best.get(nb.pos);
                    if (prev == null || ng < prev.g) {
                        Node nxt = new Node(nb.pos, cur, ng, ng + heuristic(nb.pos, goal));
                        best.put(nb.pos, nxt);
                        open.add(nxt);
                    }
                }
            }

            return Collections.emptyList();
        }

        private static List<BlockPos> reconstruct(Node goal) {
            LinkedList<BlockPos> out = new LinkedList<>();
            Node c = goal;
            while (c != null) {
                out.addFirst(c.pos);
                c = c.parent;
            }
            return out;
        }

        private static double heuristic(BlockPos a, BlockPos b) {
            int dx = Math.abs(a.getX() - b.getX());
            int dy = Math.abs(a.getY() - b.getY());
            int dz = Math.abs(a.getZ() - b.getZ());
            return dx + dz + (dy * 1.25);
        }

        private static double wallPenalty(World world, BlockPos p) {
            final double perSide = 0.20; // modest: reduces wall hugging without extreme detours

            int solids = 0;
            BlockPos n = p.north();
            BlockPos s = p.south();
            BlockPos e = p.east();
            BlockPos w = p.west();

            if (!world.getBlockState(n).getCollisionShape(world, n).isEmpty()) solids++;
            if (!world.getBlockState(s).getCollisionShape(world, s).isEmpty()) solids++;
            if (!world.getBlockState(e).getCollisionShape(world, e).isEmpty()) solids++;
            if (!world.getBlockState(w).getCollisionShape(world, w).isEmpty()) solids++;

            return solids * perSide;
        }

        private static List<Neighbor> neighbors(World world, BlockPos o,
                                                boolean diagonals,
                                                boolean stepUp,
                                                boolean allowDropDown,
                                                int maxDropDown) {
            List<Neighbor> out = new ArrayList<>(diagonals ? 24 : 12);

            int[][] deltas4 = new int[][] { {1,0},{-1,0},{0,1},{0,-1} };
            int[][] deltas8 = new int[][] { {1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1} };
            int[][] deltas = diagonals ? deltas8 : deltas4;

            for (int[] d : deltas) {
                int dx = d[0], dz = d[1];
                boolean diag = (dx != 0 && dz != 0);

                if (diag && !canDiagonal(world, o, dx, dz)) continue;

                BlockPos same = o.add(dx, 0, dz);
                if (isStandable(world, same)) {
                    out.add(new Neighbor(same, diag ? 1.4142 : 1.0));
                    continue;
                }

                if (stepUp) {
                    BlockPos up = o.add(dx, 1, dz);
                    if (isStandable(world, up)) {
                        // Avoid step-ups that have been observed to fail repeatedly.
                        if (!PathfindScript.isStepUpEdgeAvoided(o, up)) {
                            out.add(new Neighbor(up, diag ? 1.9 : 1.5));
                        }
                        continue;
                    }
                }

                if (allowDropDown) {
                    for (int drop = 1; drop <= Math.max(1, maxDropDown); drop++) {
                        BlockPos down = o.add(dx, -drop, dz);
                        if (!isStandable(world, down)) continue;
                        if (!hasEscapeBackUp(world, down, o.getY())) continue;

                        double cost = (diag ? 1.4142 : 1.0) + (drop * 0.55);
                        out.add(new Neighbor(down, cost));
                        break;
                    }
                }
            }

            return out;
        }

        private static boolean canDiagonal(World world, BlockPos o, int dx, int dz) {
            BlockPos a = o.add(dx, 0, 0);
            BlockPos b = o.add(0, 0, dz);

            return isBodyPassable(world, a) && isHeadPassable(world, a.up())
                    && isBodyPassable(world, b) && isHeadPassable(world, b.up());
        }

        private static boolean hasEscapeBackUp(World world, BlockPos start, int originY) {
            if (start.getY() >= originY) return true;

            ArrayDeque<BlockPos> q = new ArrayDeque<>();
            HashSet<BlockPos> seen = new HashSet<>();

            q.add(start);
            seen.add(start);

            int expanded = 0;
            while (!q.isEmpty() && expanded < DROP_ESCAPE_SEARCH_LIMIT) {
                BlockPos cur = q.poll();
                expanded++;

                if (cur.getY() >= originY) return true;
                if (manhattan(cur, start) > DROP_ESCAPE_MAX_RADIUS) continue;

                for (int[] d : new int[][] { {1,0},{-1,0},{0,1},{0,-1} }) {
                    int dx = d[0], dz = d[1];
                    enqueueIfOk(world, cur.add(dx, 0, dz), seen, q);
                    enqueueIfOk(world, cur.add(dx, 1, dz), seen, q);
                    enqueueIfOk(world, cur.add(dx, -1, dz), seen, q);
                }
            }

            return false;
        }

        private static void enqueueIfOk(World world, BlockPos pos, Set<BlockPos> seen, ArrayDeque<BlockPos> q) {
            if (seen.contains(pos)) return;
            if (!isStandable(world, pos)) return;
            seen.add(pos);
            q.add(pos);
        }

        private static boolean isStandable(World world, BlockPos pos) {
            if (AVOID_FLUIDS && containsFluid(world, pos)) return false;

            if (!isBodyPassable(world, pos)) return false;
            if (!isHeadPassable(world, pos.up())) return false;

            if (AVOID_FLUIDS && containsFluid(world, pos.up())) return false;

            return hasFloor(world, pos);
        }

        static boolean isStandablePublic(World world, BlockPos pos) {
            return isStandable(world, pos);
        }

        private static boolean hasFloor(World world, BlockPos feetPos) {
            if (!world.getBlockState(feetPos).getCollisionShape(world, feetPos).isEmpty()) {
                if (AVOID_FLUIDS && containsFluid(world, feetPos)) return false;
                return true;
            }

            BlockPos below = feetPos.down();
            if (AVOID_FLUIDS && containsFluid(world, below)) return false;

            return !world.getBlockState(below).getCollisionShape(world, below).isEmpty();
        }

        private static boolean isBodyPassable(World world, BlockPos pos) {
            VoxelShape shape = world.getBlockState(pos).getCollisionShape(world, pos);
            if (shape.isEmpty()) return true;

            // Top-half blocks (e.g., upside-down stairs/top slabs) often have minY around 0.5.
            // Those are NOT passable for the player's feet volume.
            double minY = shape.getMin(Direction.Axis.Y);
            double maxY = shape.getMax(Direction.Axis.Y);

            if (minY > 0.20) return false;

            // Allow low-profile collision in the feet block (bottom slabs, carpet, etc.)
            return maxY <= BODY_CLEARANCE_MIN_Y;
        }

        private static boolean isHeadPassable(World world, BlockPos pos) {
            return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
        }

        private static boolean containsFluid(World world, BlockPos pos) {
            return !world.getFluidState(pos).isEmpty();
        }
    }
}
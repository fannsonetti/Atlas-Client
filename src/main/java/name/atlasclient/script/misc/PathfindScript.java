package name.atlasclient.script.misc;

import name.atlasclient.script.Script;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.debug.DebugRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
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
    @Override public void setEnabled(boolean enabled) { this.enabled = enabled; }

    private static PathfindScript ACTIVE_INSTANCE = null;

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

    // Only stop near the real goal
    private static final double STOP_GOAL_DIST = 0.95;

    // Require vertical agreement before declaring "stop/arrived".
    // This prevents the macro from stopping under an elevated target (common when a ledge fall occurs).
    private static final double STOP_GOAL_FEET_Y_EPS = 0.35;

    // Repath
    private static final int REPATH_EVERY_TICKS = 40;
    private static final int OFFPATH_REPATH_COOLDOWN_TICKS = 35;

    // A*
    private static final int MAX_ITERATIONS = 40_000;
    private static final int MAX_RANGE = 170;

    // Macro segments
    private static final int MACRO_STEP = 155;
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
    private static final boolean RENDER_ONE_Y_LOWER = false;

    private static final float WHITE_R = 1.0f, WHITE_G = 1.0f, WHITE_B = 1.0f, WHITE_A = 0.60f;
    private static final float GOAL_R  = 0.20f, GOAL_G  = 1.00f, GOAL_B  = 0.20f, GOAL_A  = 0.75f;
    private static final float DEST_R  = 1.00f, DEST_G  = 0.15f, DEST_B  = 0.15f, DEST_A  = 0.75f;

    // Visual overlay: target is ~1.01x a block (avoid 1.1x-1.2x appearance).
    // Box.expand(e) expands on both sides: final size = 1.0 + 2e.
    // 1.0 + 2e = 1.01 => e = 0.005.
    private static final double BOX_EXPAND = 0.005;

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

    // Carrot lookahead (forgiving "next path part")
    private static final int CARROT_NODES = 4;

    // AoTE
    private static final String AOTE_NAME = "Aspect of the End";
    private static final int AOTE_FORWARD_LOOKAHEAD_NODES = 6;
    private static final double AOTE_MIN_DIST_TO_USE = 7.2;
    private static final double AOTE_RAYCAST_RANGE = 8.6;
    private static final int AOTE_COOLDOWN_TICKS = 14;
    private static final float AOTE_MAX_YAW_ERROR_DEG = 10.0f;

    // ---------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------

    private Vec3d finalTargetCenter = DEFAULT_TARGET_CENTER;
    private BlockPos finalTargetBlock = blockFromCenter(DEFAULT_TARGET_CENTER);

    private Vec3d macroGoalCenter = null;
    private BlockPos macroGoalBlock = null;

    private List<BlockPos> routeCheckpoints = new ArrayList<>();
    private int checkpointIndex = 0;
    private boolean checkpointsReady = false;

    private List<BlockPos> currentPath = Collections.emptyList();
    private int pathNodeIndex = 0;

    private BlockPos currentWalkTarget = null;
    private BlockPos currentSegmentGoal = null;

    private int ticksSinceLastPath = 0;
    private int offPathRepathCooldown = 0;

    private Runnable onArrived = null;

    private static boolean RENDER_HOOK_REGISTERED = false;

    private int jumpHoldTicks = 0;
    private int jumpCooldownTicks = 0;
    private int jumpGraceTicks = 0;

    // Step-up tracking (to detect repeated failed jump attempts and force a longer alternative path)
    private BlockPos trackedStepLead = null;
    private BlockPos trackedUpNode = null;
    private int trackedStepFailCount = 0;
    private int trackedStepAttemptTimer = 0;
    private boolean forceRepathNow = false;
    private final Set<Long> avoidedStepUpEdges = new HashSet<>();

    private int aoteCooldownTicks = 0;

    // Key stability
    private boolean lastForward = false;

    // ---------------------------------------------------------------------
    // Script lifecycle
    // ---------------------------------------------------------------------

    @Override
    public void onEnable(MinecraftClient client) {
        this.enabled = true;
        ACTIVE_INSTANCE = this;
        ensureRenderHook();

        resetPathState();
        checkpointsReady = false;

        jumpGraceTicks = JUMP_GRACE_TICKS_ON_START;
        jumpHoldTicks = 0;
        jumpCooldownTicks = 0;

        aoteCooldownTicks = 0;
        lastForward = false;

        if (client != null && client.player != null) {
            client.player.sendMessage(Text.literal("PathfindScript enabled."), false);
        }
    }

    @Override
    public void onDisable() {
        this.enabled = false;
        if (ACTIVE_INSTANCE == this) ACTIVE_INSTANCE = null;

        releaseKeys();
        resetPathState();

        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null) {
            client.player.sendMessage(Text.literal("PathfindScript disabled."), false);
        }
    }

    // ---------------------------------------------------------------------
    // Tick
    // ---------------------------------------------------------------------

    @Override
    public void onTick(MinecraftClient client) {
        if (!enabled) return;
        if (client == null || client.player == null || client.world == null || client.options == null) return;

        boolean desiredForward = lastForward; // sticky: do not drop W on one bad tick
        boolean desiredJump = false;

        try {
            ClientPlayerEntity player = client.player;
            World world = client.world;

            if (!isNavigating()) {
                desiredForward = false;
                lastForward = false;
                currentWalkTarget = null;
                currentSegmentGoal = null;
                return;
            }

            if (offPathRepathCooldown > 0) offPathRepathCooldown--;
            if (aoteCooldownTicks > 0) aoteCooldownTicks--;

            // Snap start
            BlockPos start = snapStartToStandable(world, feetBlock(player));
            if (start == null) return;

            // Determine macro segment end (may be final)
            BlockPos segmentEnd = ensureMacroGoal(world, start, finalTargetBlock);
            if (segmentEnd == null) return;

            // Segment complete?
            // For the final target, do NOT use the forgiving XZ-only check; require vertical agreement.
            boolean segmentDone = segmentEnd.equals(finalTargetBlock)
                    ? isAtStopGoal(player, segmentEnd)
                    : isAtGoalXZ(player.getPos(), segmentEnd);

            if (segmentDone) {
                if (segmentEnd.equals(finalTargetBlock)) {
                    finishRun(client);
                    desiredForward = false;
                    lastForward = false;
                    return;
                }
                // Advance macro segment
                macroGoalBlock = null;
                macroGoalCenter = null;

                routeCheckpoints.clear();
                checkpointIndex = 0;
                checkpointsReady = false;

                resetPathState();
                desiredForward = false;
                lastForward = false;
                return;
            }

            // Build checkpoints + initial path for this segment
            if (!checkpointsReady) {
                List<BlockPos> segPath = AStar.find(world, start, segmentEnd,
                        MAX_RANGE, MAX_ITERATIONS,
                        ALLOW_DIAGONALS, ALLOW_STEP_UP, ALLOW_DROP_DOWN, MAX_DROP_DOWN);

                if (segPath.isEmpty()) return;

                routeCheckpoints = generateSparseCheckpointsFromRawPath(segPath, segmentEnd);
                checkpointIndex = 0;
                checkpointsReady = true;

                currentPath = segPath;
                pathNodeIndex = 0;
                ticksSinceLastPath = 0;

                // Seed walk target
                currentWalkTarget = currentPath.get(Math.min(1, currentPath.size() - 1));
            }

            // Determine checkpoint goal inside this segment
            BlockPos checkpointGoal = getCurrentCheckpointGoal(segmentEnd);
            currentSegmentGoal = checkpointGoal;

            // If we reached current checkpoint, advance
            if (checkpointGoal != null && isAtGoalXZ(player.getPos(), checkpointGoal)) {
                checkpointIndex++;
                ticksSinceLastPath = REPATH_EVERY_TICKS; // encourage a refresh after checkpoint
                checkpointGoal = getCurrentCheckpointGoal(segmentEnd);
                currentSegmentGoal = checkpointGoal;
            }

            // Repath logic (less twitchy)
            ticksSinceLastPath++;
            boolean needRepath = forceRepathNow || currentPath.isEmpty() || ticksSinceLastPath >= REPATH_EVERY_TICKS;

            if (!needRepath && offPathRepathCooldown == 0
                    && isOffPath(player.getPos(), currentPath, pathNodeIndex, OFFPATH_MAX_DIST)) {
                needRepath = true;
                offPathRepathCooldown = OFFPATH_REPATH_COOLDOWN_TICKS;
            }

            if (needRepath) {
                forceRepathNow = false;
                ticksSinceLastPath = 0;

                BlockPos segStart = snapStartToStandable(world, feetBlock(player));
                if (segStart == null) return;

                BlockPos goal = (currentSegmentGoal != null) ? currentSegmentGoal : segmentEnd;

                List<BlockPos> newPath = AStar.find(world, segStart, goal,
                        MAX_RANGE, MAX_ITERATIONS,
                        ALLOW_DIAGONALS, ALLOW_STEP_UP, ALLOW_DROP_DOWN, MAX_DROP_DOWN);

                if (!newPath.isEmpty()) {
                    currentPath = newPath;
                    pathNodeIndex = 0;
                    currentWalkTarget = currentPath.get(Math.min(1, currentPath.size() - 1));
                }
            }

            if (currentPath.isEmpty()) return;

            // (CRITICAL) Progress index: monotonic + pass-based advance (clamped on step-ups)
            updatePathIndexForgiving(player.getPos());

            // Corner-cut prevention: if the next segment is a sharp turn (or a pending step-up),
            // do not aim multiple nodes ahead until we are close to the corner/ledge.
            // This reduces premature yaw changes that can cause the player to "jump off" edges.
            boolean lockToCurrentForTurn = false;
            if (currentPath.size() >= 3 && pathNodeIndex + 1 < currentPath.size()) {
                BlockPos prevN = currentPath.get(Math.max(0, pathNodeIndex - 1));
                BlockPos curN  = currentPath.get(pathNodeIndex);
                BlockPos nextN = currentPath.get(pathNodeIndex + 1);
                boolean stepUpPending = nextN.getY() > curN.getY() && player.getBoundingBox().minY < (double) nextN.getY() - 0.01;

                if (stepUpPending || isSharpTurnXZ(prevN, curN, nextN, 35.0)) {
                    double nearCur = distanceXZ(player.getPos(), centerOf(curN));
                    lockToCurrentForTurn = nearCur > 0.55;
                }
            }

            // Carrot target (forgiving "next path part")
            int carrotBase = lockToCurrentForTurn ? pathNodeIndex : (pathNodeIndex + CARROT_NODES);
            int carrotIdx = Math.min(currentPath.size() - 1, carrotBase);
            BlockPos carrot = currentPath.get(carrotIdx);

            // LOS target, but only if it is as-far-or-farther than carrot
            BlockPos los = selectLineOfSightTarget(world, player, currentPath, pathNodeIndex);
            BlockPos controlTarget = carrot;

            if (los != null) {
                int losIdx = indexOfNode(currentPath, los, pathNodeIndex,
                        Math.min(currentPath.size() - 1, pathNodeIndex + LOS_LOOKAHEAD_MAX_NODES));
                if (losIdx >= carrotIdx) controlTarget = los;
            }

            currentWalkTarget = controlTarget;

            // Rotate
            float desiredYaw = computeDesiredYaw(player.getPos(), controlTarget);
            float yawError = Math.abs(MathHelper.wrapDegrees(desiredYaw - player.getYaw()));
            float yawStep = adaptiveYawStep(yawError);
            setPlayerYawSmooth(player, desiredYaw, yawStep);

            // Forward: do NOT stop per-node; stop only near checkpoint/segment/final goal
            BlockPos stopGoal = (currentSegmentGoal != null) ? currentSegmentGoal : segmentEnd;
            desiredForward = !isAtStopGoal(player, stopGoal);

            // Jump: conservative (only when required) - looks ahead for upcoming step-up
            desiredJump = computeDesiredJump(client, player);

            // AoTE (optional) - only if moving and reasonably aligned
            if (desiredForward) {
                tryUseAote(client, player, yawError);
            }

        } finally {
            client.options.forwardKey.setPressed(desiredForward);
            client.options.backKey.setPressed(false);
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
            client.options.sprintKey.setPressed(false);
            client.options.jumpKey.setPressed(desiredJump);

            lastForward = desiredForward;
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

    private void updatePathIndexForgiving(Vec3d playerPos) {
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

            boolean stepUp = next.getY() > cur.getY();

            // IMPORTANT: never advance onto the "up" node until we are actually up.
            // Otherwise, the walker thinks it is already one block ahead and will not
            // trigger jump/step logic while still pressed against the ledge.
            if (stepUp) {
                // Require that our feet are effectively on the higher block.
                // (Player Y is continuous; use a small tolerance.)
                boolean actuallyUp = playerPos.y >= (double) next.getY() - 0.05;
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
    
        // Success: we reached (or exceeded) the destination Y-level
        if (player.getBlockPos().getY() >= trackedUpNode.getY()) {
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

        // Look ahead for the next upward step so we do not miss jumps if the index advances early.
        BlockPos stepLead = null;
        int maxLook = Math.min(currentPath.size() - 2, pathNodeIndex + 5);

        for (int i = pathNodeIndex; i <= maxLook; i++) {
            BlockPos a = currentPath.get(i);
            BlockPos b = currentPath.get(i + 1);
            if (b.getY() > a.getY()) {
                stepLead = a; // block BEFORE the step-up
                break;
            }
        }

        if (stepLead == null) return false;

        // Must actually be near the step lead-in, otherwise do not jump yet.
        // At higher horizontal speeds, allow triggering slightly earlier to compensate for overshooting.
        double speedXZ = new Vec3d(player.getVelocity().x, 0.0, player.getVelocity().z).length();
        double leadProximity = 0.95 + MathHelper.clamp(speedXZ * 1.6, 0.0, 0.60);
        if (distanceXZ(player.getPos(), centerOf(stepLead)) > leadProximity) return false;

        // Additional sanity: if the step-up target is not above our feet Y, do not jump.
        // (This protects against weird paths when snapped Y differs slightly.)
        BlockPos upNode = null;
        int stepLeadIdx = indexOfNode(currentPath, stepLead, pathNodeIndex, Math.min(currentPath.size() - 1, pathNodeIndex + 6));
        if (stepLeadIdx >= 0 && stepLeadIdx + 1 < currentPath.size()) {
            BlockPos up = currentPath.get(stepLeadIdx + 1);
            if (up.getY() <= py) return false;
            upNode = up;

            // If we previously failed this specific step-up multiple times, avoid it and repath.
            if (isStepUpEdgeAvoided(stepLead, upNode)) {
                forceRepathNow = true;
                return false;
            }
        } else {
            return false;
        }

        World world = client.world;

        // Robust "need-to-jump" detection for 1-block step-ups:
        // - We are near the lead-in (checked above)
        // - The next node is exactly one block higher (typical ledge step)
        // - We are not yet actually on the higher level
        // - There is clearance at the destination (feet + head)
        //
        // This intentionally does NOT rely on the player's current yaw/rotation vector,
        // because being pinned on corners (walls/fences/top-slabs) can desync facing from the true ledge.
        double feetY = player.getBoundingBox().minY;
        if (feetY >= (double) upNode.getY() - 0.01) return false;

        if (upNode.getY() != stepLead.getY() + 1) return false;

        // The solid ledge block you're trying to climb is at (upNode.down()).
        BlockPos ledgeBlock = upNode.down();

        // Destination spaces that must be empty/passable.
        BlockPos destFeet = upNode;
        BlockPos destHead = upNode.up();

        VoxelShape destFeetShape = world.getBlockState(destFeet).getCollisionShape(world, destFeet);
        VoxelShape destHeadShape = world.getBlockState(destHead).getCollisionShape(world, destHead);

        if (!destFeetShape.isEmpty()) return false;
        if (!destHeadShape.isEmpty()) return false;

        // If there is no ledge block, this is not a step-up into a solid face.
        if (world.getBlockState(ledgeBlock).getCollisionShape(world, ledgeBlock).isEmpty()) return false;

        // Ensure we are generally moving toward the step direction (avoid jumping in place).
        Vec3d toUp = centerOf(upNode).subtract(player.getPos());
        Vec3d horiz = new Vec3d(toUp.x, 0.0, toUp.z);
        if (horiz.lengthSquared() < 1e-6) return false;
        horiz = horiz.normalize();
        Vec3d forward = player.getRotationVec(1.0f);
        Vec3d forwardXZ = new Vec3d(forward.x, 0.0, forward.z);
        if (forwardXZ.lengthSquared() > 1e-6) {
            forwardXZ = forwardXZ.normalize();
            if (forwardXZ.dotProduct(horiz) < -0.10) return false;
        }

        onStepUpAttemptStarted(stepLead, upNode);
        jumpHoldTicks = JUMP_HOLD_TICKS;
        jumpCooldownTicks = JUMP_COOLDOWN_TICKS;
        return true;
    }

    // ---------------------------------------------------------------------
    // Macro goal
    // ---------------------------------------------------------------------

    private BlockPos ensureMacroGoal(World world, BlockPos start, BlockPos finalGoal) {
        int distMan = manhattan(start, finalGoal);

        if (distMan <= MAX_RANGE) {
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

        double step = Math.min((double) MACRO_STEP, (double) (MAX_RANGE - 20));
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

        if (inst.finalTargetBlock != null) {
            drawBoxAt(renderPos(inst.finalTargetBlock), matrices, consumers, cx, cy, cz,
                    DEST_R, DEST_G, DEST_B, DEST_A);
        }

        if (inst.currentSegmentGoal != null) {
            drawBoxAt(renderPos(inst.currentSegmentGoal), matrices, consumers, cx, cy, cz,
                    GOAL_R, GOAL_G, GOAL_B, GOAL_A);
        }

        if (inst.currentPath != null && !inst.currentPath.isEmpty()) {
            int start = Math.max(0, inst.pathNodeIndex);
            int end = Math.min(inst.currentPath.size(), start + RENDER_MAX_WHITE_NODES);
            for (int i = start; i < end; i++) {
                drawBoxAt(renderPos(inst.currentPath.get(i)), matrices, consumers, cx, cy, cz,
                        WHITE_R, WHITE_G, WHITE_B, WHITE_A);
            }
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

    // ---------------------------------------------------------------------
    // Rotation + target selection
    // ---------------------------------------------------------------------

    private static float computeDesiredYaw(Vec3d playerPos, BlockPos target) {
        Vec3d t = centerOf(target);
        double dx = t.x - playerPos.x;
        double dz = t.z - playerPos.z;
        return (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
    }

    private static float adaptiveYawStep(float yawErrorDeg) {
        float t = MathHelper.clamp(yawErrorDeg / 90.0f, 0.0f, 1.0f);
        return lerp(YAW_STEP_SMOOTH, YAW_STEP_FAST, t);
    }

    private static void setPlayerYawSmooth(ClientPlayerEntity player, float desiredYaw, float maxStep) {
        float currentYaw = player.getYaw();
        float delta = MathHelper.wrapDegrees(desiredYaw - currentYaw);
        float clamped = MathHelper.clamp(delta, -maxStep, maxStep);
        float newYaw = currentYaw + clamped;

        player.setYaw(newYaw);
        player.setHeadYaw(newYaw);
    }

    // LOS steering (corridor-safe)
    private static BlockPos selectLineOfSightTarget(World world, ClientPlayerEntity player,
                                                    List<BlockPos> path, int startIndex) {
        if (world == null || player == null || path == null || path.isEmpty()) return null;

        int start = MathHelper.clamp(startIndex, 0, path.size() - 1);
        int max = Math.min(path.size() - 1, start + LOS_LOOKAHEAD_MAX_NODES);

        Vec3d eye = player.getPos().add(0.0, LOS_EYE_HEIGHT, 0.0);
        Vec3d playerPos = player.getPos();

        BlockPos best = path.get(start);

        for (int i = start; i <= max; i++) {
            BlockPos cand = path.get(i);

            Vec3d candPt = new Vec3d(cand.getX() + 0.5, playerPos.y + LOS_EYE_HEIGHT, cand.getZ() + 0.5);
            if (!hasLineOfSight(world, eye, candPt, player)) break;

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

    private static boolean hasLineOfSight(World world, Vec3d from, Vec3d to, ClientPlayerEntity player) {
        RaycastContext ctx = new RaycastContext(
                from, to,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                player
        );
        HitResult hit = world.raycast(ctx);
        return hit.getType() == HitResult.Type.MISS || hit.getPos().squaredDistanceTo(to) < 0.20;
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

    private static boolean isAtGoalXZ(Vec3d playerPos, BlockPos goal) {
        return distanceXZ(playerPos, centerOf(goal)) <= GOAL_REACH_DIST;
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
    }

    private static void releaseKeys() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null) return;

        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
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

package name.atlasclient.script.mining;

import name.atlasclient.config.Rotation;
import name.atlasclient.script.Script;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Box;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.world.RaycastContext;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.debug.DebugRenderer;

import org.joml.Matrix4f;
import org.joml.Matrix3f;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Mithril-only mining macro with tier-priority targeting, smooth rotation (Linear or Bezier),
 * and CRIT particle "look-at" behavior.
 */
public final class MithrilMiningScript implements Script {

    public static final class Settings {
        // Master enable for this miner (separate from Script enable)
        private static boolean mithrilMinerEnabled = true;

        // Strictness 1..5 (higher = more strict)
        // 5 -> wool only, 1 -> all tiers
        private static int strictness = 5;

        private static boolean mineTitanium = false;
        private static boolean debugMessages = false;

        // Targeting / visuals
        private static float maxMineDistance = 4.5f; // blocks
        private static boolean renderTargetLine = false; // removed (line tracer disabled)
        private static boolean renderCursorMarker = true;

        /**
         * Local enum retained for UI/backwards-compat; values are persisted in {@link Rotation}.
         */
        public enum RotationType { LINEAR, BEZIER }

        // CRIT look controls
        private static boolean critLookEnabled = true;
        private static int critMaxLooksPerSecond = 12; // 1..60
        private static int critMaxAgeMs = 350;         // drop older particles
        private static float critMaxDistance = 2f;    // blocks
        private static boolean critInterruptMining = true;

        public static boolean isMithrilMinerEnabled() { return mithrilMinerEnabled; }
        public static void setMithrilMinerEnabled(boolean v) { mithrilMinerEnabled = v; }

        public static int getStrictness() { return strictness; }
        public static void setStrictness(int v) { strictness = clampInt(v, 1, 5); }

        public static boolean isMineTitanium() { return mineTitanium; }
        public static void setMineTitanium(boolean v) { mineTitanium = v; }

        public static boolean isDebugMessages() { return debugMessages; }
        public static void setDebugMessages(boolean v) { debugMessages = v; }

        public static float getMaxMineDistance() { return maxMineDistance; }
        public static void setMaxMineDistance(float v) { maxMineDistance = clampFloat(v, 2.0f, 8.0f); }

        public static boolean isRenderTargetLine() { return renderTargetLine; }
        public static void setRenderTargetLine(boolean v) { renderTargetLine = v; }

        public static boolean isRenderCursorMarker() { return renderCursorMarker; }
        public static void setRenderCursorMarker(boolean v) { renderCursorMarker = v; }

        // ----------------------------
        // Rotation: delegated to config
        // ----------------------------

        public static RotationType getRotationType() {
            return Rotation.isBezierRotation() ? RotationType.BEZIER : RotationType.LINEAR;
        }

        public static void setRotationType(RotationType t) {
            RotationType resolved = (t == null) ? RotationType.BEZIER : t;
            Rotation.setBezierRotation(resolved == RotationType.BEZIER);
        }

        /** 0..1000 */
        public static int getRotationSpeed() {
            return Rotation.getRotationSpeed();
        }

        public static void setRotationSpeed(int v) {
            Rotation.setRotationSpeed(v);
        }

        /** 0.0..2.5 */
        public static float getBezierSpeed() {
            return Rotation.getBezierSpeed();
        }

        public static void setBezierSpeed(float v) {
            Rotation.setBezierSpeed(v);
        }

        /** 0.0..1.0 */
        public static float getRotationRandomness() {
            return Rotation.getRotationRandomness();
        }

        public static void setRotationRandomness(float v) {
            Rotation.setRotationRandomness(v);
        }

        public static boolean isCritLookEnabled() { return critLookEnabled; }
        public static void setCritLookEnabled(boolean v) { critLookEnabled = v; }

        public static int getCritMaxLooksPerSecond() { return critMaxLooksPerSecond; }
        public static void setCritMaxLooksPerSecond(int v) { critMaxLooksPerSecond = clampInt(v, 1, 60); }

        public static int getCritMaxAgeMs() { return critMaxAgeMs; }
        public static void setCritMaxAgeMs(int v) { critMaxAgeMs = clampInt(v, 50, 2000); }

        public static float getCritMaxDistance() { return critMaxDistance; }
        public static void setCritMaxDistance(float v) { critMaxDistance = clampFloat(v, 1f, 64f); }

        public static boolean isCritInterruptMining() { return critInterruptMining; }
        public static void setCritInterruptMining(boolean v) { critInterruptMining = v; }

        private static int clampInt(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
        private static float clampFloat(float v, float min, float max) { return Math.max(min, Math.min(max, v)); }

        private static int clamp(int v, int min, int max) {
            return Math.max(min, Math.min(max, v));
        }

        private static float clampF(float v, float min, float max) {
            return Math.max(min, Math.min(max, v));
        }
    }


    // ---------------------------------------------------------------------
    // Script metadata
    // ---------------------------------------------------------------------

    @Override public String id() { return "mithril_miner"; }
    @Override public String displayName() { return "Mithril Miner"; }
    @Override public String description() { return "Mines mithril blocks near you."; }
    @Override public String category() { return "Mining"; }

    private boolean enabled = false;
    @Override public boolean isEnabled() { return enabled; }
    @Override public void setEnabled(boolean enabled) { this.enabled = enabled; }

    // ---------------------------------------------------------------------
    // Constants
    // ---------------------------------------------------------------------

    private static final int DEFAULT_RADIUS = 4;
    private static final int WALKAWAY_DIST_SQ = 9;          // 3 blocks
    private static final int BLOCK_TIMEOUT_TICKS = 80;      // ~4s at 20 TPS
    private static final int BLACKLIST_TICKS = 60;          // skip unreachable targets for ~3s

    // Tier order (best -> worst)
    private static final String[] MITHRIL_TIERS = new String[] {
            "minecraft:light_blue_wool",
            "minecraft:prismarine",
            "minecraft:dark_prismarine",
            "minecraft:gray_wool",
            "minecraft:cyan_terracotta"
    };
    private static final String TITANIUM = "minecraft:polished_diorite";

    // ---------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------

    private final Set<Block> targets = new HashSet<>();
    private final java.util.HashMap<BlockPos, Integer> targetBlacklistTicks = new java.util.HashMap<>();
    private Vec3d startPos = null;

    private BlockPos currentTarget = null;
    private int targetTicks = 0;

    // Face/aim sampling state to avoid occlusion softlocks
    private Direction currentTargetFace = null;
    private int faceAimIndex = 0;
    private double lockedAimU = Double.NaN;
    private double lockedAimV = Double.NaN;
    private long lastAimShuffleNanos = 0L;
    private int driftCooldownTicks = 0;

    // Last CRIT point for rendering and steering
    private Vec3d lastCritPos = null;
    private long lastCritSeenNanos = 0L;

    // Rotation planner state
    private boolean rotating = false;
    private float startYaw, startPitch;
    private float goalYaw, goalPitch;
    private float rotT = 0f;
    private float noiseYaw = 0f, noisePitch = 0f;
    private int rotationSettleTicks = 0;

    // Crit look rate limiting
    private long lastCritLookNanos = 0L;

    // ---------------------------------------------------------------------
    // CRIT particle queue API (called by a mixin)
    // ---------------------------------------------------------------------

    private static final class CritEvent {
        final double x, y, z;
        final long tNanos;
        CritEvent(double x, double y, double z, long tNanos) {
            this.x = x; this.y = y; this.z = z; this.tNanos = tNanos;
        }
    }

    private static final int CRIT_QUEUE_MAX = 256;
    private static final ArrayDeque<CritEvent> CRIT_QUEUE = new ArrayDeque<>(CRIT_QUEUE_MAX);

    public static void recordCritParticle(double x, double y, double z) {
        synchronized (CRIT_QUEUE) {
            if (CRIT_QUEUE.size() >= CRIT_QUEUE_MAX) CRIT_QUEUE.pollFirst();
            CRIT_QUEUE.addLast(new CritEvent(x, y, z, System.nanoTime()));
        }
    }

    private static CritEvent pollCrit() {
        synchronized (CRIT_QUEUE) {
            return CRIT_QUEUE.pollFirst();
        }
    }

    private static long LAST_PARTICLE_SEEN_DEBUG_NANOS = 0L;

    public static void recordMiningParticle(String id, double x, double y, double z) {
        recordCritParticle(x, y, z);
    }

    public static void debugParticleSeen(String id, double x, double y, double z) {
        MinecraftClient c = MinecraftClient.getInstance();
        if (c == null || c.player == null) return;

        long now = System.nanoTime();
        if (now - LAST_PARTICLE_SEEN_DEBUG_NANOS < 250_000_000L) return;
        LAST_PARTICLE_SEEN_DEBUG_NANOS = now;

        c.player.sendMessage(Text.literal("[Mithril Miner] Particle seen: " + id), false);
    }

    private static int getCritQueueSize() {
        synchronized (CRIT_QUEUE) { return CRIT_QUEUE.size(); }
    }

    private static String fmt3(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    /**
     * Removes and returns the closest valid CRIT event to the player, discarding stale ones.
     */
    private static CritEvent pollClosestCrit(MinecraftClient client, long nowNanos) {
        if (client == null || client.player == null) return null;

        double maxD = Settings.getCritMaxDistance();
        double maxDistSq = maxD * maxD;
        long maxAgeMs = Settings.getCritMaxAgeMs();

        CritEvent best = null;
        double bestDistSq = Double.MAX_VALUE;

        synchronized (CRIT_QUEUE) {
            int n = CRIT_QUEUE.size();
            for (int i = 0; i < n; i++) {
                CritEvent ev = CRIT_QUEUE.pollFirst();
                if (ev == null) break;

                long ageMs = (nowNanos - ev.tNanos) / 1_000_000L;
                if (ageMs > maxAgeMs) {
                    // drop stale
                    continue;
                }

                double distSq = squaredDistanceFromEye(client.player, ev.x, ev.y, ev.z);
                if (distSq > maxDistSq) {
                    // keep (still fresh) in case player moves
                    CRIT_QUEUE.addLast(ev);
                    continue;
                }

                if (distSq < bestDistSq) {
                    if (best != null) CRIT_QUEUE.addLast(best);
                    best = ev;
                    bestDistSq = distSq;
                } else {
                    CRIT_QUEUE.addLast(ev);
                }
            }
        }

        return best;
    }

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    public MithrilMiningScript() {
        refreshTargets();
    }

    @Override
    public void onEnable(MinecraftClient client) {
        refreshTargets();
        ensureRenderHook();
        ACTIVE_INSTANCE = this;
        ClientPlayerEntity p = client.player;
        if (p != null) startPos = p.getPos();

        currentTarget = null;
        targetTicks = 0;
        rotating = false;

        debug(client, "Enabled. strictness=" + Settings.getStrictness()
                + ", titanium=" + Settings.isMineTitanium()
                + ", rotType=" + Settings.getRotationType()
                + ", speed=" + Settings.getRotationSpeed()
                + ", bezier=" + Settings.getBezierSpeed()
                + ", rand=" + Settings.getRotationRandomness());
    }

    @Override
    public void onDisable() {
        ACTIVE_INSTANCE = null;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.options != null) {
            client.options.attackKey.setPressed(false);
        }
        if (client != null) debug(client, "Disabled.");
    }


    private static void ensureRenderHook() {
        if (RENDER_HOOK_REGISTERED) return;
        RENDER_HOOK_REGISTERED = true;

        WorldRenderEvents.LAST.register(MithrilMiningScript::onWorldRender);
    }



    /** Draw a thin debug line in world render context (RenderLayer.getLines()) */
    private static void drawLine(MatrixStack matrices, VertexConsumer vc,
                                 double x1, double y1, double z1,
                                 double x2, double y2, double z2,
                                 float r, float g, float b, float a) {
        if (matrices == null) return;
        MatrixStack.Entry entry = matrices.peek();
        Matrix4f posMat = entry.getPositionMatrix();
        float dx = (float) (x2 - x1);
        float dy = (float) (y2 - y1);
        float dz = (float) (z2 - z1);
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-6f) len = 1.0f;
        float nx = dx / len;
        float ny = dy / len;
        float nz = dz / len;

        vc.vertex(posMat, (float) x1, (float) y1, (float) z1).color(r, g, b, a).normal(entry, nx, ny, nz);
        vc.vertex(posMat, (float) x2, (float) y2, (float) z2).color(r, g, b, a).normal(entry, nx, ny, nz);
    }

    private static void onWorldRender(WorldRenderContext ctx) {
        MithrilMiningScript inst = ACTIVE_INSTANCE;
        if (inst == null) return;
        if (!inst.enabled) return;
        if (!Settings.isMithrilMinerEnabled()) return;
        if (inst.currentTarget == null) return;

        // Render a subtle outline around the selected block
        MatrixStack matrices = ctx.matrixStack();
        VertexConsumerProvider consumers = ctx.consumers();
        if (matrices == null || consumers == null) return;

        var camera = ctx.camera();
        if (camera == null) return;

        double cx = camera.getPos().x;
        double cy = camera.getPos().y;
        double cz = camera.getPos().z;

        Box box = new Box(inst.currentTarget).expand(0.002).offset(-cx, -cy, -cz);

        // Color: light cyan, low alpha. (r,g,b,a)
        DebugRenderer.drawBox(matrices, consumers, box, 0.35f, 0.85f, 1.0f, 0.65f);



        // Cursor marker: small white box at the current crosshair hit position (world-space)
        if (Settings.isRenderCursorMarker()) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null && mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.BLOCK) {
                BlockHitResult bhr = (BlockHitResult) mc.crosshairTarget;
                Vec3d hp = bhr.getPos();
                double s = 0.03; // half-size => small cube
                Box hb = new Box(hp.x - s, hp.y - s, hp.z - s, hp.x + s, hp.y + s, hp.z + s).offset(-cx, -cy, -cz);
                DebugRenderer.drawBox(matrices, consumers, hb, 1.0f, 1.0f, 1.0f, 0.95f);
            }
        }


        // CRIT highlight: small red box (20% block) around the last seen CRIT point
        if (inst.lastCritPos != null) {
            long now = System.nanoTime();
            long ageMs = (now - inst.lastCritSeenNanos) / 1_000_000L;
            long maxShowMs = Math.max(1500L, Settings.getCritMaxAgeMs());
            if (ageMs <= maxShowMs) {
                double s = 0.10; // half-size => 0.2 block edge length
                Box cb = new Box(
                        inst.lastCritPos.x - s, inst.lastCritPos.y - s, inst.lastCritPos.z - s,
                        inst.lastCritPos.x + s, inst.lastCritPos.y + s, inst.lastCritPos.z + s
                ).offset(-cx, -cy, -cz);

                DebugRenderer.drawBox(matrices, consumers, cb, 1.0f, 0.0f, 0.0f, 0.45f);
            } else {
                inst.lastCritPos = null;
            }
        }
    }

    // ---------------------------------------------------------------------
    // Tick
    // ---------------------------------------------------------------------

    @Override
    public void onTick(MinecraftClient client) {
        if (!enabled) return;

        if (!Settings.isMithrilMinerEnabled()) {
            client.options.attackKey.setPressed(false);
            return;
        }

        if (client.player == null || client.world == null) return;

        tickBlacklist();

        // Walk-away safety
        if (startPos != null) {
            Vec3d p = client.player.getPos();
            double dx = p.x - startPos.x;
            double dy = p.y - startPos.y;
            double dz = p.z - startPos.z;
            if ((dx*dx + dy*dy + dz*dz) > WALKAWAY_DIST_SQ) {
                client.options.attackKey.setPressed(false);
                debug(client, "Walked away >3 blocks; stopping.");
                enabled = false;
                return;
            }
        }

        // Update rotation animation (if any)
        updateRotation(client);

        // Validate current target
        if (currentTarget != null && !isStillValidTarget(client, currentTarget)) {
            currentTarget = null;
            clearAimLock();
            lastCritPos = null;
            targetTicks = 0;
        }

        // Acquire new target if needed
        if (currentTarget == null) {
            refreshTargets(); // in case settings changed in UI
            clearAimLock();
            currentTarget = findBestTarget(client, DEFAULT_RADIUS);
            lastCritPos = null;
            targetTicks = 0;

            if (currentTarget == null) {
                client.options.attackKey.setPressed(false);
                return;
            }

            // Start rotation toward the block face/center
            float[] yp = computeYawPitchToBlock(client, currentTarget);
            beginRotation(client.player.getYaw(), client.player.getPitch(), yp[0], yp[1]);
        }

        // Mining logic
        targetTicks++;

        // Hard distance gate: avoid selecting/attempting blocks outside reach.
        double maxD = Settings.getMaxMineDistance();
        double distSq = squaredDistanceFromEye(client.player, currentTarget.getX() + 0.5, currentTarget.getY() + 0.5, currentTarget.getZ() + 0.5);
        if (distSq > maxD * maxD) {
            client.options.attackKey.setPressed(false);
            if (Settings.isDebugMessages()) debug(client, "Target out of range; dropping target " + currentTarget);
            currentTarget = null;
            clearAimLock();
            targetTicks = 0;
            return;
        }

        // If we are rotating, optionally do not mine until close enough
        if (rotating) {
            // If you want a small "mine while turning", lower this threshold
            float yawErr = Math.abs(wrapDegrees(goalYaw - client.player.getYaw()));
            float pitchErr = Math.abs(goalPitch - client.player.getPitch());
            if (yawErr > 6f || pitchErr > 4f) {
                client.options.attackKey.setPressed(false);
                return;
            }
        }


        // Aim verification / correction. We lock onto a working point on the visible face to avoid softlocks.
        if (currentTarget != null) {
            // If our crosshair is not even on the block, (re)acquire a visible aim point.
            if (!isCrosshairOnBlock(client, currentTarget)) {
                boolean locked = tryLockVisibleAim(client, currentTarget);
                if (!locked) {
                    // Fully visible blocks can still fail a strict ray test due to edges; if this happens, drop target to avoid softlock.
                    if (Settings.isDebugMessages()) debug(client, "No visible face/point; dropping target");
                    client.options.attackKey.setPressed(false);
                    currentTarget = null;
                    clearAimLock();
                    targetTicks = 0;
                    return;
                }

                Vec3d aimPoint = facePoint(currentTarget, currentTargetFace, lockedAimU, lockedAimV);
                float[] yp = computeYawPitchToPoint(client, aimPoint.x, aimPoint.y, aimPoint.z);
                // Smoothly rotate back onto the intended block aim point (avoid snapping when drifting past).
                beginRotation(client.player.getYaw(), client.player.getPitch(), yp[0], yp[1]);
                client.options.attackKey.setPressed(false);
                return;
            }
        }

        // Hold attack
        client.options.attackKey.setPressed(true);


        // While mining, prefer steering to the closest CRIT point if available.
        updateCritLook(client, true);

        // Timeout failsafe
        if (targetTicks > BLOCK_TIMEOUT_TICKS) {
            client.options.attackKey.setPressed(false);
            debug(client, "Target timeout; dropping target " + currentTarget);
            currentTarget = null;
            targetTicks = 0;
        }
    }

    private void tickBlacklist() {
        if (targetBlacklistTicks.isEmpty()) return;
        var it = targetBlacklistTicks.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            int v = e.getValue() - 1;
            if (v <= 0) it.remove();
            else e.setValue(v);
        }
    }

    // ---------------------------------------------------------------------
    // Face/aim sampling (prevents occlusion softlocks)
    // ---------------------------------------------------------------------

    private static final double[][] FACE_OFFSETS = new double[][] {
            {0.00, 0.18}, {0.00, -0.18}, {0.18, 0.00}, {-0.18, 0.00},
            {0.18, 0.18}, {0.18, -0.18}, {-0.18, 0.18}, {-0.18, -0.18},
            {0.00, 0.32}, {0.00, -0.32}, {0.32, 0.00}, {-0.32, 0.00},
            {0.32, 0.32}, {0.32, -0.32}, {-0.32, 0.32}, {-0.32, -0.32},
            {0.00, 0.00}
    };

    private static Vec3d facePoint(BlockPos pos, Direction face, double u, double v) {
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.5;
        double cz = pos.getZ() + 0.5;

        // Slightly outside the face so the ray "lands" on that block reliably
        double ox = face.getOffsetX() * 0.501;
        double oy = face.getOffsetY() * 0.501;
        double oz = face.getOffsetZ() * 0.501;

        int rx, ry, rz, ux, uy, uz;
        switch (face) {
            case UP:
            case DOWN:
                // Face plane is XZ
                rx = 1; ry = 0; rz = 0;
                ux = 0; uy = 0; uz = 1;
                break;
            case NORTH:
            case SOUTH:
                // Face plane is XY
                rx = 1; ry = 0; rz = 0;
                ux = 0; uy = 1; uz = 0;
                break;
            default: // EAST/WEST
                // Face plane is ZY
                rx = 0; ry = 0; rz = 1;
                ux = 0; uy = 1; uz = 0;
                break;
        }

        double px = cx + ox + (rx * u) + (ux * v);
        double py = cy + oy + (ry * u) + (uy * v);
        double pz = cz + oz + (rz * u) + (uz * v);

        return new Vec3d(px, py, pz);
    }

    private static double approach(double current, double target, double maxDelta) {
        if (Double.isNaN(current)) return target;
        double delta = target - current;
        if (delta > maxDelta) delta = maxDelta;
        if (delta < -maxDelta) delta = -maxDelta;
        return current + delta;
    }

    private BlockHitResult raycastToPoint(MinecraftClient client, Vec3d point) {
        if (client == null || client.player == null || client.world == null) return null;
        Vec3d from = client.player.getCameraPosVec(1.0f);
        RaycastContext ctx = new RaycastContext(
                from,
                point,
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,
                client.player
        );
        HitResult hit = client.world.raycast(ctx);
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) return null;
        return (BlockHitResult) hit;
    }

    private static boolean isCrosshairOnBlock(MinecraftClient client, BlockPos pos) {
        if (client == null) return false;
        HitResult hr = client.crosshairTarget;
        if (hr == null || hr.getType() != HitResult.Type.BLOCK) return false;
        BlockHitResult bhr = (BlockHitResult) hr;
        return bhr.getBlockPos().equals(pos);
    }

    private void clearAimLock() {
        currentTargetFace = null;
        lockedAimU = Double.NaN;
        lockedAimV = Double.NaN;
        faceAimIndex = 0;
        lastAimShuffleNanos = 0L;
    }

    private boolean tryLockVisibleAim(MinecraftClient client, BlockPos target) {
        // Rotate the starting index occasionally so we explore different points if partially occluded,
        // but keep it slow to avoid visible snapping.
        long now = System.nanoTime();
        if (now - lastAimShuffleNanos > 1_250_000_000L) { // ~0.8/sec
            faceAimIndex = (faceAimIndex + 1) % FACE_OFFSETS.length;
            lastAimShuffleNanos = now;
        }

        float curYaw = client.player.getYaw();
        float curPitch = client.player.getPitch();

        Direction bestFace = null;
        double bestU = Double.NaN, bestV = Double.NaN;
        int bestIdx = -1;
        double bestScore = Double.MAX_VALUE;

        // Consider all candidate points; pick the one that is (a) actually visible via raycast
        // and (b) requires the smallest rotation from our current view.
        for (Direction face : Direction.values()) {
            for (int k = 0; k < FACE_OFFSETS.length; k++) {
                int idx = (faceAimIndex + k) % FACE_OFFSETS.length;
                double uCand = FACE_OFFSETS[idx][0];
                double vCand = FACE_OFFSETS[idx][1];

                Vec3d p = facePoint(target, face, uCand, vCand);
                BlockHitResult hit = raycastToPoint(client, p);
                if (hit == null) continue;
                if (!hit.getBlockPos().equals(target)) continue;

                // Use the real hit position to avoid "always center" behavior.
                Vec3d hp = hit.getPos();
                Direction visibleFace = hit.getSide();

                // Convert hit position into (u,v) on that visible face plane.
                double u = 0.0, v = 0.0;
                double dx = hp.x - (target.getX() + 0.5);
                double dy = hp.y - (target.getY() + 0.5);
                double dz = hp.z - (target.getZ() + 0.5);

                switch (visibleFace) {
                    case UP:
                    case DOWN:
                        u = dx;
                        v = dz;
                        break;
                    case NORTH:
                    case SOUTH:
                        u = dx;
                        v = dy;
                        break;
                    default: // EAST/WEST
                        u = dz;
                        v = dy;
                        break;
                }

                // Clamp to our sampling window to keep aim points stable and not on the extreme edge.
                u = MathHelper.clamp(u, -0.32, 0.32);
                v = MathHelper.clamp(v, -0.32, 0.32);

                float[] yp = computeYawPitchToPoint(client, hp.x, hp.y, hp.z);
                float yawErr = Math.abs(wrapDegrees(yp[0] - curYaw));
                float pitchErr = Math.abs(yp[1] - curPitch);

                double score = yawErr + pitchErr * 1.15; // slightly favor yaw stability
                if (score < bestScore) {
                    bestScore = score;
                    bestFace = visibleFace;
                    bestU = u;
                    bestV = v;
                    bestIdx = idx;
                }
            }
        }

        if (bestFace == null || Double.isNaN(bestU) || Double.isNaN(bestV)) return false;

        currentTargetFace = bestFace;
        lockedAimU = bestU;
        lockedAimV = bestV;
        faceAimIndex = bestIdx;

        if (Settings.isDebugMessages()) {
            debug(client, "Aim locked: face=" + currentTargetFace + " idx=" + faceAimIndex);
        }
        return true;
    }

    private void driftAimTowardCenter(MinecraftClient client, double ratePerTick) {
        if (client == null || client.player == null || client.world == null) return;
        if (currentTarget == null || currentTargetFace == null) return;
        if (Double.isNaN(lockedAimU) || Double.isNaN(lockedAimV)) return;

        // Prevent oscillation: if a drift step would move off-target, pause drifting briefly.
        if (driftCooldownTicks > 0) {
            driftCooldownTicks--;
            return;
        }

        // Propose a very small step toward face-center (kept minimal; most aim stability comes from locking)
        double newU = lockedAimU * (1.0 - ratePerTick);
        double newV = lockedAimV * (1.0 - ratePerTick);

        // Validate the drifted aim point: it must still raycast to the same target block.
        Vec3d candidate = facePoint(currentTarget, currentTargetFace, newU, newV);
        BlockHitResult hit = raycastToPoint(client, candidate);

        if (hit != null && hit.getBlockPos().equals(currentTarget)) {
            // Accept drift. Optionally keep face authoritative (it may change near edges).
            // Keep the locked face stable; changing faces near edges causes rapid "jumping".
            lockedAimU = newU;
            lockedAimV = newV;
            return;
        }

        // If full step fails, try a smaller step.
        double newU2 = lockedAimU * (1.0 - ratePerTick * 0.5);
        double newV2 = lockedAimV * (1.0 - ratePerTick * 0.5);
        Vec3d candidate2 = facePoint(currentTarget, currentTargetFace, newU2, newV2);
        BlockHitResult hit2 = raycastToPoint(client, candidate2);

        if (hit2 != null && hit2.getBlockPos().equals(currentTarget)) {
            currentTargetFace = hit2.getSide();
            lockedAimU = newU2;
            lockedAimV = newV2;
            return;
        }

        // Drift would move off the block. Recover by selecting the nearest-to-center valid sample point.
        int bestIdx = -1;
        double bestScore = Double.MAX_VALUE;
        Direction bestFace = currentTargetFace;
        double bestU = lockedAimU, bestV = lockedAimV;

        for (int i = 0; i < FACE_OFFSETS.length; i++) {
            double u = FACE_OFFSETS[i][0];
            double v = FACE_OFFSETS[i][1];
            double du = u - lockedAimU;
            double dv = v - lockedAimV;
            double score = du * du + dv * dv; // smaller => closer to our current aim (prevents snapping to center)

            Vec3d p = facePoint(currentTarget, currentTargetFace, u, v);
            BlockHitResult hh = raycastToPoint(client, p);
            if (hh == null) continue;
            if (!hh.getBlockPos().equals(currentTarget)) continue;

            if (score < bestScore) {
                bestScore = score;
                bestIdx = i;
                bestFace = hh.getSide();
                bestU = u;
                bestV = v;
            }
        }

        if (bestIdx != -1) {
            currentTargetFace = bestFace;

            // Move gradually toward the recovered point instead of snapping (prevents "teleporting" aim).
            double maxStep = 0.06; // per tick
            lockedAimU = approach(lockedAimU, bestU, maxStep);
            lockedAimV = approach(lockedAimV, bestV, maxStep);

            faceAimIndex = bestIdx;
            // pause drifting for a moment to avoid repeated edge flipping
            driftCooldownTicks = 8;
        } else {
            // No valid "more central" point exists; stop drifting temporarily.
            driftCooldownTicks = 12;
        }
    }
    // ---------------------------------------------------------------------
    // Targeting
    // ---------------------------------------------------------------------

    private void refreshTargets() {
        targets.clear();

        // Strictness: higher = more strict
        // Convert strictness 1..5 into allowed depth 5..1
        int depth = 6 - Settings.getStrictness(); // 5 (loose) -> 1 (strict)
        depth = Math.max(1, Math.min(5, depth));

        for (int i = 0; i < depth; i++) {
            addTarget(MITHRIL_TIERS[i]);
        }

        if (Settings.isMineTitanium()) {
            addTarget(TITANIUM);
        }
    }

    private void addTarget(String id) {
        Block b = Registries.BLOCK.get(Identifier.of(id));
        if (b != null) targets.add(b);
    }

    private boolean touchesAir(MinecraftClient client, BlockPos pos) {
        for (Direction d : Direction.values()) {
            if (client.world.isAir(pos.offset(d))) return true;
        }
        return false;
    }

    private boolean isStillValidTarget(MinecraftClient client, BlockPos pos) {
        Block b = client.world.getBlockState(pos).getBlock();
        if (!targets.contains(b)) return false;
        return touchesAir(client, pos);
    }

    private int priority(Block b) {
        Identifier id = Registries.BLOCK.getId(b);
        if (id == null) return 999;
        String s = id.toString();

        if (Settings.isMineTitanium() && s.equals(TITANIUM)) return 0;

        int base = Settings.isMineTitanium() ? 1 : 0;
        for (int i = 0; i < MITHRIL_TIERS.length; i++) {
            if (s.equals(MITHRIL_TIERS[i])) return base + i;
        }
        return 999;
    }


    private static final class VisibleAim {
        final Direction face;
        final double u;
        final double v;
        final int idx;
        VisibleAim(Direction face, double u, double v, int idx) {
            this.face = face;
            this.u = u;
            this.v = v;
            this.idx = idx;
        }
    }

    /**
     * Probe for an actually visible aim point on any face of the target using raycasts from the eye to sample points.
     * This does not require turning the camera, and lets us skip fully occluded/unreachable blocks immediately.
     */
    private VisibleAim probeVisibleAim(MinecraftClient client, BlockPos target, int startIdx) {
        BlockHitResult bestHit = null;
        Direction bestFace = null;
        double bestU = 0, bestV = 0;
        int bestIdx = -1;

        for (Direction face : Direction.values()) {
            for (int k = 0; k < FACE_OFFSETS.length; k++) {
                int idx = (startIdx + k) % FACE_OFFSETS.length;
                double u = FACE_OFFSETS[idx][0];
                double v = FACE_OFFSETS[idx][1];
                Vec3d p = facePoint(target, face, u, v);

                BlockHitResult hit = raycastToPoint(client, p);
                if (hit == null) continue;
                if (!hit.getBlockPos().equals(target)) continue;

                bestHit = hit;
                bestFace = hit.getSide();
                bestU = u;
                bestV = v;
                bestIdx = idx;
                break;
            }
            if (bestHit != null) break;
        }

        if (bestHit == null) return null;
        return new VisibleAim(bestFace, bestU, bestV, bestIdx);
    }

    /**
     * Python-parity selection: find best tier present, then pick closest within that tier.
     */
    private BlockPos findBestTarget(MinecraftClient client, int radius) {
        BlockPos origin = client.player.getBlockPos();


        double maxD = Settings.getMaxMineDistance();
        double maxDistSq = maxD * maxD;

        int bestTier = Integer.MAX_VALUE;

        // pass 1: best tier present
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos p = origin.add(dx, dy, dz);
                    if (targetBlacklistTicks.containsKey(p)) continue;
                    Block b = client.world.getBlockState(p).getBlock();
                    if (!targets.contains(b)) continue;
                    if (!touchesAir(client, p)) continue;
                    double d = squaredDistanceFromEye(client.player, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
                    if (d > maxDistSq) continue;

                    // Skip blocks with no currently visible aim point (prevents counterproductive snap-to-test behavior)
                    if (probeVisibleAim(client, p, faceAimIndex) == null) {
                        targetBlacklistTicks.put(p, BLACKLIST_TICKS);
                        continue;
                    }

                    int t = priority(b);
                    if (t < bestTier) bestTier = t;
                }
            }
        }

        if (bestTier == Integer.MAX_VALUE) return null;

        // pass 2: closest within best tier
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos p = origin.add(dx, dy, dz);
                    if (targetBlacklistTicks.containsKey(p)) continue;
                    Block b = client.world.getBlockState(p).getBlock();
                    if (!targets.contains(b)) continue;
                    if (!touchesAir(client, p)) continue;
                    if (priority(b) != bestTier) continue;

                    double d = squaredDistanceFromEye(client.player, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
                    if (d > maxDistSq) continue;
                    VisibleAim va = probeVisibleAim(client, p, faceAimIndex);
                    if (va == null) {
                        targetBlacklistTicks.put(p, BLACKLIST_TICKS);
                        continue;
                    }

                    if (d < bestDistSq) {
                        bestDistSq = d;
                        best = p;

                        // Pre-lock the aim point so we do not need to "look at" the block to know the face.
                        currentTargetFace = va.face;
                        lockedAimU = va.u;
                        lockedAimV = va.v;
                        faceAimIndex = va.idx;
                    }
                }
            }
        }
        return best;
    }

    // ---------------------------------------------------------------------
    // Rotation
    // ---------------------------------------------------------------------

    private void beginRotation(float currentYaw, float currentPitch, float targetYaw, float targetPitch) {
        this.rotating = true;
        this.rotT = 0f;
        this.rotationSettleTicks = 0;

        this.startYaw = currentYaw;
        this.startPitch = currentPitch;
        this.goalYaw = targetYaw;
        this.goalPitch = targetPitch;

        float r = Settings.getRotationRandomness();
        this.noiseYaw = ((float) Math.random() - 0.5f) * 2f * r * 0.6f;
        this.noisePitch = ((float) Math.random() - 0.5f) * 2f * r * 0.4f;
    }

    private void updateRotation(MinecraftClient client) {
        if (!rotating || client.player == null) return;

        if (Settings.getRotationType() == Settings.RotationType.LINEAR) {
            // linear step each tick
            float maxStep = linearMaxStepPerTick(Settings.getRotationSpeed());
            float cy = client.player.getYaw();
            float cp = client.player.getPitch();

            float dy = wrapDegrees(goalYaw - cy);
            float dp = goalPitch - cp;

            // Gentler acceleration/deceleration: slow down as we approach the goal, but not too abruptly.
            float yawErr = Math.abs(dy);
            float pitchErr = Math.abs(dp);

            // Make the slowdown region larger (divisors higher) to avoid a sharp "brake".
            float yawScale = MathHelper.clamp(yawErr / 30f, 0.10f, 1f);
            float pitchScale = MathHelper.clamp(pitchErr / 24f, 0.10f, 1f);

            // Also reduce the absolute step a bit to avoid "snappy" movement.
            float stepYaw = maxStep * 0.65f * yawScale;
            float stepPitch = (maxStep * 0.75f) * 0.65f * pitchScale;

            float ny = cy + clampAbs(dy, stepYaw) + noiseYaw * 0.20f;
            float np = cp + clampAbs(dp, stepPitch) + noisePitch * 0.20f;

            client.player.setYaw(ny);
            client.player.setPitch(MathHelper.clamp(np, -90f, 90f));

            // Require a few settle ticks before stopping
            if (yawErr <= 0.9f && pitchErr <= 0.9f) {
                rotationSettleTicks++;
                if (rotationSettleTicks >= 4) rotating = false;
            } else {
                rotationSettleTicks = 0;
            }
            return;
        }

        // Bezier plan: multi-tick progress with ease curve
        float speed = Settings.getRotationSpeed();  // 0..1000
        float mult = Settings.getBezierSpeed();     // 0.0..2.5

        // Convert to dt: ~0.02..0.20 per tick, scaled by multiplier
        float base = 0.012f + (speed / 1000f) * 0.10f;
        float dt = MathHelper.clamp(base * mult, 0.010f, 0.14f);

        rotT = Math.min(1f, rotT + dt);

        float eased = easeInOutCubic(rotT);

        float dy = wrapDegrees(goalYaw - startYaw);
        float dp = goalPitch - startPitch;

        float ny = startYaw + dy * eased + noiseYaw;
        float np = startPitch + dp * eased + noisePitch;

        client.player.setYaw(ny);
        client.player.setPitch(MathHelper.clamp(np, -90f, 90f));

        if (rotT >= 1f) rotating = false;
    }

    private static float linearMaxStepPerTick(int speed0to1000) {
        // 0 -> 2 deg/tick, 1000 -> 18 deg/tick (tweakable)
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

    private static float wrapDegrees(float deg) {
        return MathHelper.wrapDegrees(deg);
    }

    private float[] computeYawPitchToBlock(MinecraftClient client, BlockPos pos) {
        // Aim at block center
        double tx = pos.getX() + 0.5;
        double ty = pos.getY() + 0.5;
        double tz = pos.getZ() + 0.5;
        return computeYawPitchToPoint(client, tx, ty, tz);
    }

    private float[] computeYawPitchToPoint(MinecraftClient client, double tx, double ty, double tz) {
        double px = client.player.getX();
        double py = client.player.getY() + 1.5;
        double pz = client.player.getZ();

        double dx = tx - px;
        double dy = ty - py;
        double dz = tz - pz;

        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double horiz = Math.sqrt(dx*dx + dz*dz);
        double pitch = -Math.toDegrees(Math.atan2(dy, horiz));

        return new float[] { (float) yaw, (float) pitch };
    }

    /**
     * Squared distance from the player's eye reference point to a world coordinate.
     * Uses a fixed eye offset (player.getY() + 1.5) as requested.
     */
    private static double squaredDistanceFromEye(ClientPlayerEntity player, double x, double y, double z) {
        if (player == null) return Double.POSITIVE_INFINITY;
        double dx = x - player.getX();
        double dy = y - (player.getY() + 1.5);
        double dz = z - player.getZ();
        return dx*dx + dy*dy + dz*dz;
    }

    // ---------------------------------------------------------------------
    // CRIT look behavior
    // ---------------------------------------------------------------------

    private void updateCritLook(MinecraftClient client, boolean mining) {
        if (!Settings.isCritLookEnabled()) return;

        // If interruption is disabled, avoid crit-look while rotating unless we are mining.
        if (!Settings.isCritInterruptMining()) {
            if (rotating && !mining) return;
        }

        long now = System.nanoTime();
        int maxPerSec = Settings.getCritMaxLooksPerSecond();
        long minInterval = 1_000_000_000L / Math.max(1, maxPerSec);
        if (now - lastCritLookNanos < minInterval) return;

        CritEvent ev = pollClosestCrit(client, now);
        if (ev == null) {
            if (Settings.isDebugMessages()) debug(client, "CRIT none q=" + getCritQueueSize());
            return;
        }

        long ageMs = (now - ev.tNanos) / 1_000_000L;
        if (ageMs > Settings.getCritMaxAgeMs()) return;

        double distSq = squaredDistanceFromEye(client.player, ev.x, ev.y, ev.z);
        double maxD = Settings.getCritMaxDistance();
        if (distSq > maxD * maxD) return;

        // Begin a short look animation
        float[] yp = computeYawPitchToPoint(client, ev.x, ev.y, ev.z);
        beginRotation(client.player.getYaw(), client.player.getPitch(), yp[0], yp[1]);
        lastCritLookNanos = now;
        lastCritPos = new Vec3d(ev.x, ev.y, ev.z);
        lastCritSeenNanos = now;
        if (Settings.isDebugMessages()) debug(client, "CRIT aim (" + fmt3(ev.x) + "," + fmt3(ev.y) + "," + fmt3(ev.z) + ")");
    }

    // ---------------------------------------------------------------------
    // Logging
    // ---------------------------------------------------------------------

    private void debug(MinecraftClient client, String msg) {
        if (!Settings.isDebugMessages()) return;
        if (client.player == null) return;
        client.player.sendMessage(Text.literal("[Mithril Miner] " + msg), false);
    }

    // Active instance used by render hook (Fabric events are static)
    private static volatile MithrilMiningScript ACTIVE_INSTANCE = null;
    private static volatile boolean RENDER_HOOK_REGISTERED = false;
}

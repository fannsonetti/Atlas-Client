package name.atlasclient.config;

/**
 * Centralized rotation configuration shared across scripts.
 *
 * <p>Keep all rotation-related defaults here so individual scripts do not hardcode
 * default values (e.g., rotation speed) in their own Settings classes.</p>
 */
public final class Rotation {

    private Rotation() {}

    

    // ---------------------------------------------------------------------
    // Cooperative rotation arbitration (prevents scripts fighting / flicking)
    // ---------------------------------------------------------------------
    private static String rotationOwner = null;
    private static long rotationOwnerUntilTick = -1L;

    /** Returns true if {@code owner} may apply rotation changes at {@code nowTick}. */
    public static synchronized boolean allowRotation(String owner, long nowTick) {
        if (rotationOwner != null && nowTick > rotationOwnerUntilTick) {
            rotationOwner = null;
            rotationOwnerUntilTick = -1L;
        }
        return rotationOwner == null || rotationOwner.equals(owner);
    }

    /**
     * Attempts to claim rotation control for {@code ttlTicks}. Returns true if the claim is granted.
     * If already owned by {@code owner}, extends the lease.
     */
    public static synchronized boolean tryClaimRotation(String owner, long nowTick, int ttlTicks) {
        if (ttlTicks < 1) ttlTicks = 1;

        if (rotationOwner != null && nowTick > rotationOwnerUntilTick) {
            rotationOwner = null;
            rotationOwnerUntilTick = -1L;
        }

        if (rotationOwner == null || rotationOwner.equals(owner)) {
            rotationOwner = owner;
            rotationOwnerUntilTick = nowTick + ttlTicks;
            return true;
        }
        return false;
    }

    /** Releases rotation control if currently owned by {@code owner}. */
    public static synchronized void releaseRotation(String owner) {
        if (rotationOwner != null && rotationOwner.equals(owner)) {
            rotationOwner = null;
            rotationOwnerUntilTick = -1L;
        }
    }

// ---------------------------------------------------------------------
    // Defaults / state
    // ---------------------------------------------------------------------

    /** True = Bezier, false = Linear. */
    private static boolean bezierRotation = true;

    /** 0..1000 */
    private static int rotationSpeed = 300;

    /** 0.0..2.5 */
    private static float bezierSpeed = 2.5f;

    /** 0.0..1.0 */
    private static float rotationRandomness = 0.0f;

    // ---------------------------------------------------------------------
    // Accessors
    // ---------------------------------------------------------------------

    public static boolean isBezierRotation() {
        return bezierRotation;
    }

    public static void setBezierRotation(boolean v) {
        bezierRotation = v;
    }

    public static int getRotationSpeed() {
        return rotationSpeed;
    }

    public static void setRotationSpeed(int v) {
        rotationSpeed = clampInt(v, 0, 1000);
    }

    public static float getBezierSpeed() {
        return bezierSpeed;
    }

    public static void setBezierSpeed(float v) {
        bezierSpeed = clampFloat(v, 0.0f, 10f);
    }

    public static float getRotationRandomness() {
        return rotationRandomness;
    }

    public static void setRotationRandomness(float v) {
        rotationRandomness = clampFloat(v, 0.0f, 1.0f);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float clampFloat(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}

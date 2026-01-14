package name.atlasclient.script.mining;

import name.atlasclient.script.Script;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.*;

public final class OreMiningScript implements Script {
    private boolean enabled = false;

    private static final int DEFAULT_RADIUS = 4;
    private static final int WALKAWAY_DIST_SQ = 9;
    private static final int BLOCK_TIMEOUT_TICKS = 80;
    private static final float LOOK_STEP_DEG = 6.0f;

    public enum OreChoice {
        COAL("Coal", "minecraft:coal_ore", "minecraft:coal_block"),
        DIAMOND("Diamond", "minecraft:diamond_ore", "minecraft:diamond_block"),
        EMERALD("Emerald", "minecraft:emerald_ore", "minecraft:emerald_block"),
        GOLD("Gold", "minecraft:gold_ore", "minecraft:gold_block"),
        IRON("Iron", "minecraft:iron_ore", "minecraft:iron_block"),
        LAPIS("Lapis", "minecraft:lapis_ore", "minecraft:lapis_block"),
        OBSIDIAN("Obsidian", null, "minecraft:obsidian"),
        REDSTONE("Redstone", "minecraft:redstone_ore", "minecraft:redstone_block");

        public final String label;
        public final String oreId;
        public final String blockId;

        OreChoice(String label, String oreId, String blockId) {
            this.label = label;
            this.oreId = oreId;
            this.blockId = blockId;
        }
    }

    private OreChoice selected = OreChoice.COAL;

    private final Set<Block> targets = new HashSet<>();

    private Vec3d startPos = null;
    private BlockPos currentTarget = null;
    private int targetTicks = 0;

    public OreMiningScript() {
        rebuildTargets();
    }

    public OreChoice getSelected() { return selected; }

    public void nextOre() {
        OreChoice[] values = OreChoice.values();
        int i = selected.ordinal();
        selected = values[(i + 1) % values.length];
        rebuildTargets();
    }

    private void rebuildTargets() {
        targets.clear();
        if (selected.oreId != null) addTarget(selected.oreId);
        if (selected.blockId != null) addTarget(selected.blockId);

    }

    private void addTarget(String id) {
        Block b = Registries.BLOCK.get(Identifier.of(id));
        if (b != null) targets.add(b);
    }

    @Override public String id() { return "ore_miner"; }
    @Override public String displayName() { return "Ore Miner"; }
    @Override public String description() { return "Mines a selected ore type (choose in the menu)."; }
    @Override public String category() { return "Mining"; }

    @Override public boolean isEnabled() { return enabled; }
    @Override public void setEnabled(boolean enabled) { this.enabled = enabled; }

    @Override
    public void onEnable(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p != null) startPos = p.getPos();
        currentTarget = null;
        targetTicks = 0;
    }

    @Override
    public void onDisable() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.options != null) {
            client.options.attackKey.setPressed(false);
        }
    }

    @Override
    public void onTick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        if (startPos != null) {
            Vec3d now = client.player.getPos();
            double dx = now.x - startPos.x;
            double dy = now.y - startPos.y;
            double dz = now.z - startPos.z;
            if (dx*dx + dy*dy + dz*dz > WALKAWAY_DIST_SQ) {
                setEnabled(false);
                onDisable();
                return;
            }
        }

        if (client.currentScreen != null) {
            client.options.attackKey.setPressed(false);
            currentTarget = null;
            targetTicks = 0;
            return;
        }

        if (currentTarget == null || !isStillTarget(client, currentTarget)) {
            currentTarget = findNearestTarget(client, DEFAULT_RADIUS);
            targetTicks = 0;
        }

        if (currentTarget == null) {
            client.options.attackKey.setPressed(false);
            return;
        }

        targetTicks++;
        if (targetTicks > BLOCK_TIMEOUT_TICKS) {
            currentTarget = null;
            targetTicks = 0;
            client.options.attackKey.setPressed(false);
            return;
        }

        smoothLookAtBlock(client, currentTarget);
        client.options.attackKey.setPressed(true);
    }

    private boolean isStillTarget(MinecraftClient client, BlockPos pos) {
        if (!targets.contains(client.world.getBlockState(pos).getBlock())) return false;
        return touchesAir(client, pos);
    }

    private boolean touchesAir(MinecraftClient client, BlockPos pos) {
        return client.world.isAir(pos.north())
                || client.world.isAir(pos.south())
                || client.world.isAir(pos.east())
                || client.world.isAir(pos.west())
                || client.world.isAir(pos.up())
                || client.world.isAir(pos.down());
    }

    private BlockPos findNearestTarget(MinecraftClient client, int radius) {
        BlockPos origin = client.player.getBlockPos();
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos p = origin.add(dx, dy, dz);
                    if (!targets.contains(client.world.getBlockState(p).getBlock())) continue;
                    if (!touchesAir(client, p)) continue;

                    double d = client.player.squaredDistanceTo(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
                    if (d < bestDistSq) {
                        bestDistSq = d;
                        best = p;
                    }
                }
            }
        }
        return best;
    }

    private void smoothLookAtBlock(MinecraftClient client, BlockPos pos) {
        Vec3d eye = client.player.getEyePos();
        Vec3d target = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        Vec3d diff = target.subtract(eye);

        double dx = diff.x;
        double dy = diff.y;
        double dz = diff.z;

        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float horiz = (float) Math.sqrt(dx * dx + dz * dz);
        float targetPitch = (float) Math.toDegrees(-Math.atan2(dy, horiz));

        float yaw = client.player.getYaw();
        float pitch = client.player.getPitch();

        float newYaw = stepAngle(yaw, targetYaw, LOOK_STEP_DEG);
        float newPitch = stepAngle(pitch, targetPitch, LOOK_STEP_DEG);

        client.player.setYaw(newYaw);
        client.player.setPitch(newPitch);
    }

    private static float stepAngle(float current, float target, float maxStep) {
        float delta = wrapDegrees(target - current);
        if (delta > maxStep) delta = maxStep;
        if (delta < -maxStep) delta = -maxStep;
        return current + delta;
    }

    private static float wrapDegrees(float degrees) {
        float d = degrees % 360.0f;
        if (d >= 180.0f) d -= 360.0f;
        if (d < -180.0f) d += 360.0f;
        return d;
    }
}

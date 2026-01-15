package name.atlasclient.script.combat;

import name.atlasclient.script.Script;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Random;

public final class GraveyardScript implements Script {

    private boolean enabled = false;
    private final Random random = new Random();

    private long nextClickMs = 0L;

    @Override public String id() { return "graveyard_zombie"; }
    @Override public String displayName() { return "Graveyard Zombie"; }
    @Override public String description() { return "Automatically attacks nearby Graveyard Zombies."; }
    @Override public String category() { return "Misc"; }

    @Override public boolean isEnabled() { return enabled; }
    @Override public void setEnabled(boolean enabled) { this.enabled = enabled; }

    @Override
    public void onDisable() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.options != null) {
            client.options.attackKey.setPressed(false);
        }
    }

    @Override
    public void onTick(MinecraftClient client) {
        if (!enabled || client.player == null || client.world == null) return;

        // 2.5 block radius
        Box box = client.player.getBoundingBox().expand(2.5);
        List<Entity> entities = client.world.getOtherEntities(client.player, box);

        LivingEntity target = null;

        for (Entity e : entities) {
            if (e instanceof LivingEntity le) {
                if (le.getName().getString().equalsIgnoreCase("graveyard zombie")) {
                    target = le;
                    break;
                }
            }
        }

        if (target == null) {
            client.options.attackKey.setPressed(false);
            return;
        }

        // Rotate toward the zombie using global sensitivity snapping
        lookAt(client, target.getEyePos());

        long now = System.currentTimeMillis();
        if (now >= nextClickMs) {
            client.options.attackKey.setPressed(true);

            int cps = 5 + random.nextInt(6); // 5..10
            long delay = 1000L / cps;
            nextClickMs = now + delay;
        } else {
            client.options.attackKey.setPressed(false);
        }
    }

    // ---------------------------------------------------------------------
    // Rotation with global sensitivity quantization (like Mithril miner)
    // ---------------------------------------------------------------------

    private void lookAt(MinecraftClient client, Vec3d target) {
        Vec3d eye = client.player.getEyePos();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;

        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));

        float curYaw = client.player.getYaw();
        float curPitch = client.player.getPitch();

        float dyaw = MathHelper.wrapDegrees(yaw - curYaw);
        float dpitch = pitch - curPitch;

        // Global sensitivity step (same math vanilla uses)
        float sens = client.options.getMouseSensitivity().getValue().floatValue();
        float f = sens * 0.6F + 0.2F;
        float step = f * f * f * 1.2F;

        dyaw -= dyaw % step;
        dpitch -= dpitch % step;

        client.player.setYaw(curYaw + dyaw);
        client.player.setPitch(MathHelper.clamp(curPitch + dpitch, -90F, 90F));
    }
}

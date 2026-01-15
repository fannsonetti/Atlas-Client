package name.atlasclient.mixin;

import name.atlasclient.script.mining.MithrilMiningScript;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public class MixinClientPlayNetworkHandlerParticles {

    @Inject(method = "onParticle", at = @At("HEAD"))
    private void atlas$onParticle(ParticleS2CPacket packet, CallbackInfo ci) {
        ParticleEffect effect = packet.getParameters();
        if (effect == null) return;

        Identifier id = Registries.PARTICLE_TYPE.getId(effect.getType());
        if (id == null) return;

        String s = id.toString();

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.player != null) {
            double d2 = mc.player.squaredDistanceTo(packet.getX(), packet.getY(), packet.getZ());
            if (d2 < 25.0) {
                MithrilMiningScript.debugParticleSeen(s, packet.getX(), packet.getY(), packet.getZ());
            }
        }

        if ("minecraft:crit".equals(s)
                || "minecraft:happy_villager".equals(s)
                || "minecraft:damage_indicator".equals(s)) {
            MithrilMiningScript.recordMiningParticle(s, packet.getX(), packet.getY(), packet.getZ());
        }
    }
}

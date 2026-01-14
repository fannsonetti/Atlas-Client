package name.atlasclient.mixin;

import name.atlasclient.script.mining.MithrilMiningScript;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientWorld.class)
public class ClientWorldCritParticleMixin {

    @Inject(
            method = "addParticle(Lnet/minecraft/particle/ParticleEffect;DDDDDD)V",
            at = @At("HEAD")
    )
    private void atlasclient$onAddParticle(
            ParticleEffect effect,
            double x, double y, double z,
            double velocityX, double velocityY, double velocityZ,
            CallbackInfo ci
    ) {
        if (effect == null) return;

        Identifier id = Registries.PARTICLE_TYPE.getId(effect.getType());
        if (id == null) return;

        // Command blocks using: /particle minecraft:crit ...
        if ("minecraft:crit".equals(id.toString())) {
            MithrilMiningScript.recordCritParticle(x, y, z);
        }
    }
}

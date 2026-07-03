package com.rinke.portalglimpse.mixin;

import com.rinke.portalglimpse.ghost.GhostState;

import net.minecraft.client.particle.BillboardParticle;
import net.minecraft.client.particle.PortalParticle;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the purple Nether-portal particles while ghosting is active (design doc §3.2 step 3), so
 * they don't drift into a capture. {@code buildGeometry} is the per-frame particle draw call.
 */
@Mixin(BillboardParticle.class)
public class BillboardParticleMixin {

	@Inject(method = "buildGeometry", at = @At("HEAD"), cancellable = true)
	private void portalglimpse$hidePortalParticles(VertexConsumer vertexConsumer, Camera camera, float tickDelta,
			CallbackInfo ci) {
		if (GhostState.isActive() && (Object) this instanceof PortalParticle) {
			ci.cancel();
		}
	}
}

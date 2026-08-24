package com.rinke.portalglimpse.mixin;

import com.rinke.portalglimpse.capture.CaptureRenderer;
import com.rinke.portalglimpse.ghost.GhostState;

import net.minecraft.client.particle.BillboardParticle;
import net.minecraft.client.particle.PortalParticle;
import net.minecraft.client.particle.SpellParticle;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides two kinds of particle from a capture. {@code buildGeometry} is the per-frame particle draw call.
 *
 * <p><b>Portal particles</b>, while ghosting is active (design doc §3.2 step 3) — the portal being captured
 * is hidden, so its own drifting particles shouldn't survive it.
 *
 * <p><b>Status-effect particles</b>, for the length of a capture. These are emitted around the PLAYER, and
 * the capture camera sits inside the portal a couple of blocks away, so a player under any effect gets their
 * own swirl baked into the destination photo — and it stays there for as long as the glimpse does. Only
 * {@link SpellParticle} is suppressed, which is the effect swirl and nothing else; smoke, flame, drips and
 * every other particle in the destination are left alone, because those are the scenery.
 */
@Mixin(BillboardParticle.class)
public class BillboardParticleMixin {

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void portalglimpse$hidePortalParticles(VertexConsumer vertexConsumer, Camera camera, float tickDelta,
			CallbackInfo ci) {
		if (GhostState.isActive() && (Object) this instanceof PortalParticle) {
			ci.cancel();
			return;
		}
		if ((GhostState.isActive() || CaptureRenderer.isActive()) && (Object) this instanceof SpellParticle) {
			ci.cancel();
		}
	}
}

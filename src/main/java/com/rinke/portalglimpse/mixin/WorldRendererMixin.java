package com.rinke.portalglimpse.mixin;

import com.rinke.portalglimpse.render.PortalGlowOutline;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.PostEffectProcessor;
import net.minecraft.client.render.WorldRenderer;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The manual-capture glow ({@link PortalGlowOutline}) draws a portal-opening silhouette into the
 * entity-outline buffer, but vanilla only runs the {@code entity_outline} post-process (the glow)
 * when a real entity is glowing. This forces that post-process to run when we've emitted a glow this
 * frame, so the portal outline actually composites. Injected right after the outline buffer is drawn
 * (so our silhouette is already in the framebuffer).
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {

	@Shadow
	@Final
	private MinecraftClient client;

	@Shadow
	private PostEffectProcessor entityOutlinePostProcessor;

	@Shadow
	protected abstract boolean canDrawEntityOutlines();

	@Inject(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/render/OutlineVertexConsumerProvider;draw()V",
			shift = At.Shift.AFTER))
	private void portalglimpse$forcePortalGlow(CallbackInfo ci) {
		if (!PortalGlowOutline.shouldComposite() || entityOutlinePostProcessor == null
				|| !canDrawEntityOutlines()) {
			return;
		}
		entityOutlinePostProcessor.render(this.client.getRenderTickCounter().getTickDelta(true));
		this.client.getFramebuffer().beginWrite(false);
	}
}


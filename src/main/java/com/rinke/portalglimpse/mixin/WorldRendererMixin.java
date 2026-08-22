package com.rinke.portalglimpse.mixin;

import com.rinke.portalglimpse.render.PortalEntityMask;

import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks into the world renderer for the entity-over-panorama effect.
 *
 * <p><b>Dropped in the 1.21.2 port:</b> this class also used to force vanilla's {@code entity_outline}
 * post-process to run so the manual-capture glow ({@code PortalGlowOutline}) composited, since vanilla
 * only runs it when a real entity is glowing. It did that by shadowing WorldRenderer's
 * {@code entityOutlinePostProcessor} and calling {@code render(tickDelta)} on it directly.
 *
 * <p>1.21.2 removed both. Post-effects are now scheduled through a {@code FrameGraphBuilder} —
 * {@code PostEffectProcessor.render} takes a frame graph and a framebuffer set rather than a tick delta,
 * and the world renderer no longer holds the processor at all; it is built on demand from
 * {@code ShaderLoader.loadPostEffect}. Forcing it again means scheduling into that graph, which is real
 * work for a feature that is already parked: the manual capture it serves is broken on 1.21 too, gated
 * behind {@code /pgdebug} and disabled under shaders. The injection point itself still exists, so this is
 * a deliberate omission rather than a dead end — see TESTING.md's parked list.
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {

	/**
	 * Entity-over-panorama (§ pt.14): a player standing just behind a glimpse portal's plane is skipped
	 * here and re-rendered OVER the finished portal by {@link com.rinke.portalglimpse.render.VanillaGlimpseRenderer}
	 * so they read as standing IN the destination. Detection runs now (entity pass); the re-render consumes
	 * what it collects after the portal's own passes.
	 */
	@Inject(method = "renderEntity", at = @At("HEAD"), cancellable = true)
	private void portalglimpse$deferNearPlayer(Entity entity, double cameraX, double cameraY, double cameraZ,
			float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, CallbackInfo ci) {
		if (entity instanceof PlayerEntity player && PortalEntityMask.shouldDefer(player)) {
			ci.cancel();
		}
	}
}

package com.rinke.portalglimpse.mixin;

import com.rinke.portalglimpse.render.GlimpseRenderers;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws our panorama as a post-composite overlay under an Iris shaderpack. A custom core shader can't
 * render inside Iris's deferred pipeline, so {@code VanillaGlimpseRenderer#renderWorld} stashes the
 * panorama (and this frame's camera view/projection) instead of drawing it, and this hook paints it
 * once Iris is done.
 *
 * <p>Iris runs its composite/final passes in {@code finalizeLevelRendering()} at the TAIL of
 * {@code WorldRenderer.render} — AFTER Fabric's {@code WorldRenderEvents.LAST}, which is why drawing at
 * LAST was overwritten. Injecting at the TAIL of {@code finalizeLevelRendering} puts our draw after the
 * composite, with the main framebuffer bound and the scene depth still intact.
 *
 * <p>{@code @Pseudo} + {@code remap = false}: the target only exists when Iris is installed (skipped
 * silently otherwise), and {@code finalizeLevelRendering} is Iris's own (never-remapped) method.
 * {@code require = 0}: if a future Iris renames it, the overlay just stops instead of crashing.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public class IrisPipelineMixin {

	@Inject(method = "finalizeLevelRendering", at = @At("TAIL"), remap = false, require = 0)
	private void portalglimpse$drawOverlayAfterComposite(CallbackInfo ci) {
		GlimpseRenderers.get().renderAfterShaders();
	}
}

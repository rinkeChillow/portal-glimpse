package com.rinke.portalglimpse.render;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

/**
 * Wires the active {@link GlimpseRenderer} into the frame (after the translucent terrain pass, so
 * glimpses composite like portal-stuff), and drops texture/render state when leaving a world.
 */
public final class GlimpseWorldRendering {

	private GlimpseWorldRendering() {
	}

	public static void register() {
		PortalShaders.register();
		WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> GlimpseRenderers.get().renderWorld(context));
		// The Iris-shader overlay (renderAfterShaders) is driven by SodiumIrisPipelineMixin instead of a
		// WorldRenderEvent, because it must run AFTER Iris's composite — later than any Fabric world event.
		// Manual-capture glow: feed the portal silhouette into the entity-outline buffer before it's
		// flushed, so vanilla's outline post-process (forced by WorldRendererMixin) glows the edges.
		WorldRenderEvents.AFTER_ENTITIES.register(PortalGlowOutline::render);
		// RTT shader method: draw the panorama quad here (entity phase) so Iris shades it in its gbuffers.
		WorldRenderEvents.AFTER_ENTITIES.register(context -> {
			if (!GlimpseSettings.rttTranslucentPass) {
				GlimpseRenderers.get().renderInEntityPass(context);
			}
		});
		// ...or here instead, one pass later. A shaderpack's deferred programs run BETWEEN the opaque gbuffers
		// and the translucent ones, and that is where screen-space AO is applied — so geometry drawn in the
		// translucent stage is composited AFTER the AO and never receives it. Drawing the RTT quad here is
		// therefore the only way to exempt OUR quad from a pack's AO without touching the pack (shader options
		// are compile-time and pack-global, so they can never be scoped per-draw). See
		// GlimpseSettings.rttTranslucentPass. Registered after the renderWorld handler above so the stash it
		// writes each frame is already populated when this runs.
		WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
			if (GlimpseSettings.rttTranslucentPass) {
				GlimpseRenderers.get().renderInEntityPass(context);
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
			GlimpseTextures.clear(client);
			PanoramaTextures.clear(client);
			GlimpseRenderState.clear(client);
		}));
	}
}

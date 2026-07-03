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
		WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> GlimpseRenderers.get().renderWorld(context));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
			GlimpseTextures.clear(client);
			GlimpseRenderState.clear(client);
		}));
	}
}

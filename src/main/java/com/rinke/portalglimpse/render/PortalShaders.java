package com.rinke.portalglimpse.render;

import com.rinke.portalglimpse.PortalGlimpse;

import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;

import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.VertexFormats;

/**
 * Registers and holds Portal Glimpse's core shaders (design doc §7 abstraction: our own shader,
 * loaded through Fabric so it survives resource reloads).
 *
 * <p>{@code portal_panorama} samples the six captured cubemap faces by per-fragment view direction
 * — the parallax "window into another world" (§4.1). Vertex format is POSITION only: the vertex
 * positions are camera-relative, so they double as the view ray in the shader.
 */
public final class PortalShaders {

	private static ShaderProgram panorama;
	private static ShaderProgram loading;
	private static ShaderProgram swirl;

	private PortalShaders() {
	}

	public static void register() {
		CoreShaderRegistrationCallback.EVENT.register(context -> {
			context.register(PortalGlimpse.id("portal_panorama"), VertexFormats.POSITION,
					program -> panorama = program);
			// Full-screen variant for the portal loading screen: a whole-window quad whose per-fragment
			// view ray comes from a fixed camera basis (Forward/Right/Up + the FOV tangents), so the
			// captured destination cubemap fills the screen from the angle the player entered at.
			context.register(PortalGlimpse.id("portal_loading"), VertexFormats.POSITION,
					program -> loading = program);
			// The translucent animated portal swirl tiled over that backdrop (the loading-screen veil).
			context.register(PortalGlimpse.id("portal_swirl"), VertexFormats.POSITION,
					program -> swirl = program);
		});
	}

	/** The panorama shader, or null before resources have loaded. */
	public static ShaderProgram panorama() {
		return panorama;
	}

	/** The full-screen loading-backdrop shader, or null before resources have loaded. */
	public static ShaderProgram loading() {
		return loading;
	}

	/** The loading-screen portal-swirl veil shader, or null before resources have loaded. */
	public static ShaderProgram swirl() {
		return swirl;
	}
}

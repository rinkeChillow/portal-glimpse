package com.rinke.portalglimpse.render;

import com.rinke.portalglimpse.PortalGlimpse;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Defines;
import net.minecraft.client.gl.ShaderLoader;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.ShaderProgramKey;
import net.minecraft.client.render.VertexFormats;

/**
 * Holds Portal Glimpse's core shaders (design doc §7 abstraction: our own shader, surviving resource
 * reloads).
 *
 * <p>{@code portal_panorama} samples the six captured cubemap faces by per-fragment view direction
 * — the parallax "window into another world" (§4.1). Vertex format is POSITION only: the vertex
 * positions are camera-relative, so they double as the view ray in the shader.
 *
 * <p>1.21.2 replaced the registration callback these used to go through with {@link ShaderLoader},
 * which builds a program on demand from a {@link ShaderProgramKey} and caches it. So there is nothing
 * to register up front any more — the key IS the registration, and asking the loader for it again
 * after a resource reload hands back the rebuilt program rather than a stale one. That is why these
 * are fetched per call instead of being held in a field.
 */
public final class PortalShaders {

	private static final ShaderProgramKey PANORAMA = key("portal_panorama");
	/**
	 * Full-screen variant for the portal loading screen: a whole-window quad whose per-fragment view
	 * ray comes from a fixed camera basis (Forward/Right/Up + the FOV tangents), so the captured
	 * destination cubemap fills the screen from the angle the player entered at.
	 */
	private static final ShaderProgramKey LOADING = key("portal_loading");
	/** The translucent animated portal swirl tiled over that backdrop (the loading-screen veil). */
	private static final ShaderProgramKey SWIRL = key("portal_swirl");

	private PortalShaders() {
	}

	private static ShaderProgramKey key(String name) {
		// Definitions live at assets/portal-glimpse/shaders/core/<name>.json, and ShaderLoader resolves
		// them under "shaders", so the id carries the core/ prefix exactly as vanilla's own keys do.
		return new ShaderProgramKey(PortalGlimpse.id("core/" + name), VertexFormats.POSITION,
				Defines.EMPTY);
	}

	/** The panorama shader, or null before resources have loaded. */
	public static ShaderProgram panorama() {
		return program(PANORAMA);
	}

	/** The full-screen loading-backdrop shader, or null before resources have loaded. */
	public static ShaderProgram loading() {
		return program(LOADING);
	}

	/** The loading-screen portal-swirl veil shader, or null before resources have loaded. */
	public static ShaderProgram swirl() {
		return program(SWIRL);
	}

	private static ShaderProgram program(ShaderProgramKey key) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) {
			return null;
		}
		ShaderLoader loader = client.getShaderLoader();
		return loader == null ? null : loader.getOrCreateProgram(key);
	}
}

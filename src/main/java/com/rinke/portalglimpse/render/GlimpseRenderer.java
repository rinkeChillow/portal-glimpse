package com.rinke.portalglimpse.render;

/**
 * Thin rendering abstraction (design doc §7, the "architectural promise").
 *
 * <p>Every glimpse-drawing operation is meant to flow through this interface so the dedicated
 * Sodium/Iris compatibility pass (Phase 6) can swap the implementation instead of rewriting
 * features. Right now this is a skeleton — drawing methods (postcard, veil, panorama parallax)
 * are added as the capture and render phases land. For now a backend only has to identify
 * itself.
 */
public interface GlimpseRenderer {

	/** Human-readable backend name, e.g. {@code "vanilla"} or {@code "sodium"}. */
	String name();

	/** Called once during client init after this backend has been selected as active. */
	default void init() {
	}

	/**
	 * Draw all visible glimpses for this frame. Called after the translucent terrain pass, so the
	 * quads composite over the world like portal-stuff (§4.3).
	 */
	default void renderWorld(net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext context) {
	}
}

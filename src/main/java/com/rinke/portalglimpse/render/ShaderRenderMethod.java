package com.rinke.portalglimpse.render;

/**
 * How the glimpse is drawn while an Iris shaderpack is active (a custom core shader can't render in
 * Iris's deferred pipeline the normal way). Only relevant under shaders; the vanilla/Sodium path is
 * unaffected either way. Exposed as a config toggle so both can be compared in-game.
 */
public enum ShaderRenderMethod {

	/**
	 * Render-to-texture: the panorama is baked to an offscreen texture and drawn as a surface INSIDE
	 * Iris's pipeline, so the shaderpack lights/PBRs it (and the veil) like any other geometry.
	 */
	RTT,

	/**
	 * Post-composite overlay: the panorama + veil are drawn on top after Iris finishes compositing,
	 * with our own shader. Reliable, but not shader-lit (drawn after the shaderpack's passes).
	 */
	OVERLAY;
}

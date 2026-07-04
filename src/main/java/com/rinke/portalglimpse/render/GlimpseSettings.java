package com.rinke.portalglimpse.render;

/**
 * Live-tunable rendering settings (design doc §4.3 / §6). Debug keybinds adjust these in-game;
 * Cloth Config takes over persistence in Phase 5.
 */
public final class GlimpseSettings {

	/** Veil opacity 0..255 — 0 = pure glimpse (only the view), 255 = fully vanilla swirl (§4.3). */
	public static int veilAlpha = 100;

	/** Master toggle for the glimpse view layer (H). The modded veil renders either way. */
	public static boolean glimpsesVisible = true;

	/** Proximity fade of the 2D postcard as the player approaches (toggle J). Phase 4's panorama
	 * crossfade will eventually take over the close range this frees up. */
	public static boolean proximityFade = true;

	/** Interior-mapping sphere radius as a MULTIPLE of the viewer→portal distance (§4.1). Because the
	 * sphere grows/shrinks in lock-step with distance, the portal samples the SAME angular cone of the
	 * panorama at every range — the content scales with the portal (like the postcard) instead of
	 * telephoto-magnifying as you back away. Magnification ratio = (1 + scale) / scale, so higher =
	 * wider / smaller content (→ real-world scale), lower = more zoomed but still constant.
	 * Live-tunable (Numpad 8/2). */
	public static float panoramaScale = 8.0F;

	private GlimpseSettings() {
	}
}

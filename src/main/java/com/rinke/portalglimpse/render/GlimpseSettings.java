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

	/** Radius (blocks) of the fixed interior-mapping sphere centered on the portal (§4.1).
	 * Live-tunable (Numpad 8/2). Large (≈64) behaves like the real distant environment: content
	 * scales with the portal and stays world-fixed. Small = roomier but breaks scaling. */
	public static float panoramaRadius = 64.0F;

	private GlimpseSettings() {
	}
}

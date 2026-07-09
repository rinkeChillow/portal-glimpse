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

	/** Half field-of-view (degrees) the portal shows of the destination panorama (§4.1). The sphere
	 * radius is derived per-frame so the destination scales with the portal (no telephoto) as you move.
	 * Higher = wider view / smaller content, and brings the scaling's onset closer (critical distance
	 * h·cot(FOV)). Live-tunable (Numpad 8/2). */
	public static float panoramaFovDegrees = 60.0F;

	/** DEBUG (Numpad 0): freeze the player in nether portals — no dimension travel and no nausea
	 * wobble — so the in-portal glimpse behaviour can be inspected without being teleported away.
	 * Read from the render thread and (in singleplayer) the integrated-server thread. */
	public static volatile boolean debugBlockPortalTravel = false;

	private GlimpseSettings() {
	}
}

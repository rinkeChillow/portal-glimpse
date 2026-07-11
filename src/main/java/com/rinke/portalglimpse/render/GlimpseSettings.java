package com.rinke.portalglimpse.render;

import net.minecraft.util.Identifier;
import net.minecraft.world.World;

/**
 * Live-tunable rendering settings (design doc §4.3 / §6). The config screen persists these; the
 * debug keybinds adjust them live (not persisted).
 */
public final class GlimpseSettings {

	/**
	 * Veil opacity 0..255, split by the dimension you are VIEWING (the glimpse content), because the
	 * swirl reads differently over a Nether view than an Overworld view. The opacity targets the
	 * view, so it's flipped relative to where you stand: standing in the Overworld you look at the
	 * Nether ({@link #netherVeilAlpha}); standing in the Nether you look at the Overworld
	 * ({@link #overworldVeilAlpha}). 0 = clear window, 255 = full vanilla swirl (§4.3).
	 */
	public static int netherVeilAlpha = 51;     // ~20% — the Nether view, seen from the Overworld
	public static int overworldVeilAlpha = 102; // ~40% — the Overworld view, seen from the Nether

	/** Auto-capture cooldown per portal, in minutes (0 = capture on every eligible travel). */
	public static int autoCaptureCooldownMinutes = 5;

	/** Chunks (each direction) that must be loaded around the arrival portal before an auto capture —
	 * the loading screen is held until they are, so the panorama shows real terrain, not void. */
	public static int captureChunkRadius = 4;

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

	/** Master gate for ALL debug tooling — the tuning keybinds, the debug cubemap (K), the
	 * loading-screen hold (Numpad 5) and the block-travel freeze (Numpad 0). Default OFF; toggled by
	 * the hidden {@code /pgdebug} command ({@link DebugCommand}). Normal players never see the debug
	 * keys do anything. Read from several client-thread spots (and the mixin tick hook). */
	public static volatile boolean debugMode = false;

	private GlimpseSettings() {
	}

	private static boolean isNether(Identifier dimension) {
		return World.NETHER.getValue().equals(dimension);
	}

	/** Veil alpha for a glimpse whose CONTENT is the given (viewed) dimension. */
	public static int veilAlphaForView(Identifier viewedDimension) {
		return isNether(viewedDimension) ? netherVeilAlpha : overworldVeilAlpha;
	}

	/** Veil alpha for a portal in the dimension the player stands in (the view is the opposite dim). */
	public static int veilAlphaForStandingIn(Identifier currentDimension) {
		return isNether(currentDimension) ? overworldVeilAlpha : netherVeilAlpha;
	}

	/** Debug nudge (Numpad 9/6): adjust the veil for the view the player is currently looking at. */
	public static void nudgeVeilForStandingIn(Identifier currentDimension, int delta) {
		if (isNether(currentDimension)) {
			overworldVeilAlpha = Math.max(0, Math.min(255, overworldVeilAlpha + delta));
		} else {
			netherVeilAlpha = Math.max(0, Math.min(255, netherVeilAlpha + delta));
		}
	}
}

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

	/** How the glimpse draws while an Iris shaderpack is active (see {@link ShaderRenderMethod}). No
	 * effect without shaders. Default OVERLAY — the reliable post-composite path (RTT is still WIP:
	 * Iris overwrites geometry drawn via world events, so RTT renders blank for now). */
	public static ShaderRenderMethod shaderRenderMethod = ShaderRenderMethod.OVERLAY;

	/** Entity-over-panorama (§ pt.14): a player standing in a glimpse portal (within half a block of its
	 * plane) is re-rendered OVER the panorama, scissored to the opening, so they read as standing IN the
	 * destination dimension. The band is hard-coded (see {@code PortalEntityMask}); this is the on/off. */
	public static boolean entityOverPanorama = true;

	/** Proximity fade of the 2D postcard as the player approaches (toggle J). Phase 4's panorama
	 * crossfade will eventually take over the close range this frees up. */
	public static boolean proximityFade = true;

	/** RTT + shaders only: inject the invisible "god-ray occluder" — a cage of opaque terrain tucked behind the
	 * panorama so a shaderpack's volumetric light stops on it instead of shining through the glimpse. Turn OFF
	 * to see whether the rays still pierce the panorama on their own (the RTT quad now writes depth itself, so
	 * the cage may be redundant). See {@code VanillaGlimpseRenderer#buildOccluders}. */
	public static boolean godRayOccluder = true;

	/** Show the little nether-portal shortcut in the pause menu, next to Advancements, that opens these
	 * settings ({@code config/PortalMenuButton}). On by default; off for anyone who wants their pause menu
	 * left alone, and reaches the settings through Mod Menu instead. Read when the pause menu is built. */
	public static boolean menuButton = true;

	/** Whether the veil (the portal swirl) is drawn at all while a shaderpack is active. DEFAULT OFF.
	 *
	 * <p>Under shaders the glimpse is the point: the destination comes through shader-lit and reads as a real
	 * view, and laying a swirl over it only takes that away. Without shaders the veil stays exactly as it was,
	 * so a vanilla portal still looks like a vanilla portal — this gate is shader-side only and touches
	 * neither the no-shader path nor the per-dimension {@link #netherVeilAlpha}/{@link #overworldVeilAlpha}.
	 *
	 * <p>Turning it back on restores whichever shader veil the path uses: {@link #rttVeilMode} on RTT, the
	 * post-composite swirl on the overlay path. */
	public static boolean shaderVeil = false;

	/** RTT + shaders only: how the veil (the living portal swirl) is drawn over the RTT panorama — see
	 * {@link RttVeilMode} for what each mode trades away. Cycled live with Numpad * so the two can be compared
	 * side by side in the same portal. */
	public static RttVeilMode rttVeilMode = RttVeilMode.TERRAIN;

	/** Half field-of-view (degrees) the portal shows of the destination panorama (§4.1). The sphere
	 * radius is derived per-frame so the destination scales with the portal (no telephoto) as you move.
	 * Higher = wider view / smaller content, and brings the scaling's onset closer (critical distance
	 * h·cot(FOV)). Live-tunable (Numpad 8/2). */
	public static float panoramaFovDegrees = 60.0F;

	/** RTT-only: camera motion-prediction strength for the offscreen panorama render, as the lerp factor
	 * (1.0 = no prediction / full 1-frame lag, 2.0 = full one-frame extrapolation). Cancels the RTT lag on
	 * smooth motion; lower it toward 1.0 if fast flicks overshoot. Read on the render thread. */
	public static volatile float rttMotionPrediction = 2.0F;

	/** DEBUG (Numpad 0): freeze the player in nether portals — no dimension travel and no nausea
	 * wobble — so the in-portal glimpse behaviour can be inspected without being teleported away.
	 * Read from the render thread and (in singleplayer) the integrated-server thread. */
	public static volatile boolean debugBlockPortalTravel = false;

	/** DEBUG (Numpad 3): blit the offscreen RTT panorama framebuffer full-screen over the view, so its raw
	 * contents can be inspected directly (independent of the portal quad's sampling). Read on the render
	 * thread. Temporary diagnostic for the shader RTT path. */
	public static volatile boolean debugRttBlit = false;

	/** DEBUG (Numpad /): shrink the frustum used for CULLING only — a narrow cone (this full angle, degrees) around
	 * the camera look, WITHOUT touching the real view FOV or screen. Off-screen... no: portals outside this small
	 * cone are culled while still on your (normal-FOV) screen, so you can watch the frustum cull happen. 0 = off
	 * (cull against the real view frustum only). Cycled 0→60→40→25→0. Render-thread. */
	public static volatile float debugCullFovDegrees = 0.0F;

	/** Nearest-N cap: at most this many portals render the expensive PARALLAX PANORAMA each frame (the closest
	 * ones, within {@code PANORAMA_DISTANCE}); the rest fall back to the cheap flat postcard (kept at full
	 * opacity so they never blank). Stops the panorama cost scaling with portal count in a dense scene. Cycle it
	 * down with the /pgdebug key (N) to watch the fallback happen. Render-thread. */
	public static volatile int maxPanoramas = 6;

	/** DEBUG (Numpad +): draw the RTT glimpse in the TRANSLUCENT pass instead of the entity (opaque) pass —
	 * the only lever that can exempt our quad from a shaderpack's screen-space AO without touching the world.
	 *
	 * <p>A pack's deferred programs run BETWEEN the opaque gbuffers and the translucent ones; that's what
	 * {@code deferred}/{@code deferred1} are for, and it's where AO lands. Our quad currently draws at
	 * {@code AFTER_ENTITIES} = the opaque stage, so the AO pass sees its genuine concave box corners and
	 * creases them, correctly. Geometry drawn in the TRANSLUCENT stage is composited after AO has already been
	 * applied and never receives it — and only our quad moves, so the world keeps its AO.
	 *
	 * <p>This is the render-side answer to a problem that has no shader-side one: Solas derives AO from
	 * {@code depthtex0} alone (no material input), so no gbuffer routing can exempt us, and shader OPTIONS are
	 * compile-time and pack-global so they can never be scoped to one draw. Pass order is the remaining
	 * granularity. Unlike an Iris mixin this cannot crash anything — worst case it looks wrong.
	 *
	 * <p>KNOWN COSTS, measured in-game 2026-07-28 — this kills the creases but is NOT free, and both costs
	 * come from the same root: after the deferred pass the real scene is already shaded into the colour
	 * buffer, so our quad is compositing ONTO the finished world rather than being part of it.
	 * <ul>
	 * <li><b>The real terrain shows through the glimpse.</b> Our layer blends, so wherever the sampled FBO is
	 * not fully opaque the already-shaded world behind the portal survives underneath and you read its
	 * silhouettes inside the destination. In the opaque stage this could not happen: the quad wrote depth
	 * before the world was shaded, so the terrain behind was simply never drawn there.</li>
	 * <li><b>Screen-space reflections lose the glimpse.</b> SSR is computed in the composite passes from the
	 * gbuffer, so geometry added afterwards cannot appear in any reflection — water reflects the world
	 * behind the portal instead of the destination. This one is STRUCTURAL, not a bug to chase: "after the
	 * reflection pass" and "visible in reflections" are mutually exclusive by construction.</li>
	 * </ul>
	 * Sorting against other portals' glimpses, clouds and particles may also change. Render-thread. */
	public static volatile boolean rttTranslucentPass = false;

	/** DEBUG (L): split the RTT glimpse into a colour pass and a FLAT depth pass, so a
	 * depth-driven ambient occlusion has no corners to crease.
	 *
	 * <p>The point the other two levers each miss. A pack like Solas builds AO purely from {@code depthtex0},
	 * so what decides whether we get creased is the SHAPE of the depth we write — and our panorama box writes
	 * real concave corners. Dropping depth entirely ({@link #debugRttNoDepthWrite}) removes the creases but
	 * also removes our occlusion; moving to the translucent pass ({@link #rttTranslucentPass}) removes them but
	 * costs reflections and lets the real terrain show through. This keeps the box's COLOUR and replaces only
	 * its DEPTH with a flat plane across the opening: the AO samples a flat wall and finds nothing to crease,
	 * while everything stays in the opaque stage so reflections and occlusion behave exactly as before.
	 *
	 * <p>Trade: the depth is now a plane rather than the box, so things that depend on the box's true depth
	 * (volumetrics stopping at the right distance, entities sorting INSIDE the departure box) see the plane
	 * instead. Watch the box while stepping into a portal. Render-thread. */
	public static volatile boolean rttFlatDepth = false;

	/** DEBUG (Numpad -): force the RTT glimpse to draw WITHOUT writing depth, to test whether a shaderpack's
	 * screen-space AO is what creases our box corners.
	 *
	 * <p>Some packs' AO can't be escaped by picking a render program. Solas computes it in {@code deferred.glsl}
	 * from {@code depthtex0} ALONE — {@code reconstructNormal} derives the surface normal from neighbouring
	 * depth samples, writes the result to colortex5.g, and {@code deferred1} multiplies it into the colour with
	 * no material test beyond "not sky". So the unlit beacon-beam routing that spares us on BSL and Photon (whose
	 * lighting reads gbuffer data that beaconbeam zeroes) does nothing here: the only input is depth, and our
	 * panorama box has genuine concave corners for the AO to find.
	 *
	 * <p>That leaves depth itself as the only lever. With no depth written, depthtex0 keeps whatever lies behind
	 * our quads and the AO computed there is what lands on us — which is ≈1.0 (none) wherever that's distant or
	 * sky. If the creases vanish with this on, the theory holds and it becomes a per-pack flag on
	 * {@link ShaderPackCalibration}; if they persist, depth is ruled out entirely.
	 *
	 * <p>NOT free: depth-write is load-bearing. {@code PortalRenderLayers.COLOR_ONLY} exists because stamping
	 * depth on transparent fragments flattens what shows through, and the glimpse currently writes depth only
	 * while essentially opaque. Expect other portals' glimpses, clouds and particles to sort differently while
	 * this is on. Render-thread. */
	public static volatile boolean debugRttNoDepthWrite = false;

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

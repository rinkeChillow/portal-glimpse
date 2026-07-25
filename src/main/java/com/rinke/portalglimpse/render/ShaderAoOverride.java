package com.rinke.portalglimpse.render;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forces a shaderpack's ambient-occlusion options OFF, by feeding overrides into Iris's own shader-option
 * system — the same channel as un-ticking the setting in Iris's shader GUI.
 *
 * <h2>Why this exists</h2>
 * Some packs' AO cannot be escaped from our side at all. The unlit {@code gbuffers_beaconbeam} routing
 * (see {@code PortalRenderLayers}) spares us on BSL and Photon because their lighting and AO read gbuffer
 * data that beaconbeam zeroes out, so our quads fall out of the lighting path. But Solas computes AO in
 * {@code deferred.glsl} from {@code depthtex0} ALONE — {@code reconstructNormal} derives the surface normal
 * from neighbouring depth samples, writes to colortex5.g, and {@code deferred1} multiplies it into the
 * colour with no material test beyond "not sky". No choice of render program can opt out of that, because
 * the program we land in is not an input to it. Our panorama box has genuine concave corners, so the pack
 * creases them, correctly and unavoidably.
 *
 * <p>That leaves turning the pack's AO off outright. It is a blunt instrument — the whole world loses its
 * screen-space AO, not just our portals — which is why it is OFF by default and framed to the user as a
 * trade, not a fix.
 *
 * <h2>How</h2>
 * {@code ShaderPackOptions(IncludeGraph, Map<String, String>)} takes the user's changed shader options (the
 * map Iris parses out of the pack's config file). {@code IrisShaderPackOptionsMixin} merges the entries
 * below into it, so the pack compiles as though the user had turned AO off themselves. Values therefore use
 * Iris's config encoding: {@code "false"} for a boolean option, or the literal replacement token for a
 * multiple-choice one.
 *
 * <h2>Version fragility, and the fallback</h2>
 * This reaches into Iris internals, so an Iris update can move the target out from under us. The mixin is
 * {@code @Pseudo} + {@code require = 0}, so that degrades to "never fires" rather than a crash — and
 * {@link #markApplied()} lets us TELL, instead of silently rendering creased corners and calling it working.
 * If the setting is on and {@link #isWorking()} goes false after a pack load, the feature reports itself
 * broken and we fall back to the old behaviour (the AO stays, exactly as before this class existed).
 */
public final class ShaderAoOverride {

	private static final Logger LOGGER = LoggerFactory.getLogger("portal-glimpse");

	/** Pack keyword → the option overrides that turn that pack's AO off. Matched with {@code contains()}
	 * against the lowercased pack file name, same convention as {@link ShaderPackCalibration}. Only add a
	 * pack once its option names have actually been read out of its source — a wrong name is silently
	 * ignored by Iris, which looks identical to the mixin not firing. */
	private static final Map<String, Map<String, String>> BY_KEYWORD = new LinkedHashMap<>();

	static {
		// Solas V3.7, shaders/lib/common.glsl: `#define SSAO` under "// Ambient Occlusion //", a plain
		// boolean option. VANILLA_AO next to it is the baked per-vertex kind, which never touches our quads
		// (they aren't block models), so leave it alone — killing it would degrade the world for nothing.
		BY_KEYWORD.put("solas", Map.of("SSAO", "false"));
		// NOT YET WIRED — each needs its option names read out of the pack first, and the encoding differs:
		//   photon        — SHADER_AO looks like a multiple-choice with a SHADER_AO_NONE token, not a boolean
		//   complementary — SSAO_I / SSAO_QUALI (intensity + quality, so "off" may be an intensity of 0)
		//   rethinking    — same codebase as complementary
		//   bliss         — GTAO_* family plus DH_AMBIENT_OCCLUSION
		//   bsl           — no AO define matched; BSL is already fine via the unlit routing, so low priority
	}

	/** Set by the mixin the first time it actually runs. Distinguishes "Iris changed and our hook is dead"
	 * from "the hook ran and this pack simply has no entry" — without it, both look like creased corners. */
	private static volatile boolean applied;
	/** True once we've asked Iris for a pack whose AO we meant to override, so {@link #isWorking()} only
	 * judges the mixin after there was actually something for it to do. */
	private static volatile boolean expected;

	private ShaderAoOverride() {
	}

	/** The overrides for the loaded pack, or empty when the feature is off or the pack has no entry. */
	public static Map<String, String> overridesForCurrentPack() {
		if (!GlimpseSettings.suppressShaderAo) {
			return Collections.emptyMap();
		}
		String name = IrisCompat.currentPackName().orElse("").toLowerCase(java.util.Locale.ROOT);
		if (name.isEmpty()) {
			return Collections.emptyMap();
		}
		for (Map.Entry<String, Map<String, String>> e : BY_KEYWORD.entrySet()) {
			if (name.contains(e.getKey())) {
				expected = true;
				return e.getValue();
			}
		}
		return Collections.emptyMap();
	}

	/** Called from the mixin so we know the hook is alive. */
	public static void markApplied() {
		if (!applied) {
			applied = true;
			LOGGER.info("[portal-glimpse] Iris shader-option hook is live — AO suppression can be applied.");
		}
	}

	/** False once we've had a pack worth overriding but the mixin never fired — i.e. Iris moved and this
	 * feature is dead. The mod keeps working; the corner creases simply come back. */
	public static boolean isWorking() {
		return !expected || applied;
	}

	/** Whether the feature is on, targets the loaded pack, AND the hook is alive. */
	public static boolean isActiveForCurrentPack() {
		return !overridesForCurrentPack().isEmpty() && applied;
	}

	/** One line for the config screen / debug readout. */
	public static String statusLine() {
		if (!GlimpseSettings.suppressShaderAo) {
			return "Shader AO suppression: off";
		}
		if (!isWorking()) {
			return "Shader AO suppression: UNAVAILABLE — Iris changed and the hook no longer applies";
		}
		Map<String, String> o = overridesForCurrentPack();
		if (o.isEmpty()) {
			return "Shader AO suppression: on, but this pack has no entry yet";
		}
		return "Shader AO suppression: on — forcing " + o;
	}

	/** True if any pack is wired, for tests//debug. */
	public static boolean hasEntryFor(String packName) {
		String n = packName.toLowerCase(java.util.Locale.ROOT);
		return BY_KEYWORD.keySet().stream().anyMatch(n::contains);
	}
}

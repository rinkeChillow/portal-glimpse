package com.rinke.portalglimpse.render;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.rinke.portalglimpse.PortalGlimpse;

import net.minecraft.client.MinecraftClient;

/**
 * Per-shaderpack calibration for the RTT glimpse.
 *
 * <p><b>Why this has to exist.</b> The RTT glimpse is drawn through the beacon-beam render type so Iris
 * routes it to a pack's UNLIT {@code gbuffers_beaconbeam} program — that's what kills the AO seams and makes
 * every face of the box shade identically. The catch is that each pack implements that "emissive" pass with
 * completely different maths, applied to OUR texture:
 *
 * <ul>
 *   <li><b>BSL</b> — {@code pow(c, 2.2) * 4.0} then {@code sqrt} ⇒ ≈ {@code 2·c^1.1}. Near-linear, so a
 *       simple counter-dim lands it right. <b>Calibrated.</b></li>
 *   <li><b>Complementary Reimagined / Rethinking Voxels</b> (shared codebase) — {@code c * c * 4.0} plus a
 *       distance falloff. Squaring CRUSHES dark scenes (the Nether reads near-black, only lava survives) and
 *       BURNS bright ones. A scalar can't undo a curve, so this needs gamma pre-compensation, not a dim.</li>
 *   <li><b>Photon</b> — the full-emissive material path; comes out darker, and the fade misbehaves.</li>
 * </ul>
 *
 * <p>So a pack is only "supported" once someone has actually tuned it in-game. Everything else reports
 * unsupported rather than silently looking wrong — see {@link #isCurrentPackSupported()}.
 *
 * <p>Matching is by keyword against the pack's full file name (which carries a version, e.g.
 * {@code "BSL_v10.1.3.zip"}), so a pack keeps working when the user updates it.
 */
public final class ShaderPackCalibration {

	/**
	 * One pack's tuning.
	 *
	 * @param key      the stable id we match on ("bsl", "photon", …)
	 * @param display  human-readable name for messages
	 * @param unlitDim vertex-colour multiplier countering the pack's emissive gain. Both packs start from
	 *                 {@code albedo = texture * color}, so this is a universal brightness dial.
	 */
	public record Calibration(String key, String display, float unlitDim) {
	}

	/** Used when a pack has no entry: the BSL-ish default, so an unknown pack still renders (just uncalibrated)
	 * instead of going black or blowing out. */
	public static final Calibration FALLBACK = new Calibration("unknown", "Uncalibrated", 0.40F);

	/** Keyword → calibration. Ordered: the first keyword contained in the pack name wins, so put more specific
	 * keywords first if two could ever overlap. ONLY add an entry once the pack has actually been tuned. */
	private static final Map<String, Calibration> BY_KEYWORD = new LinkedHashMap<>();

	static {
		// Tuned in-game 2026-07-21. BSL's beaconbeam is near-linear, so the counter-dim alone matches.
		BY_KEYWORD.put("bsl", new Calibration("bsl", "BSL", 0.40F));
		// NOT YET CALIBRATED (deliberately absent — they report unsupported):
		//   photon        — full-emissive path: darker, and the fade misbehaves
		//   complementary — c*c*4 + distance falloff: crushes darks, burns brights (needs gamma, not a dim)
		//   rethinking    — same codebase as complementary
		//   bliss         — untested
	}

	/** Remembers the last pack we logged/announced, so swapping packs re-announces but a steady state doesn't
	 * spam. Written from the render thread. */
	private static volatile String lastSeenPack;
	private static volatile boolean lastSeenSupported;

	private ShaderPackCalibration() {
	}

	/** The active pack's full name (e.g. "BSL_v10.1.3.zip"), or empty when Iris is absent / no pack loaded. */
	public static Optional<String> packName() {
		return IrisCompat.currentPackName();
	}

	/** The calibration for the active pack, or empty if we have none for it (i.e. it's unsupported). */
    public static Optional<Calibration> current() {
		return packName().flatMap(ShaderPackCalibration::match);
	}

	/** Calibration for the active pack, falling back to {@link #FALLBACK} so rendering still works. */
	public static Calibration currentOrFallback() {
		return current().orElse(FALLBACK);
	}

	/** True when a shaderpack is active AND we have a tuned calibration for it. */
	public static boolean isCurrentPackSupported() {
		return current().isPresent();
	}

	private static Optional<Calibration> match(String fullName) {
		String normalized = fullName.toLowerCase(Locale.ROOT);
		for (Map.Entry<String, Calibration> e : BY_KEYWORD.entrySet()) {
			if (normalized.contains(e.getKey())) {
				return Optional.of(e.getValue());
			}
		}
		return Optional.empty();
	}

	/**
	 * Note the active pack, logging once whenever it changes. Returns true if this call saw a NEW pack, so the
	 * caller can announce an unsupported one to the player exactly once per swap.
	 */
	public static boolean noteCurrentPack() {
		String name = packName().orElse(null);
		if (name == null) {
			lastSeenPack = null;
			return false;
		}
		if (name.equals(lastSeenPack)) {
			return false;
		}
		lastSeenPack = name;
		Optional<Calibration> cal = match(name);
		lastSeenSupported = cal.isPresent();
		if (cal.isPresent()) {
			PortalGlimpse.LOGGER.info("Portal Glimpse: shaderpack '{}' recognised — using '{}' RTT calibration",
					name, cal.get().display());
		} else {
			PortalGlimpse.LOGGER.warn("Portal Glimpse: shaderpack '{}' has no RTT calibration yet — the glimpse "
					+ "may look too dark, washed out or blown out. Falling back to the uncalibrated default.", name);
		}
		return true;
	}

	/** Whether the last noted pack was supported (for UI that doesn't want to re-resolve). */
	public static boolean lastSeenSupported() {
		return lastSeenSupported;
	}

	/**
	 * True if a pack whose file/folder name contains {@code keyword} is sitting in the game's
	 * {@code shaderpacks} folder — i.e. the player could switch to it right now without downloading anything.
	 * Lets the "unsupported pack" prompt offer the right action: switch to a supported pack you already have,
	 * versus go and download one.
	 */
	public static boolean isPackInstalled(String keyword) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.runDirectory == null) {
			return false;
		}
		File dir = new File(client.runDirectory, "shaderpacks");
		File[] entries = dir.listFiles();
		if (entries == null) {
			return false;
		}
		String needle = keyword.toLowerCase(Locale.ROOT);
		for (File f : entries) {
			if (f.getName().toLowerCase(Locale.ROOT).contains(needle)) {
				return true;
			}
		}
		return false;
	}

	/** Every keyword we currently have a tuned calibration for (for messages like "supported: bsl"). */
	public static Set<String> supportedKeywords() {
		return Collections.unmodifiableSet(BY_KEYWORD.keySet());
	}

	/** The tuned packs' display names, comma-joined — e.g. "BSL". Grows by itself as entries are added, so UI
	 * that lists what's supported never needs updating alongside the table. */
	public static String supportedDisplayNames() {
		return BY_KEYWORD.values().stream().map(Calibration::display).distinct()
				.collect(java.util.stream.Collectors.joining(", "));
	}
}

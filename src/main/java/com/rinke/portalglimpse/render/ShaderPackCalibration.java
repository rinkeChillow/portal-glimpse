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
	 * One pack's tuning. Two dials, because a pack can distort our texture in two different ways:
	 *
	 * @param key      the stable id we match on ("bsl", "photon", …)
	 * @param display  human-readable name for messages
	 * @param unlitDim vertex-colour multiplier countering the pack's emissive GAIN. Every pack starts from
	 *                 {@code albedo = texture * color}, so this is a universal brightness dial.
	 * @param gamma    exponent applied to the panorama in {@code portal_panorama.fsh} before the pack sees it,
	 *                 countering the pack's CURVE. 1.0 = none. A pack that squares our colour needs 0.5 here;
	 *                 no amount of {@code unlitDim} can substitute, since scaling can't straighten a curve.
	 */
	public record Calibration(String key, String display, float unlitDim, float gamma,
			SupportLevel level, String caveat) {

		/** For packs that only need the brightness dial. */
		public Calibration(String key, String display, float unlitDim) {
			this(key, display, unlitDim, 1.0F, SupportLevel.FULL, null);
		}

		/** For packs that also need the curve dial. */
		public Calibration(String key, String display, float unlitDim, float gamma) {
			this(key, display, unlitDim, gamma, SupportLevel.FULL, null);
		}
	}

	/**
	 * How well the glimpse actually holds up on a pack. Three tiers rather than supported/not, because a pack
	 * can be colour-correct and still show an artefact we cannot reach from our side — saying so plainly beats
	 * either hiding it or writing the whole pack off.
	 */
	public enum SupportLevel {
		/** Tuned and clean. */
		FULL,
		/** Tuned, but with a known visual artefact worth warning about — see {@code caveat}. */
		PARTIAL,
		/** No entry: renders on the fallback numbers and may look wrong. */
		UNSUPPORTED
	}

	/** Used when a pack has no entry: the BSL-ish default, so an unknown pack still renders (just uncalibrated)
	 * instead of going black or blowing out. */
	public static final Calibration FALLBACK =
			new Calibration("unknown", "Uncalibrated", 0.40F, 1.0F, SupportLevel.UNSUPPORTED, null);

	/** Keyword → calibration. Ordered: the first keyword contained in the pack name wins, so put more specific
	 * keywords first if two could ever overlap. ONLY add an entry once the pack has actually been tuned. */
	private static final Map<String, Calibration> BY_KEYWORD = new LinkedHashMap<>();

	static {
		// Tuned in-game 2026-07-21. BSL's beaconbeam is near-linear, so the counter-dim alone matches.
		BY_KEYWORD.put("bsl", new Calibration("bsl", "BSL", 0.40F));
		// Confirmed working in-game 2026-07-28 on the fallback numbers.
		BY_KEYWORD.put("photon", new Calibration("photon", "Photon", 0.40F));
		// Confirmed in-game 2026-07-25 ("practically perfect") at the fallback numbers. Solas's beaconbeam is
		// `albedo.rgb * 1.5` into DRAWBUFFERS:0 — unlit and near-linear like BSL's, so the same dim lands right
		// and no gamma is needed. Listed explicitly so the pack reports SUPPORTED rather than riding FALLBACK.
		// (Its screen-space AO still creases our box corners — that's depth-driven and unrelated to these dials;
		// see GlimpseSettings.debugRttNoDepthWrite.)
		// PARTIAL: colour is right, but Solas derives its AO from depthtex0 alone (no material input), so it
		// creases the glimpse's edges and no render-side routing of ours can opt out. See
		// portal-seam-ambient-occlusion notes / GlimpseSettings.rttFlatDepth.
		BY_KEYWORD.put("solas", new Calibration("solas", "Solas", 0.40F, 1.0F, SupportLevel.PARTIAL,
				"You will see dark creases along the edges of the glimpse."));
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

	/**
	 * Calibration the RENDERER should use: the live-tuning override if one is active for this pack, else the
	 * table entry, else {@link #FALLBACK} so an unknown pack still renders.
	 *
	 * <p>Note this is deliberately the only accessor that honours the override — {@link #current()} and
	 * {@link #isCurrentPackSupported()} keep reporting what the TABLE says, so tuning a pack in-game doesn't
	 * make the "unsupported" prompt and the config status line start lying about it being calibrated.
	 */
	public static Calibration currentOrFallback() {
		Calibration live = tuning;
		if (live != null && packName().map(tuningPack::equals).orElse(false)) {
			return live;
		}
		return current().orElse(FALLBACK);
	}

	// ---------------------------------------------------------------------------------------------------
	// Live tuning (debug keys). Calibrating a pack means looking at it, so the numbers below can't be guessed
	// from source — they're dialled in-game and then pasted into BY_KEYWORD above. Rebuilding the mod per
	// guess would make that unbearable, so the debug keys nudge these instead and print a paste-ready line.
	// ---------------------------------------------------------------------------------------------------

	/** Active live override, or null when we're running straight off the table. */
	private static volatile Calibration tuning;
	/** The pack {@link #tuning} was dialled for, so swapping packs abandons it instead of misapplying it. */
	private static volatile String tuningPack;

	/** Seeds the override from whatever the pack currently renders with, so nudging starts where you are. */
	private static Calibration beginTuning() {
		String name = packName().orElse(null);
		if (name == null) {
			return null;
		}
		if (tuning == null || !name.equals(tuningPack)) {
			Calibration base = current().orElse(FALLBACK);
			tuningPack = name;
			tuning = new Calibration(base.key(), base.display(), base.unlitDim(), base.gamma(),
					base.level(), base.caveat());
		}
		return tuning;
	}

	/** Nudge the brightness dial. Clamped well clear of zero so you can't tune yourself blind. */
	public static void nudgeDim(float delta) {
		Calibration c = beginTuning();
		if (c != null) {
			tuning = new Calibration(c.key(), c.display(),
					clamp(c.unlitDim() + delta, 0.02F, 4.0F), c.gamma(), c.level(), c.caveat());
		}
	}

	/** Nudge the curve dial. 0.5 undoes a squaring pass; 2.0 undoes a square-root one. */
	public static void nudgeGamma(float delta) {
		Calibration c = beginTuning();
		if (c != null) {
			tuning = new Calibration(c.key(), c.display(), c.unlitDim(),
					clamp(c.gamma() + delta, 0.20F, 4.0F), c.level(), c.caveat());
		}
	}

	/** Drop the override and go back to whatever the table says. */
	public static void clearTuning() {
		tuning = null;
		tuningPack = null;
	}

	/** True while a live override is in effect for the loaded pack. */
	public static boolean isTuning() {
		return tuning != null && packName().map(tuningPack::equals).orElse(false);
	}

	/**
	 * The dialled-in values as the exact line to paste into {@link #BY_KEYWORD} — the whole point of tuning is
	 * to end up with one of these, so hand it over ready to commit rather than making someone transcribe two
	 * floats off the action bar. The keyword is guessed from the pack's file name (first alphabetic run,
	 * lowercased), which is what matching uses anyway.
	 */
	public static String tuningTableLine() {
		Calibration c = tuning;
		if (c == null) {
			return "";
		}
		String keyword = guessKeyword(tuningPack);
		String display = keyword.isEmpty() ? "?" : Character.toUpperCase(keyword.charAt(0)) + keyword.substring(1);
		return String.format(Locale.ROOT,
				"BY_KEYWORD.put(\"%s\", new Calibration(\"%s\", \"%s\", %.3fF, %.3fF));",
				keyword, keyword, display, c.unlitDim(), c.gamma());
	}

	/** First run of letters in the file name — "BSL_v10.1.3.zip" → "bsl", "photon_v1.3b.zip" → "photon". */
	private static String guessKeyword(String fullName) {
		if (fullName == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (char ch : fullName.toCharArray()) {
			if (Character.isLetter(ch)) {
				sb.append(Character.toLowerCase(ch));
			} else if (sb.length() > 0) {
				break;
			}
		}
		return sb.toString();
	}

	private static float clamp(float v, float min, float max) {
		return v < min ? min : (v > max ? max : v);
	}

	/** True when a shaderpack is active AND we have a tuned calibration for it. */
	public static boolean isCurrentPackSupported() {
		return current().isPresent();
	}

	/** The active pack's tier — {@link SupportLevel#UNSUPPORTED} when we have no entry for it. */
	public static SupportLevel currentLevel() {
		return current().map(Calibration::level).orElse(SupportLevel.UNSUPPORTED);
	}

	/** The caveat to show for a PARTIAL pack; empty for any other tier. */
	public static Optional<String> currentCaveat() {
		return current().filter(c -> c.level() == SupportLevel.PARTIAL).map(Calibration::caveat);
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
		lastSeenSupported = cal.isPresent() && cal.get().level() == SupportLevel.FULL;
		if (cal.isPresent() && cal.get().level() == SupportLevel.PARTIAL) {
			PortalGlimpse.LOGGER.info("Portal Glimpse: shaderpack '{}' is PARTIALLY supported — {}",
					name, cal.get().caveat());
		} else if (cal.isPresent()) {
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

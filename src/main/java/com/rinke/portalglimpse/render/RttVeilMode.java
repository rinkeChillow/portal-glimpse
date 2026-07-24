package com.rinke.portalglimpse.render;

/**
 * How the veil (the living portal swirl) is drawn on the RTT path.
 *
 * <p>The two modes are not just settings — they are two fundamentally different ways of presenting geometry to
 * a shaderpack, with different things gained and lost:
 *
 * <ul>
 *   <li>{@link #QUAD} — our own quad, drawn on an entity render layer. WE control it completely: per-dimension
 *       opacity, fading with the portal, exact placement. But a pack can only ever shade it as generic entity
 *       geometry, because the block id a pack keys its portal rules on ({@code mc_Entity}) exists only in the
 *       TERRAIN pass. No quad can claim to be a block.</li>
 *   <li>{@link #TERRAIN} — inject the real {@code minecraft:nether_portal} state into the chunk mesh via
 *       {@link TerrainOverride}, so it IS the portal block as far as every renderer and every pack is
 *       concerned, and gets the pack's full portal treatment (emission, bloom, the lot). The cost is that it's
 *       vanilla's rendering: no opacity slider, no fade, no custom placement.</li>
 * </ul>
 *
 * <p>So it's expressive control versus the shaderpack's own magic, and which one wins is a matter of taste —
 * hence a live toggle rather than a decision baked into the code.
 */
public enum RttVeilMode {
	/** No veil at all — the bare panorama. */
	NONE,
	/** Our custom quad: fully controllable, shaded only as generic geometry. */
	QUAD,
	/** The real portal block injected into the terrain mesh: the pack shades it as a nether portal. */
	TERRAIN;

	public RttVeilMode next() {
		return switch (this) {
			case NONE -> QUAD;
			case QUAD -> TERRAIN;
			case TERRAIN -> NONE;
		};
	}

	public String label() {
		return switch (this) {
			case NONE -> "OFF — bare panorama, no swirl";
			case QUAD -> "CUSTOM QUAD — our veil (opacity + fade control, not portal-shaded)";
			case TERRAIN -> "REAL PORTAL BLOCK — shaderpack shades it as a nether portal";
		};
	}
}

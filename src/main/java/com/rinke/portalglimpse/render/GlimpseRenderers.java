package com.rinke.portalglimpse.render;

/**
 * Selects and holds the active {@link GlimpseRenderer}.
 *
 * <p>Backend detection (Sodium/Iris presence) is centralized here so the rest of the mod never
 * has to branch on which renderer is present — it just calls {@link #get()}.
 */
public final class GlimpseRenderers {

	private static GlimpseRenderer active;

	private GlimpseRenderers() {
	}

	/** Chooses the backend and initializes it. Call once from client init. */
	public static void init() {
		active = select();
		active.init();
	}

	/** The active backend. Falls back to vanilla if accessed before {@link #init()}. */
	public static GlimpseRenderer get() {
		if (active == null) {
			active = new VanillaGlimpseRenderer();
		}
		return active;
	}

	private static GlimpseRenderer select() {
		// Phase 6 will branch here on Sodium/Iris presence and return a dedicated backend.
		// Until then, vanilla rendering handles every case.
		return new VanillaGlimpseRenderer();
	}
}

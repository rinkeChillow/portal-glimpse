package com.rinke.portalglimpse.render;

import java.lang.reflect.Method;

import net.minecraft.client.texture.Sprite;

/**
 * Soft, reflection-based bridge to Sodium's {@code SpriteUtil} API. Sodium only advances an animated
 * texture's frames while it sees that sprite rendered somewhere in the world. Our Sodium hiding mixin
 * removes the real nether-portal blocks from Sodium's mesh, so Sodium decides the {@code nether_portal}
 * sprite is unused and freezes it — and our custom veil, which samples that same atlas sprite, freezes
 * with it. Marking the sprite active each frame we draw the veil keeps it animating.
 *
 * <p>Resolved reflectively so the mod carries no hard Sodium dependency: without Sodium (or if its API
 * changes) every call is a no-op and vanilla animates the sprite normally anyway.
 */
public final class SodiumCompat {

	private static final Object SPRITE_UTIL;
	private static final Method MARK_ACTIVE;

	// SodiumWorldRenderer.instanceNullable() (static) + getVisibleChunkCount() + isTerrainRenderComplete()
	// + scheduleTerrainUpdate().
	private static final Method SWR_INSTANCE;
	private static final Method SWR_VISIBLE_COUNT;
	private static final Method SWR_TERRAIN_COMPLETE;
	private static final Method SWR_SCHEDULE_UPDATE;

	static {
		Object instance = null;
		Method mark = null;
		try {
			Class<?> spriteUtil = Class.forName("net.caffeinemc.mods.sodium.api.texture.SpriteUtil");
			instance = spriteUtil.getField("INSTANCE").get(null);
			mark = spriteUtil.getMethod("markSpriteActive", Sprite.class);
		} catch (Throwable ignored) {
			// Sodium absent or API moved — stay null, markSpriteActive() becomes a no-op.
		}
		SPRITE_UTIL = instance;
		MARK_ACTIVE = mark;

		Method swrInstance = null;
		Method swrVisible = null;
		Method swrComplete = null;
		Method swrSchedule = null;
		try {
			Class<?> swr = Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer");
			swrInstance = swr.getMethod("instanceNullable");
			swrVisible = swr.getMethod("getVisibleChunkCount");
			swrComplete = swr.getMethod("isTerrainRenderComplete");
			swrSchedule = swr.getMethod("scheduleTerrainUpdate");
		} catch (Throwable ignored) {
			// Sodium absent — isTerrainReady() will report ready (no gate).
		}
		SWR_INSTANCE = swrInstance;
		SWR_VISIBLE_COUNT = swrVisible;
		SWR_TERRAIN_COMPLETE = swrComplete;
		SWR_SCHEDULE_UPDATE = swrSchedule;
	}

	private SodiumCompat() {
	}

	/**
	 * True if Sodium is absent (no gate) OR Sodium has built a non-empty render list AND its chunk-mesh
	 * build queue is drained — i.e. terrain is actually meshed, not just chunk-data loaded. Used to hold
	 * a capture until Sodium's async meshes exist, so the offscreen render isn't a blank sky.
	 */
	public static boolean isTerrainReady() {
		if (SWR_INSTANCE == null || SWR_VISIBLE_COUNT == null || SWR_TERRAIN_COMPLETE == null) {
			return true;
		}
		try {
			Object renderer = SWR_INSTANCE.invoke(null);
			if (renderer == null) {
				return true; // Sodium not managing this world
			}
			int visible = (Integer) SWR_VISIBLE_COUNT.invoke(renderer);
			boolean buildsDone = (Boolean) SWR_TERRAIN_COMPLETE.invoke(renderer);
			return visible > 0 && buildsDone;
		} catch (Throwable ignored) {
			return true; // never let a compat hiccup block captures
		}
	}

	/**
	 * Force Sodium to rebuild its chunk render list on the next {@code setupTerrain}. Sodium only rebuilds
	 * when the camera position or projection changes, but a panorama capture rotates in place (same
	 * position + projection), so without this every face after the first reuses the first face's
	 * direction-culled list and comes back blank. Call once before each capture shot. No-op without Sodium.
	 */
	public static void scheduleTerrainUpdate() {
		if (SWR_INSTANCE == null || SWR_SCHEDULE_UPDATE == null) {
			return;
		}
		try {
			Object renderer = SWR_INSTANCE.invoke(null);
			if (renderer != null) {
				SWR_SCHEDULE_UPDATE.invoke(renderer);
			}
		} catch (Throwable ignored) {
			// no-op
		}
	}

	/** Ask Sodium to keep animating this sprite this frame. No-op without Sodium. */
	public static void markSpriteActive(Sprite sprite) {
		if (SPRITE_UTIL == null || MARK_ACTIVE == null || sprite == null) {
			return;
		}
		try {
			MARK_ACTIVE.invoke(SPRITE_UTIL, sprite);
		} catch (Throwable ignored) {
			// Never let a compat hiccup break rendering.
		}
	}
}

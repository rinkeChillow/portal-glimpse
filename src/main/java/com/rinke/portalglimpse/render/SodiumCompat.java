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
	}

	private SodiumCompat() {
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

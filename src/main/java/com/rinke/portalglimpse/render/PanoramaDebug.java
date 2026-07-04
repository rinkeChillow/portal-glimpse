package com.rinke.portalglimpse.render;

import java.util.UUID;

import com.rinke.portalglimpse.PortalGlimpse;

import net.minecraft.util.Identifier;

/**
 * Debug aid for the panorama shader: swaps a portal's captured cubemap for six labeled, coloured
 * test faces (assets/portal-glimpse/textures/debug/panorama_0..5.png) so face mapping and
 * orientation (rotation / mirroring) are readable at a glance. Toggled per portal with the K key.
 */
public final class PanoramaDebug {

	/** The six labeled test faces, in capture order (0=south, 1=west, 2=north, 3=east, 4=up, 5=down). */
	public static final Identifier[] FACES = new Identifier[6];

	static {
		for (int i = 0; i < 6; i++) {
			FACES[i] = Identifier.of(PortalGlimpse.MOD_ID, "textures/debug/panorama_" + i + ".png");
		}
	}

	private static volatile UUID target;

	private PanoramaDebug() {
	}

	/** Toggle the debug cubemap on the given portal (off if it was already the target). */
	public static void toggle(UUID id) {
		target = id.equals(target) ? null : id;
	}

	public static boolean isTarget(UUID id) {
		return id.equals(target);
	}
}

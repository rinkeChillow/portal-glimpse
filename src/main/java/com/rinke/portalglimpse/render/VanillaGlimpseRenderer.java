package com.rinke.portalglimpse.render;

/**
 * Default glimpse renderer built on vanilla Minecraft rendering.
 *
 * <p>Design doc §7 strategy "B leaning C": build every feature on vanilla rendering first and
 * get the mod alive, then a dedicated Sodium/Iris pass swaps this backend out. "Sodium is the
 * baseline reality" — but vanilla is where we start.
 */
public class VanillaGlimpseRenderer implements GlimpseRenderer {

	@Override
	public String name() {
		return "vanilla";
	}
}

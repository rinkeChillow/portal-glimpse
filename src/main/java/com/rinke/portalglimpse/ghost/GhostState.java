package com.rinke.portalglimpse.ghost;

import java.util.Collections;
import java.util.Set;

import net.minecraft.util.math.BlockPos;

/**
 * The shared "ghosting" flag (design doc §3.2 step 3): while active, a specific set of block
 * positions is made invisible so a capture isn't polluted by the portal frame/blocks.
 *
 * <p>Read from chunk-builder worker threads (the render mixins) and written from the client
 * thread, so both fields are {@code volatile} and {@link #hidden} is always replaced with an
 * immutable snapshot rather than mutated in place.
 */
public final class GhostState {

	private static volatile boolean active = false;
	private static volatile Set<Long> hidden = Collections.emptySet();

	private GhostState() {
	}

	public static boolean isActive() {
		return active;
	}

	public static boolean isHidden(long posLong) {
		return active && hidden.contains(posLong);
	}

	public static boolean isHidden(BlockPos pos) {
		return isHidden(pos.asLong());
	}

	/** Activate ghosting over the given positions (an immutable snapshot is taken). */
	public static void set(Set<Long> hiddenPositions) {
		hidden = Set.copyOf(hiddenPositions);
		active = true;
	}

	public static void clear() {
		active = false;
		hidden = Collections.emptySet();
	}
}

package com.rinke.portalglimpse.ghost;

import java.util.Collections;
import java.util.Map;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

/**
 * The shared "ghosting" flag (design doc §3.2 step 3): while active, a specific set of block
 * positions is meshed as a substitute state so a capture isn't polluted by the portal frame/blocks.
 *
 * <p>Each ghosted position maps to the {@link BlockState} it should mesh as: portal-interior blocks
 * (and frame obsidian with no matching wall around it) map to air so they vanish; frame obsidian
 * embedded in a wall maps to a CLONE of that wall block so the capture reads as continuous instead
 * of punching a hole (see {@code GhostController.computeHiddenBlocks}).
 *
 * <p>Read from chunk-builder worker threads (the render mixins) and written from the client
 * thread, so both fields are {@code volatile} and {@link #replacements} is always replaced with an
 * immutable snapshot rather than mutated in place.
 */
public final class GhostState {

	private static volatile boolean active = false;
	private static volatile Map<Long, BlockState> replacements = Collections.emptyMap();

	private GhostState() {
	}

	public static boolean isActive() {
		return active;
	}

	public static boolean isHidden(long posLong) {
		return active && replacements.containsKey(posLong);
	}

	public static boolean isHidden(BlockPos pos) {
		return isHidden(pos.asLong());
	}

	/** The state the given position should mesh as while ghosted, or {@code null} if it isn't ghosted. */
	public static BlockState replacementFor(BlockPos pos) {
		if (!active) {
			return null;
		}
		return replacements.get(pos.asLong());
	}

	/** Activate ghosting; each position maps to the state it should mesh as (an immutable snapshot is taken). */
	public static void set(Map<Long, BlockState> hiddenReplacements) {
		replacements = Map.copyOf(hiddenReplacements);
		active = true;
	}

	public static void clear() {
		active = false;
		replacements = Collections.emptyMap();
	}
}

package com.rinke.portalglimpse.render;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

/**
 * Client-side terrain-mesh injection: positions meshed as a substitute {@link BlockState} through the
 * SAME hooks as capture-ghosting ({@code ChunkRendererRegionMixin} + {@code SodiumLevelSliceMixin}).
 * Injected states are therefore REAL TERRAIN to every renderer and every shaderpack — meshed by
 * vanilla/Sodium into the terrain gbuffer pass, writing depthtex0+depthtex1 and getting normal block
 * handling — indistinguishable from a block the player placed by hand. Render-only: collision and
 * interaction still follow the real (usually air) blocks.
 *
 * <p>Born from the god-ray fix: both BSL's and Photon's volumetric marches are bounded ONLY by gbuffer
 * depth (verified in the packs' GLSL), which no entity-pass or raw-GL draw ever wrote — but a hand-placed
 * block occludes them perfectly. This makes our occluder BE that block.
 *
 * <p>Fed by {@link #syncPortal} — the per-frame portal god-ray occluder, which diffs the desired set and
 * reschedules changed chunks, exactly like {@link GlimpseRenderState#sync}.
 *
 * <p>Read from chunk-build worker threads, written from the client thread — volatile immutable snapshots.
 */
public final class TerrainOverride {

	private static volatile Map<Long, BlockState> portal = Collections.emptyMap();

	/** Positions an override was REMOVED from, each with the client tick by which its rebuild must have been
	 * re-issued. Only removals are tracked, because only removals can be SEEN: a dropped rebuild for an ADDED
	 * position just means no occluder there (the god rays come back — invisible unless you look for it), while
	 * a dropped rebuild for a REMOVED one leaves the black occluder meshed in mid-air until some unrelated
	 * block update happens to rebuild that section. */
	private static final Map<Long, Integer> pendingRemovals = new HashMap<>();
	/** Cap, so a long run of churn can't grow the map without bound. */
	private static final int MAX_PENDING = 4096;
	/** Client ticks a removed position is re-checked for. An ABSOLUTE deadline per position, not a countdown:
	 * the occluder set changes almost every frame while a portal is on screen, so any timer that restarted on
	 * each new removal would be starved forever and never fire. */
	private static final int RETRY_TICKS = 40;
	/** How often the pending set is re-issued while it has entries. */
	private static final int RETRY_INTERVAL_TICKS = 10;
	private static int tickCounter;

	private TerrainOverride() {
	}

	/** The state the given packed position should mesh as, or {@code null} if not overridden. */
	public static BlockState replacementFor(long posLong) {
		Map<Long, BlockState> p = portal;
		return p.isEmpty() ? null : p.get(posLong);
	}

	/** Replace the per-frame portal occluder set; schedules chunk rebuilds around any position that
	 * appeared or disappeared (values never change in place — always the same occluder block). */
	public static void syncPortal(MinecraftClient client, Map<Long, BlockState> desired) {
		Map<Long, BlockState> current = portal;
		if (current.equals(desired)) {
			return;
		}
		// Positions present in exactly one of current/desired — those chunks must re-mesh.
		Set<Long> changed = new HashSet<>(current.keySet());
		changed.addAll(desired.keySet());
		Set<Long> intersection = new HashSet<>(current.keySet());
		intersection.retainAll(desired.keySet());
		changed.removeAll(intersection);

		// Anything leaving the map must stop being meshed, and one scheduleBlockRenders is not reliable for
		// that: it only reaches sections the renderer currently holds built, so a removal made while crossing
		// a chunk boundary — or while that section is mid-rebuild or not yet built — is silently dropped.
		Set<Long> removed = new HashSet<>(current.keySet());
		removed.removeAll(desired.keySet());
		if (!removed.isEmpty()) {
			if (pendingRemovals.size() + removed.size() > MAX_PENDING) {
				pendingRemovals.clear();
			}
			int deadline = tickCounter + RETRY_TICKS;
			for (long pos : removed) {
				pendingRemovals.put(pos, deadline);
			}
		}

		portal = desired.isEmpty() ? Collections.emptyMap() : Map.copyOf(desired);
		reschedule(client, changed);
	}

	/**
	 * Re-issue the rebuild for recently removed positions until their deadline passes.
	 *
	 * <p>Deliberately does NOT touch the renderer globally — no {@code worldRenderer.reload()}. Only the
	 * specific positions are rescheduled, so this cannot interfere with terrain loading or with
	 * {@link GlimpseRenderState}'s own block-hiding sync.
	 */
	public static void tick(MinecraftClient client) {
		tickCounter++;
		if (pendingRemovals.isEmpty() || tickCounter % RETRY_INTERVAL_TICKS != 0) {
			return;
		}
		final int now = tickCounter;
		pendingRemovals.values().removeIf(deadline -> deadline <= now);
		if (pendingRemovals.isEmpty()) {
			return;
		}
		reschedule(client, new HashSet<>(pendingRemovals.keySet()));
	}

	/** Drop the injected set and any pending work — for LEAVING a world, where keeping either would apply one
	 * world's overrides to another's terrain. NOT called on join: the renderer is at its most fragile while a
	 * world is still loading, and the per-frame sync re-establishes the correct set on the first frame anyway. */
	public static void reset() {
		portal = Collections.emptyMap();
		pendingRemovals.clear();
	}

	public static void clearPortal(MinecraftClient client) {
		syncPortal(client, Collections.emptyMap());
	}

	private static void reschedule(MinecraftClient client, Set<Long> changed) {
		if (client.worldRenderer == null || changed.isEmpty()) {
			return;
		}
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (long packed : changed) {
			BlockPos pos = BlockPos.fromLong(packed);
			minX = Math.min(minX, pos.getX());
			maxX = Math.max(maxX, pos.getX());
			minY = Math.min(minY, pos.getY());
			maxY = Math.max(maxY, pos.getY());
			minZ = Math.min(minZ, pos.getZ());
			maxZ = Math.max(maxZ, pos.getZ());
		}
		client.worldRenderer.scheduleBlockRenders(minX, minY, minZ, maxX, maxY, maxZ);
	}
}

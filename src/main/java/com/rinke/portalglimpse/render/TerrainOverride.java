package com.rinke.portalglimpse.render;

import java.util.Collections;
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
 * <p>Two independent sources, both consulted on the mesh hot path (debug wins ties):
 * <ul>
 *   <li>{@link #syncPortal} — the per-frame portal god-ray occluder (diffs + reschedules changed chunks,
 *       exactly like {@link GlimpseRenderState#sync}).</li>
 *   <li>{@link #setDebug}/{@link #clearDebug} — the manual {@code ShadowBoxDebug} test cube.</li>
 * </ul>
 *
 * <p>Read from chunk-build worker threads, written from the client thread — volatile immutable snapshots.
 */
public final class TerrainOverride {

	private static volatile Map<Long, BlockState> portal = Collections.emptyMap();
	private static volatile Map<Long, BlockState> debug = Collections.emptyMap();

	private TerrainOverride() {
	}

	/** The state the given packed position should mesh as, or {@code null} if not overridden. */
	public static BlockState replacementFor(long posLong) {
		Map<Long, BlockState> d = debug;
		if (!d.isEmpty()) {
			BlockState s = d.get(posLong);
			if (s != null) {
				return s;
			}
		}
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

		portal = desired.isEmpty() ? Collections.emptyMap() : Map.copyOf(desired);
		reschedule(client, changed);
	}

	public static void clearPortal(MinecraftClient client) {
		syncPortal(client, Collections.emptyMap());
	}

	/** Set the debug cube; the caller ({@code ShadowBoxDebug}) schedules its own re-mesh. */
	public static void setDebug(Map<Long, BlockState> map) {
		debug = map.isEmpty() ? Collections.emptyMap() : Map.copyOf(map);
	}

	public static void clearDebug() {
		debug = Collections.emptyMap();
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

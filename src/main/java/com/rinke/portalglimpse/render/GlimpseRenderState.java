package com.rinke.portalglimpse.render;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

/**
 * Portal-interior block positions whose vanilla rendering is replaced by a glimpse. The chunk
 * mesher skips them (via {@code ChunkRendererRegionMixin}) and {@code VanillaGlimpseRenderer}
 * draws the glimpse + veil quads instead.
 *
 * <p>Written from the render thread each frame ({@link #sync}); read by chunk-builder worker
 * threads, hence the volatile immutable snapshot.
 */
public final class GlimpseRenderState {

	private static volatile Set<Long> hidden = Collections.emptySet();

	private GlimpseRenderState() {
	}

	public static boolean isHidden(BlockPos pos) {
		return hidden.contains(pos.asLong());
	}

	/** Packed-coordinate variant for allocation-free callers (the Sodium mesher hook). */
	public static boolean isHidden(long posLong) {
		return hidden.contains(posLong);
	}

	/** Update the hidden set; schedules chunk rebuilds around any position that changed. */
	public static void sync(MinecraftClient client, Set<Long> desired) {
		Set<Long> current = hidden;
		if (current.equals(desired)) {
			return;
		}
		Set<Long> changed = new HashSet<>(current);
		changed.addAll(desired);
		Set<Long> intersection = new HashSet<>(current);
		intersection.retainAll(desired);
		changed.removeAll(intersection);

		hidden = Set.copyOf(desired);

		if (client.worldRenderer != null && !changed.isEmpty()) {
			int minX = Integer.MAX_VALUE;
			int minY = Integer.MAX_VALUE;
			int minZ = Integer.MAX_VALUE;
			int maxX = Integer.MIN_VALUE;
			int maxY = Integer.MIN_VALUE;
			int maxZ = Integer.MIN_VALUE;
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

	public static void clear(MinecraftClient client) {
		sync(client, Collections.emptySet());
	}
}

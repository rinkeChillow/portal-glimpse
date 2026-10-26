package com.rinke.portalglimpse.render;

import java.util.Collections;
import java.util.Set;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

/**
 * Portal-interior block positions whose vanilla rendering is replaced by a glimpse. The chunk
 * mesher skips them (via {@code ChunkRendererRegionMixin}) and {@code VanillaGlimpseRenderer}
 * draws the glimpse + veil quads instead.
 *
 * <p>Written from the render thread each frame ({@link #sync}); read by chunk-builder worker
 * threads, hence the volatile snapshot — a set built in full, published by one volatile write, and
 * never mutated afterwards, so concurrent reads are safe without locking.
 *
 * <p>The snapshot is a primitive {@link LongSet} because {@link #isHidden} runs for every block of every
 * chunk rebuild. A {@code Set<Long>} boxed a fresh {@code Long} on each of those calls — permanently, in
 * every world, even with no portal anywhere near — since the boxing happens at the call site before the
 * set is even consulted.
 */
public final class GlimpseRenderState {

	private static volatile LongSet hidden = LongSets.EMPTY_SET;

	private GlimpseRenderState() {
	}

	public static boolean isHidden(BlockPos pos) {
		LongSet h = hidden;
		return !h.isEmpty() && h.contains(pos.asLong());
	}

	/** Packed-coordinate variant for allocation-free callers (the Sodium mesher hook). */
	public static boolean isHidden(long posLong) {
		LongSet h = hidden;
		return !h.isEmpty() && h.contains(posLong);
	}

	/** Update the hidden set; schedules chunk rebuilds around any position that changed. */
	public static void sync(MinecraftClient client, Set<Long> desired) {
		LongSet current = hidden;
		LongSet next = desired.isEmpty() ? LongSets.EMPTY_SET : new LongOpenHashSet(desired);
		if (current.equals(next)) {
			return;
		}
		// Positions in exactly one of the two sets — those sections must re-mesh.
		LongOpenHashSet changed = new LongOpenHashSet(current);
		changed.addAll(next);
		LongOpenHashSet both = new LongOpenHashSet(current);
		both.retainAll(next);
		changed.removeAll(both);

		hidden = next;
		BlockRebuilds.schedule(client, changed);
	}

	/**
	 * Force a re-mesh of every currently-hidden position, even though the set hasn't changed. Toggling an
	 * Iris shaderpack re-meshes all chunks, which can bring a hidden portal back (no set-diff fires a
	 * rebuild), so we re-schedule the hidden region when the shader state flips.
	 */
	public static void reschedule(MinecraftClient client) {
		BlockRebuilds.schedule(client, hidden);
	}

	public static void clear(MinecraftClient client) {
		sync(client, Collections.emptySet());
	}
}

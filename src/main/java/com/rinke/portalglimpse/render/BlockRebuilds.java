package com.rinke.portalglimpse.render;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;

/**
 * Re-meshes the chunk sections around a set of changed block positions — and only those.
 *
 * <p>The callers used to fold every changed position into ONE bounding box and hand that to
 * {@code scheduleBlockRenders}. Fine for a single portal, but two portals changing in the same frame
 * 150 blocks apart produced a box spanning everything between them, and every section in it rebuilt.
 * The god-ray occluder set changes most frames while a portal is on screen, so that was a rebuild storm
 * whenever two portals were in play at once.
 *
 * <p>Here the positions are grouped by the section they fall in, and each group is scheduled on its own.
 * Vanilla's six-int {@code scheduleBlockRenders} already pads by one block before converting to section
 * coordinates, so every changed block and its neighbours are still covered — this is a strict subset of
 * what the single box rebuilt, never less than what is needed.
 */
final class BlockRebuilds {

	private BlockRebuilds() {
	}

	static void schedule(MinecraftClient client, LongSet positions) {
		if (client.worldRenderer == null || positions.isEmpty()) {
			return;
		}
		// Per-section [minX, minY, minZ, maxX, maxY, maxZ].
		Long2ObjectMap<int[]> boxes = new Long2ObjectOpenHashMap<>();
		for (LongIterator it = positions.iterator(); it.hasNext(); ) {
			long packed = it.nextLong();
			int x = BlockPos.unpackLongX(packed);
			int y = BlockPos.unpackLongY(packed);
			int z = BlockPos.unpackLongZ(packed);
			long key = ChunkSectionPos.asLong(x >> 4, y >> 4, z >> 4);
			int[] b = boxes.get(key);
			if (b == null) {
				boxes.put(key, new int[] { x, y, z, x, y, z });
			} else {
				b[0] = Math.min(b[0], x);
				b[1] = Math.min(b[1], y);
				b[2] = Math.min(b[2], z);
				b[3] = Math.max(b[3], x);
				b[4] = Math.max(b[4], y);
				b[5] = Math.max(b[5], z);
			}
		}
		for (int[] b : boxes.values()) {
			client.worldRenderer.scheduleBlockRenders(b[0], b[1], b[2], b[3], b[4], b[5]);
		}
	}
}

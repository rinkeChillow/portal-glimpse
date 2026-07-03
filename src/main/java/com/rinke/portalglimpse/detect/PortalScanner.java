package com.rinke.portalglimpse.detect;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Locates and assembles Nether portals from the block world (design doc §5, §12 item 8).
 *
 * <p>Client-side detection: on chunk load we cheaply find portal blocks (palette-gated per
 * section), then flood-fill each seed into a complete portal plane. A portal that extends into
 * an unloaded chunk is <em>deferred</em> — we only ever register a complete block set so identity
 * stays stable (§5.3). It gets picked up when the neighbouring chunk loads.
 */
public final class PortalScanner {

	private PortalScanner() {
	}

	/** A fully-resolved portal candidate ready to register. */
	public static final class Candidate {
		public final List<BlockPos> interior;
		public final BlockPos anchor;
		public final Direction.Axis axis;

		Candidate(List<BlockPos> interior, BlockPos anchor, Direction.Axis axis) {
			this.interior = interior;
			this.anchor = anchor;
			this.axis = axis;
		}
	}

	/** Cheaply find every Nether-portal block in a freshly loaded chunk. */
	public static List<BlockPos> findPortalBlocks(ClientWorld world, WorldChunk chunk) {
		List<BlockPos> found = new ArrayList<>();
		ChunkSection[] sections = chunk.getSectionArray();
		int startX = chunk.getPos().getStartX();
		int startZ = chunk.getPos().getStartZ();

		for (int index = 0; index < sections.length; index++) {
			ChunkSection section = sections[index];
			if (section == null || section.isEmpty()) {
				continue;
			}
			// Palette gate: skip sections that provably contain no portal blocks.
			if (!section.hasAny(state -> state.isOf(Blocks.NETHER_PORTAL))) {
				continue;
			}
			int baseY = world.sectionIndexToCoord(index) * 16;
			for (int ly = 0; ly < 16; ly++) {
				for (int lx = 0; lx < 16; lx++) {
					for (int lz = 0; lz < 16; lz++) {
						if (section.getBlockState(lx, ly, lz).isOf(Blocks.NETHER_PORTAL)) {
							found.add(new BlockPos(startX + lx, baseY + ly, startZ + lz));
						}
					}
				}
			}
		}
		return found;
	}

	/**
	 * Flood-fill the full portal plane from {@code seed}. Returns {@code null} if the plane may
	 * extend into an unloaded chunk (defer — see class doc).
	 */
	public static Candidate scan(ClientWorld world, BlockPos seed) {
		BlockState seedState = world.getBlockState(seed);
		if (!seedState.isOf(Blocks.NETHER_PORTAL)) {
			return null;
		}
		Direction.Axis axis = seedState.get(NetherPortalBlock.AXIS);

		// A portal plane is vertical, spanning Y and its horizontal axis.
		Direction[] dirs = axis == Direction.Axis.X
				? new Direction[] { Direction.UP, Direction.DOWN, Direction.EAST, Direction.WEST }
				: new Direction[] { Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH };

		Set<BlockPos> visited = new HashSet<>();
		Deque<BlockPos> queue = new ArrayDeque<>();
		queue.add(seed);
		visited.add(seed);

		List<BlockPos> interior = new ArrayList<>();
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;

		while (!queue.isEmpty()) {
			BlockPos pos = queue.poll();
			interior.add(pos);
			minX = Math.min(minX, pos.getX());
			minY = Math.min(minY, pos.getY());
			minZ = Math.min(minZ, pos.getZ());

			for (Direction dir : dirs) {
				BlockPos next = pos.offset(dir);
				if (visited.contains(next)) {
					continue;
				}
				if (!isLoaded(world, next)) {
					// The plane might continue past the loaded edge — bail and retry later.
					return null;
				}
				if (world.getBlockState(next).isOf(Blocks.NETHER_PORTAL)) {
					visited.add(next);
					queue.add(next);
				}
			}
		}

		return new Candidate(interior, new BlockPos(minX, minY, minZ), axis);
	}

	private static boolean isLoaded(ClientWorld world, BlockPos pos) {
		return world.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false) != null;
	}
}

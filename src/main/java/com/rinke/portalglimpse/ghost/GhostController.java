package com.rinke.portalglimpse.ghost;

import java.util.HashSet;
import java.util.Set;

import com.rinke.portalglimpse.data.PortalRecord;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Drives portal "ghosting" (design doc §3.2 step 3): make a specific portal's frame + portal
 * blocks (and its purple particles) invisible so a capture is clean, then restore them.
 *
 * <p>Used by the capture pipeline — {@link #activate}/{@link #deactivate} wrap the capture.
 * Toggling schedules a targeted rebuild of only the portal's chunk sections (silent — no
 * resource-pack reload screen, no full-world flicker).
 */
public final class GhostController {

	/** Block-coordinate box {minX,minY,minZ,maxX,maxY,maxZ} of the currently/last ghosted region. */
	private static int[] lastRegion;

	private GhostController() {
	}

	/** Ghost a specific portal (its interior blocks + coplanar obsidian frame). */
	public static void activate(MinecraftClient client, PortalRecord record) {
		ClientWorld world = client.world;
		if (world == null) {
			return;
		}
		Set<Long> hidden = computeHiddenBlocks(world, record);
		GhostState.set(hidden);
		lastRegion = regionOf(hidden);
		rebuild(client, lastRegion);
	}

	public static void deactivate(MinecraftClient client) {
		GhostState.clear();
		rebuild(client, lastRegion);
		lastRegion = null;
	}

	/**
	 * Hidden set = the portal-interior blocks plus the obsidian frame in the same plane. The frame
	 * is coplanar with the interior, so we scan the interior's bounding rectangle expanded by one
	 * along the two in-plane axes (the perpendicular axis stays fixed) and collect obsidian.
	 */
	public static Set<Long> computeHiddenBlocks(ClientWorld world, PortalRecord record) {
		Set<Long> hidden = new HashSet<>();

		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (BlockPos pos : record.interior) {
			hidden.add(pos.asLong());
			minX = Math.min(minX, pos.getX());
			maxX = Math.max(maxX, pos.getX());
			minY = Math.min(minY, pos.getY());
			maxY = Math.max(maxY, pos.getY());
			minZ = Math.min(minZ, pos.getZ());
			maxZ = Math.max(maxZ, pos.getZ());
		}

		BlockPos.Mutable cursor = new BlockPos.Mutable();
		if (record.axis == Direction.Axis.X) {
			int z = minZ; // portal plane is X/Y at a fixed Z
			for (int x = minX - 1; x <= maxX + 1; x++) {
				for (int y = minY - 1; y <= maxY + 1; y++) {
					addIfObsidian(world, cursor.set(x, y, z), hidden);
				}
			}
		} else {
			int x = minX; // portal plane is Z/Y at a fixed X
			for (int z = minZ - 1; z <= maxZ + 1; z++) {
				for (int y = minY - 1; y <= maxY + 1; y++) {
					addIfObsidian(world, cursor.set(x, y, z), hidden);
				}
			}
		}
		return hidden;
	}

	private static void addIfObsidian(ClientWorld world, BlockPos pos, Set<Long> hidden) {
		if (world.getBlockState(pos).isOf(Blocks.OBSIDIAN)) {
			hidden.add(pos.asLong());
		}
	}

	private static int[] regionOf(Set<Long> positions) {
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (long packed : positions) {
			BlockPos pos = BlockPos.fromLong(packed);
			minX = Math.min(minX, pos.getX());
			maxX = Math.max(maxX, pos.getX());
			minY = Math.min(minY, pos.getY());
			maxY = Math.max(maxY, pos.getY());
			minZ = Math.min(minZ, pos.getZ());
			maxZ = Math.max(maxZ, pos.getZ());
		}
		return new int[] { minX, minY, minZ, maxX, maxY, maxZ };
	}

	private static void rebuild(MinecraftClient client, int[] region) {
		if (region == null || client.worldRenderer == null) {
			return;
		}
		// Rebuild only the sections covering the portal (block-coord box). Silent chunk re-render,
		// NOT a resource reload — no "resource pack changed" screen, no full-world flicker.
		client.worldRenderer.scheduleBlockRenders(region[0], region[1], region[2], region[3], region[4], region[5]);
	}
}

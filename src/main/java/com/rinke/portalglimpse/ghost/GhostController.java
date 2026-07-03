package com.rinke.portalglimpse.ghost;

import java.util.HashSet;
import java.util.Set;

import com.rinke.portalglimpse.data.PortalRecord;
import com.rinke.portalglimpse.data.PortalStore;
import com.rinke.portalglimpse.detect.PortalDetection;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Drives portal "ghosting" (design doc §3.2 step 3): make a specific portal's frame + portal
 * blocks (and its purple particles) invisible so the capture is clean, then restore them.
 *
 * <p>This is the primitive Phase 2's capture will reuse — {@link #activate}/{@link #deactivate}
 * wrap the capture. For now {@link #toggleNearest} exposes it to a debug keybind for testing.
 * Toggling triggers a silent chunk-mesh rebuild (no resource-pack reload screen).
 */
public final class GhostController {

	private GhostController() {
	}

	/** Ghost a specific portal (its interior blocks + coplanar obsidian frame). */
	public static void activate(MinecraftClient client, PortalRecord record) {
		ClientWorld world = client.world;
		if (world == null) {
			return;
		}
		GhostState.set(computeHiddenBlocks(world, record));
		rebuild(client);
	}

	public static void deactivate(MinecraftClient client) {
		GhostState.clear();
		rebuild(client);
	}

	/** Debug/test entry: toggle ghosting on the nearest registered portal in the current dimension. */
	public static void toggleNearest(MinecraftClient client) {
		if (GhostState.isActive()) {
			deactivate(client);
			message(client, "Ghost OFF", Formatting.GRAY);
			return;
		}
		PortalStore store = PortalDetection.store();
		ClientPlayerEntity player = client.player;
		ClientWorld world = client.world;
		if (store == null || player == null || world == null) {
			return;
		}
		Identifier dimension = world.getRegistryKey().getValue();
		PortalRecord nearest = findNearest(store, player.getBlockPos(), dimension);
		if (nearest == null) {
			message(client, "Ghost: no registered portal nearby", Formatting.RED);
			return;
		}
		activate(client, nearest);
		message(client, "Ghost ON — portal at " + nearest.anchor.getX() + ", "
				+ nearest.anchor.getY() + ", " + nearest.anchor.getZ(), Formatting.LIGHT_PURPLE);
	}

	private static PortalRecord findNearest(PortalStore store, BlockPos from, Identifier dimension) {
		PortalRecord best = null;
		double bestSq = Double.MAX_VALUE;
		for (PortalRecord record : store.all()) {
			if (!record.dimension.equals(dimension)) {
				continue;
			}
			double distSq = record.anchor.getSquaredDistance(from);
			if (distSq < bestSq) {
				bestSq = distSq;
				best = record;
			}
		}
		return best;
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

	private static void rebuild(MinecraftClient client) {
		if (client.worldRenderer != null) {
			// Rebuilds chunk meshes silently — this is a chunk re-render, NOT a resource reload,
			// so no "resource pack changed" screen appears.
			client.worldRenderer.reload();
		}
	}

	private static void message(MinecraftClient client, String text, Formatting color) {
		if (client.player != null) {
			client.player.sendMessage(Text.literal("[Portal Glimpse] " + text).formatted(color), true);
		}
	}
}

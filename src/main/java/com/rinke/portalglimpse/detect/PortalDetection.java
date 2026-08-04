package com.rinke.portalglimpse.detect;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.List;

import com.rinke.portalglimpse.data.PortalStore;
import com.rinke.portalglimpse.config.GlimpseConfig;
import com.rinke.portalglimpse.render.GlimpseSettings;
import com.rinke.portalglimpse.data.PortalStorage;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Wires portal detection into the client lifecycle (design doc §3.2 existing-world scan, §12 item 8).
 *
 * <p>On world/server join we load the record store for that identity; as chunks stream in we scan
 * them for portals and register any new ones. This is exactly how "existing worlds" get populated:
 * every portal is recorded as it's encountered — but it holds no glimpse until traveled (§3.1).
 */
public final class PortalDetection {

	/** Temporary: print detection/save events to chat so Phase 1 can be tested in-game. */
	private static final boolean DEBUG_CHAT = true;

	/** Proximity scan cadence and radius — catches portals lit inside already-loaded chunks. */
	private static final int SCAN_INTERVAL_TICKS = 20;
	private static final int SCAN_RADIUS_CHUNKS = 3;

	private static PortalStore store;
	private static int tickCounter;

	private PortalDetection() {
	}

	public static void register() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> onJoin(client));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onDisconnect());
		ClientChunkEvents.CHUNK_LOAD.register(PortalDetection::onChunkLoad);
		ClientTickEvents.END_CLIENT_TICK.register(PortalDetection::onClientTick);
	}

	/** The active store for the joined world/server, or null when not in-world. */
	public static PortalStore store() {
		return store;
	}

	private static void onJoin(MinecraftClient client) {
		Path base = PortalStorage.resolveBaseDir(client);
		store = PortalStore.load(base);
	}

	private static void onDisconnect() {
		store = null;
	}

	private static void onChunkLoad(ClientWorld world, WorldChunk chunk) {
		PortalStore current = store;
		if (current != null) {
			scanChunk(world, chunk, current);
		}
	}

	/** Periodically re-scan chunks around the player so newly-lit portals are caught promptly. */
	private static void onClientTick(MinecraftClient client) {
		// Keep the capture radius inside the render distance. Checked here rather than in the config screen
		// because render distance is usually changed in VANILLA's video settings, with our screen closed.
		if (GlimpseSettings.clampCaptureRadius(client.options.getClampedViewDistance())) {
			GlimpseConfig.get().captureChunkRadius = GlimpseSettings.captureChunkRadius;
			GlimpseConfig.get().save();
		}
		if (++tickCounter < SCAN_INTERVAL_TICKS) {
			return;
		}
		tickCounter = 0;
		scanAroundPlayer(client);
	}

	/** Immediately scan the chunks around the player. Also used by travel capture on arrival. */
	public static void scanAroundPlayer(MinecraftClient client) {
		PortalStore current = store;
		ClientWorld world = client.world;
		ClientPlayerEntity player = client.player;
		if (current == null || world == null || player == null) {
			return;
		}
		ChunkPos center = player.getChunkPos();
		for (int dx = -SCAN_RADIUS_CHUNKS; dx <= SCAN_RADIUS_CHUNKS; dx++) {
			for (int dz = -SCAN_RADIUS_CHUNKS; dz <= SCAN_RADIUS_CHUNKS; dz++) {
				Chunk chunk = world.getChunk(center.x + dx, center.z + dz, ChunkStatus.FULL, false);
				if (chunk instanceof WorldChunk worldChunk) {
					scanChunk(world, worldChunk, current);
				}
			}
		}
	}

	private static void scanChunk(ClientWorld world, WorldChunk chunk, PortalStore current) {
		List<BlockPos> portalBlocks = PortalScanner.findPortalBlocks(world, chunk);
		if (portalBlocks.isEmpty()) {
			return;
		}
		Identifier dimension = world.getRegistryKey().getValue();
		// Flood-fill once per connected portal, skipping seeds already covered by THIS pass rather than seeds
		// already claimed by some record.
		//
		// Skipping claimed seeds was wrong, and it is what let a resized portal stay broken forever. Break and
		// re-light a portal bigger and you get a second record for the new shape (§5.3 keys identity on the
		// block set), while the old record still claims its blocks. From then on EVERY portal block is claimed
		// by one record or the other, so every seed was skipped, register() never ran again, and nothing ever
		// reconciled them — the old sub-shape kept painting its glimpse across part of the opening while the
		// rest stayed vanilla, and it survived restarts because load() re-claimed both.
		//
		// Re-registering the same unchanged portal is cheap and idempotent (register reuses the record by id),
		// so the only real cost is one flood-fill per portal per scan.
		Set<Long> visitedThisPass = new HashSet<>();
		for (BlockPos seed : portalBlocks) {
			if (visitedThisPass.contains(seed.asLong())) {
				continue;
			}
			PortalScanner.Candidate candidate = PortalScanner.scan(world, seed);
			if (candidate == null) {
				continue; // incomplete — will be re-scanned when the neighbouring chunk loads
			}
			for (BlockPos pos : candidate.interior) {
				visitedThisPass.add(pos.asLong());
			}
			PortalStore.RegisterResult result =
					current.register(dimension, candidate.anchor, candidate.interior, candidate.axis);
			if (result.isNew()) {
				announce(result);
			}
		}
	}

	private static void announce(PortalStore.RegisterResult result) {
		if (!DEBUG_CHAT) {
			return;
		}
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		if (player == null) {
			return;
		}
		// Coordinates and block count are developer detail — they only mean something while diagnosing a
		// detection, and reading them to every player who lights a portal is noise. Debug mode gets the full
		// line; everyone else just gets told the portal was picked up.
		BlockPos anchor = result.record().anchor;
		String detail = GlimpseSettings.debugMode
				? " at " + anchor.getX() + ", " + anchor.getY() + ", " + anchor.getZ()
						+ " (" + result.record().interior.size() + " blocks)"
				: "";
		player.sendMessage(Text.literal("[Portal Glimpse] Portal detected" + detail)
				.formatted(Formatting.AQUA), false);
		if (result.saved()) {
			player.sendMessage(Text.literal("[Portal Glimpse] Portal saved successfully")
					.formatted(Formatting.GREEN), false);
		} else {
			player.sendMessage(Text.literal("[Portal Glimpse] Portal save FAILED — check logs")
					.formatted(Formatting.RED), false);
		}
	}
}

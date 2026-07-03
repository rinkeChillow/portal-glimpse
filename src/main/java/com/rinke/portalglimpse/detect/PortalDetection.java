package com.rinke.portalglimpse.detect;

import java.nio.file.Path;
import java.util.List;

import com.rinke.portalglimpse.data.PortalStore;
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
		PortalStore current = store;
		ClientWorld world = client.world;
		ClientPlayerEntity player = client.player;
		if (current == null || world == null || player == null) {
			return;
		}
		if (++tickCounter < SCAN_INTERVAL_TICKS) {
			return;
		}
		tickCounter = 0;

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
		for (BlockPos seed : portalBlocks) {
			if (current.isClaimed(seed)) {
				continue;
			}
			PortalScanner.Candidate candidate = PortalScanner.scan(world, seed);
			if (candidate == null) {
				continue; // incomplete — will be re-scanned when the neighbouring chunk loads
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
		BlockPos anchor = result.record().anchor;
		player.sendMessage(Text.literal("[Portal Glimpse] Portal detected at "
				+ anchor.getX() + ", " + anchor.getY() + ", " + anchor.getZ()
				+ " (" + result.record().interior.size() + " blocks)").formatted(Formatting.AQUA), false);
		if (result.saved()) {
			player.sendMessage(Text.literal("[Portal Glimpse] Portal saved successfully")
					.formatted(Formatting.GREEN), false);
		} else {
			player.sendMessage(Text.literal("[Portal Glimpse] Portal save FAILED — check logs")
					.formatted(Formatting.RED), false);
		}
	}
}

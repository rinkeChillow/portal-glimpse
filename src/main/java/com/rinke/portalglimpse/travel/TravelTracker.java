package com.rinke.portalglimpse.travel;

import java.util.UUID;

import com.rinke.portalglimpse.PortalGlimpse;
import com.rinke.portalglimpse.capture.CaptureManager;
import com.rinke.portalglimpse.data.PortalRecord;
import com.rinke.portalglimpse.data.PortalStore;
import com.rinke.portalglimpse.detect.PortalDetection;
import com.rinke.portalglimpse.mixin.DownloadingTerrainScreenAccessor;
import com.rinke.portalglimpse.render.GlimpseSettings;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DownloadingTerrainScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkStatus;

/**
 * The automatic glimpse lifecycle (design doc §3.2): when the player travels through a Nether
 * portal, hold the "Loading terrain" screen a moment longer, ghost the destination portal, capture
 * from its center, and store the glimpse on the <em>origin</em> portal — the portal you entered
 * now shows where it leads. Also records/refreshes the A↔B link, silently re-pointing when vanilla
 * fuzzy linking sends the player somewhere new (§5.4).
 *
 * <p>Flow: standing inside a registered portal remembers it as the travel origin → dimension
 * change arms the tracker and holds the loading screen ({@code DownloadingTerrainScreenMixin}) →
 * once vanilla's own terrain-ready condition passes and the arrival portal is registered, the
 * capture runs behind the held screen → links update → screen releases. The player never knows.
 */
public final class TravelTracker {

	/** How recently the player must have touched the origin portal for a dim change to count as travel. */
	private static final long ORIGIN_FRESH_MS = 15_000;

	/** Ticks to wait for the arrival portal + capture-radius chunks before giving up (never softlock). */
	private static final int ARRIVAL_TIMEOUT_TICKS = 600;

	/** Max squared distance between player and a portal anchor to accept it as the arrival portal. */
	private static final double MAX_ARRIVAL_DIST_SQ = 32 * 32;

	private enum State {
		IDLE,
		AWAITING_ARRIVAL,
		CAPTURING
	}

	private static State state = State.IDLE;
	private static UUID originId;
	private static Identifier originDimension;
	private static long originTouchTime;
	private static Identifier lastWorldKey;
	private static int arrivalTicksLeft;

	/** Player health when the hold began — if it drops, the player is under attack: release the screen. */
	private static float holdStartHealth = -1.0F;

	/** Read by DownloadingTerrainScreenMixin (render thread) to keep the screen up. */
	private static volatile boolean holdLoadingScreen;

	private TravelTracker() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(TravelTracker::onTick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
	}

	public static boolean shouldHoldLoadingScreen() {
		return holdLoadingScreen;
	}

	private static void onTick(MinecraftClient client) {
		ClientWorld world = client.world;
		ClientPlayerEntity player = client.player;
		if (world == null || player == null) {
			// Mid-transition the world is briefly null — keep the origin memory, it's about to matter.
			return;
		}

		Identifier worldKey = world.getRegistryKey().getValue();
		if (lastWorldKey == null) {
			lastWorldKey = worldKey;
		} else if (!worldKey.equals(lastWorldKey)) {
			lastWorldKey = worldKey;
			onDimensionChanged(worldKey);
		}

		switch (state) {
			case IDLE -> trackOrigin(client, player, worldKey);
			case AWAITING_ARRIVAL -> tickAwaitArrival(client, player, worldKey);
			case CAPTURING -> {
				// CaptureManager is driving; its callback returns us to IDLE.
			}
		}
	}

	/** Remember the portal the player is standing in — the future travel origin. */
	private static void trackOrigin(MinecraftClient client, ClientPlayerEntity player, Identifier worldKey) {
		PortalStore store = PortalDetection.store();
		if (store == null) {
			return;
		}
		BlockPos feet = player.getBlockPos();
		if (!client.world.getBlockState(feet).isOf(Blocks.NETHER_PORTAL)) {
			return;
		}
		PortalRecord record = store.recordAt(feet);
		if (record != null) {
			originId = record.id;
			originDimension = worldKey;
			originTouchTime = System.currentTimeMillis();
		}
	}

	private static void onDimensionChanged(Identifier newWorldKey) {
		if (state != State.IDLE) {
			return;
		}
		if (originId == null || originDimension == null || newWorldKey.equals(originDimension)) {
			return;
		}
		if (System.currentTimeMillis() - originTouchTime > ORIGIN_FRESH_MS) {
			originId = null; // stale — this dim change wasn't portal travel
			return;
		}
		state = State.AWAITING_ARRIVAL;
		arrivalTicksLeft = ARRIVAL_TIMEOUT_TICKS;
		holdLoadingScreen = true;
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		holdStartHealth = player != null ? player.getHealth() : -1.0F;
		PortalGlimpse.LOGGER.info("Portal Glimpse: portal travel {} -> {}, awaiting arrival portal",
				originDimension, newWorldKey);
	}

	private static void tickAwaitArrival(MinecraftClient client, ClientPlayerEntity player, Identifier worldKey) {
		// If the player takes damage while we're holding the loading screen (a mob got to them during
		// the chunk wait), release it immediately so they can defend themselves — don't hold + hurt.
		if (holdStartHealth >= 0.0F && player.getHealth() < holdStartHealth - 0.01F) {
			abort("player took damage during the hold — releasing the loading screen");
			return;
		}
		if (--arrivalTicksLeft <= 0) {
			feedback(player, "Glimpse skipped — terrain wasn't ready in time", Formatting.GRAY);
			abort("arrival portal / capture-radius chunks not ready in time");
			return;
		}
		PortalStore store = PortalDetection.store();
		if (store == null) {
			abort("no portal store");
			return;
		}

		// Fast-path the arrival portal's registration (chunk-load scan may not have run yet).
		PortalDetection.scanAroundPlayer(client);

		// Respect vanilla's own "terrain is ready" condition before capturing (§12 item 9): the
		// screen's shouldClose supplier is exactly what vanilla would use to dismiss it.
		if (client.currentScreen instanceof DownloadingTerrainScreen screen
				&& !((DownloadingTerrainScreenAccessor) screen).portalglimpse$getShouldClose().getAsBoolean()) {
			return;
		}

		// Identify the arrival portal: standing inside it, or the nearest one within range.
		PortalRecord destination = store.recordAt(player.getBlockPos());
		if (destination == null) {
			PortalRecord nearest = store.findNearest(player.getBlockPos(), worldKey);
			if (nearest != null
					&& nearest.anchor.getSquaredDistance(player.getBlockPos()) <= MAX_ARRIVAL_DIST_SQ) {
				destination = nearest;
			}
		}
		if (destination == null) {
			return; // registration may still be in flight — keep waiting until timeout
		}

		// Hold the loading screen until the configured radius of chunks around the arrival portal has
		// loaded, so the captured panorama shows real terrain instead of ungenerated void. The timeout
		// still bounds the wait so it can never softlock.
		if (!captureChunksLoaded(client, destination)) {
			return;
		}

		PortalRecord origin = store.get(originId);
		if (origin == null) {
			abort("origin record vanished");
			return;
		}

		relink(store, origin, destination);

		// This trip's auto capture is saved to the ORIGIN record (it displays the side you came from).
		// If THAT record already has a pinned manual capture, the auto glimpse would just be overridden
		// by it — so skip it, and only it. The linked portal captures independently on its own trips.
		// (Players who curate a side manually never pay the auto-capture cost for that side, §3.4.)
		if (origin.manual.hasCapture && origin.manual.pinned) {
			PortalGlimpse.LOGGER.info("Portal Glimpse: manual capture pinned on {} — skipping its auto capture",
					origin.id);
			finish();
			return;
		}

		long cooldownMs = GlimpseSettings.autoCaptureCooldownMinutes * 60_000L;
		long sinceLast = System.currentTimeMillis() - origin.auto.timestamp;
		if (origin.auto.hasCapture && sinceLast < cooldownMs) {
			PortalGlimpse.LOGGER.info("Portal Glimpse: cooldown active for {} ({}s left), links refreshed only",
					origin.id, (cooldownMs - sinceLast) / 1000);
			finish();
			return;
		}

		state = State.CAPTURING;
		if (!CaptureManager.request(client, destination, origin, TravelTracker::finish)) {
			finish(); // a manual capture is mid-flight — skip this one gracefully
		}
	}

	/**
	 * Whether the configured chunk radius (each direction) around the portal is loaded to FULL status.
	 * Radius 0 disables the wait (capture as soon as vanilla terrain is ready).
	 */
	private static boolean captureChunksLoaded(MinecraftClient client, PortalRecord portal) {
		int radius = GlimpseSettings.captureChunkRadius;
		if (radius <= 0 || client.world == null) {
			return true;
		}
		// Chunks past the client's render distance never load, so never wait for them (else we'd hold
		// the screen until the timeout). The config screen warns when the radius exceeds render distance.
		radius = Math.min(radius, client.options.getClampedViewDistance());
		int centerX = portal.anchor.getX() >> 4;
		int centerZ = portal.anchor.getZ() >> 4;
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				if (client.world.getChunkManager().getChunk(centerX + dx, centerZ + dz, ChunkStatus.FULL, false) == null) {
					return false;
				}
			}
		}
		return true;
	}

	private static void feedback(ClientPlayerEntity player, String text, Formatting color) {
		if (player != null) {
			player.sendMessage(Text.literal("[Portal Glimpse] " + text).formatted(color), true);
		}
	}

	/** Record/refresh the A↔B link. Overwrites stale links without drama (§5.4). */
	private static void relink(PortalStore store, PortalRecord origin, PortalRecord destination) {
		if (!destination.id.equals(origin.linkedId)) {
			origin.linkedId = destination.id;
			store.save(origin);
		}
		if (!origin.id.equals(destination.linkedId)) {
			destination.linkedId = origin.id;
			store.save(destination);
		}
	}

	private static void abort(String reason) {
		PortalGlimpse.LOGGER.info("Portal Glimpse: travel capture skipped — {}", reason);
		finish();
	}

	private static void finish() {
		holdLoadingScreen = false;
		state = State.IDLE;
		originId = null;
		originDimension = null;
	}

	private static void reset() {
		finish();
		lastWorldKey = null;
	}
}

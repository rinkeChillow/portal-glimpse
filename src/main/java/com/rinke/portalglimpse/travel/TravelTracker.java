package com.rinke.portalglimpse.travel;

import java.util.UUID;

import com.rinke.portalglimpse.PortalGlimpse;
import com.rinke.portalglimpse.capture.CaptureManager;
import com.rinke.portalglimpse.data.PortalRecord;
import com.rinke.portalglimpse.data.PortalStore;
import com.rinke.portalglimpse.detect.PortalDetection;
import com.rinke.portalglimpse.mixin.DownloadingTerrainScreenAccessor;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DownloadingTerrainScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

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

	/** Ticks to wait for the arrival portal before giving up (safety valve — never softlock). */
	private static final int ARRIVAL_TIMEOUT_TICKS = 200;

	/** Max squared distance between player and a portal anchor to accept it as the arrival portal. */
	private static final double MAX_ARRIVAL_DIST_SQ = 32 * 32;

	/** Auto-capture cooldown per portal (§3.3, default 5 minutes; configurable later). */
	private static final long CAPTURE_COOLDOWN_MS = 5 * 60 * 1000;

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
		PortalGlimpse.LOGGER.info("Portal Glimpse: portal travel {} -> {}, awaiting arrival portal",
				originDimension, newWorldKey);
	}

	private static void tickAwaitArrival(MinecraftClient client, ClientPlayerEntity player, Identifier worldKey) {
		if (--arrivalTicksLeft <= 0) {
			abort("arrival portal not found in time");
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

		long sinceLast = System.currentTimeMillis() - origin.auto.timestamp;
		if (origin.auto.hasCapture && sinceLast < CAPTURE_COOLDOWN_MS) {
			PortalGlimpse.LOGGER.info("Portal Glimpse: cooldown active for {} ({}s left), links refreshed only",
					origin.id, (CAPTURE_COOLDOWN_MS - sinceLast) / 1000);
			finish();
			return;
		}

		state = State.CAPTURING;
		if (!CaptureManager.request(client, destination, origin, TravelTracker::finish)) {
			finish(); // a manual capture is mid-flight — skip this one gracefully
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

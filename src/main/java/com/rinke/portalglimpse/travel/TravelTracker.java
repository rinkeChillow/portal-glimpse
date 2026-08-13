package com.rinke.portalglimpse.travel;

import java.util.UUID;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.rinke.portalglimpse.PortalGlimpse;
import com.rinke.portalglimpse.render.CreeperWarning;
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
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.sound.SoundEvents;
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
	/** Whether the player was already flinching last tick, so only the START of a hit counts. */
	private static boolean wasHurt;

	/** How far to watch for a creeper. Wide, because the point is to react while it is still walking. */
	private static final double CREEPER_WATCH_RADIUS = 16.0;

	/** How directly a creeper must face the player to count as tracking them. cos(50°) — generous, since a
	 * mob walking toward you weaves rather than staring straight down the line. */
	private static final double CREEPER_FACING_DOT = 0.64;

	/** Blocks per tick a creeper must be closing to count as approaching. A creeper chasing moves ~0.2/tick;
	 * this is well under that but clear of the jitter of a mob milling about. */
	private static final double CREEPER_CLOSING_PER_TICK = 0.03;

	/** Ticks of facing-and-closing before we believe it. One tick of coincidence is not aggro.
	 *
	 * <p>The streak DECAYS by one on a bad tick rather than resetting to zero — see the note in creeperCheck
	 * about lag. Resetting meant a creeper that stuttered had to earn the full count again from scratch. */
	private static final int CREEPER_CONFIRM_TICKS = 3;

	/** What the nearby creepers are up to, for the loading screen to narrate. */
	public enum CreeperMood {
		/** None in range. */
		NONE,
		/** One just turned up — has it noticed you? */
		PRESENT,
		/** Been there a while and shows no interest. */
		LINGERING,
		/** Facing you and closing. */
		APPROACHING
	}

	private static volatile CreeperMood creeperMood = CreeperMood.NONE;

	/** Ticks a creeper can be around, not approaching, before it counts as merely loitering. */
	private static final int CREEPER_LINGER_TICKS = 50;
	/** How long "Creeper is approaching!" stays up before the hold actually breaks — long enough to read. */
	private static final long CREEPER_APPROACH_GRACE_MS = 700;

	private static int creeperSeenTicks;
	private static long approachingSince;

	/** For the loading screen. */
	public static CreeperMood creeperMood() {
		return creeperMood;
	}

	/** Creeper entity id → how many ticks running it has been closing on the player while facing them. */
	private static final Map<Integer, Integer> creeperStreak = new HashMap<>();
	/** Creeper entity id → its distance to the player last tick. */
	private static final Map<Integer, Double> creeperLastDist = new HashMap<>();

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
				// CaptureManager is driving; its callback returns us to IDLE — unless the player is being hurt,
				// in which case the capture is abandoned and they get their screen back immediately.
				if (tookDamage(player) || creeperPrimed(client, player)) {
					CaptureManager.cancel(client);
					playCancelSound(player);
					abort("player attacked during the capture");
				}
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
		wasHurt = player != null && player.hurtTime > 0;
		PortalGlimpse.LOGGER.info("Portal Glimpse: portal travel {} -> {}, awaiting arrival portal",
				originDimension, newWorldKey);
	}

	private static void tickAwaitArrival(MinecraftClient client, ClientPlayerEntity player, Identifier worldKey) {
		// If the player takes damage while we're holding the loading screen (a mob got to them during
		// the chunk wait), release it immediately so they can defend themselves — don't hold + hurt.
		if (tookDamage(player) || creeperPrimed(client, player)) {
			playCancelSound(player);
			abort("player attacked during the hold — releasing the loading screen");
			return;
		}
		// Running out of time no longer throws the trip away. Waiting the full timeout and THEN skipping the
		// capture is the worst of both: the player paid for the whole hold and got nothing, and the portal
		// stays blank until they happen to travel again. Past the deadline we take what has loaded — a glimpse
		// with some unloaded distance in it beats no glimpse at all, and the next trip refreshes it anyway.
		boolean timedOut = --arrivalTicksLeft <= 0;

		PortalStore store = PortalDetection.store();
		if (store == null) {
			abort("no portal store");
			return;
		}

		// Fast-path the arrival portal's registration (chunk-load scan may not have run yet).
		PortalDetection.scanAroundPlayer(client);

		// Respect vanilla's own "terrain is ready" condition before capturing (§12 item 9): the
		// screen's shouldClose supplier is exactly what vanilla would use to dismiss it.
		if (!timedOut && client.currentScreen instanceof DownloadingTerrainScreen screen
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
			if (timedOut) {
				// Nothing to photograph — no portal was ever identified, so there is no partial result to keep.
				feedback(player, "Glimpse skipped — no portal found on arrival", Formatting.GRAY);
				abort("arrival portal never resolved before the timeout");
			}
			return; // registration may still be in flight — keep waiting until timeout
		}

		// Hold the loading screen until the configured radius of chunks around the arrival portal has
		// loaded, so the captured panorama shows real terrain instead of ungenerated void. The timeout
		// still bounds the wait so it can never softlock.
		if (!timedOut && !captureChunksLoaded(client, destination)) {
			return;
		}

		PortalRecord origin = store.get(originId);
		if (origin == null) {
			abort("origin record vanished");
			return;
		}

		if (timedOut) {
			feedback(player, "Glimpse captured early — terrain wasn't fully loaded", Formatting.GRAY);
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

	/**
	 * The capture breaking, as a sound rather than a line of chat.
	 *
	 * <p>The respawn-anchor depletion — a thing that had charge losing it. No text: this is a breakage, and
	 * a sound says it faster than a chat line.
	 */
	private static void playCancelSound(ClientPlayerEntity player) {
		if (player == null) {
			return;
		}
		player.playSound(SoundEvents.BLOCK_RESPAWN_ANCHOR_DEPLETE.value(), 0.7F, 1.0F);

	}

	/**
	 * True on the tick the player starts taking damage — of any kind, from anything.
	 *
	 * <p>Reads {@code hurtTime}, the flinch timer the server sets on every damage event, rather than watching
	 * the health NUMBER. Comparing health values can't tell a hit from a heal: eat or regenerate during the
	 * hold and the baseline moves, so a later hit that leaves you above it goes unnoticed. It also misses
	 * damage that costs no health at all — absorption, or a hit soaked entirely by armour. hurtTime fires for
	 * all of it, and only the rising edge counts so one hit triggers once.
	 */
	private static boolean tookDamage(ClientPlayerEntity player) {
		if (player == null) {
			return false;
		}
		boolean hurt = player.hurtTime > 0;
		boolean started = hurt && !wasHurt;
		wasHurt = hurt;
		return started;
	}

	/**
	 * True when a creeper is coming for the player — ideally well before it starts to swell.
	 *
	 * <p>Once the fuse is lit it is already too late to do anything useful with the warning, so this tries to
	 * catch the approach instead. The client is never told what a mob is TARGETING — {@code getTarget()} is
	 * server-side and reads null here — and paths aren't synced either, so aggro has to be inferred from what
	 * IS synced: where the creeper is and which way it is looking.
	 *
	 * <p>A creeper that is both facing the player and closing on them, for several ticks running, is chasing
	 * them. Either signal alone is worthless — mobs drift toward you by accident and glance at you while
	 * wandering — but together, sustained, they don't happen by chance. The streak requirement is what keeps
	 * a creeper milling about near the portal from cancelling every arrival.
	 *
	 * <p>A lit fuse still counts, as a backstop for the case the approach was missed entirely (ignited by
	 * flint and steel, or it rounded a corner already primed).
	 */
	private static boolean creeperPrimed(MinecraftClient client, ClientPlayerEntity player) {
		creeperCheck(client, player);
		if (creeperMood != CreeperMood.APPROACHING) {
			approachingSince = 0L;
			return false;
		}
		// Don't yank the screen the instant it's spotted — let "Creeper is approaching!" sit there long enough
		// to be read, THEN break. A fraction of a second costs nothing and the warning is the point.
		if (approachingSince == 0L) {
			approachingSince = System.currentTimeMillis();
			CreeperWarning.trigger();
			return false;
		}
		return System.currentTimeMillis() - approachingSince >= CREEPER_APPROACH_GRACE_MS;
	}

	private static boolean creeperCheck(MinecraftClient client, ClientPlayerEntity player) {
		if (client.world == null || player == null) {
			return false;
		}
		Box area = player.getBoundingBox().expand(CREEPER_WATCH_RADIUS);
		List<CreeperEntity> nearby =
				client.world.getEntitiesByClass(CreeperEntity.class, area, c -> true);
		Set<Integer> seen = new HashSet<>();
		boolean aggro = false;

		for (CreeperEntity creeper : nearby) {
			int id = creeper.getId();
			seen.add(id);

			if (creeper.getFuseSpeed() > 0 || creeper.isIgnited()) {
				aggro = true;
				continue;
			}

			double dist = creeper.distanceTo(player);
			Double last = creeperLastDist.put(id, dist);

			// No movement AT ALL since last tick means no position update arrived, not that the creeper stood
			// still. Under lag those are indistinguishable from here, and treating a missing update as "not
			// approaching" is what let a stuttering creeper look harmless — so skip the tick entirely and
			// keep whatever streak it had.
			if (last != null && Math.abs(last - dist) < 1.0E-6) {
				continue;
			}

			// Facing: its look direction against the direction to the player. Head yaw rather than body yaw —
			// a mob turns its head to what it is tracking before the body follows.
			Vec3d toPlayer = player.getPos().subtract(creeper.getPos());
			double flat = Math.sqrt(toPlayer.x * toPlayer.x + toPlayer.z * toPlayer.z);
			boolean facing = false;
			if (flat > 0.001) {
				double yawRad = Math.toRadians(creeper.headYaw);
				double lookX = -Math.sin(yawRad);
				double lookZ = Math.cos(yawRad);
				facing = (lookX * (toPlayer.x / flat) + lookZ * (toPlayer.z / flat)) >= CREEPER_FACING_DOT;
			}
			boolean closing = last != null && (last - dist) >= CREEPER_CLOSING_PER_TICK;

			// Decay instead of reset: one bad tick (a pause, a corner, a hiccup) shouldn't wipe the evidence,
			// so a creeper that resumes its approach re-confirms almost immediately rather than starting over.
			int prev = creeperStreak.getOrDefault(id, 0);
			int streak = facing && closing ? prev + 1 : Math.max(0, prev - 1);
			creeperStreak.put(id, streak);
			if (streak >= CREEPER_CONFIRM_TICKS) {
				aggro = true;
			}
		}

		// Forget creepers that wandered out of range, so a returning one starts its streak from scratch.
		creeperStreak.keySet().retainAll(seen);
		creeperLastDist.keySet().retainAll(seen);

		if (aggro) {
			creeperMood = CreeperMood.APPROACHING;
		} else if (nearby.isEmpty()) {
			creeperSeenTicks = 0;
			creeperMood = CreeperMood.NONE;
		} else {
			creeperSeenTicks++;
			creeperMood = creeperSeenTicks >= CREEPER_LINGER_TICKS
					? CreeperMood.LINGERING
					: CreeperMood.PRESENT;
		}
		return aggro;
	}

	private static void abort(String reason) {
		PortalGlimpse.LOGGER.info("Portal Glimpse: travel capture skipped — {}", reason);
		finish();
	}

	private static void finish() {
		holdLoadingScreen = false;
		wasHurt = false;
		creeperStreak.clear();
		creeperLastDist.clear();
		creeperMood = CreeperMood.NONE;
		creeperSeenTicks = 0;
		approachingSince = 0L;
		state = State.IDLE;
		originId = null;
		originDimension = null;
	}

	private static void reset() {
		finish();
		lastWorldKey = null;
	}
}

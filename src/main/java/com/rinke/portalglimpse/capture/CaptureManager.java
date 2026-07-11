package com.rinke.portalglimpse.capture;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.rinke.portalglimpse.PortalGlimpse;
import com.rinke.portalglimpse.data.PortalRecord;
import com.rinke.portalglimpse.data.PortalStore;
import com.rinke.portalglimpse.detect.PortalDetection;
import com.rinke.portalglimpse.ghost.GhostController;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * The capture pipeline (design doc §3.2 / §3.4): earn a glimpse by rendering a 6-face cubemap and
 * two postcards of the (ghosted) surroundings.
 *
 * <p>Every capture originates at the <em>camera portal's</em> geometric center — width, height and
 * depth — with a world-aligned panorama orientation (face 0 = south, always), so cubemaps are
 * deterministic per portal regardless of how the player arrived. The images can be stored under a
 * <em>different</em> record: travel captures shoot at the destination portal but belong to the
 * origin portal (§3.2 "displayed on the origin portal").
 *
 * <p>Flow per request: ghost the camera portal → wait a few ticks for chunk re-mesh → render all
 * shots → un-ghost → mark the save-record's auto slot → notify the requester.
 */
public final class CaptureManager {

	/** Per-face panorama / postcard resolution (design doc §5.6 default 1024²). */
	private static final int CAPTURE_RESOLUTION = 1024;

	/** How far the postcard camera sits from the portal plane, in blocks. */
	private static final double POSTCARD_DISTANCE = 1.0;

	/** Ticks to wait after ghosting so the portal's chunk sections finish re-meshing before capture. */
	private static final int GHOST_SETTLE_TICKS = 8;

	private enum Phase {
		IDLE,
		SETTLING
	}

	private static Phase phase = Phase.IDLE;
	private static int ticksLeft;
	private static PortalRecord cameraPortal;
	private static PortalRecord saveTo;
	private static boolean manualCapture;
	private static Runnable onFinished;

	private CaptureManager() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(CaptureManager::onTick);
	}

	public static boolean isBusy() {
		return phase != Phase.IDLE;
	}

	/** Debug-key entry: capture the nearest registered portal in the current dimension. */
	public static void requestCaptureNearest(MinecraftClient client) {
		PortalStore store = PortalDetection.store();
		ClientPlayerEntity player = client.player;
		ClientWorld world = client.world;
		if (store == null || player == null || world == null) {
			return;
		}
		Identifier dimension = world.getRegistryKey().getValue();
		PortalRecord nearest = store.findNearest(player.getBlockPos(), dimension);
		if (nearest == null) {
			feedback(client, "Capture: no registered portal nearby", Formatting.RED);
			return;
		}
		request(client, nearest, nearest, null);
	}

	/**
	 * Start a capture: camera at {@code camera}'s center, images stored under {@code save}'s record.
	 * {@code whenDone} runs on the client thread after the capture finishes or fails.
	 *
	 * @return false if a capture is already in progress (the request is dropped).
	 */
	public static boolean request(MinecraftClient client, PortalRecord camera, PortalRecord save,
			Runnable whenDone) {
		return request(client, camera, save, false, whenDone);
	}

	/**
	 * As {@link #request(MinecraftClient, PortalRecord, PortalRecord, Runnable)}, but {@code manual}
	 * captures write a separate {@code manual_*} image set and fill the record's manual slot (pinned),
	 * so a player-curated glimpse coexists with — and wins over — the automatic one.
	 */
	public static boolean request(MinecraftClient client, PortalRecord camera, PortalRecord save,
			boolean manual, Runnable whenDone) {
		if (phase != Phase.IDLE) {
			return false;
		}
		cameraPortal = camera;
		saveTo = save;
		manualCapture = manual;
		onFinished = whenDone;
		GhostController.activate(client, camera); // ghost + targeted chunk rebuild
		phase = Phase.SETTLING;
		ticksLeft = GHOST_SETTLE_TICKS;
		return true;
	}

	private static void onTick(MinecraftClient client) {
		if (phase != Phase.SETTLING) {
			return;
		}
		if (--ticksLeft > 0) {
			return;
		}
		Runnable callback = onFinished;
		try {
			performCapture(client, cameraPortal, saveTo, manualCapture);
		} catch (Exception e) {
			PortalGlimpse.LOGGER.warn("Portal Glimpse: capture failed", e);
			feedback(client, "Capture failed — check logs", Formatting.RED);
		} finally {
			GhostController.deactivate(client);
			phase = Phase.IDLE;
			cameraPortal = null;
			saveTo = null;
			manualCapture = false;
			onFinished = null;
			if (callback != null) {
				callback.run();
			}
		}
	}

	private static void performCapture(MinecraftClient client, PortalRecord camera, PortalRecord save,
			boolean manual) throws Exception {
		PortalStore store = PortalDetection.store();
		ClientPlayerEntity player = client.player;
		if (store == null || player == null) {
			return;
		}
		Path dir = store.baseDir().resolve(save.id.toString());
		Vec3d center = portalCenter(camera);

		// Manual captures live alongside the automatic ones under a "manual_" filename prefix, so a
		// player-curated glimpse and the travel-refreshed one coexist in the same folder.
		String prefix = manual ? "manual_" : "";

		List<CaptureRenderer.Shot> shots = new ArrayList<>();

		// The panorama: 6 cubemap faces from the portal's center, world-aligned so the cubemap is
		// deterministic per portal (0=south, 1=west, 2=north, 3=east, 4=up, 5=down).
		shots.add(new CaptureRenderer.Shot(center, 0.0F, 0.0F, prefix + "panorama_0.png"));
		shots.add(new CaptureRenderer.Shot(center, 90.0F, 0.0F, prefix + "panorama_1.png"));
		shots.add(new CaptureRenderer.Shot(center, 180.0F, 0.0F, prefix + "panorama_2.png"));
		shots.add(new CaptureRenderer.Shot(center, 270.0F, 0.0F, prefix + "panorama_3.png"));
		shots.add(new CaptureRenderer.Shot(center, 0.0F, -90.0F, prefix + "panorama_4.png"));
		shots.add(new CaptureRenderer.Shot(center, 0.0F, 90.0F, prefix + "panorama_5.png"));

		// The postcards: camera hops 1 block out on each side of the portal plane and shoots
		// through the (ghosted) portal. Named for the side the camera stands on — the face the
		// postcard will be displayed on. Axis X portal spans east-west → faces north/south;
		// axis Z spans north-south → faces east/west. (MC yaw: 0=south, 90=west, 180=north, -90=east.)
		if (camera.axis == Direction.Axis.X) {
			shots.add(new CaptureRenderer.Shot(center.add(0, 0, -POSTCARD_DISTANCE), 0.0F, 0.0F,
					prefix + "postcard_north.png"));
			shots.add(new CaptureRenderer.Shot(center.add(0, 0, POSTCARD_DISTANCE), 180.0F, 0.0F,
					prefix + "postcard_south.png"));
		} else {
			shots.add(new CaptureRenderer.Shot(center.add(-POSTCARD_DISTANCE, 0, 0), -90.0F, 0.0F,
					prefix + "postcard_west.png"));
			shots.add(new CaptureRenderer.Shot(center.add(POSTCARD_DISTANCE, 0, 0), 90.0F, 0.0F,
					prefix + "postcard_east.png"));
		}

		// Suppress the portal-nausea screen wobble for the shots — right after travel the effect
		// is at full strength and would warp every capture.
		float nausea = player.nauseaIntensity;
		float prevNausea = player.prevNauseaIntensity;
		player.nauseaIntensity = 0.0F;
		player.prevNauseaIntensity = 0.0F;
		try {
			CaptureRenderer.capture(client, dir, CAPTURE_RESOLUTION, shots);
		} finally {
			player.nauseaIntensity = nausea;
			player.prevNauseaIntensity = prevNausea;
		}

		if (manual) {
			save.manual.hasCapture = true;
			save.manual.pinned = true; // pin it so it wins over — and isn't overwritten by — auto travel
			save.manual.timestamp = System.currentTimeMillis();
		} else {
			save.auto.hasCapture = true;
			save.auto.timestamp = System.currentTimeMillis();
		}
		store.save(save);

		PortalGlimpse.LOGGER.info("Portal Glimpse: captured {} glimpse (camera {} -> record {})",
				manual ? "manual" : "auto", camera.id, save.id);
		// Auto capture has no other indicator, so it chimes (the "zombie villager cured" sound). Manual
		// capture already signals with the green glow, so it stays silent.
		if (!manual && client.player != null) {
			client.player.playSound(SoundEvents.ENTITY_ZOMBIE_VILLAGER_CONVERTED, 1.0F, 1.0F);
		}
	}

	/** Geometric center of the portal's interior blocks — width, height and depth (§3.2). */
	public static Vec3d portalCenter(PortalRecord record) {
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (BlockPos pos : record.interior) {
			minX = Math.min(minX, pos.getX());
			maxX = Math.max(maxX, pos.getX());
			minY = Math.min(minY, pos.getY());
			maxY = Math.max(maxY, pos.getY());
			minZ = Math.min(minZ, pos.getZ());
			maxZ = Math.max(maxZ, pos.getZ());
		}
		return new Vec3d((minX + maxX + 1) / 2.0, (minY + maxY + 1) / 2.0, (minZ + maxZ + 1) / 2.0);
	}

	private static void feedback(MinecraftClient client, String text, Formatting color) {
		if (client.player != null) {
			client.player.sendMessage(Text.literal("[Portal Glimpse] " + text).formatted(color), true);
		}
	}
}

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
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * The capture pipeline (design doc §3.2 / §3.4): earn a glimpse by taking a 6-face cubemap of the
 * (ghosted) surroundings and storing it on the portal record.
 *
 * <p>This first cut is the <em>manual</em> capture, driven by a debug key: it ghosts the target
 * portal, waits a few ticks for the chunk mesh to rebuild, then reuses vanilla
 * {@link MinecraftClient#takePanorama} to render + save the six faces, un-ghosts, and marks the
 * record's automatic slot. The loading-screen-hold auto-capture on travel comes later.
 */
public final class CaptureManager {

	/** Per-face panorama / postcard resolution (design doc §5.6 default 1024²). */
	private static final int CAPTURE_RESOLUTION = 1024;

	/** How far the postcard camera sits from the portal plane, in blocks. */
	private static final double POSTCARD_DISTANCE = 2.0;

	/** Ticks to wait after ghosting so the portal's chunk sections finish re-meshing before capture. */
	private static final int GHOST_SETTLE_TICKS = 8;

	private enum Phase {
		IDLE,
		SETTLING
	}

	private static Phase phase = Phase.IDLE;
	private static int ticksLeft;
	private static PortalRecord target;

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
		if (phase != Phase.IDLE) {
			return;
		}
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
		begin(client, nearest);
	}

	private static void begin(MinecraftClient client, PortalRecord record) {
		target = record;
		GhostController.activate(client, record); // ghost + targeted chunk rebuild
		phase = Phase.SETTLING;
		ticksLeft = GHOST_SETTLE_TICKS;
		feedback(client, "Capturing portal…", Formatting.LIGHT_PURPLE);
	}

	private static void onTick(MinecraftClient client) {
		if (phase != Phase.SETTLING) {
			return;
		}
		if (--ticksLeft > 0) {
			return;
		}
		try {
			performCapture(client, target);
		} catch (Exception e) {
			PortalGlimpse.LOGGER.warn("Portal Glimpse: capture failed", e);
			feedback(client, "Capture failed — check logs", Formatting.RED);
		} finally {
			GhostController.deactivate(client); // un-ghost while GhostState is still active for the render
			phase = Phase.IDLE;
			target = null;
		}
	}

	private static void performCapture(MinecraftClient client, PortalRecord record) throws Exception {
		PortalStore store = PortalDetection.store();
		ClientPlayerEntity player = client.player;
		if (store == null || player == null) {
			return;
		}
		Path dir = store.baseDir().resolve(record.id.toString());

		List<CaptureRenderer.Shot> shots = new ArrayList<>();

		// The panorama: 6 cubemap faces from the player's eyes, same face order/orientation as
		// vanilla takePanorama (0=view yaw, 1=+90°, 2=+180°, 3=-90°, 4=up, 5=down).
		Vec3d eye = player.getEyePos();
		float yaw = player.getYaw();
		shots.add(new CaptureRenderer.Shot(eye, yaw, 0.0F, "panorama_0.png"));
		shots.add(new CaptureRenderer.Shot(eye, yaw + 90.0F, 0.0F, "panorama_1.png"));
		shots.add(new CaptureRenderer.Shot(eye, yaw + 180.0F, 0.0F, "panorama_2.png"));
		shots.add(new CaptureRenderer.Shot(eye, yaw - 90.0F, 0.0F, "panorama_3.png"));
		shots.add(new CaptureRenderer.Shot(eye, yaw, -90.0F, "panorama_4.png"));
		shots.add(new CaptureRenderer.Shot(eye, yaw, 90.0F, "panorama_5.png"));

		// The postcards: camera hops 2 blocks out on each side of the portal plane and shoots
		// through the (ghosted) portal. Named for the side the camera stands on — the face the
		// postcard will be displayed on. Axis X portal spans east-west → faces north/south;
		// axis Z spans north-south → faces east/west. (MC yaw: 0=south, 90=west, 180=north, -90=east.)
		Vec3d center = portalCenter(record);
		if (record.axis == Direction.Axis.X) {
			shots.add(new CaptureRenderer.Shot(center.add(0, 0, -POSTCARD_DISTANCE), 0.0F, 0.0F,
					"postcard_north.png"));
			shots.add(new CaptureRenderer.Shot(center.add(0, 0, POSTCARD_DISTANCE), 180.0F, 0.0F,
					"postcard_south.png"));
		} else {
			shots.add(new CaptureRenderer.Shot(center.add(-POSTCARD_DISTANCE, 0, 0), -90.0F, 0.0F,
					"postcard_west.png"));
			shots.add(new CaptureRenderer.Shot(center.add(POSTCARD_DISTANCE, 0, 0), 90.0F, 0.0F,
					"postcard_east.png"));
		}

		CaptureRenderer.capture(client, dir, CAPTURE_RESOLUTION, shots);

		record.auto.hasCapture = true;
		record.auto.timestamp = System.currentTimeMillis();
		store.save(record);

		PortalGlimpse.LOGGER.info("Portal Glimpse: captured panorama + postcards for {} -> {}", record.id, dir);
		feedback(client, "Portal captured — panorama + postcards saved", Formatting.GREEN);
	}

	/** Geometric center of the portal's interior blocks. */
	private static Vec3d portalCenter(PortalRecord record) {
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

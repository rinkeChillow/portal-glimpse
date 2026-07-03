package com.rinke.portalglimpse.capture;

import java.nio.file.Files;
import java.nio.file.Path;

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

	/** Per-face panorama resolution (design doc §5.6 default 1024²). */
	private static final int PANORAMA_RESOLUTION = 1024;

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
		if (store == null) {
			return;
		}
		Path dir = store.baseDir().resolve(record.id.toString());
		Files.createDirectories(dir);

		// Reuse Mojang's own 6-face cubemap capture. Renders from the player's position with the
		// portal ghosted (mesh already rebuilt) and writes panorama_0.png..panorama_5.png.
		client.takePanorama(dir.toFile(), PANORAMA_RESOLUTION, PANORAMA_RESOLUTION);

		record.auto.hasCapture = true;
		record.auto.timestamp = System.currentTimeMillis();
		store.save(record);

		PortalGlimpse.LOGGER.info("Portal Glimpse: captured panorama for {} -> {}", record.id, dir);
		feedback(client, "Portal captured — panorama saved", Formatting.GREEN);
	}

	private static void feedback(MinecraftClient client, String text, Formatting color) {
		if (client.player != null) {
			client.player.sendMessage(Text.literal("[Portal Glimpse] " + text).formatted(color), true);
		}
	}
}

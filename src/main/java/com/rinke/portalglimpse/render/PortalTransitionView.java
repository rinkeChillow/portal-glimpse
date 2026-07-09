package com.rinke.portalglimpse.render;

import com.rinke.portalglimpse.data.PortalRecord;
import com.rinke.portalglimpse.data.PortalStore;
import com.rinke.portalglimpse.detect.PortalDetection;

import org.lwjgl.glfw.GLFW;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DownloadingTerrainScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;

/**
 * Captures what the player was looking at through a portal the instant they travel, so the loading
 * screen can keep showing that destination view instead of a plain swirl ("last thing you saw", in
 * reverse). While the player stands in a captured portal we keep a fresh candidate (their view angle,
 * FOV, and that portal's panorama faces); the dimension change freezes it as the active backdrop.
 */
public final class PortalTransitionView {

	/** A candidate is only frozen if it was refreshed within this many ticks of the dimension change. */
	private static final int CANDIDATE_FRESH_TICKS = 3;

	/** Drop the active backdrop this many ticks after the loading screen is gone. */
	private static final int CLEAR_AFTER_TICKS = 10;

	/** How long (ticks) the backdrop panorama takes to fade out once the loading screen is actually
	 * done, instead of cutting away instantly (Phase 4.9). The swirl veil is unaffected — it keeps
	 * spinning at its own opacity through and after this fade. */
	private static final int CLOSE_FADE_TICKS = 12;

	// Candidate — refreshed each tick while standing in a captured portal (origin side).
	private static Identifier[] candidateFaces;
	private static float candidateYaw;
	private static float candidatePitch;
	private static float candidateFov;
	private static int candidateTick;

	private static int tickCounter;
	private static int clearGrace;
	private static Identifier lastDimension;

	// Active — frozen for the loading screen. Read from the render thread.
	private static volatile boolean active;
	private static Identifier[] activeFaces;
	private static final float[] forward = new float[3];
	private static final float[] right = new float[3];
	private static final float[] up = new float[3];
	private static float activeFov;

	/** DEBUG: while a backdrop is active the loading screen is held open until Numpad 5 is pressed, so
	 * the view can be inspected instead of flashing past. Temporary testing aid. */
	private static volatile boolean debugReleased;

	/** Ticks left in the closing fade, or -1 when not fading (§Phase 4.9). Counts down from
	 * {@link #CLOSE_FADE_TICKS} to 0 once every mandatory hold has cleared. */
	private static int closeFadeTicksLeft = -1;

	private PortalTransitionView() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(PortalTransitionView::onTick);
	}

	public static boolean isActive() {
		return active && activeFaces != null;
	}

	public static Identifier[] faces() {
		return activeFaces;
	}

	public static float[] forward() {
		return forward;
	}

	public static float[] right() {
		return right;
	}

	public static float[] up() {
		return up;
	}

	public static float fovDegrees() {
		return activeFov;
	}

	/** DEBUG: true while the loading screen should be held open waiting for Numpad 5. */
	public static boolean shouldHoldForDebug() {
		return active && activeFaces != null && !debugReleased;
	}

	/**
	 * Call once every mandatory hold on the loading screen has cleared. Ramps the backdrop out over
	 * {@link #CLOSE_FADE_TICKS} instead of vanishing the instant the screen is allowed to close.
	 * Returns true while the fade is still running (the caller should keep holding the screen open);
	 * once it finishes the backdrop is cleared and this returns false, letting the screen proceed.
	 */
	public static boolean tickClosingFade() {
		if (!active || activeFaces == null) {
			closeFadeTicksLeft = -1;
			return false;
		}
		if (closeFadeTicksLeft < 0) {
			closeFadeTicksLeft = CLOSE_FADE_TICKS;
		}
		if (closeFadeTicksLeft == 0) {
			active = false;
			activeFaces = null;
			closeFadeTicksLeft = -1;
			return false;
		}
		closeFadeTicksLeft--;
		return true;
	}

	/** Backdrop opacity: 1.0 while holding, ramping to 0.0 over the closing fade. The swirl veil
	 * drawn over it is untouched — only the destination view underneath dissolves. */
	public static float backdropAlpha() {
		return closeFadeTicksLeft < 0 ? 1.0F : closeFadeTicksLeft / (float) CLOSE_FADE_TICKS;
	}

	/**
	 * Freeze the fresh candidate immediately if the backdrop isn't active yet. Called from the render
	 * thread as the loading screen first draws, so our backdrop is up on frame one instead of waiting
	 * for the dimension-change tick — which is starved during chunk loading, letting vanilla flash
	 * through first. Only a candidate freshly captured while standing in the portal (this travel)
	 * qualifies, so non-portal loading screens stay vanilla.
	 */
	public static void tryArmForLoading() {
		if (!active && candidateFaces != null && tickCounter - candidateTick <= CANDIDATE_FRESH_TICKS) {
			freeze();
		}
	}

	private static void onTick(MinecraftClient client) {
		tickCounter++;

		// Poll the raw Numpad-5 state (keybinds don't fire while a screen is open) to release the hold.
		if (InputUtil.isKeyPressed(client.getWindow().getHandle(), GLFW.GLFW_KEY_KP_5)) {
			debugReleased = true;
		}

		ClientWorld world = client.world;
		ClientPlayerEntity player = client.player;
		if (world == null || player == null) {
			lastDimension = null;
			candidateFaces = null;
			return;
		}

		Identifier dimension = world.getRegistryKey().getValue();
		if (lastDimension != null && !dimension.equals(lastDimension)) {
			// Crossed a dimension boundary — freeze the candidate as the loading backdrop if it's fresh.
			if (candidateFaces != null && tickCounter - candidateTick <= CANDIDATE_FRESH_TICKS) {
				freeze();
			} else {
				active = false;
			}
			candidateFaces = null;
		}
		lastDimension = dimension;

		// Refresh the candidate while the player stands in a captured portal.
		PortalRecord portal = capturedPortalAt(player, world);
		if (portal != null) {
			PortalStore store = PortalDetection.store();
			Identifier[] faces = store == null ? null
					: PanoramaTextures.get(client, store.baseDir(), portal);
			if (faces != null) {
				candidateFaces = faces;
				candidateYaw = player.getYaw();
				candidatePitch = player.getPitch();
				candidateFov = client.options.getFov().getValue().floatValue();
				candidateTick = tickCounter;
			}
		}

		// Drop the backdrop once the loading screen has been gone for a moment.
		if (active) {
			if (client.currentScreen instanceof DownloadingTerrainScreen) {
				clearGrace = 0;
			} else if (++clearGrace > CLEAR_AFTER_TICKS) {
				active = false;
				activeFaces = null;
			}
		}
	}

	/** Build the world-axis camera basis from the frozen yaw/pitch and mark the backdrop active. */
	private static void freeze() {
		float yaw = (float) Math.toRadians(candidateYaw);
		float pitch = (float) Math.toRadians(candidatePitch);
		float cosPitch = MathHelper.cos(pitch);
		// MC look vector: (-sin(yaw)cos(pitch), -sin(pitch), cos(yaw)cos(pitch)).
		float fx = -MathHelper.sin(yaw) * cosPitch;
		float fy = -MathHelper.sin(pitch);
		float fz = MathHelper.cos(yaw) * cosPitch;
		forward[0] = fx;
		forward[1] = fy;
		forward[2] = fz;

		// right = normalize(forward × worldUp); worldUp = (0,1,0).
		float rx = -fz;
		float rz = fx;
		float rlen = MathHelper.sqrt(rx * rx + rz * rz);
		if (rlen < 1.0e-4F) { // looking almost straight up/down — pick an arbitrary horizontal right
			rx = 1.0F;
			rz = 0.0F;
			rlen = 1.0F;
		}
		right[0] = rx / rlen;
		right[1] = 0.0F;
		right[2] = rz / rlen;

		// up = right × forward.
		up[0] = right[1] * forward[2] - right[2] * forward[1];
		up[1] = right[2] * forward[0] - right[0] * forward[2];
		up[2] = right[0] * forward[1] - right[1] * forward[0];

		activeFaces = candidateFaces;
		activeFov = candidateFov;
		clearGrace = 0;
		closeFadeTicksLeft = -1; // fresh backdrop starts fully opaque, not mid-fade
		debugReleased = false; // hold the loading screen open for inspection until Numpad 5
		active = true;
	}

	/** The captured portal the player's body currently overlaps, or null. */
	private static PortalRecord capturedPortalAt(ClientPlayerEntity player, ClientWorld world) {
		PortalStore store = PortalDetection.store();
		if (store == null) {
			return null;
		}
		Box box = player.getBoundingBox();
		int minX = MathHelper.floor(box.minX);
		int maxX = MathHelper.floor(box.maxX);
		int minY = MathHelper.floor(box.minY);
		int maxY = MathHelper.floor(box.maxY);
		int minZ = MathHelper.floor(box.minZ);
		int maxZ = MathHelper.floor(box.maxZ);
		BlockPos.Mutable pos = new BlockPos.Mutable();
		for (int x = minX; x <= maxX; x++) {
			for (int y = minY; y <= maxY; y++) {
				for (int z = minZ; z <= maxZ; z++) {
					pos.set(x, y, z);
					if (!world.getBlockState(pos).isOf(Blocks.NETHER_PORTAL)) {
						continue;
					}
					PortalRecord record = store.recordAt(pos);
					if (record != null && record.auto.hasCapture) {
						return record;
					}
				}
			}
		}
		return null;
	}
}

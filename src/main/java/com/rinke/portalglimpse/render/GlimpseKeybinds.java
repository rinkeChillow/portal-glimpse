package com.rinke.portalglimpse.render;

import org.lwjgl.glfw.GLFW;

import com.rinke.portalglimpse.data.PortalRecord;
import com.rinke.portalglimpse.data.PortalStore;
import com.rinke.portalglimpse.detect.PortalDetection;
import com.rinke.portalglimpse.ghost.GhostController;
import com.rinke.portalglimpse.ghost.GhostState;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

/**
 * Hard-coded DEBUG keys, read from raw GLFW state so they DON'T appear in the vanilla Controls menu
 * (they're not meant to be rebound — only the developer uses them). All gated behind the hidden
 * {@code /pgdebug} toggle ({@link GlimpseSettings#debugMode}), off by default:
 * Numpad 9/6 veil ±, Numpad 8/2 FOV ±, H glimpses, J postcard fade, K debug cubemap, Numpad 0
 * block-travel, Numpad 1 ghost-freeze (hide+clone the nearest portal with no capture, for testing the
 * block-hiding under other renderers like Sodium), Numpad 3 RTT FBO blit, and the shaderpack calibration
 * dials — Numpad 7/4 brightness ±, Page Up/Down gamma ±, Numpad . to copy the finished table line — and
 * Numpad * to toggle the RTT veil, Numpad / to cycle the debug cull cone (frustum-cull test), Numpad
 * Enter to cycle the nearest-N panorama cap, and Numpad - to drop the RTT glimpse's depth-write (the
 * shader-AO corner-crease test). (The
 * Numpad-5 loading-screen hold is polled separately in {@code PortalTransitionView}.)
 */
public final class GlimpseKeybinds {

	private static final int STEP = 25;

	private static final int KEY_VEIL_UP = GLFW.GLFW_KEY_KP_9;
	private static final int KEY_VEIL_DOWN = GLFW.GLFW_KEY_KP_6;
	private static final int KEY_TOGGLE_GLIMPSES = GLFW.GLFW_KEY_H;
	private static final int KEY_TOGGLE_FADE = GLFW.GLFW_KEY_J;
	private static final int KEY_FOV_UP = GLFW.GLFW_KEY_KP_8;
	private static final int KEY_FOV_DOWN = GLFW.GLFW_KEY_KP_2;
	private static final int KEY_DEBUG_PANORAMA = GLFW.GLFW_KEY_K;
	private static final int KEY_BLOCK_TRAVEL = GLFW.GLFW_KEY_KP_0;
	private static final int KEY_GHOST_FREEZE = GLFW.GLFW_KEY_KP_1;
	private static final int KEY_RTT_BLIT = GLFW.GLFW_KEY_KP_3;
	private static final int KEY_DIM_UP = GLFW.GLFW_KEY_KP_7;
	private static final int KEY_DIM_DOWN = GLFW.GLFW_KEY_KP_4;
	private static final int KEY_GAMMA_UP = GLFW.GLFW_KEY_PAGE_UP;
	private static final int KEY_GAMMA_DOWN = GLFW.GLFW_KEY_PAGE_DOWN;
	private static final int KEY_CALIBRATION_REPORT = GLFW.GLFW_KEY_KP_DECIMAL;
	private static final int KEY_RTT_VEIL = GLFW.GLFW_KEY_KP_MULTIPLY;
	private static final int KEY_CULL_FOV = GLFW.GLFW_KEY_KP_DIVIDE;
	private static final int KEY_NEAREST_N = GLFW.GLFW_KEY_KP_ENTER;
	private static final int KEY_RTT_NO_DEPTH = GLFW.GLFW_KEY_KP_SUBTRACT;

	/** How far one press moves each calibration dial. Brightness is a linear gain so it wants a fine step;
	 * gamma is an exponent where small moves are already very visible. */
	private static final float DIM_STEP = 0.02F;
	private static final float GAMMA_STEP = 0.05F;

	private static final int[] KEYS = {
			KEY_VEIL_UP, KEY_VEIL_DOWN, KEY_TOGGLE_GLIMPSES, KEY_TOGGLE_FADE,
			KEY_FOV_UP, KEY_FOV_DOWN, KEY_DEBUG_PANORAMA, KEY_BLOCK_TRAVEL, KEY_GHOST_FREEZE,
			KEY_RTT_BLIT, KEY_DIM_UP, KEY_DIM_DOWN, KEY_GAMMA_UP, KEY_GAMMA_DOWN,
			KEY_CALIBRATION_REPORT, KEY_RTT_VEIL, KEY_CULL_FOV, KEY_NEAREST_N, KEY_RTT_NO_DEPTH
	};
	/** Previous frame's down-state per key, for rising-edge detection. */
	private static final boolean[] WAS_DOWN = new boolean[KEYS.length];
	/** Last entity-mask decision we printed, so the diagnostic only fires on change (see onTick). */
	private static String lastMaskDecision = "";

	private GlimpseKeybinds() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(GlimpseKeybinds::onTick);
	}

	private static void onTick(MinecraftClient client) {
		long handle = client.getWindow().getHandle();
		boolean[] justPressed = new boolean[KEYS.length];
		for (int i = 0; i < KEYS.length; i++) {
			boolean down = InputUtil.isKeyPressed(handle, KEYS[i]);
			justPressed[i] = down && !WAS_DOWN[i];
			WAS_DOWN[i] = down;
		}
		// Only while the hidden debug mode is on, and not while a screen (chat/menu) owns input.
		if (!GlimpseSettings.debugMode || client.currentScreen != null) {
			return;
		}

		Identifier dim = client.world != null ? client.world.getRegistryKey().getValue() : null;
		boolean veilChanged = false;
		if (justPressed[0] && dim != null) {
			GlimpseSettings.nudgeVeilForStandingIn(dim, STEP);
			veilChanged = true;
		}
		if (justPressed[1] && dim != null) {
			GlimpseSettings.nudgeVeilForStandingIn(dim, -STEP);
			veilChanged = true;
		}
		if (veilChanged) {
			int alpha = GlimpseSettings.veilAlphaForStandingIn(dim);
			int percent = Math.round(alpha * 100.0F / 255.0F);
			actionbar(client, "Veil opacity (this view): " + percent + "%"
					+ (alpha == 0 ? " (pure glimpse)" : "")
					+ (alpha == 255 ? " (fully vanilla)" : ""));
		}
		if (justPressed[2]) {
			GlimpseSettings.glimpsesVisible = !GlimpseSettings.glimpsesVisible;
			actionbar(client, GlimpseSettings.glimpsesVisible
					? "Portal Glimpse ON"
					: "Portal Glimpse OFF — vanilla portals");
		}
		if (justPressed[3]) {
			GlimpseSettings.proximityFade = !GlimpseSettings.proximityFade;
			actionbar(client, GlimpseSettings.proximityFade
					? "Postcard distance fade ON"
					: "Postcard distance fade OFF");
		}
		boolean fovChanged = false;
		if (justPressed[4]) {
			GlimpseSettings.panoramaFovDegrees = Math.min(60.0F, GlimpseSettings.panoramaFovDegrees + 5.0F);
			fovChanged = true;
		}
		if (justPressed[5]) {
			GlimpseSettings.panoramaFovDegrees = Math.max(20.0F, GlimpseSettings.panoramaFovDegrees - 5.0F);
			fovChanged = true;
		}
		if (fovChanged) {
			actionbar(client, String.format("Panorama FOV: %.0f° (higher = wider / smaller content)",
					GlimpseSettings.panoramaFovDegrees));
		}
		if (justPressed[6]) {
			toggleDebugPanorama(client);
		}
		if (justPressed[7]) {
			// Blocking travel only works with an integrated server (SP / LAN host); a remote server is
			// authoritative and unmodded, so refuse rather than pretend it worked.
			if (!GlimpseSettings.debugBlockPortalTravel && client.getServer() == null) {
				actionbar(client, "Portal travel block is singleplayer-only — a remote server controls teleporting");
			} else {
				GlimpseSettings.debugBlockPortalTravel = !GlimpseSettings.debugBlockPortalTravel;
				actionbar(client, GlimpseSettings.debugBlockPortalTravel
						? "Portal travel BLOCKED — stand in the portal to inspect (no teleport, no nausea)"
						: "Portal travel restored");
			}
		}
		if (justPressed[8]) {
			toggleGhostFreeze(client);
		}
		if (justPressed[9]) {
			GlimpseSettings.debugRttBlit = !GlimpseSettings.debugRttBlit;
			actionbar(client, GlimpseSettings.debugRttBlit
					? "RTT FBO blit ON — offscreen panorama buffer shown full-screen"
					: "RTT FBO blit OFF");
		}
		boolean calibrationChanged = false;
		if (justPressed[10]) {
			ShaderPackCalibration.nudgeDim(DIM_STEP);
			calibrationChanged = true;
		}
		if (justPressed[11]) {
			ShaderPackCalibration.nudgeDim(-DIM_STEP);
			calibrationChanged = true;
		}
		if (justPressed[12]) {
			ShaderPackCalibration.nudgeGamma(GAMMA_STEP);
			calibrationChanged = true;
		}
		if (justPressed[13]) {
			ShaderPackCalibration.nudgeGamma(-GAMMA_STEP);
			calibrationChanged = true;
		}
		if (calibrationChanged) {
			reportCalibration(client, false);
		}
		if (justPressed[14]) {
			reportCalibration(client, true);
		}
		if (justPressed[15]) {
			GlimpseSettings.rttVeilMode = GlimpseSettings.rttVeilMode.next();
			actionbar(client, "RTT veil: " + GlimpseSettings.rttVeilMode.label());
		}
		if (justPressed[16]) {
			// Cycle the debug cull cone: OFF -> 60 -> 40 -> 25 -> OFF. Portals outside it are culled while still
			// on-screen, so you can watch the frustum cull as you turn/move (the real view FOV is untouched).
			float f = GlimpseSettings.debugCullFovDegrees;
			f = f <= 0.0F ? 60.0F : (f > 50.0F ? 40.0F : (f > 30.0F ? 25.0F : 0.0F));
			GlimpseSettings.debugCullFovDegrees = f;
			actionbar(client, f <= 0.0F
					? "Debug cull cone OFF — culling against the real view frustum"
					: String.format("Debug cull cone: %.0f° — portals outside it cull while still on-screen", f));
		}
		if (justPressed[17]) {
			// Cycle the nearest-N panorama cap: 6 -> 4 -> 2 -> 1 -> 6. Dial it down to watch the further portals
			// fall back from the parallax panorama to the flat postcard.
			int n = GlimpseSettings.maxPanoramas;
			n = n > 4 ? 4 : (n > 2 ? 2 : (n > 1 ? 1 : 6));
			GlimpseSettings.maxPanoramas = n;
			actionbar(client, "Nearest-N panorama cap: " + n
					+ " (closest " + n + " render the parallax panorama; the rest show the postcard)");
		}
		if (justPressed[18]) {
			// Depth-write off for the RTT glimpse: the test for whether a pack's screen-space AO is what creases
			// our box corners. Solas derives its AO from depthtex0 alone, so no choice of render program can opt
			// out — depth is the only input, and therefore the only lever. See GlimpseSettings.debugRttNoDepthWrite.
			boolean off = !GlimpseSettings.debugRttNoDepthWrite;
			GlimpseSettings.debugRttNoDepthWrite = off;
			actionbar(client, off
					? "RTT depth-write OFF — if the corner creases go, it's the pack's depth-driven AO"
					: "RTT depth-write ON (normal)");
		}
		// TEMP DIAGNOSTIC: surface why the entity-over-panorama (PortalEntityMask) did or didn't engage for a
		// player near a captured portal — printed only when the decision changes, so it's not spam.
		if (!PortalEntityMask.debugDecision.equals(lastMaskDecision)) {
			lastMaskDecision = PortalEntityMask.debugDecision;
			actionbar(client, "EntityMask: " + lastMaskDecision);
		}
		// While travel is blocked, keep the client's portal-nausea wobble pinned at zero.
		if (GlimpseSettings.debugBlockPortalTravel && client.player != null) {
			client.player.nauseaIntensity = 0.0F;
			client.player.prevNauseaIntensity = 0.0F;
		}
	}

	/**
	 * Show where the two RTT calibration dials currently sit for the loaded shaderpack.
	 *
	 * <p>Calibrating means LOOKING at the portal, so these numbers can't be derived from a pack's source — they
	 * get dialled in-game and then baked into {@link ShaderPackCalibration}'s table. On a nudge this is just an
	 * action-bar readout; on an explicit press ({@code full}) it also drops the finished table line into chat
	 * and onto the clipboard, so a tuning session ends with something ready to paste rather than two floats to
	 * transcribe by eye.
	 */
	private static void reportCalibration(MinecraftClient client, boolean full) {
		if (!ShaderPackCalibration.isTuning()) {
			actionbar(client, ShaderPackCalibration.packName().isPresent()
					? "Calibration: no live tuning yet — Numpad 7/4 brightness, Page Up/Down gamma"
					: "Calibration: no shaderpack loaded");
			return;
		}
		ShaderPackCalibration.Calibration c = ShaderPackCalibration.currentOrFallback();
		actionbar(client, String.format("RTT calibration — brightness %.2f, gamma %.2f  (%s)",
				c.unlitDim(), c.gamma(), ShaderPackCalibration.packName().orElse("?")));
		if (!full) {
			return;
		}
		String line = ShaderPackCalibration.tuningTableLine();
		client.keyboard.setClipboard(line);
		if (client.player != null) {
			client.player.sendMessage(Text.literal("[Portal Glimpse] copied to clipboard:")
					.formatted(Formatting.LIGHT_PURPLE), false);
			client.player.sendMessage(Text.literal(line).formatted(Formatting.AQUA), false);
		}
	}

	/**
	 * Toggle the ghost (obsidian-hide + wall-clone) on the nearest portal, WITHOUT a capture, and hold
	 * it. Purely a diagnostic: it runs {@link GhostController}'s hide/clone logic and freezes it so the
	 * effect can be inspected under other renderers (e.g. Sodium) — press once to hide, again to restore.
	 * Re-pressing recomputes, so newly placed wall blocks around the frame are picked up (toggle off/on).
	 */
	private static void toggleGhostFreeze(MinecraftClient client) {
		if (GhostState.isActive()) {
			GhostController.deactivate(client);
			actionbar(client, "Ghost freeze OFF — portal restored");
			return;
		}
		PortalStore store = PortalDetection.store();
		ClientWorld world = client.world;
		if (store == null || client.player == null || world == null) {
			return;
		}
		PortalRecord nearest = store.findNearest(client.player.getBlockPos(), world.getRegistryKey().getValue());
		if (nearest == null) {
			actionbar(client, "Ghost freeze: no portal nearby");
			return;
		}
		GhostController.activate(client, nearest);
		actionbar(client, "Ghost freeze ON — obsidian + portal hidden/cloned, no capture "
				+ "(toggle off/on to recompute after placing blocks)");
	}

	/** Swap the nearest registered portal's panorama for the labeled debug cubemap (toggle). */
	private static void toggleDebugPanorama(MinecraftClient client) {
		PortalStore store = PortalDetection.store();
		ClientWorld world = client.world;
		if (store == null || client.player == null || world == null) {
			return;
		}
		PortalRecord nearest = store.findNearest(client.player.getBlockPos(), world.getRegistryKey().getValue());
		if (nearest == null) {
			actionbar(client, "Debug panorama: no portal nearby");
			return;
		}
		PanoramaDebug.toggle(nearest.id);
		actionbar(client, PanoramaDebug.isTarget(nearest.id)
				? "Debug panorama ON — nearest portal (capture it first if blank)"
				: "Debug panorama OFF");
	}

	private static void actionbar(MinecraftClient client, String text) {
		if (client.player != null) {
			client.player.sendMessage(Text.literal("[Portal Glimpse] " + text)
					.formatted(Formatting.LIGHT_PURPLE), true);
		}
	}
}

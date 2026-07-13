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
 * block-hiding under other renderers like Sodium). (The Numpad-5 loading-screen hold is polled
 * separately in {@code PortalTransitionView}.)
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

	private static final int[] KEYS = {
			KEY_VEIL_UP, KEY_VEIL_DOWN, KEY_TOGGLE_GLIMPSES, KEY_TOGGLE_FADE,
			KEY_FOV_UP, KEY_FOV_DOWN, KEY_DEBUG_PANORAMA, KEY_BLOCK_TRAVEL, KEY_GHOST_FREEZE
	};
	/** Previous frame's down-state per key, for rising-edge detection. */
	private static final boolean[] WAS_DOWN = new boolean[KEYS.length];

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
		// While travel is blocked, keep the client's portal-nausea wobble pinned at zero.
		if (GlimpseSettings.debugBlockPortalTravel && client.player != null) {
			client.player.nauseaIntensity = 0.0F;
			client.player.prevNauseaIntensity = 0.0F;
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

package com.rinke.portalglimpse.render;

import org.lwjgl.glfw.GLFW;

import com.rinke.portalglimpse.data.PortalRecord;
import com.rinke.portalglimpse.data.PortalStore;
import com.rinke.portalglimpse.detect.PortalDetection;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Debug keys for live-tuning the glimpse rendering while iterating:
 * <ul>
 *   <li>Numpad 9 / 6 — veil opacity up / down (§4.3; 0 = pure glimpse, 100% = vanilla swirl)</li>
 *   <li>H — toggle the glimpse view layer on/off; the modded veil keeps rendering either way</li>
 * </ul>
 */
public final class GlimpseKeybinds {

	private static final int STEP = 25;

	private static KeyBinding veilUpKey;
	private static KeyBinding veilDownKey;
	private static KeyBinding toggleGlimpsesKey;
	private static KeyBinding toggleFadeKey;
	private static KeyBinding radiusUpKey;
	private static KeyBinding radiusDownKey;
	private static KeyBinding debugPanoramaKey;
	private static KeyBinding blockTravelKey;

	private GlimpseKeybinds() {
	}

	public static void register() {
		veilUpKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.portal-glimpse.veil_up",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_KP_9,
				"key.categories.portal-glimpse"));
		veilDownKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.portal-glimpse.veil_down",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_KP_6,
				"key.categories.portal-glimpse"));
		toggleGlimpsesKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.portal-glimpse.toggle_glimpses",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_H,
				"key.categories.portal-glimpse"));
		toggleFadeKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.portal-glimpse.toggle_fade",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_J,
				"key.categories.portal-glimpse"));
		radiusUpKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.portal-glimpse.radius_up",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_KP_8,
				"key.categories.portal-glimpse"));
		radiusDownKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.portal-glimpse.radius_down",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_KP_2,
				"key.categories.portal-glimpse"));
		debugPanoramaKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.portal-glimpse.debug_panorama",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_K,
				"key.categories.portal-glimpse"));
		blockTravelKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.portal-glimpse.block_travel",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_KP_0,
				"key.categories.portal-glimpse"));
		ClientTickEvents.END_CLIENT_TICK.register(GlimpseKeybinds::onTick);
	}

	private static void onTick(MinecraftClient client) {
		if (veilUpKey == null || veilDownKey == null || toggleGlimpsesKey == null) {
			return;
		}
		// All of these are debug tools, hidden behind the /pgdebug toggle (default off). When debug is
		// off the keys do nothing, so a normal player pressing H/J/K/numpad sees no effect.
		if (!GlimpseSettings.debugMode) {
			return;
		}
		net.minecraft.util.Identifier dim = client.world != null
				? client.world.getRegistryKey().getValue() : null;
		boolean veilChanged = false;
		while (veilUpKey.wasPressed()) {
			if (dim != null) {
				GlimpseSettings.nudgeVeilForStandingIn(dim, STEP);
				veilChanged = true;
			}
		}
		while (veilDownKey.wasPressed()) {
			if (dim != null) {
				GlimpseSettings.nudgeVeilForStandingIn(dim, -STEP);
				veilChanged = true;
			}
		}
		if (veilChanged) {
			int alpha = GlimpseSettings.veilAlphaForStandingIn(dim);
			int percent = Math.round(alpha * 100.0F / 255.0F);
			actionbar(client, "Veil opacity (this view): " + percent + "%"
					+ (alpha == 0 ? " (pure glimpse)" : "")
					+ (alpha == 255 ? " (fully vanilla)" : ""));
		}
		while (toggleGlimpsesKey.wasPressed()) {
			GlimpseSettings.glimpsesVisible = !GlimpseSettings.glimpsesVisible;
			actionbar(client, GlimpseSettings.glimpsesVisible
					? "Glimpses ON — windows into the other world"
					: "Glimpses OFF — modded swirl only");
		}
		while (toggleFadeKey.wasPressed()) {
			GlimpseSettings.proximityFade = !GlimpseSettings.proximityFade;
			actionbar(client, GlimpseSettings.proximityFade
					? "Postcard distance fade ON"
					: "Postcard distance fade OFF");
		}
		boolean fovChanged = false;
		while (radiusUpKey.wasPressed()) {
			GlimpseSettings.panoramaFovDegrees = Math.min(60.0F, GlimpseSettings.panoramaFovDegrees + 5.0F);
			fovChanged = true;
		}
		while (radiusDownKey.wasPressed()) {
			GlimpseSettings.panoramaFovDegrees = Math.max(20.0F, GlimpseSettings.panoramaFovDegrees - 5.0F);
			fovChanged = true;
		}
		if (fovChanged) {
			actionbar(client, String.format("Panorama FOV: %.0f° (higher = wider / smaller content)",
					GlimpseSettings.panoramaFovDegrees));
		}
		while (debugPanoramaKey.wasPressed()) {
			toggleDebugPanorama(client);
		}
		while (blockTravelKey.wasPressed()) {
			// Blocking travel only works where THIS client also runs the (integrated) server that
			// decides teleportation — singleplayer or a LAN host. On a remote server the server is
			// authoritative and unmodded (we're client-only), so it'd teleport you regardless; refuse
			// loudly instead of pretending it worked.
			if (!GlimpseSettings.debugBlockPortalTravel && client.getServer() == null) {
				actionbar(client, "Portal travel block is singleplayer-only — a remote server controls teleporting");
				continue;
			}
			GlimpseSettings.debugBlockPortalTravel = !GlimpseSettings.debugBlockPortalTravel;
			actionbar(client, GlimpseSettings.debugBlockPortalTravel
					? "Portal travel BLOCKED — stand in the portal to inspect (no teleport, no nausea)"
					: "Portal travel restored");
		}
		// While travel is blocked, keep the client's portal-nausea wobble pinned at zero so the view
		// stays clear even if any lingering portal state tries to ramp it up.
		if (GlimpseSettings.debugBlockPortalTravel && client.player != null) {
			client.player.nauseaIntensity = 0.0F;
			client.player.prevNauseaIntensity = 0.0F;
		}
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

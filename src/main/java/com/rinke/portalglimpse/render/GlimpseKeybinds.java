package com.rinke.portalglimpse.render;

import org.lwjgl.glfw.GLFW;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
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
		ClientTickEvents.END_CLIENT_TICK.register(GlimpseKeybinds::onTick);
	}

	private static void onTick(MinecraftClient client) {
		if (veilUpKey == null || veilDownKey == null || toggleGlimpsesKey == null) {
			return;
		}
		boolean veilChanged = false;
		while (veilUpKey.wasPressed()) {
			GlimpseSettings.veilAlpha = Math.min(255, GlimpseSettings.veilAlpha + STEP);
			veilChanged = true;
		}
		while (veilDownKey.wasPressed()) {
			GlimpseSettings.veilAlpha = Math.max(0, GlimpseSettings.veilAlpha - STEP);
			veilChanged = true;
		}
		if (veilChanged) {
			int percent = Math.round(GlimpseSettings.veilAlpha * 100.0F / 255.0F);
			actionbar(client, "Veil opacity: " + percent + "%"
					+ (GlimpseSettings.veilAlpha == 0 ? " (pure glimpse)" : "")
					+ (GlimpseSettings.veilAlpha == 255 ? " (fully vanilla)" : ""));
		}
		while (toggleGlimpsesKey.wasPressed()) {
			GlimpseSettings.glimpsesVisible = !GlimpseSettings.glimpsesVisible;
			actionbar(client, GlimpseSettings.glimpsesVisible
					? "Glimpses ON — windows into the other world"
					: "Glimpses OFF — modded swirl only");
		}
	}

	private static void actionbar(MinecraftClient client, String text) {
		if (client.player != null) {
			client.player.sendMessage(Text.literal("[Portal Glimpse] " + text)
					.formatted(Formatting.LIGHT_PURPLE), true);
		}
	}
}

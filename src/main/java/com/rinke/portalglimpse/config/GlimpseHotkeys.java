package com.rinke.portalglimpse.config;

import org.lwjgl.glfw.GLFW;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Player-facing keybinds (NOT gated behind /pgdebug, unlike {@code GlimpseKeybinds}). Both default to
 * unbound so nothing is claimed unless the player sets a key: toggle glimpses on/off (persisted via
 * {@link GlimpseConfig}) and open the config screen.
 */
public final class GlimpseHotkeys {

	private static KeyBinding toggleGlimpsesKey;
	private static KeyBinding openConfigKey;

	private GlimpseHotkeys() {
	}

	/** The keybindings, exposed so the config screen can offer to rebind them too. */
	public static KeyBinding toggleGlimpsesKeyBinding() {
		return toggleGlimpsesKey;
	}

	public static KeyBinding openConfigKeyBinding() {
		return openConfigKey;
	}

	public static void register() {
		toggleGlimpsesKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.portal-glimpse.toggle_glimpses_hotkey",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_UNKNOWN, // default: none
				"key.categories.portal-glimpse"));
		openConfigKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.portal-glimpse.open_config",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_UNKNOWN, // default: none
				"key.categories.portal-glimpse"));
		ClientTickEvents.END_CLIENT_TICK.register(GlimpseHotkeys::onTick);
	}

	private static void onTick(MinecraftClient client) {
		if (toggleGlimpsesKey == null || openConfigKey == null) {
			return;
		}
		while (toggleGlimpsesKey.wasPressed()) {
			GlimpseConfig config = GlimpseConfig.get();
			config.glimpsesVisible = !config.glimpsesVisible;
			config.save(); // applies to GlimpseSettings and persists
			if (client.player != null) {
				client.player.sendMessage(Text.literal(config.glimpsesVisible
						? "[Portal Glimpse] ON" : "[Portal Glimpse] OFF — vanilla portals")
						.formatted(config.glimpsesVisible ? Formatting.LIGHT_PURPLE : Formatting.GRAY), true);
			}
		}
		while (openConfigKey.wasPressed()) {
			client.setScreen(GlimpseConfigScreen.create(client.currentScreen));
		}
	}
}

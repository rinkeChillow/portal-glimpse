package com.rinke.portalglimpse.ghost;

import org.lwjgl.glfw.GLFW;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

/**
 * Temporary debug keybind (default: G) to toggle ghosting on the nearest registered portal, so the
 * Phase 1.5 invisibility can be tested by hand before Phase 2 wires it into the capture.
 */
public final class GhostKeybinding {

	private static KeyBinding toggleKey;

	private GhostKeybinding() {
	}

	public static void register() {
		toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.portal-glimpse.toggle_ghost",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_G,
				"key.categories.portal-glimpse"));
		ClientTickEvents.END_CLIENT_TICK.register(GhostKeybinding::onTick);
	}

	private static void onTick(MinecraftClient client) {
		if (toggleKey == null) {
			return;
		}
		while (toggleKey.wasPressed()) {
			GhostController.toggleNearest(client);
		}
	}
}

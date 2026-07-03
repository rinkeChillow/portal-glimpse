package com.rinke.portalglimpse.capture;

import org.lwjgl.glfw.GLFW;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

/**
 * Debug keybind (default: G) that triggers a manual capture of the nearest portal. Capture handles
 * ghosting internally, so this is just the trigger.
 */
public final class CaptureKeybinding {

	private static KeyBinding captureKey;

	private CaptureKeybinding() {
	}

	public static void register() {
		captureKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.portal-glimpse.capture",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_G,
				"key.categories.portal-glimpse"));
		ClientTickEvents.END_CLIENT_TICK.register(CaptureKeybinding::onTick);
	}

	private static void onTick(MinecraftClient client) {
		if (captureKey == null) {
			return;
		}
		while (captureKey.wasPressed()) {
			CaptureManager.requestCaptureNearest(client);
		}
	}
}

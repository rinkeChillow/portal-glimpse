package com.rinke.portalglimpse.render;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * Phase 4.9: once the loading screen closes, keeps the portal swirl on screen as a full-screen
 * overlay for as long as the player is still standing in the portal they arrived through
 * ({@link PortalArrivalGate}) — the confirmation that they're "in the swirl" until they visually
 * step outside it. Only then does the swirl itself fade, over {@link #FADE_OUT_TICKS}.
 */
public final class PortalArrivalVeil {

	/** How long (ticks) the veil takes to fade out once the player steps clear of the arrival portal. */
	private static final int FADE_OUT_TICKS = 10;

	private static boolean wasArmed;
	private static int fadeTicksLeft = -1;

	private PortalArrivalVeil() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> onTick());
		HudRenderCallback.EVENT.register((context, tickCounter) -> render(context));
	}

	private static void onTick() {
		boolean armed = PortalArrivalGate.isArmed();
		if (armed) {
			fadeTicksLeft = -1; // stays fully visible the whole time the gate is armed
		} else if (wasArmed) {
			fadeTicksLeft = FADE_OUT_TICKS; // just stepped clear of the portal — start the fade
		} else if (fadeTicksLeft > 0) {
			fadeTicksLeft--;
		}
		wasArmed = armed;
	}

	private static void render(DrawContext context) {
		float alpha = alpha();
		if (alpha <= 0.0F) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.currentScreen != null) {
			return; // a screen (e.g. the loading backdrop itself) already owns the full-screen veil
		}
		PortalLoadingBackdrop.drawSwirl(client, alpha);
	}

	private static float alpha() {
		if (PortalArrivalGate.isArmed()) {
			return 1.0F;
		}
		return fadeTicksLeft <= 0 ? 0.0F : fadeTicksLeft / (float) FADE_OUT_TICKS;
	}
}

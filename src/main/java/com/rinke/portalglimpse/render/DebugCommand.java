package com.rinke.portalglimpse.render;

import com.rinke.portalglimpse.ghost.GhostController;
import com.rinke.portalglimpse.ghost.GhostState;

import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;

import com.rinke.portalglimpse.debug.McpServer;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * The hidden client command {@code /pgdebug} — toggles all of the mod's debug tooling (the tuning
 * keybinds in {@link GlimpseKeybinds}, the debug cubemap {@link PanoramaDebug}, the loading-screen
 * hold in {@link PortalTransitionView}, and the block-travel freeze). Default OFF.
 *
 * <p>It is deliberately NOT registered as a Brigadier command, so it never appears in chat
 * autocomplete or the command suggestion list — a normal player can't discover it. Instead we watch
 * the outgoing command text via {@link ClientSendMessageEvents#ALLOW_COMMAND} and, when it exactly
 * matches, swallow it (return {@code false}) so the server never sees an "unknown command". If you
 * know the string you type it and it works; if you don't, it's invisible.
 */
public final class DebugCommand {

	private static final String COMMAND = "pgdebug";

	private DebugCommand() {
	}

	public static void register() {
		ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
			if (!command.trim().equalsIgnoreCase(COMMAND)) {
				return true; // not ours — let it send normally
			}
			toggle();
			return false; // swallow it: no server round-trip, no "unknown command" reply
		});
	}

	private static void toggle() {
		boolean on = !GlimpseSettings.debugMode;
		GlimpseSettings.debugMode = on;
		MinecraftClient client = MinecraftClient.getInstance();
		// The MCP server exists only while debug mode does — see McpServer for why that gate matters.
		if (on) {
			McpServer.start();
		} else {
			McpServer.stop();
		}
		if (!on) {
			// Leaving debug mode: undo the live debug state the (now inert) keybinds can't clear.
			GlimpseSettings.debugBlockPortalTravel = false;
			PanoramaDebug.clear();
			if (GhostState.isActive()) {
				GhostController.deactivate(client); // restore a frozen ghost so the portal isn't left hidden
			}
		}
		if (client.player != null) {
			client.player.sendMessage(Text.literal("[Portal Glimpse] Debug mode: " + (on ? "ON" : "OFF"))
					.formatted(on ? Formatting.LIGHT_PURPLE : Formatting.GRAY), false);
			if (on && McpServer.isRunning()) {
				client.player.sendMessage(Text.literal("[Portal Glimpse] MCP server on 127.0.0.1:"
						+ McpServer.port() + "/mcp").formatted(Formatting.DARK_GRAY), false);
			}
		}
	}
}

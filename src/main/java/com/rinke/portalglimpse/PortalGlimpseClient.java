package com.rinke.portalglimpse;

import com.rinke.portalglimpse.capture.CaptureManager;
import com.rinke.portalglimpse.capture.ManualCapture;
import com.rinke.portalglimpse.config.GlimpseConfig;
import com.rinke.portalglimpse.config.GlimpseHotkeys;
import com.rinke.portalglimpse.config.PreviewOverlay;
import com.rinke.portalglimpse.detect.PortalDetection;
import com.rinke.portalglimpse.render.DebugCommand;
import com.rinke.portalglimpse.render.GlimpseRenderers;
import com.rinke.portalglimpse.render.GlimpseKeybinds;
import com.rinke.portalglimpse.render.GlimpseWorldRendering;
import com.rinke.portalglimpse.render.PanoramaSummonDebug;
import com.rinke.portalglimpse.render.PortalArrivalGate;
import com.rinke.portalglimpse.render.PortalArrivalVeil;
import com.rinke.portalglimpse.render.PortalTransitionView;
import com.rinke.portalglimpse.travel.TravelTracker;

import net.fabricmc.api.ClientModInitializer;

public class PortalGlimpseClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		// Load persistent settings first so they're applied before anything renders (Phase 5). The
		// Cloth Config screen (Mod Menu button) writes them back; GlimpseSettings holds the live copy.
		GlimpseConfig.load();

		// Select the rendering backend up front (design doc §7: all rendering goes behind a
		// thin abstraction so the later Sodium/Iris pass swaps implementations, not features).
		GlimpseRenderers.init();

		// Detect and register portals as the world streams in, and load their records (§3.2, §5).
		PortalDetection.register();

		// Capture pipeline (§3.2/§3.4): ghosts a portal and takes its cubemap glimpse. The manual
		// capture keybind (G) is disabled for now — captures happen automatically on portal travel
		// (TravelTracker). Re-add CaptureKeybinding.register() to bring the debug key back.
		CaptureManager.register();

		// Manual capture: Ctrl+Shift+right-click a portal to pin/cancel a player-curated glimpse (§3.4).
		ManualCapture.register();

		// Automatic glimpse on portal travel, behind the held loading screen (§3.2).
		TravelTracker.register();

		// Draw glimpses on portal surfaces every frame (§4): postcard under the living veil.
		GlimpseWorldRendering.register();
		GlimpseKeybinds.register();

		// Hidden "/pgdebug" command gates all the debug keybinds/tools above (default off) so normal
		// players never trigger them — only someone who knows the exact command can turn them on.
		DebugCommand.register();

		// Panorama preview tool (/pgdebug only): Ctrl+Shift+right-click a non-portal block to summon a floating
		// 3x3 panorama of a stored glimpse (the other dimension's), for inspecting captures without a portal.
		// Registered AFTER ManualCapture so portal clicks are consumed by it (capture-pin) first.
		PanoramaSummonDebug.register();

		// Player-facing keybinds (default unbound): toggle glimpses, open the config screen.
		GlimpseHotkeys.register();

		// Global init hook so the maximized-preview overlay can attach to the config screen.
		PreviewOverlay.registerGlobal();

		// Hide a portal's glimpse while the player is still standing in it right after teleporting in.
		PortalArrivalGate.register();

		// Remember what the player saw through the portal on entry, to show it on the loading screen.
		PortalTransitionView.register();

		// Phase 4.9: keep the portal swirl up as a full-screen overlay after the loading screen closes,
		// fading it out only once the player physically steps clear of the arrival portal.
		PortalArrivalVeil.register();

		PortalGlimpse.LOGGER.info("Portal Glimpse client ready — glimpse renderer: {}",
				GlimpseRenderers.get().name());
	}
}

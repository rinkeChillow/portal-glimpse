package com.rinke.portalglimpse;

import com.rinke.portalglimpse.capture.CaptureManager;
import com.rinke.portalglimpse.detect.PortalDetection;
import com.rinke.portalglimpse.render.GlimpseRenderers;
import com.rinke.portalglimpse.render.GlimpseKeybinds;
import com.rinke.portalglimpse.render.GlimpseWorldRendering;
import com.rinke.portalglimpse.render.PortalArrivalGate;
import com.rinke.portalglimpse.render.PortalArrivalVeil;
import com.rinke.portalglimpse.render.PortalTransitionView;
import com.rinke.portalglimpse.travel.TravelTracker;

import net.fabricmc.api.ClientModInitializer;

public class PortalGlimpseClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		// Select the rendering backend up front (design doc §7: all rendering goes behind a
		// thin abstraction so the later Sodium/Iris pass swaps implementations, not features).
		GlimpseRenderers.init();

		// Detect and register portals as the world streams in, and load their records (§3.2, §5).
		PortalDetection.register();

		// Capture pipeline (§3.2/§3.4): ghosts a portal and takes its cubemap glimpse. The manual
		// capture keybind (G) is disabled for now — captures happen automatically on portal travel
		// (TravelTracker). Re-add CaptureKeybinding.register() to bring the debug key back.
		CaptureManager.register();

		// Automatic glimpse on portal travel, behind the held loading screen (§3.2).
		TravelTracker.register();

		// Draw glimpses on portal surfaces every frame (§4): postcard under the living veil.
		GlimpseWorldRendering.register();
		GlimpseKeybinds.register();

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

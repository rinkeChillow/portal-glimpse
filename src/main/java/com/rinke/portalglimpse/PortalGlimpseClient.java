package com.rinke.portalglimpse;

import com.rinke.portalglimpse.capture.CaptureKeybinding;
import com.rinke.portalglimpse.capture.CaptureManager;
import com.rinke.portalglimpse.detect.PortalDetection;
import com.rinke.portalglimpse.render.GlimpseRenderers;
import com.rinke.portalglimpse.render.GlimpseKeybinds;
import com.rinke.portalglimpse.render.GlimpseWorldRendering;
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

		// Capture pipeline (§3.2/§3.4): the debug key ghosts a portal and takes its cubemap glimpse.
		CaptureManager.register();
		CaptureKeybinding.register();

		// Automatic glimpse on portal travel, behind the held loading screen (§3.2).
		TravelTracker.register();

		// Draw glimpses on portal surfaces every frame (§4): postcard under the living veil.
		GlimpseWorldRendering.register();
		GlimpseKeybinds.register();

		PortalGlimpse.LOGGER.info("Portal Glimpse client ready — glimpse renderer: {}",
				GlimpseRenderers.get().name());
	}
}

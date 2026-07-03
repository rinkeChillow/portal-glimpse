package com.rinke.portalglimpse;

import com.rinke.portalglimpse.detect.PortalDetection;
import com.rinke.portalglimpse.render.GlimpseRenderers;

import net.fabricmc.api.ClientModInitializer;

public class PortalGlimpseClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		// Select the rendering backend up front (design doc §7: all rendering goes behind a
		// thin abstraction so the later Sodium/Iris pass swaps implementations, not features).
		GlimpseRenderers.init();

		// Detect and register portals as the world streams in, and load their records (§3.2, §5).
		PortalDetection.register();

		PortalGlimpse.LOGGER.info("Portal Glimpse client ready — glimpse renderer: {}",
				GlimpseRenderers.get().name());
	}
}

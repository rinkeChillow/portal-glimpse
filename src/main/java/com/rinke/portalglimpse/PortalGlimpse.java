package com.rinke.portalglimpse;

import com.rinke.portalglimpse.render.OccluderBlock;

import net.fabricmc.api.ModInitializer;

import net.minecraft.util.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PortalGlimpse implements ModInitializer {
	public static final String MOD_ID = "portal-glimpse";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// Common entrypoint. This is a 100% client-side mod (design doc §2) — no server
		// logic lives here; see PortalGlimpseClient for the real initialization.
		// The one exception: the god-ray occluder block must be registered before registries
		// freeze (blocks can't register from the client entrypoint), though it is only ever used
		// client-side via terrain-mesh injection (TerrainOverride) and never placed in the world.
		OccluderBlock.register();
		LOGGER.info("Portal Glimpse loaded.");
	}

	public static Identifier id(String path) {
		return Identifier.of(MOD_ID, path);
	}
}

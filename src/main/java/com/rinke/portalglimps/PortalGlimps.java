package com.rinke.portalglimps;

import net.fabricmc.api.ModInitializer;

import net.minecraft.util.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PortalGlimps implements ModInitializer {
	public static final String MOD_ID = "portalglimps";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
        LOGGER.info("hehe boi");
	}

	public static Identifier id(String path) {
		return Identifier.of(MOD_ID, path);
	}
}

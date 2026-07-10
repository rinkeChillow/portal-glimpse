package com.rinke.portalglimpse.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Wires the Cloth Config screen to Mod Menu's "Config" button. Only loaded when Mod Menu is present
 * (it's a {@code modmenu} entrypoint), so Mod Menu stays an optional dependency.
 */
public final class ModMenuIntegration implements ModMenuApi {

	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return GlimpseConfigScreen::create;
	}
}

package com.rinke.portalglimpse.mixin;

import java.util.function.BooleanSupplier;

import net.minecraft.client.gui.screen.DownloadingTerrainScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the screen's own terrain-ready condition, so travel capture fires exactly when vanilla
 * would have dismissed the screen (design doc §12 item 9: "study how vanilla decides terrain is
 * ready and hook after it").
 */
@Mixin(DownloadingTerrainScreen.class)
public interface DownloadingTerrainScreenAccessor {

	@Accessor("shouldClose")
	BooleanSupplier portalglimpse$getShouldClose();
}

package com.rinke.portalglimpse.mixin;

import com.rinke.portalglimpse.travel.TravelTracker;

import net.minecraft.client.gui.screen.DownloadingTerrainScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Holds the "Loading terrain" screen while a travel capture is running behind it (design doc
 * §3.2 step 2). Vanilla's {@code tick()} only decides whether to close the screen, so cancelling
 * it while the tracker is busy simply keeps the screen up; the tracker always releases the hold
 * (bounded by its own timeout), after which vanilla logic resumes untouched.
 */
@Mixin(DownloadingTerrainScreen.class)
public class DownloadingTerrainScreenMixin {

	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void portalglimpse$holdForCapture(CallbackInfo ci) {
		if (TravelTracker.shouldHoldLoadingScreen()) {
			ci.cancel();
		}
	}
}

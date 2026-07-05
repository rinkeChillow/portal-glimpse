package com.rinke.portalglimpse.mixin;

import com.rinke.portalglimpse.render.PortalLoadingBackdrop;
import com.rinke.portalglimpse.render.PortalTransitionView;
import com.rinke.portalglimpse.travel.TravelTracker;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.DownloadingTerrainScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Two portal-travel tweaks to the "Loading terrain" screen:
 * <ul>
 *   <li>Holds the screen while a travel capture runs behind it (design doc §3.2 step 2). Vanilla's
 *       {@code tick()} only decides whether to close, so cancelling it keeps the screen up; the
 *       tracker always releases the hold (bounded by its own timeout).</li>
 *   <li>Replaces the plain portal swirl with the destination view the player was looking at on entry
 *       ({@link PortalLoadingBackdrop}) when one is armed — the "last thing you saw", in reverse.</li>
 * </ul>
 */
@Mixin(DownloadingTerrainScreen.class)
public class DownloadingTerrainScreenMixin {

	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void portalglimpse$holdForCapture(CallbackInfo ci) {
		if (TravelTracker.shouldHoldLoadingScreen() || PortalTransitionView.shouldHoldForDebug()) {
			ci.cancel();
		}
	}

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void portalglimpse$renderBackdrop(DrawContext context, int mouseX, int mouseY, float delta,
			CallbackInfo ci) {
		if (PortalLoadingBackdrop.render(context)) {
			ci.cancel();
		}
	}
}

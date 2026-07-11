package com.rinke.portalglimpse.mixin;

import com.rinke.portalglimpse.config.PreviewOverlay;

import me.shedaniel.clothconfig2.gui.AbstractConfigScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While the maximized portal preview ({@link PreviewOverlay}) is open on top of a Cloth config
 * screen, swallow every {@code addTooltip} so the config entries behind it don't show their tooltips
 * on hover — the only tooltip allowed then is the overlay's own "drag to look around" hint, which is
 * drawn directly and never goes through this path. Timing-independent (the tooltip is never even
 * collected), unlike clearing the list after the fact.
 */
@Mixin(value = AbstractConfigScreen.class, remap = false)
public class AbstractConfigScreenMixin {

	@Inject(method = "addTooltip", at = @At("HEAD"), cancellable = true, remap = false)
	private void portalglimpse$suppressTooltipsWhileMaximized(CallbackInfo ci) {
		if (PreviewOverlay.isActive()) {
			ci.cancel();
		}
	}
}

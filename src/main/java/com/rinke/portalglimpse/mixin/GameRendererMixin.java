package com.rinke.portalglimpse.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.rinke.portalglimpse.capture.CaptureRenderer;

import net.minecraft.client.render.GameRenderer;

/**
 * While capturing panorama faces under an Iris shaderpack, widen the (otherwise hard-coded 90°) panorama
 * FOV to {@link CaptureRenderer#shaderCaptureFov} so the shaderpack's screen-edge vignette lands OUTSIDE the
 * central 90° that the capture keeps — {@link CaptureRenderer} crops the rim off, leaving seam-free faces.
 *
 * <p>Captures zero of {@code getFov}'s arguments so it doesn't depend on that method's exact signature; only
 * the flag being &gt; 0 (set only during our shader capture) triggers it, so nothing else is affected.
 *
 * <p>Injected at RETURN, NOT HEAD-cancel: Iris also hooks {@code getFov}, and short-circuiting its body left
 * Iris's per-frame view state stale during the capture (every panorama face rendered the first/front view).
 * At RETURN the whole method — and Iris's hooks — run; we only replace the final value.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

	// require = 0: if the getFov mapping ever shifts, this simply no-ops (vignette stays) instead of crashing.
	@Inject(method = "getFov", at = @At("RETURN"), cancellable = true, require = 0)
	private void portalglimpse$wideCaptureFov(CallbackInfoReturnable<Double> cir) {
		float fov = CaptureRenderer.shaderCaptureFov;
		if (fov > 0.0F) {
			cir.setReturnValue((double) fov);
		}
	}
}

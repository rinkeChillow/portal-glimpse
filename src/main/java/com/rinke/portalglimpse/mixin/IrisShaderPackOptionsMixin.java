package com.rinke.portalglimpse.mixin;

import java.util.HashMap;
import java.util.Map;

import com.rinke.portalglimpse.render.ShaderAoOverride;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Feeds our shader-option overrides into Iris as the pack's options are resolved, so a pack can be compiled
 * with its ambient occlusion off.
 *
 * <p>{@code ShaderPackOptions(IncludeGraph, Map<String, String>)} receives the user's changed shader options
 * — the map Iris parses out of the pack's config file, the same one the shader GUI writes. Adding entries to
 * it is therefore indistinguishable, from the pack's point of view, from the user having turned the setting
 * off by hand; no GLSL is rewritten and no pack-specific code path is patched.
 *
 * <p>WHY a whole mixin for this: some packs' AO has no per-object opt-out at all. Solas derives it from
 * {@code depthtex0} alone in {@code deferred.glsl}, with no material input, so no render-layer or gbuffer
 * routing on our side can exempt our quads (see {@link ShaderAoOverride} for the full reasoning). Turning
 * the pack's AO off is the only remaining lever, and Iris owns that switch.
 *
 * <p>We copy the map rather than mutating it: Iris may hand us an immutable or shared instance, and our
 * entries must not leak back into the user's saved settings.
 *
 * <p>{@code @Pseudo} + {@code remap = false} + {@code require = 0}: the target only exists with Iris
 * installed, the names are Iris's own and never remapped, and if a future Iris moves this constructor the
 * injection simply never happens. That is the designed failure mode — {@link ShaderAoOverride#markApplied()}
 * records that we ran, so the mod can SAY the feature is unavailable rather than quietly leaving the creases
 * and appearing broken.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.shaderpack.option.ShaderPackOptions", remap = false)
public class IrisShaderPackOptionsMixin {

	// NOT static: the target is an instance constructor, so the handler must be an instance method too —
	// a static one is a hard mixin-apply error rather than the silent no-op `require = 0` gives us.
	@ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, remap = false, require = 0)
	private Map<String, String> portalglimpse$forceAoOptions(Map<String, String> changedConfigs) {
		ShaderAoOverride.markApplied();
		Map<String, String> ours = ShaderAoOverride.overridesForCurrentPack();
		if (ours.isEmpty()) {
			return changedConfigs;
		}
		Map<String, String> merged = new HashMap<>(changedConfigs == null ? Map.of() : changedConfigs);
		merged.putAll(ours);
		return merged;
	}
}

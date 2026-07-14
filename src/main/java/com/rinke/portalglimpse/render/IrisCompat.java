package com.rinke.portalglimpse.render;

import java.lang.reflect.Method;

/**
 * Soft, reflection-based bridge to Iris's public {@code IrisApi}, used only to ask "is a shaderpack
 * currently active?". When one is, our custom-core-shader panorama can't render in Iris's deferred
 * pipeline the normal way (Iris replaces the fragment stage), so we defer it to a post-composite
 * overlay pass instead (see {@code VanillaGlimpseRenderer#renderAfterShaders}).
 *
 * <p>Resolved reflectively so the mod carries no hard Iris dependency: without Iris every call returns
 * {@code false} and the normal (vanilla/Sodium) render path runs unchanged.
 */
public final class IrisCompat {

	private static final Method GET_INSTANCE;
	private static final Method IS_SHADER_PACK_IN_USE;

	static {
		Method getInstance = null;
		Method inUse = null;
		try {
			Class<?> irisApi = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
			getInstance = irisApi.getMethod("getInstance");
			inUse = irisApi.getMethod("isShaderPackInUse");
		} catch (Throwable ignored) {
			// Iris absent or API moved — shadersActive() stays false forever.
		}
		GET_INSTANCE = getInstance;
		IS_SHADER_PACK_IN_USE = inUse;
	}

	private IrisCompat() {
	}

	/** True only if Iris is present AND a shaderpack is loaded from disk. Safe (false) without Iris. */
	public static boolean shadersActive() {
		if (GET_INSTANCE == null || IS_SHADER_PACK_IN_USE == null) {
			return false;
		}
		try {
			Object api = GET_INSTANCE.invoke(null);
			return api != null && (Boolean) IS_SHADER_PACK_IN_USE.invoke(api);
		} catch (Throwable ignored) {
			return false;
		}
	}
}

package com.rinke.portalglimpse.render;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Soft, reflection-based bridge to Iris, used to ask "is a shaderpack active?" and "which one?".
 * When one is active our custom-core-shader panorama can't render in Iris's deferred pipeline the
 * normal way (Iris replaces the fragment stage), so we defer it to a post-composite overlay pass
 * instead (see {@code VanillaGlimpseRenderer#renderAfterShaders}).
 *
 * <p>Resolved reflectively so the mod carries no hard Iris dependency: without Iris every call returns
 * {@code false}/empty and the normal (vanilla/Sodium) render path runs unchanged.
 */
public final class IrisCompat {

	private static final Method GET_INSTANCE;
	private static final Method IS_SHADER_PACK_IN_USE;
	/** {@code Iris.getIrisConfig()} — INTERNAL. The public IrisApi exposes no pack name, so this is the only
	 * route to it; guarded so a future rename just degrades to "unknown pack". */
	private static final Method GET_IRIS_CONFIG;
	/** {@code IrisConfig.getShaderPackName()} → {@code Optional<String>} (e.g. "BSL_v10.1.3.zip"). */
	private static final Method GET_SHADER_PACK_NAME;

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

		Method irisConfig = null;
		Method packName = null;
		try {
			irisConfig = Class.forName("net.irisshaders.iris.Iris").getMethod("getIrisConfig");
			packName = Class.forName("net.irisshaders.iris.config.IrisConfig").getMethod("getShaderPackName");
		} catch (Throwable ignored) {
			// Iris absent or internals moved — currentPackName() stays empty.
		}
		GET_IRIS_CONFIG = irisConfig;
		GET_SHADER_PACK_NAME = packName;
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

	/**
	 * Open Iris's own shaderpack-selection screen, with {@code parent} as the screen to return to. Uses the
	 * public {@code IrisApi#openMainIrisScreenObj(Object)} (Object-typed precisely so callers need no Iris
	 * classes). Returns false if Iris is absent or the call failed, so the caller can fall back.
	 */
	public static boolean openShaderPackScreen(Object parent) {
		if (GET_INSTANCE == null) {
			return false;
		}
		try {
			Object api = GET_INSTANCE.invoke(null);
			if (api == null) {
				return false;
			}
			Method open = api.getClass().getMethod("openMainIrisScreenObj", Object.class);
			Object screen = open.invoke(api, parent);
			if (screen instanceof net.minecraft.client.gui.screen.Screen s) {
				net.minecraft.client.MinecraftClient.getInstance().setScreen(s);
				return true;
			}
			return false;
		} catch (Throwable ignored) {
			return false;
		}
	}

	/**
	 * The active shaderpack's FULL name as Iris knows it — the file/folder name, e.g.
	 * {@code "BSL_v10.1.3.zip"} or {@code "ComplementaryReimagined_r5.8.1.zip"}. Empty when Iris is absent,
	 * no pack is loaded, or the internal API moved.
	 *
	 * <p>Read fresh each call (cheap reflection): the player can swap packs at runtime, and Iris reloads its
	 * pipeline on the change, so caching would go stale.
	 */
	public static Optional<String> currentPackName() {
		if (GET_IRIS_CONFIG == null || GET_SHADER_PACK_NAME == null || !shadersActive()) {
			return Optional.empty();
		}
		try {
			Object config = GET_IRIS_CONFIG.invoke(null);
			if (config == null) {
				return Optional.empty();
			}
			Object name = GET_SHADER_PACK_NAME.invoke(config);
			if (name instanceof Optional<?> opt) {
				return opt.filter(String.class::isInstance).map(String.class::cast);
			}
			return name instanceof String s ? Optional.of(s) : Optional.empty();
		} catch (Throwable ignored) {
			return Optional.empty();
		}
	}
}

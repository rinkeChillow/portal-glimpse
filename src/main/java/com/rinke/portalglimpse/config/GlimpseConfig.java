package com.rinke.portalglimpse.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.rinke.portalglimpse.PortalGlimpse;
import com.rinke.portalglimpse.render.GlimpseSettings;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Persistent, player-facing settings (Phase 5). Loaded once at client start and written back when
 * the Cloth Config screen is saved; the values are mirrored into {@link GlimpseSettings}, which is
 * what the renderer actually reads (so the render code stays oblivious to the config layer).
 *
 * <p>Debug-only tuning (the /pgdebug keybinds) still writes {@link GlimpseSettings} directly and is
 * intentionally NOT persisted — the config screen is the durable source of truth.
 */
public final class GlimpseConfig {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("portal-glimpse.json");

	private static GlimpseConfig instance;

	// Mirrors of the runtime fields in GlimpseSettings that players are allowed to set.
	public boolean glimpsesVisible = true;
	public float panoramaFovDegrees = 60.0F; // 20..60 (half field-of-view)
	public int netherVeilAlpha = 51;         // 0..255 (~20%) — Nether view, seen from the Overworld
	public int overworldVeilAlpha = 102;     // 0..255 (~40%) — Overworld view, seen from the Nether

	private GlimpseConfig() {
	}

	public static GlimpseConfig get() {
		if (instance == null) {
			load();
		}
		return instance;
	}

	/** Load from disk (or defaults on first run) and push the values into the live settings. */
	public static void load() {
		GlimpseConfig loaded = null;
		if (Files.exists(PATH)) {
			try (Reader reader = Files.newBufferedReader(PATH)) {
				loaded = GSON.fromJson(reader, GlimpseConfig.class);
			} catch (Exception e) {
				PortalGlimpse.LOGGER.warn("Failed to read config {} — using defaults", PATH, e);
			}
		}
		instance = loaded != null ? loaded : new GlimpseConfig();
		instance.clamp();
		instance.apply();
		if (loaded == null) {
			instance.save(); // write a default file on first run so it's discoverable/editable
		}
	}

	/** Persist to disk and re-apply to the live settings. */
	public void save() {
		clamp();
		apply();
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			PortalGlimpse.LOGGER.error("Failed to write config {}", PATH, e);
		}
	}

	/** Copy the persistent values into the runtime settings the renderer reads. */
	public void apply() {
		GlimpseSettings.glimpsesVisible = glimpsesVisible;
		GlimpseSettings.panoramaFovDegrees = panoramaFovDegrees;
		GlimpseSettings.netherVeilAlpha = netherVeilAlpha;
		GlimpseSettings.overworldVeilAlpha = overworldVeilAlpha;
	}

	private void clamp() {
		netherVeilAlpha = Math.max(0, Math.min(255, netherVeilAlpha));
		overworldVeilAlpha = Math.max(0, Math.min(255, overworldVeilAlpha));
		panoramaFovDegrees = Math.max(20.0F, Math.min(60.0F, panoramaFovDegrees));
	}
}

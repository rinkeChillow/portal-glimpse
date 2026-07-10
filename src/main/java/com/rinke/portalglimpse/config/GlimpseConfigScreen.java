package com.rinke.portalglimpse.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.rinke.portalglimpse.data.PortalRecord;
import com.rinke.portalglimpse.data.PortalStore;
import com.rinke.portalglimpse.detect.PortalDetection;
import com.rinke.portalglimpse.render.PanoramaTextures;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.gui.entries.IntegerSliderEntry;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * Builds the Cloth Config settings screen (opened from the Mod Menu "Config" button). On save the
 * values are written into {@link GlimpseConfig}, which persists them and mirrors them into the live
 * {@code GlimpseSettings}.
 */
public final class GlimpseConfigScreen {

	private GlimpseConfigScreen() {
	}

	public static Screen create(Screen parent) {
		GlimpseConfig config = GlimpseConfig.get();

		ConfigBuilder builder = ConfigBuilder.create()
				.setParentScreen(parent)
				.setTitle(Text.translatable("portal-glimpse.config.title"))
				.setSavingRunnable(config::save);

		ConfigEntryBuilder entry = builder.entryBuilder();
		ConfigCategory general = builder.getOrCreateCategory(
				Text.translatable("portal-glimpse.config.category.general"));

		general.addEntry(entry.startBooleanToggle(
						Text.translatable("portal-glimpse.config.glimpsesVisible"), config.glimpsesVisible)
				.setDefaultValue(true)
				.setTooltip(Text.translatable("portal-glimpse.config.glimpsesVisible.tooltip"))
				.setSaveConsumer(v -> config.glimpsesVisible = v)
				.build());

		general.addEntry(entry.startIntSlider(
						Text.translatable("portal-glimpse.config.panoramaFov"),
						Math.round(config.panoramaFovDegrees), 20, 60)
				.setDefaultValue(60)
				.setTextGetter(v -> Text.literal(v + "°"))
				.setTooltip(Text.translatable("portal-glimpse.config.panoramaFov.tooltip"))
				.setSaveConsumer(v -> config.panoramaFovDegrees = v)
				.build());

		// Veil opacity is stored 0..255 but presented as a friendly 0..100%, split by the dimension
		// being VIEWED (the swirl reads differently over a Nether vs Overworld view). Each slider gets
		// a live preview box above it (a portal-shaped panorama with the swirl at the current value).
		MinecraftClient client = MinecraftClient.getInstance();

		IntegerSliderEntry netherVeil = entry.startIntSlider(
						Text.translatable("portal-glimpse.config.netherVeilOpacity"),
						Math.round(config.netherVeilAlpha * 100.0F / 255.0F), 0, 100)
				.setDefaultValue(20)
				.setTextGetter(v -> Text.literal(v + "%"))
				.setTooltip(Text.translatable("portal-glimpse.config.netherVeilOpacity.tooltip"))
				.setSaveConsumer(v -> config.netherVeilAlpha = Math.round(v * 255.0F / 100.0F))
				.build();
		general.addEntry(new PortalPreviewEntry(
				Text.translatable("portal-glimpse.config.netherVeilOpacity"),
				() -> netherVeil.getValue(), pickPanoramaFace(client)));
		general.addEntry(netherVeil);

		IntegerSliderEntry overworldVeil = entry.startIntSlider(
						Text.translatable("portal-glimpse.config.overworldVeilOpacity"),
						Math.round(config.overworldVeilAlpha * 100.0F / 255.0F), 0, 100)
				.setDefaultValue(40)
				.setTextGetter(v -> Text.literal(v + "%"))
				.setTooltip(Text.translatable("portal-glimpse.config.overworldVeilOpacity.tooltip"))
				.setSaveConsumer(v -> config.overworldVeilAlpha = Math.round(v * 255.0F / 100.0F))
				.build();
		general.addEntry(new PortalPreviewEntry(
				Text.translatable("portal-glimpse.config.overworldVeilOpacity"),
				() -> overworldVeil.getValue(), pickPanoramaFace(client)));
		general.addEntry(overworldVeil);

		return builder.build();
	}

	/**
	 * A backdrop for the preview box: a random face of a real captured panorama if any are loaded,
	 * otherwise the vanilla title-screen panorama. Re-rolled each time the screen is built, so the
	 * preview varies between openings.
	 */
	private static Identifier pickPanoramaFace(MinecraftClient client) {
		List<Identifier> pool = new ArrayList<>();
		PortalStore store = PortalDetection.store();
		if (store != null) {
			for (PortalRecord record : store.all()) {
				if (!record.auto.hasCapture) {
					continue;
				}
				Identifier[] faces = PanoramaTextures.get(client, store.baseDir(), record);
				if (faces != null) {
					for (int i = 0; i < 4; i++) { // horizontal faces read best in a portal box
						if (faces[i] != null) {
							pool.add(faces[i]);
						}
					}
				}
			}
		}
		if (pool.isEmpty()) {
			for (int i = 0; i < 6; i++) {
				pool.add(Identifier.ofVanilla("textures/gui/title/background/panorama_" + i + ".png"));
			}
		}
		return pool.get(new Random().nextInt(pool.size()));
	}
}

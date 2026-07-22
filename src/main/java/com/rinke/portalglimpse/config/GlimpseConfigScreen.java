package com.rinke.portalglimpse.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.rinke.portalglimpse.data.PortalRecord;
import com.rinke.portalglimpse.data.PortalStore;
import com.rinke.portalglimpse.detect.PortalDetection;
import com.rinke.portalglimpse.render.PanoramaTextures;
import com.rinke.portalglimpse.render.ShaderPackCalibration;
import com.rinke.portalglimpse.render.ShaderRenderMethod;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.gui.entries.IntegerSliderEntry;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
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
				.setSavingRunnable(() -> {
					config.save();
					// Keybind fields below edit the game's KeyBindings directly; refresh + persist them.
					KeyBinding.updateKeysByCode();
					MinecraftClient.getInstance().options.write();
				});

		ConfigEntryBuilder entry = builder.entryBuilder();
		ConfigCategory general = builder.getOrCreateCategory(
				Text.translatable("portal-glimpse.config.category.general"));

		general.addEntry(entry.startBooleanToggle(
						Text.translatable("portal-glimpse.config.glimpsesVisible"), config.glimpsesVisible)
				.setDefaultValue(true)
				.setTooltip(Text.translatable("portal-glimpse.config.glimpsesVisible.tooltip"))
				.setSaveConsumer(v -> config.glimpsesVisible = v)
				.build());

		general.addEntry(entry.startBooleanToggle(
						Text.translatable("portal-glimpse.config.entityOverPanorama"), config.entityOverPanorama)
				.setDefaultValue(true)
				.setTooltip(Text.translatable("portal-glimpse.config.entityOverPanorama.tooltip"))
				.setSaveConsumer(v -> config.entityOverPanorama = v)
				.build());

		general.addEntry(entry.startEnumSelector(
						Text.translatable("portal-glimpse.config.shaderRenderMethod"),
						ShaderRenderMethod.class, config.shaderRenderMethod)
				.setDefaultValue(ShaderRenderMethod.OVERLAY)
				.setEnumNameProvider(v -> Text.translatable(
						"portal-glimpse.config.shaderRenderMethod." + ((ShaderRenderMethod) v).name().toLowerCase()))
				.setTooltip(Text.translatable("portal-glimpse.config.shaderRenderMethod.tooltip"))
				.setSaveConsumer(v -> config.shaderRenderMethod = v)
				.build());

		// RTT-only options (only relevant to the render-to-texture method). Shown only when RTT is the active
		// method — change the method and reopen the screen to see/hide them.
		if (config.shaderRenderMethod == ShaderRenderMethod.RTT) {
			// Read-only status: which shaderpack Iris reports, and whether we have RTT calibration tuned for it.
			// An uncalibrated pack still renders (on a fallback dim) but can look too dark/washed/blown out.
			general.addEntry(entry.startTextDescription(
					ShaderPackCalibration.packName()
							.map(name -> ShaderPackCalibration.isCurrentPackSupported()
									? Text.translatable("portal-glimpse.config.shaderpackStatus.supported", name)
											.formatted(Formatting.GREEN)
									: Text.translatable("portal-glimpse.config.shaderpackStatus.unsupported", name)
											.formatted(Formatting.YELLOW))
							.orElse(Text.translatable("portal-glimpse.config.shaderpackStatus.none")
									.formatted(Formatting.GRAY)))
					.build());

			general.addEntry(entry.startBooleanToggle(
							Text.translatable("portal-glimpse.config.godRayOccluder"), config.godRayOccluder)
					.setDefaultValue(true)
					.setTooltip(Text.translatable("portal-glimpse.config.godRayOccluder.tooltip"))
					.setSaveConsumer(v -> config.godRayOccluder = v)
					.build());

			general.addEntry(entry.startIntSlider(
							Text.translatable("portal-glimpse.config.rttPrediction"),
							config.rttMotionPredictionPercent, 0, 100)
					.setDefaultValue(100)
					.setTextGetter(v -> Text.literal(v + "%"))
					.setTooltip(Text.translatable("portal-glimpse.config.rttPrediction.tooltip"))
					.setSaveConsumer(v -> config.rttMotionPredictionPercent = v)
					.build());
		}

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

		// The label is drawn on the preview row (centred alongside the box), so the slider itself has
		// a blank name — matching the mockup's single, vertically-centred label.
		IntegerSliderEntry netherVeil = entry.startIntSlider(
						Text.empty(),
						Math.round(config.netherVeilAlpha * 100.0F / 255.0F), 0, 100)
				.setDefaultValue(20)
				.setTextGetter(v -> Text.literal(v + "%"))
				.setTooltip(Text.translatable("portal-glimpse.config.netherVeilOpacity.tooltip"))
				.setSaveConsumer(v -> config.netherVeilAlpha = Math.round(v * 255.0F / 100.0F))
				.build();
		general.addEntry(new PortalPreviewEntry(
				Text.translatable("portal-glimpse.config.netherVeilOpacity"),
				() -> netherVeil.getValue(), pickPanorama(client, true)));
		general.addEntry(netherVeil);

		IntegerSliderEntry overworldVeil = entry.startIntSlider(
						Text.empty(),
						Math.round(config.overworldVeilAlpha * 100.0F / 255.0F), 0, 100)
				.setDefaultValue(40)
				.setTextGetter(v -> Text.literal(v + "%"))
				.setTooltip(Text.translatable("portal-glimpse.config.overworldVeilOpacity.tooltip"))
				.setSaveConsumer(v -> config.overworldVeilAlpha = Math.round(v * 255.0F / 100.0F))
				.build();
		general.addEntry(new PortalPreviewEntry(
				Text.translatable("portal-glimpse.config.overworldVeilOpacity"),
				() -> overworldVeil.getValue(), pickPanorama(client, false)));
		general.addEntry(overworldVeil);

		general.addEntry(entry.startIntSlider(
						Text.translatable("portal-glimpse.config.captureCooldown"),
						config.autoCaptureCooldownMinutes, 0, 60)
				.setDefaultValue(5)
				.setTextGetter(v -> v == 0 ? Text.translatable("portal-glimpse.config.captureCooldown.off")
						: Text.literal(v + " min"))
				.setTooltip(Text.translatable("portal-glimpse.config.captureCooldown.tooltip"))
				.setSaveConsumer(v -> config.autoCaptureCooldownMinutes = v)
				.build());

		general.addEntry(entry.startIntSlider(
						Text.translatable("portal-glimpse.config.captureRadius"),
						config.captureChunkRadius, 0, 8)
				.setDefaultValue(4)
				.setTextGetter(v -> {
					int viewDistance = MinecraftClient.getInstance().options.getClampedViewDistance();
					// Warn (but still allow) when the radius exceeds render distance — those chunks never
					// load, so the wait would be capped there anyway.
					return v > viewDistance
							? Text.translatable("portal-glimpse.config.captureRadius.overRender", v, viewDistance)
									.formatted(Formatting.GOLD)
							: Text.literal(v + " chunks");
				})
				.setTooltip(Text.translatable("portal-glimpse.config.captureRadius.tooltip"))
				.setSaveConsumer(v -> config.captureChunkRadius = v)
				.build());

		// The two player keybinds, also editable here (they remain in the vanilla Controls menu too).
		general.addEntry(entry.fillKeybindingField(
				Text.translatable("key.portal-glimpse.toggle_glimpses_hotkey"),
				GlimpseHotkeys.toggleGlimpsesKeyBinding()).build());
		general.addEntry(entry.fillKeybindingField(
				Text.translatable("key.portal-glimpse.open_config"),
				GlimpseHotkeys.openConfigKeyBinding()).build());

		Screen screen = builder.build();
		// The maximized preview draws as an overlay ON TOP of this screen (keeps it sharp, no blur).
		// Actual hook-up happens on the screen's AFTER_INIT (see PreviewOverlay.registerGlobal).
		PreviewOverlay.expect(screen);
		return screen;
	}

	/**
	 * Horizontal panorama faces for a preview, re-rolled each time the screen is built so it varies.
	 * {@code wantNether} selects the CONTENT dimension: the Nether-veil preview shows Nether views,
	 * the Overworld-veil preview shows Overworld views. Priority:
	 * <ol>
	 *   <li>in-world — a random matching-dimension capture already loaded on the GPU;</li>
	 *   <li>otherwise (e.g. the main menu) — a random matching saved capture pulled off disk;</li>
	 *   <li>failing both — netherrack for a Nether view, or the vanilla title panorama for an Overworld view.</li>
	 * </ol>
	 */
	private static Identifier[] pickPanorama(MinecraftClient client, boolean wantNether) {
		PortalStore store = PortalDetection.store();
		if (store != null) {
			List<PortalRecord> matches = new ArrayList<>();
			for (PortalRecord record : store.all()) {
				Boolean nether = PreviewPanoramas.contentIsNether(record.dimension);
				if (record.auto.hasCapture && nether != null && nether == wantNether) {
					matches.add(record);
				}
			}
			Collections.shuffle(matches);
			for (PortalRecord record : matches) {
				Identifier[] faces = PanoramaTextures.get(client, store.baseDir(), record);
				if (faces != null) {
					return new Identifier[] { faces[0], faces[1], faces[2], faces[3] };
				}
			}
		}

		// No matching live captures (or no world): pull a matching saved capture off disk.
		Identifier[] fromDisk = PreviewPanoramas.pickFaces(client, wantNether);
		if (fromDisk != null) {
			return fromDisk;
		}

		// Nothing captured for that dimension yet: a dimension-appropriate placeholder.
		if (wantNether) {
			return new Identifier[] { Identifier.ofVanilla("textures/block/netherrack.png") };
		}
		Identifier[] title = new Identifier[4];
		for (int i = 0; i < 4; i++) {
			title[i] = Identifier.ofVanilla("textures/gui/title/background/panorama_" + i + ".png");
		}
		return title;
	}
}

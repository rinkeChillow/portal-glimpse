package com.rinke.portalglimpse.config;

import com.mojang.blaze3d.systems.RenderSystem;

import com.rinke.portalglimpse.PortalGlimpse;
import com.rinke.portalglimpse.render.IrisCompat;
import com.rinke.portalglimpse.render.ShaderPackCalibration;
import com.rinke.portalglimpse.render.ShaderRenderMethod;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmLinkScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;

/**
 * The in-your-face prompt shown when the player is running the RTT glimpse on a shaderpack we have no
 * calibration for (see {@link ShaderPackCalibration}). Each pack implements the unlit
 * {@code gbuffers_beaconbeam} pass with its own emissive maths, so an untuned pack can look far too dark,
 * washed out or blown out — better to say so plainly, and point at the ways forward, than to look broken.
 *
 * <p>It deliberately interrupts whatever screen is open (pack swaps happen inside the shaderpack menu, so a
 * polite prompt would never fire when it matters); the interrupted screen becomes its parent so closing
 * returns the player there.
 */
public class UnsupportedShaderScreen extends Screen {

	/** Where "Download BSL" sends people — the only pack with a tuned RTT calibration right now. */
	private static final String BSL_URL = "https://modrinth.com/shader/bsl-shaders";
	private static final String DISCORD_INVITE = "https://discord.gg/2e9MaDrjH";
	/** Shown in-game (with the avatar) so the player can copy it and message the author directly. */
	private static final String DISCORD_HANDLE = "rinke_";
	/** The author's Discord avatar. Optional: if the file isn't present we simply draw the name alone, so a
	 * missing texture never shows up as the pink/black placeholder. */
	private static final Identifier AVATAR =
			Identifier.of(PortalGlimpse.MOD_ID, "textures/gui/author_discord.png");
	private static final int AVATAR_SIZE = 64;
	/** How long the avatar takes to fade in after "Show author's Discord" — deliberately slow and gentle. */
	private static final float AVATAR_FADE_MS = 1500.0F;

	private final Screen parent;
	private final String packName;
	private final boolean bslInstalled;
	/** Set when the player asks to see the handle, so it (and the avatar) get rendered for copying. */
	private boolean showHandle;
	/** When {@link #showHandle} was set, so the avatar can ease in from it. Wall-clock rather than ticks: this
	 * screen pauses the game in singleplayer, and the fade should still run. */
	private long handleShownAt;

	public UnsupportedShaderScreen(Screen parent, String packName) {
		super(Text.translatable("portal-glimpse.unsupported.title"));
		this.parent = parent;
		this.packName = packName;
		this.bslInstalled = ShaderPackCalibration.isPackInstalled("bsl");
	}

	@Override
	protected void init() {
		int w = 260;
		int x = this.width / 2 - w / 2;
		// Starts well clear of the explanatory text above (which ends around height/2 - 53), so the block of
		// buttons reads as its own group instead of crowding the last line.
		int y = this.height / 2 - 26;
		int gap = 24;

		// Either "switch to one you already have" or "go get one" — never the wrong one of the two. The
		// bracketed list comes from the calibration table, so it grows by itself as packs get tuned.
		Text supported = Text.literal(ShaderPackCalibration.supportedDisplayNames());
		if (bslInstalled) {
			addDrawableChild(ButtonWidget.builder(
					Text.translatable("portal-glimpse.unsupported.chooseShaders", supported),
					b -> openIrisScreen()).dimensions(x, y, w, 20).build());
		} else {
			addDrawableChild(ButtonWidget.builder(
					Text.translatable("portal-glimpse.unsupported.downloadBsl", supported),
					b -> openLink(BSL_URL)).dimensions(x, y, w, 20).build());
		}
		y += gap;

		addDrawableChild(ButtonWidget.builder(
				Text.translatable("portal-glimpse.unsupported.discord"),
				b -> openLink(DISCORD_INVITE)).dimensions(x, y, w / 2 - 2, 20).build());
		addDrawableChild(ButtonWidget.builder(
				Text.translatable("portal-glimpse.unsupported.showHandle"),
				b -> {
					if (!showHandle) {
						handleShownAt = System.currentTimeMillis(); // starts the avatar fade
					}
					showHandle = true;
					if (this.client != null) {
						this.client.keyboard.setClipboard(DISCORD_HANDLE); // copied, so they can just paste
					}
				}).dimensions(x + w / 2 + 2, y, w / 2 - 2, 20).build());
		y += gap;

		addDrawableChild(ButtonWidget.builder(
				Text.translatable("portal-glimpse.unsupported.useOverlay"),
				b -> switchToOverlay()).dimensions(x, y, w, 20).build());
		y += gap;

		addDrawableChild(ButtonWidget.builder(
				Text.translatable("portal-glimpse.unsupported.keepGoing"),
				b -> close()).dimensions(x, y, w, 20).build());
	}

	/** Opens Iris's own shaderpack screen via its public API, so the player can switch without leaving. */
	private void openIrisScreen() {
		if (!IrisCompat.openShaderPackScreen(this)) {
			PortalGlimpse.LOGGER.warn("Portal Glimpse: couldn't open the Iris shaderpack screen");
			close();
		}
	}

	private void openLink(String url) {
		if (this.client == null) {
			return;
		}
		// Vanilla's confirmation screen — the player decides whether to actually open the browser.
		this.client.setScreen(new ConfirmLinkScreen(confirmed -> {
			if (confirmed) {
				Util.getOperatingSystem().open(url);
			}
			if (this.client != null) {
				this.client.setScreen(this);
			}
		}, url, true));
	}

	private void switchToOverlay() {
		GlimpseConfig config = GlimpseConfig.get();
		config.shaderRenderMethod = ShaderRenderMethod.OVERLAY;
		config.apply();
		config.save();
		close();
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		int cx = this.width / 2;
		int top = this.height / 2 - 118;
		context.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, top, 0xFFFFFF);
		context.drawCenteredTextWithShadow(this.textRenderer,
				Text.literal(packName).formatted(Formatting.YELLOW), cx, top + 14, 0xFFFFFF);
		context.drawCenteredTextWithShadow(this.textRenderer,
				Text.translatable("portal-glimpse.unsupported.line1").formatted(Formatting.GRAY),
				cx, top + 32, 0xFFFFFF);
		context.drawCenteredTextWithShadow(this.textRenderer,
				Text.translatable("portal-glimpse.unsupported.line2").formatted(Formatting.GRAY),
				cx, top + 44, 0xFFFFFF);
		context.drawCenteredTextWithShadow(this.textRenderer,
				Text.translatable("portal-glimpse.unsupported.line3").formatted(Formatting.GRAY),
				cx, top + 56, 0xFFFFFF);

		if (showHandle) {
			int y = this.height / 2 + 74; // below the last button (which ends around height/2 + 66)
			context.drawCenteredTextWithShadow(this.textRenderer,
					Text.translatable("portal-glimpse.unsupported.handleCopied", DISCORD_HANDLE)
							.formatted(Formatting.AQUA),
					cx, y, 0xFFFFFF);
			y += 14;
			// The author's avatar, centred under the name, easing in gently rather than popping. Optional —
			// only drawn if the texture actually ships, so a missing file degrades to just the name rather than
			// the missing-texture checkerboard.
			if (this.client != null && this.client.getResourceManager().getResource(AVATAR).isPresent()) {
				float t = MathHelper.clamp((System.currentTimeMillis() - handleShownAt) / AVATAR_FADE_MS,
						0.0F, 1.0F);
				float alpha = t * t * (3.0F - 2.0F * t); // smoothstep — eases in and settles instead of ramping
				RenderSystem.enableBlend();
				RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
				context.drawTexture(AVATAR, cx - AVATAR_SIZE / 2, y, 0.0F, 0.0F,
						AVATAR_SIZE, AVATAR_SIZE, AVATAR_SIZE, AVATAR_SIZE);
				RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F); // never leak the tint into later draws
				RenderSystem.disableBlend();
			}
		}
	}

	@Override
	public void close() {
		if (this.client != null) {
			this.client.setScreen(parent);
		}
	}
}

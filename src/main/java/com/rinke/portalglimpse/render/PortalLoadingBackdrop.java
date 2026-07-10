package com.rinke.portalglimpse.render;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.Sprite;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * Draws the portal loading screen's custom look: the destination cubemap the player was looking at
 * on entry, filling the screen from that same view angle ({@link PortalTransitionView}), with the
 * vanilla "Loading terrain…" text on top. Returns whether it took over the screen.
 */
public final class PortalLoadingBackdrop {

	private static final Text LOADING_TEXT = Text.translatable("multiplayer.downloadingTerrain");
	private static final Text DEBUG_HINT = Text.literal("[debug] hold — press Numpad 5 to continue");

	private PortalLoadingBackdrop() {
	}

	/** Render our backdrop if one is armed; returns true if it did (caller should skip vanilla's). */
	public static boolean render(DrawContext context) {
		// Arm on the first loading frame (the tick that would normally do it is starved during chunk
		// loading), so our backdrop shows immediately instead of vanilla flashing through first.
		PortalTransitionView.tryArmForLoading();
		if (!PortalTransitionView.isActive()) {
			return false;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (!drawBackdrop(client)) {
			return false;
		}
		drawSwirl(client, 1.0F);
		int cx = context.getScaledWindowWidth() / 2;
		int cy = context.getScaledWindowHeight() / 2 - 50;
		context.drawCenteredTextWithShadow(client.textRenderer, LOADING_TEXT, cx, cy, 0xFFFFFF);
		if (PortalTransitionView.shouldHoldForDebug()) {
			context.drawCenteredTextWithShadow(client.textRenderer, DEBUG_HINT, cx, cy + 14, 0xFFAA55);
		}
		return true;
	}

	/** Full-screen quad sampling the six captured faces along the frozen camera basis. */
	private static boolean drawBackdrop(MinecraftClient client) {
		ShaderProgram shader = PortalShaders.loading();
		Identifier[] faces = PortalTransitionView.faces();
		if (shader == null || faces == null) {
			return false;
		}

		float tanY = (float) Math.tan(Math.toRadians(PortalTransitionView.fovDegrees()) / 2.0);
		int w = client.getWindow().getFramebufferWidth();
		int h = client.getWindow().getFramebufferHeight();
		float aspect = h == 0 ? 1.0F : (float) w / (float) h;
		float tanX = tanY * aspect;

		RenderSystem.disableCull();
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableDepthTest();
		RenderSystem.depthMask(false);
		for (int i = 0; i < 6; i++) {
			RenderSystem.setShaderTexture(i, faces[i]);
		}
		RenderSystem.setShader(PortalShaders::loading);

		float[] f = PortalTransitionView.forward();
		float[] r = PortalTransitionView.right();
		float[] u = PortalTransitionView.up();
		setVec3(shader, "Forward", f);
		setVec3(shader, "Right", r);
		setVec3(shader, "Up", u);
		GlUniform tx = shader.getUniform("TanX");
		if (tx != null) {
			tx.set(tanX);
		}
		GlUniform ty = shader.getUniform("TanY");
		if (ty != null) {
			ty.set(tanY);
		}
		GlUniform alpha = shader.getUniform("Alpha");
		if (alpha != null) {
			// Dissolves the destination view into the real (already-loaded) world behind it once the
			// loading screen is done, instead of cutting away instantly (Phase 4.9). The swirl veil
			// drawn afterwards keeps its own opacity regardless.
			alpha.set(PortalTransitionView.backdropAlpha());
		}

		BufferBuilder buffer = Tessellator.getInstance()
				.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION);
		buffer.vertex(-1.0F, -1.0F, 0.0F);
		buffer.vertex(1.0F, -1.0F, 0.0F);
		buffer.vertex(1.0F, 1.0F, 0.0F);
		buffer.vertex(-1.0F, 1.0F, 0.0F);
		BuiltBuffer built = buffer.endNullable();
		if (built != null) {
			BufferRenderer.drawWithGlobalProgram(built);
		}

		// Restore state the GUI expects for the text pass that follows.
		RenderSystem.depthMask(true);
		RenderSystem.enableDepthTest();
		RenderSystem.enableCull();
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		return true;
	}

	private static void setVec3(ShaderProgram shader, String name, float[] v) {
		GlUniform uniform = shader.getUniform(name);
		if (uniform != null) {
			uniform.set(v[0], v[1], v[2]);
		}
	}

	/**
	 * The translucent animated portal swirl tiled full-screen — the loading-screen veil, and (via
	 * {@link PortalArrivalVeil}) the same swirl kept alive after the screen closes until the player
	 * steps clear of the arrival portal (Phase 4.9). {@code extraAlpha} layers on top of the veil's
	 * own opacity setting, e.g. for the arrival fade-out.
	 */
	static void drawSwirl(MinecraftClient client, float extraAlpha) {
		ShaderProgram shader = PortalShaders.swirl();
		if (shader == null) {
			return;
		}
		Sprite sprite = client.getBlockRenderManager().getModels()
				.getModelParticleSprite(Blocks.NETHER_PORTAL.getDefaultState());

		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableCull();
		RenderSystem.disableDepthTest();
		RenderSystem.depthMask(false);
		RenderSystem.setShaderTexture(0, sprite.getAtlasId());
		RenderSystem.setShader(PortalShaders::swirl);

		setVec2(shader, "SpriteMin", sprite.getMinU(), sprite.getMinV());
		setVec2(shader, "SpriteMax", sprite.getMaxU(), sprite.getMaxV());
		GlUniform alpha = shader.getUniform("Alpha");
		if (alpha != null) {
			// Match the in-world portal veil opacity for the view being shown. The loading backdrop
			// (and the arrival veil) show the DESTINATION, which is the dimension the client is now in,
			// so the veil tracks that dimension's setting (§4.3 / Phase-5 config).
			int veilAlpha = client.world != null
					? GlimpseSettings.veilAlphaForView(client.world.getRegistryKey().getValue())
					: GlimpseSettings.netherVeilAlpha;
			alpha.set(veilAlpha / 255.0F * extraAlpha);
		}

		BufferBuilder buffer = Tessellator.getInstance()
				.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION);
		buffer.vertex(-1.0F, -1.0F, 0.0F);
		buffer.vertex(1.0F, -1.0F, 0.0F);
		buffer.vertex(1.0F, 1.0F, 0.0F);
		buffer.vertex(-1.0F, 1.0F, 0.0F);
		BuiltBuffer built = buffer.endNullable();
		if (built != null) {
			BufferRenderer.drawWithGlobalProgram(built);
		}

		RenderSystem.depthMask(true);
		RenderSystem.enableDepthTest();
		RenderSystem.enableCull();
	}

	private static void setVec2(ShaderProgram shader, String name, float a, float b) {
		GlUniform uniform = shader.getUniform(name);
		if (uniform != null) {
			uniform.set(a, b);
		}
	}
}

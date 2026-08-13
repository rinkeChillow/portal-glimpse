package com.rinke.portalglimpse.render;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

/**
 * "Creeper!" across the middle of the screen when one breaks a glimpse capture.
 *
 * <p>Damage explains itself — you saw the hit and the health bar move. A creeper cancelling the hold from
 * eight blocks away, while it is still only walking toward you, does not: the screen just drops and nothing
 * says why. This is that missing sentence, and it doubles as a warning worth having.
 *
 * <p>Flashes green/white a few times, then fades. Timed on WALL CLOCK rather than ticks because the whole
 * point is that it appears while the game is paused behind a loading screen, where ticks are unreliable.
 */
public final class CreeperWarning {

	private static final long FLASH_MS = 130;   // one colour step
	private static final int FLASHES = 10;       // 5 green/white pairs
	private static final long FADE_MS = 900;
	private static final long FLASH_TOTAL = FLASH_MS * FLASHES;

	private static final int GREEN = 0x00C000;
	private static final int WHITE = 0xFFFFFF;
	private static final float SCALE = 3.0F;

	private static long shownAt;

	private CreeperWarning() {
	}

	public static void register() {
		HudRenderCallback.EVENT.register(CreeperWarning::render);
	}

	/** Show it. Ignored when the player has turned the warning off. */
	public static void trigger() {
		if (GlimpseSettings.creeperWarning) {
			shownAt = System.currentTimeMillis();
		}
	}

	private static void render(DrawContext context, RenderTickCounter tickCounter) {
		if (shownAt == 0) {
			return;
		}
		long elapsed = System.currentTimeMillis() - shownAt;
		if (elapsed > FLASH_TOTAL + FADE_MS) {
			shownAt = 0;
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) {
			return;
		}

		// Alternate while flashing, then hold green and fade out.
		boolean flashing = elapsed < FLASH_TOTAL;
		int colour = flashing && (elapsed / FLASH_MS) % 2 == 1 ? WHITE : GREEN;
		float alpha = flashing ? 1.0F
				: 1.0F - MathHelper.clamp((elapsed - FLASH_TOTAL) / (float) FADE_MS, 0.0F, 1.0F);
		int argb = ((int) (alpha * 255.0F) << 24) | colour;
		if ((argb >>> 24) == 0) {
			return;
		}

		Text text = Text.translatable("portal-glimpse.creeperWarning");
		int width = client.textRenderer.getWidth(text);
		context.getMatrices().push();
		// Scaled about the screen centre, so the text grows from the middle rather than the top-left.
		context.getMatrices().translate(client.getWindow().getScaledWidth() / 2.0,
				client.getWindow().getScaledHeight() / 2.0 - 40.0, 0.0);
		context.getMatrices().scale(SCALE, SCALE, 1.0F);
		context.drawTextWithShadow(client.textRenderer, text, -width / 2, 0, argb);
		context.getMatrices().pop();
	}
}

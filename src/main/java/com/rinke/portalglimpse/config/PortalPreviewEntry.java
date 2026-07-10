package com.rinke.portalglimpse.config;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.IntSupplier;

import com.mojang.blaze3d.systems.RenderSystem;

import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * A display-only Cloth Config entry that previews the veil at the live slider value: a portal-shaped
 * box showing a panorama (a random real capture, or the vanilla title-screen panorama as a fallback)
 * with the swirl painted over it at the current opacity — so you can see how the veil setting on the
 * slider directly below will look. Non-interactive (the slider owns the value); it just reads it.
 */
public final class PortalPreviewEntry extends AbstractConfigListEntry<Integer> {

	/** Vanilla nether-portal block texture: a 16×512 vertical strip of 32 animation frames. */
	private static final Identifier SWIRL = Identifier.ofVanilla("textures/block/nether_portal.png");
	private static final int SWIRL_FRAMES = 32;
	private static final int BOX_W = 62;   // ~2:3, reads like a portal opening
	private static final int BOX_H = 92;
	private static final int FRAME = 3;
	private static final int OBSIDIAN = 0xFF14141C;

	private final IntSupplier percent;      // 0..100, read live from the slider below
	private final Identifier panoramaFace;  // the backdrop chosen for this screen opening

	public PortalPreviewEntry(Text fieldName, IntSupplier percent, Identifier panoramaFace) {
		super(fieldName, false);
		this.percent = percent;
		this.panoramaFace = panoramaFace;
	}

	@Override
	public void render(DrawContext ctx, int index, int y, int x, int entryWidth, int entryHeight,
			int mouseX, int mouseY, boolean isHovered, float delta) {
		int bx = x + (entryWidth - BOX_W) / 2;
		int by = y + 2;

		// Obsidian frame around the opening so it reads as a portal.
		ctx.fill(bx - FRAME, by - FRAME, bx + BOX_W + FRAME, by + BOX_H + FRAME, OBSIDIAN);

		// Panorama backdrop — the whole face stretched into the opening (region == texture = full).
		ctx.drawTexture(panoramaFace, bx, by, BOX_W, BOX_H, 0.0F, 0.0F, 16, 16, 16, 16);

		// Swirl painted over it at the live opacity (one animated frame of the 16×512 strip).
		float alpha = Math.max(0, Math.min(100, percent.getAsInt())) / 100.0F;
		if (alpha > 0.0F) {
			int frame = (int) ((System.currentTimeMillis() / 50L) % SWIRL_FRAMES);
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
			RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
			ctx.drawTexture(SWIRL, bx, by, BOX_W, BOX_H, 0.0F, frame * 16.0F, 16, 16, 16, 512);
			RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
			RenderSystem.disableBlend();
		}
	}

	@Override
	public int getItemHeight() {
		return BOX_H + 8;
	}

	@Override
	public Integer getValue() {
		return percent.getAsInt();
	}

	@Override
	public Optional<Integer> getDefaultValue() {
		return Optional.empty();
	}

	@Override
	public List<? extends Element> children() {
		return Collections.emptyList();
	}

	@Override
	public List<? extends Selectable> narratables() {
		return Collections.emptyList();
	}
}

package com.rinke.portalglimpse.config;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.IntSupplier;

import com.mojang.blaze3d.systems.RenderSystem;

import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.Tooltip;
import me.shedaniel.math.Point;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * A Cloth Config entry that previews the veil at the live slider value. It draws the field name on
 * the left (vertically centred) and, centred over the slider directly below it, a portal like a real
 * one: a 5×5 obsidian frame around a 3×3 opening showing a panorama (dimension-correct: Nether views
 * for the Nether veil, Overworld views for the Overworld veil) with the swirl tiled 3×3 over it at
 * the current opacity. Clicking the portal opens a big, drag-to-look-around {@link PreviewScreen}.
 */
public final class PortalPreviewEntry extends AbstractConfigListEntry<Integer> {

	private static final Identifier OBSIDIAN = Identifier.ofVanilla("textures/block/obsidian.png");
	/** Vanilla nether-portal block texture: a 16×512 vertical strip of 32 animation frames. */
	private static final Identifier SWIRL = Identifier.ofVanilla("textures/block/nether_portal.png");
	private static final int SWIRL_FRAMES = 32;
	private static final int CELL = 18;              // one "block"; 5×5 frame, 3×3 opening
	private static final int TOP_PAD = 8;            // push the portal down, away from the setting above
	private static final int SLIDER_CENTER_FROM_RIGHT = 96; // centre the box over the slider below it

	private final IntSupplier percent;   // 0..100, read live from the slider below
	private final Identifier[] faces;     // horizontal panorama faces (faces[0] shown small)

	// Last-rendered box rectangle, for hit-testing clicks.
	private int boxX;
	private int boxY;
	private int boxSize;

	public PortalPreviewEntry(Text fieldName, IntSupplier percent, Identifier[] faces) {
		super(fieldName, false);
		this.percent = percent;
		this.faces = faces;
	}

	@Override
	public void render(DrawContext ctx, int index, int y, int x, int entryWidth, int entryHeight,
			int mouseX, int mouseY, boolean isHovered, float delta) {
		MinecraftClient mc = MinecraftClient.getInstance();
		int total = 5 * CELL;
		int bx = x + entryWidth - SLIDER_CENTER_FROM_RIGHT - total / 2; // centred over the slider
		int by = y + TOP_PAD;
		boxX = bx;
		boxY = by;
		boxSize = total;

		// Field name, vertically centred alongside the portal.
		int textY = y + (getItemHeight() - mc.textRenderer.fontHeight) / 2;
		ctx.drawTextWithShadow(mc.textRenderer, getFieldName(), x, textY, getPreferredTextColor());

		// 5×5 obsidian frame (tiled block texture; the opening is drawn over the centre 3×3).
		for (int r = 0; r < 5; r++) {
			for (int c = 0; c < 5; c++) {
				ctx.drawTexture(OBSIDIAN, bx + c * CELL, by + r * CELL, CELL, CELL, 0.0F, 0.0F, 16, 16, 16, 16);
			}
		}

		int ix = bx + CELL;
		int iy = by + CELL;
		int inner = 3 * CELL;

		// Panorama backdrop (forward face) across the whole 3×3 opening (region == texture = full).
		ctx.drawTexture(faces[0], ix, iy, inner, inner, 0.0F, 0.0F, 16, 16, 16, 16);

		// Swirl veil tiled 3×3 over the opening at the live opacity (one animated frame of the strip).
		float alpha = Math.max(0, Math.min(100, percent.getAsInt())) / 100.0F;
		if (alpha > 0.0F) {
			int frame = (int) ((System.currentTimeMillis() / 50L) % SWIRL_FRAMES);
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
			RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
			for (int r = 0; r < 3; r++) {
				for (int c = 0; c < 3; c++) {
					ctx.drawTexture(SWIRL, ix + c * CELL, iy + r * CELL, CELL, CELL,
							0.0F, frame * 16.0F, 16, 16, 16, 512);
				}
			}
			RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
			RenderSystem.disableBlend();
		}

		// Hover hint (Cloth draws it deferred, so it isn't clipped by the list).
		if (mouseX >= bx && mouseX <= bx + total && mouseY >= by && mouseY <= by + total) {
			getConfigScreen().addTooltip(Tooltip.of(new Point(mouseX, mouseY),
					Text.translatable("portal-glimpse.preview.clickForBigger")));
		}
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (mouseX >= boxX && mouseX <= boxX + boxSize && mouseY >= boxY && mouseY <= boxY + boxSize) {
			PreviewOverlay.open(faces, percent.getAsInt()); // draws over this config screen, no blur
			return true;
		}
		return false;
	}

	@Override
	public int getItemHeight() {
		return 5 * CELL + TOP_PAD + 4;
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

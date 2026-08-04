package com.rinke.portalglimpse.config;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * A live label plus a button, on one config row — the disk-usage readout and its clean-up shortcut.
 *
 * <p>Cloth Config has no button entry (every builder entry edits a value), and its text entries bake their
 * {@code Text} at build time. That second part is why the usage line sat on "measuring…" forever: the figure
 * is measured off-thread, and by the time it arrived there was nothing left holding a reference that would
 * re-read it. The label here is a {@link Supplier} evaluated every frame instead, so it fills in the moment
 * the scan lands and keeps up afterwards.
 *
 * <p>Label and button share a row deliberately — as two entries the button's row had no name at all, which
 * read as an unlabelled gap in the list.
 *
 * <p>Holds no value: the getters return a constant so the list never marks the screen as modified.
 */
public final class CleanupButtonEntry extends AbstractConfigListEntry<Boolean> {

	/** Cloth lays every entry out the same way: name on the left, control at {@code x + entryWidth - 150},
	 * and the control sized {@code 150 - resetWidth - 2} so the Reset button fits beside it. Reproducing that
	 * formula — rather than guessing an offset — is what makes this row line up with the sliders and keybind
	 * buttons at ANY window width, including the narrow layout where Cloth drops the name. */
	private static final int CLOTH_CONTROL_SPAN = 150;

	/** Cloth sizes its Reset button to its own label plus padding; matched here so the maths agrees. */
	private static int resetWidth(MinecraftClient client) {
		int w = client.textRenderer.getWidth(Text.translatable("text.cloth-config.reset_value")) + 6;
		return w > 0 ? w : 36;
	}

	private final ButtonWidget button;
	private final Supplier<Text> label;

	public CleanupButtonEntry(Supplier<Text> label, Text buttonText, Runnable onPress) {
		super(Text.empty(), false);
		this.label = label;
		this.button = ButtonWidget.builder(buttonText, b -> onPress.run()).dimensions(0, 0, 100, 20).build();
	}

	@Override
	public void render(DrawContext ctx, int index, int y, int x, int entryWidth, int entryHeight,
			int mouseX, int mouseY, boolean isHovered, float delta) {
		super.render(ctx, index, y, x, entryWidth, entryHeight, mouseX, mouseY, isHovered, delta);
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) {
			return;
		}
		// Label on the left where every other entry's name sits. Re-read every frame, which is what makes the
		// measured size appear once the off-thread scan lands.
		ctx.drawTextWithShadow(client.textRenderer, label.get(), x, y + 6, 0xFFFFFF);
		// Control in Cloth's own column, sized by Cloth's own rule.
		button.setX(x + entryWidth - CLOTH_CONTROL_SPAN);
		button.setWidth(CLOTH_CONTROL_SPAN - resetWidth(client) - 2);
		button.setY(y);
		button.render(ctx, mouseX, mouseY, delta);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int mouseButton) {
		return button.mouseClicked(mouseX, mouseY, mouseButton);
	}

	@Override
	public int getItemHeight() {
		return 24; // Cloth's standard row height
	}

	@Override
	public Boolean getValue() {
		return false;
	}

	@Override
	public Optional<Boolean> getDefaultValue() {
		return Optional.of(false);
	}

	@Override
	public boolean isEdited() {
		return false;
	}

	@Override
	public void save() {
	}

	@Override
	public List<? extends Element> children() {
		return List.of(button);
	}

	@Override
	public List<? extends Selectable> narratables() {
		return List.of(button);
	}

	/** Convenience for the config screen: open the clean-up list, returning to the current screen after. */
	public static void openCleanup() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client != null) {
			client.setScreen(new StorageCleanupScreen(client.currentScreen));
		}
	}
}

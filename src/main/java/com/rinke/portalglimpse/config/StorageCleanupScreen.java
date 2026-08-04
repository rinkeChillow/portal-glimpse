package com.rinke.portalglimpse.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.rinke.portalglimpse.data.GlimpseStorage;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;

/**
 * Pick which worlds' and servers' captured glimpses to delete.
 *
 * <p>Deleting is irreversible and the files are the whole point of the mod, so the button will not do it on
 * one click: it arms first and says what it is about to remove, and only a second click goes through. Anyone
 * who reaches for it by accident gets a sentence telling them exactly what they are about to lose.
 */
public class StorageCleanupScreen extends Screen {

	private final Screen parent;
	private final Set<GlimpseStorage.Location> selected = new HashSet<>();
	private List<GlimpseStorage.Location> locations = new ArrayList<>();
	private LocationList list;
	private ButtonWidget cleanButton;
	/** Second click confirms. Reset whenever the selection changes, so it can't carry over to a new target. */
	private boolean armed;
	private boolean scanning = true;

	public StorageCleanupScreen(Screen parent) {
		super(Text.translatable("portal-glimpse.cleanup.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		if (scanning) {
			// Walking every save folder can take a moment on a big install — keep it off the render thread.
			Util.getIoWorkerExecutor().execute(() -> {
				List<GlimpseStorage.Location> found = GlimpseStorage.scan();
				if (this.client != null) {
					this.client.execute(() -> {
						this.locations = found;
						this.scanning = false;
						this.clearAndInit();
					});
				}
			});
		}

		list = new LocationList();
		addDrawableChild(list);

		cleanButton = ButtonWidget.builder(Text.translatable("portal-glimpse.cleanup.delete"), b -> onClean())
				.dimensions(this.width / 2 - 154, this.height - 32, 150, 20).build();
		addDrawableChild(cleanButton);
		addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), b -> close())
				.dimensions(this.width / 2 + 4, this.height - 32, 150, 20).build());
		updateCleanButton();
	}

	private void onClean() {
		if (selected.isEmpty()) {
			return;
		}
		if (!armed) {
			armed = true;
			updateCleanButton();
			return;
		}
		for (GlimpseStorage.Location location : selected) {
			GlimpseStorage.delete(location);
		}
		selected.clear();
		armed = false;
		scanning = true; // re-measure so the numbers reflect what is actually left
		clearAndInit();
	}

	private void toggle(GlimpseStorage.Location location) {
		if (!selected.remove(location)) {
			selected.add(location);
		}
		armed = false; // selection changed — make them confirm against the new set
		updateCleanButton();
	}

	private void updateCleanButton() {
		if (cleanButton == null) {
			return;
		}
		long bytes = GlimpseStorage.totalBytes(new ArrayList<>(selected));
		cleanButton.active = !selected.isEmpty();
		cleanButton.setMessage(armed
				? Text.translatable("portal-glimpse.cleanup.confirm", GlimpseStorage.format(bytes))
						.formatted(Formatting.RED)
				: Text.translatable("portal-glimpse.cleanup.delete"));
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 14, 0xFFFFFF);
		Text subtitle = scanning
				? Text.translatable("portal-glimpse.cleanup.scanning")
				: Text.translatable("portal-glimpse.cleanup.total",
						GlimpseStorage.format(GlimpseStorage.totalBytes(locations)), locations.size());
		context.drawCenteredTextWithShadow(this.textRenderer, subtitle.copy().formatted(Formatting.GRAY),
				this.width / 2, 28, 0xFFFFFF);
		if (armed) {
			context.drawCenteredTextWithShadow(this.textRenderer,
					Text.translatable("portal-glimpse.cleanup.warning").formatted(Formatting.RED),
					this.width / 2, this.height - 46, 0xFFFFFF);
		}
	}

	@Override
	public void close() {
		if (this.client != null) {
			this.client.setScreen(parent);
		}
	}

	private class LocationList extends AlwaysSelectedEntryListWidget<LocationList.Row> {

		LocationList() {
			super(StorageCleanupScreen.this.client, StorageCleanupScreen.this.width,
					StorageCleanupScreen.this.height - 96, 44, 24);
			for (GlimpseStorage.Location location : locations) {
				addEntry(new Row(location));
			}
		}

		@Override
		public int getRowWidth() {
			return 300;
		}

		private class Row extends AlwaysSelectedEntryListWidget.Entry<Row> {

			private final GlimpseStorage.Location location;

			Row(GlimpseStorage.Location location) {
				this.location = location;
			}

			@Override
			public Text getNarration() {
				return Text.literal(location.label());
			}

			@Override
			public boolean mouseClicked(double mouseX, double mouseY, int button) {
				toggle(location);
				return true;
			}

			@Override
			public void render(DrawContext context, int index, int y, int x, int width, int height,
					int mouseX, int mouseY, boolean hovered, float delta) {
				boolean picked = selected.contains(location);
				Text name = Text.literal((picked ? "✔ " : "  ") + location.label())
						.formatted(picked ? Formatting.RED : Formatting.WHITE);
				context.drawTextWithShadow(StorageCleanupScreen.this.textRenderer, name, x + 4, y + 6, 0xFFFFFF);
				Text size = Text.translatable("portal-glimpse.cleanup.entry",
						GlimpseStorage.format(location.bytes()), location.portals())
						.formatted(Formatting.GRAY);
				context.drawTextWithShadow(StorageCleanupScreen.this.textRenderer, size,
						x + width - StorageCleanupScreen.this.textRenderer.getWidth(size) - 4, y + 6, 0xFFFFFF);
			}
		}
	}
}

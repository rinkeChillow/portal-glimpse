package com.rinke.portalglimpse.config;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.systems.RenderSystem;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * The maximized portal preview, drawn as an OVERLAY on top of the still-rendering config screen
 * (rather than a standalone Screen, which inherited the menu blur). Opened by clicking the small
 * {@link PortalPreviewEntry}; it hooks the config screen's Fabric render/mouse/keyboard events to
 * draw over it and capture input. Same 5×5 obsidian / 3×3 swirl tiling as the small preview, big and
 * centred, with a drag-to-look-around panorama strip and gentle auto-drift. Click outside the
 * obsidian (or press Esc) to close.
 */
public final class PreviewOverlay {

	private static final Identifier OBSIDIAN = Identifier.ofVanilla("textures/block/obsidian.png");
	private static final Identifier SWIRL = Identifier.ofVanilla("textures/block/nether_portal.png");
	private static final int SWIRL_FRAMES = 32;
	private static final float DRIFT_PER_MS = 0.015F;

	private static boolean active;
	private static Screen expectedScreen; // our config screen, to attach per-screen hooks once it inits
	private static Identifier[] faces = new Identifier[] { OBSIDIAN };
	private static float alpha;
	private static float panX;
	private static long lastDriftMillis;
	private static boolean dragging;
	private static double lastMouseX;

	// Last-rendered geometry, for click hit-testing between frames.
	private static int cell;
	private static int total;
	private static int inner;
	private static int bx;
	private static int by;
	private static int ix;
	private static int iy;

	private PreviewOverlay() {
	}

	/** Whether the maximized overlay is currently shown (read by the Cloth tooltip-suppression mixin). */
	public static boolean isActive() {
		return active;
	}

	/** Open the overlay for a face set at the given veil percent (0..100). */
	public static void open(Identifier[] facesIn, int percent) {
		faces = facesIn;
		alpha = Math.max(0, Math.min(100, percent)) / 100.0F;
		panX = 0.0F;
		lastDriftMillis = 0L;
		dragging = false;
		active = true;
	}

	private static void close() {
		active = false;
		dragging = false;
	}

	/**
	 * Register the one global init hook (call once at client init). The per-screen render/mouse events
	 * can only be attached to an already-initialised screen, so we wait for our config screen's
	 * AFTER_INIT (which also re-fires on resize) rather than attaching in {@code create()}.
	 */
	public static void registerGlobal() {
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (screen == expectedScreen) {
				attach(screen);
			}
		});
	}

	/** Mark the screen we're about to open as ours, so the init hook attaches to it. Starts closed. */
	public static void expect(Screen screen) {
		expectedScreen = screen;
		active = false;
	}

	private static void attach(Screen screen) {
		ScreenEvents.afterRender(screen).register(PreviewOverlay::render);
		ScreenMouseEvents.allowMouseClick(screen).register((s, mx, my, button) -> onClick(mx, my));
		ScreenMouseEvents.allowMouseScroll(screen).register((s, mx, my, hAmount, vAmount) -> !active);
		ScreenKeyboardEvents.allowKeyPress(screen).register((s, key, scancode, modifiers) -> {
			if (active && key == GLFW.GLFW_KEY_ESCAPE) {
				close();
				return false; // consume Esc so it closes the overlay, not the whole config screen
			}
			return true;
		});
	}

	private static boolean onClick(double mx, double my) {
		if (!active) {
			return true; // let the config screen (and the small preview) handle it — may open the overlay
		}
		if (mx < bx || mx > bx + total || my < by || my > by + total) {
			close(); // clicked outside the obsidian → minimize
		} else {
			dragging = true;
			lastMouseX = mx;
		}
		return false; // consume all clicks while the overlay is up
	}

	private static void render(Screen screen, DrawContext ctx, int mouseX, int mouseY, float delta) {
		if (!active) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		int w = screen.width;
		int h = screen.height;
		cell = Math.max(16, Math.min(w, h) / 6);
		total = 5 * cell;
		inner = 3 * cell;
		bx = (w - total) / 2;
		by = (h - total) / 2;
		ix = bx + cell;
		iy = by + cell;

		// Dim the config screen behind (no blur — just a translucent fill drawn on top of it).
		ctx.fill(0, 0, w, h, 0xC0000000);

		// Drag-to-look-around (polled left button) + gentle auto-drift.
		boolean down = GLFW.glfwGetMouseButton(client.getWindow().getHandle(),
				GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
		if (!down) {
			dragging = false;
		} else if (dragging) {
			panX -= (float) (mouseX - lastMouseX);
			lastMouseX = mouseX;
			markHintSeen();
		}
		long now = System.currentTimeMillis();
		if (lastDriftMillis != 0L) {
			panX += (now - lastDriftMillis) * DRIFT_PER_MS;
		}
		lastDriftMillis = now;

		// 5×5 obsidian frame.
		for (int r = 0; r < 5; r++) {
			for (int c = 0; c < 5; c++) {
				ctx.drawTexture(OBSIDIAN, bx + c * cell, by + r * cell, cell, cell, 0.0F, 0.0F, 16, 16, 16, 16);
			}
		}

		// Panorama horizontal strip in the opening, clipped and pannable (wraps around).
		ctx.enableScissor(ix, iy, ix + inner, iy + inner);
		int n = faces.length;
		int stripW = n * inner;
		float off = ((panX % stripW) + stripW) % stripW;
		for (int k = -1; k <= n; k++) {
			int fx = ix + k * inner - (int) off;
			Identifier face = faces[((k % n) + n) % n];
			ctx.drawTexture(face, fx, iy, inner, inner, 0.0F, 0.0F, 16, 16, 16, 16);
		}
		ctx.disableScissor();

		// Swirl veil tiled 3×3 over the opening at the captured opacity.
		if (alpha > 0.0F) {
			int frame = (int) ((now / 50L) % SWIRL_FRAMES);
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
			RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
			for (int r = 0; r < 3; r++) {
				for (int c = 0; c < 3; c++) {
					ctx.drawTexture(SWIRL, ix + c * cell, iy + r * cell, cell, cell,
							0.0F, frame * 16.0F, 16, 16, 16, 512);
				}
			}
			RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
			RenderSystem.disableBlend();
		}

		// One-time hint over the opening; dismissed permanently on first drag.
		if (!GlimpseConfig.get().previewHintSeen
				&& mouseX >= ix && mouseX <= ix + inner && mouseY >= iy && mouseY <= iy + inner) {
			ctx.drawTooltip(client.textRenderer, Text.translatable("portal-glimpse.preview.dragHint"), mouseX, mouseY);
		}
	}

	private static void markHintSeen() {
		if (!GlimpseConfig.get().previewHintSeen) {
			GlimpseConfig.get().previewHintSeen = true;
			GlimpseConfig.get().save();
		}
	}
}

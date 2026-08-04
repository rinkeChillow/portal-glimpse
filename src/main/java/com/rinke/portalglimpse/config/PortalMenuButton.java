package com.rinke.portalglimpse.config;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.texture.Sprite;
import com.rinke.portalglimpse.render.GlimpseSettings;

import org.lwjgl.glfw.GLFW;

import net.minecraft.text.Text;

/**
 * A little nether portal in the pause menu that opens Portal Glimpse's settings.
 *
 * <p>Built out of the game's own block sprites rather than a bespoke GUI texture: an obsidian frame around a
 * live {@code nether_portal} interior. The interior sprite is the real animated one off the block atlas, so
 * the swirl churns exactly as it does in world and follows any resource pack the player has on — a drawn
 * icon would go stale the moment they changed packs.
 *
 * <p>Proportions are a vanilla portal's: 4 cells wide by 5 tall, leaving the 2x3 opening you get from the
 * minimum build.
 *
 * <p>There is NO button background: the portal itself is the button. Hovering or focusing it draws the
 * white outline a focused vanilla widget gets, and nothing else, so it announces itself as pressable
 * without a grey slab appearing behind it. Drawing {@code widget/button} under the portal makes it look
 * like a button someone parked a portal on top of — don't.
 */
public class PortalMenuButton extends ClickableWidget {

	/** Pixels per block cell — the ONLY size knob, and it must stay a whole number.
	 *
	 * <p>Every cell has to be identical or the portal stops looking like one: interpolating cell edges across
	 * a target width to hit a percentage exactly leaves some cells 3px and others 4px, so the frame blocks
	 * and the veil cells come out different sizes and the whole thing sits off-centre. Uniform cells mean the
	 * size steps in whole pixels — 3 -> 4 is the next size up. */
	private static final int CELL = 4;

	/** The highlight outline, in the same white vanilla uses for a focused widget. */
	private static final int OUTLINE = 0xFFFFFFFF;

	private static final int FRAME_W = 4 * CELL;
	private static final int FRAME_H = 5 * CELL;
	/** One pixel of padding all round, so the hover outline sits just outside the obsidian rather than
	 * cutting into it. */
	public static final int WIDGET_W = FRAME_W + 2;
	public static final int WIDGET_H = FRAME_H + 2;

	private final Screen parent;
	/** Anchor position — where the button sits with a zero offset, i.e. beside Advancements. Dragging is
	 * stored relative to this, so the button keeps its place when the menu grid moves. */
	private final int baseX;
	private final int baseY;
	private boolean dragging;
	private int grabDx;
	private int grabDy;
	private boolean rightWasDown;

	public PortalMenuButton(int x, int y, Screen parent) {
		super(x + GlimpseSettings.menuButtonOffsetX, y + GlimpseSettings.menuButtonOffsetY,
				WIDGET_W, WIDGET_H, Text.translatable("portal-glimpse.menu.button"));
		this.parent = parent;
		this.baseX = x;
		this.baseY = y;
	}

	/**
	 * Right-hold to drag the button somewhere else; left-click still opens the settings.
	 *
	 * <p>Polls the mouse button straight from GLFW rather than using {@code mouseDragged}, because vanilla's
	 * {@code ParentElement.mouseDragged} only forwards drags for button 0 — a right-drag never reaches a child
	 * widget at all, so there is nothing to override.
	 */
	private void updateDrag(int mouseX, int mouseY) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.getWindow() == null) {
			return;
		}
		boolean down = GLFW.glfwGetMouseButton(client.getWindow().getHandle(),
				GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;

		if (down && !rightWasDown && isMouseOver(mouseX, mouseY)) {
			dragging = true;
			grabDx = mouseX - getX();
			grabDy = mouseY - getY();
		} else if (!down && dragging) {
			dragging = false;
			GlimpseConfig config = GlimpseConfig.get();
			config.menuButtonOffsetX = getX() - baseX;
			config.menuButtonOffsetY = getY() - baseY;
			config.save();
		}
		rightWasDown = down;

		if (dragging) {
			// Clamped to the screen so it can't be dragged off and lost with no way to get it back.
			int maxX = client.getWindow().getScaledWidth() - getWidth();
			int maxY = client.getWindow().getScaledHeight() - getHeight();
			setX(Math.max(0, Math.min(maxX, mouseX - grabDx)));
			setY(Math.max(0, Math.min(maxY, mouseY - grabDy)));
		}
	}

	@Override
	public void onClick(double mouseX, double mouseY) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client != null) {
			client.setScreen(GlimpseConfigScreen.create(parent));
		}
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) {
			return;
		}
		Sprite obsidian = client.getBlockRenderManager().getModels()
				.getModelParticleSprite(Blocks.OBSIDIAN.getDefaultState());
		Sprite portal = client.getBlockRenderManager().getModels()
				.getModelParticleSprite(Blocks.NETHER_PORTAL.getDefaultState());

		updateDrag(mouseX, mouseY);
		boolean lit = isHovered() || isFocused() || dragging;

		// No button background — the portal IS the button. Hovering just outlines it, the way a focused
		// vanilla widget is outlined, so it announces itself as pressable without a grey slab appearing
		// behind it.
		int originX = getX() + (getWidth() - FRAME_W) / 2;
		int originY = getY() + (getHeight() - FRAME_H) / 2;
		float glow = lit ? 1.0F : 0.72F;

		for (int col = 0; col < 4; col++) {
			for (int row = 0; row < 5; row++) {
				int px = originX + col * CELL;
				int py = originY + row * CELL;
				// The opening is the inner 2x3 — every other cell is frame, including the corners, which a
				// real portal doesn't need but which make the silhouette read as a portal at this size.
				// Every cell is CELL x CELL, frame and veil alike, so they line up exactly.
				boolean inside = col >= 1 && col <= 2 && row >= 1 && row <= 3;
				if (inside) {
					context.drawSprite(px, py, 0, CELL, CELL, portal, glow, glow, glow, 1.0F);
				} else {
					context.drawSprite(px, py, 0, CELL, CELL, obsidian,
							lit ? 1.0F : 0.85F, lit ? 1.0F : 0.85F, lit ? 1.0F : 0.85F, 1.0F);
				}
			}
		}

		if (lit) {
			context.drawBorder(getX(), getY(), getWidth(), getHeight(), OUTLINE);
		}
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		appendDefaultNarrations(builder);
	}
}

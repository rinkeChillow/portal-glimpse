package com.rinke.portalglimpse.mixin;

import com.rinke.portalglimpse.config.PortalMenuButton;
import com.rinke.portalglimpse.render.GlimpseSettings;

import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts the Portal Glimpse portal-button in the top-left of the in-game pause menu.
 *
 * <p>The pause menu rather than the title screen because that is where the settings are actually wanted:
 * you notice something about a portal while playing, hit Escape, and change it — without a round trip out
 * to the main menu. It also sits beside Options, which is where a player already looks for settings.
 *
 * <p>At the TAIL of {@code initWidgets}, so vanilla's grid is laid out first and ours can be positioned
 * against it — the button is anchored beside Advancements, found by message rather than by coordinates.
 *
 * <p>{@code require = 0}: cosmetic entry point. If a mapping change or another mod's pause screen takes
 * this method away, the button quietly doesn't appear and the game is fine — the settings stay reachable
 * through Mod Menu either way.
 */
@Mixin(GameMenuScreen.class)
public abstract class GameMenuScreenMixin extends Screen {

	protected GameMenuScreenMixin() {
		super(null);
	}

	@Inject(method = "initWidgets", at = @At("TAIL"), require = 0)
	private void portalglimpse$addPortalButton(CallbackInfo ci) {
		if (!GlimpseSettings.menuButton) {
			return;
		}
		// Sit next to Advancements rather than at fixed coordinates. The pause menu's grid moves with window
		// size and with whatever else is present (server links, Open to LAN, other mods' entries), so anything
		// hardcoded drifts out of place; finding the button and anchoring to it survives all of that.
		String advancements = Text.translatable("gui.advancements").getString();
		ClickableWidget anchor = null;
		for (Element child : this.children()) {
			if (child instanceof ClickableWidget w && w.getMessage().getString().equals(advancements)) {
				anchor = w;
				break;
			}
		}
		int x;
		int y;
		if (anchor != null) {
			// Just left of it, with the two vertically centred on each other.
			x = anchor.getX() - PortalMenuButton.WIDGET_W - 6;
			y = anchor.getY() + (anchor.getHeight() - PortalMenuButton.WIDGET_H) / 2;
		} else {
			x = 8; // Advancements missing (renamed, or a mod replaced the screen) — fall back to the corner.
			y = 8;
		}
		addDrawableChild(new PortalMenuButton(x, y, (GameMenuScreen) (Object) this));
	}
}

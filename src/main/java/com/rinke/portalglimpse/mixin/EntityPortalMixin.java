package com.rinke.portalglimpse.mixin;

import com.rinke.portalglimpse.render.GlimpseSettings;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * DEBUG hook for the Numpad-0 toggle ({@link GlimpseSettings#debugBlockPortalTravel}): while it is
 * on, a player standing in a nether portal reports it can't use portals. {@code canUsePortals} gates
 * {@code Entity.tryUsePortal}, which is what arms the {@link net.minecraft.world.dimension.PortalManager}
 * — so blocking it here suppresses BOTH the dimension teleport (server side) and the portal nausea /
 * screen wobble (client side, which reads that same portal state), letting the in-portal glimpse be
 * inspected without being carried off. Only players are affected; mobs and items still travel.
 *
 * <p>Common target class ({@code Entity}); in this client-only mod both logical sides run in the one
 * client process, so the single transform covers the integrated server too.
 */
@Mixin(Entity.class)
public abstract class EntityPortalMixin {

	@Inject(method = "canUsePortals(Z)Z", at = @At("HEAD"), cancellable = true)
	private void portalglimpse$blockPortalTravel(boolean allowVehicles, CallbackInfoReturnable<Boolean> cir) {
		if (GlimpseSettings.debugBlockPortalTravel && (Object) this instanceof PlayerEntity) {
			cir.setReturnValue(false);
		}
	}
}

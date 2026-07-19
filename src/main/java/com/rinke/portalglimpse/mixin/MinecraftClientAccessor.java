package com.rinke.portalglimpse.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets the capture pipeline temporarily point {@code MinecraftClient.getFramebuffer()} at its own
 * offscreen target. Iris renders the world into its private gbuffers and composites the final image to
 * the client's MAIN framebuffer — it ignores whatever framebuffer we merely bind — so a panorama capture
 * came back blank (only the sky, drawn before Iris's composite). Swapping the field for the shot makes
 * Iris (and everything else) composite into the buffer we read, then we restore it.
 *
 * <p>The {@code framebuffer} field is {@code final}, so {@link Mutable} is required to strip that and let
 * the accessor write it (otherwise the JVM throws {@code IllegalAccessError}).
 */
@Mixin(MinecraftClient.class)
public interface MinecraftClientAccessor {

	@Mutable
	@Accessor("framebuffer")
	void portalglimpse$setFramebuffer(Framebuffer framebuffer);
}

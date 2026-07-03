package com.rinke.portalglimpse.mixin;

import com.rinke.portalglimpse.capture.CameraOverride;

import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.BlockView;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies the capture camera override (postcard/panorama shots from a position that isn't the
 * player). Injecting at TAIL of {@code update} means everything downstream — view matrix, frustum,
 * fog — sees the overridden camera, while the player entity is untouched.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

	@Shadow
	protected abstract void setPos(double x, double y, double z);

	@Shadow
	protected abstract void setRotation(float yaw, float pitch);

	@Inject(method = "update", at = @At("TAIL"))
	private void portalglimpse$applyCaptureOverride(BlockView area, Entity focusedEntity, boolean thirdPerson,
			boolean inverseView, float tickDelta, CallbackInfo ci) {
		if (CameraOverride.isActive()) {
			setRotation(CameraOverride.yaw(), CameraOverride.pitch());
			setPos(CameraOverride.x(), CameraOverride.y(), CameraOverride.z());
		}
	}
}

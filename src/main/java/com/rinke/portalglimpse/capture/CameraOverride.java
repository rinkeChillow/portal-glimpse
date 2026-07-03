package com.rinke.portalglimpse.capture;

import net.minecraft.util.math.Vec3d;

/**
 * Detached-camera state for captures: while active, {@code CameraMixin} forces the game camera to
 * this position/rotation at the end of every {@code Camera.update}. The player entity is never
 * moved or rotated — the hop is purely visual and lasts only for the captured frames.
 */
public final class CameraOverride {

	private static volatile boolean active;
	private static double x;
	private static double y;
	private static double z;
	private static float yaw;
	private static float pitch;

	private CameraOverride() {
	}

	public static void set(Vec3d pos, float yawDeg, float pitchDeg) {
		x = pos.x;
		y = pos.y;
		z = pos.z;
		yaw = yawDeg;
		pitch = pitchDeg;
		active = true;
	}

	public static void clear() {
		active = false;
	}

	public static boolean isActive() {
		return active;
	}

	public static double x() {
		return x;
	}

	public static double y() {
		return y;
	}

	public static double z() {
		return z;
	}

	public static float yaw() {
		return yaw;
	}

	public static float pitch() {
		return pitch;
	}
}

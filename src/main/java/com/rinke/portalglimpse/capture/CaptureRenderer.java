package com.rinke.portalglimpse.capture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.rinke.portalglimpse.PortalGlimpse;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.client.util.Window;
import net.minecraft.util.Util;
import net.minecraft.util.math.Vec3d;

/**
 * Renders a list of {@link Shot}s (arbitrary camera position/rotation) into PNGs at exact paths.
 *
 * <p>Same framebuffer dance as vanilla {@code MinecraftClient.takePanorama} — offscreen
 * {@link SimpleFramebuffer}, panorama mode (90° FOV, no hand), window framebuffer size swapped to
 * the capture resolution — but the camera is placed via {@link CameraOverride} instead of rotating
 * the player, and images are written with {@link NativeImage#writeTo} directly (vanilla's
 * {@code saveScreenshot} would force a {@code screenshots/} subfolder). Pixel readback is
 * synchronous; PNG encoding/writing happens on the IO worker pool.
 */
public final class CaptureRenderer {

	/** One capture: camera at {@code pos} looking ({@code yaw}, {@code pitch}), saved as {@code fileName}. */
	public record Shot(Vec3d pos, float yaw, float pitch, String fileName) {
	}

	private CaptureRenderer() {
	}

	public static void capture(MinecraftClient client, Path dir, int resolution, List<Shot> shots)
			throws IOException {
		Files.createDirectories(dir);

		Window window = client.getWindow();
		int prevWidth = window.getFramebufferWidth();
		int prevHeight = window.getFramebufferHeight();
		SimpleFramebuffer framebuffer =
				new SimpleFramebuffer(resolution, resolution, true, MinecraftClient.IS_SYSTEM_MAC);

		client.gameRenderer.setBlockOutlineEnabled(false);
		client.gameRenderer.setRenderingPanorama(true);
		window.setFramebufferWidth(resolution);
		window.setFramebufferHeight(resolution);
		client.worldRenderer.reloadTransparencyPostProcessor();

		try {
			for (Shot shot : shots) {
				CameraOverride.set(shot.pos(), shot.yaw(), shot.pitch());
				framebuffer.beginWrite(true);
				client.gameRenderer.renderWorld(RenderTickCounter.ONE);

				NativeImage image = ScreenshotRecorder.takeScreenshot(framebuffer);
				Path file = dir.resolve(shot.fileName());
				Util.getIoWorkerExecutor().execute(() -> {
					try {
						image.writeTo(file);
					} catch (IOException e) {
						PortalGlimpse.LOGGER.warn("Portal Glimpse: failed to write {}", file, e);
					} finally {
						image.close();
					}
				});
			}
		} finally {
			CameraOverride.clear();
			framebuffer.delete();
			client.gameRenderer.setRenderingPanorama(false);
			client.gameRenderer.setBlockOutlineEnabled(true);
			window.setFramebufferWidth(prevWidth);
			window.setFramebufferHeight(prevHeight);
			client.worldRenderer.reloadTransparencyPostProcessor();
			client.getFramebuffer().beginWrite(true);
		}
	}
}

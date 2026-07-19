package com.rinke.portalglimpse.capture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.rinke.portalglimpse.PortalGlimpse;
import com.rinke.portalglimpse.mixin.MinecraftClientAccessor;
import com.rinke.portalglimpse.render.IrisCompat;
import com.rinke.portalglimpse.render.SodiumCompat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
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

	/** Panorama faces are 90° FOV. Under a shaderpack the composite bakes a screen-edge VIGNETTE into each
	 * face, so the six faces show dark SEAMS when stitched into the cubemap. To hide it we capture each face
	 * at this WIDER FOV and keep only the central 90° (see {@link #shaderCaptureFov}) — the vignetted rim is
	 * cropped away, and the kept image is framed exactly like a plain 90° shot (no zoom). */
	private static final float CAPTURE_FOV_SHADER = 110.0F;

	/** Read by {@code GameRendererMixin}: when &gt; 0 it overrides the panorama FOV for our capture render.
	 * Set only for the duration of a shader capture; 0 otherwise (vanilla capture is untouched). */
	public static volatile float shaderCaptureFov = 0.0F;

	/** Shaders only: after wiping the target, re-render each face this many extra times so any INTERNAL
	 * temporal buffers the pack keeps (that a framebuffer clear can't reach) also settle onto this face
	 * before we grab the pixels. The clear is the primary fix; this is a hedge. */
	private static final int SHADER_SETTLE_RENDERS = 8;

	private CaptureRenderer() {
	}

	/** Fraction of the wide-FOV framebuffer, per axis, that the central 90° occupies (tan ratio). */
	private static float cropFraction() {
		return (float) (Math.tan(Math.toRadians(45.0)) / Math.tan(Math.toRadians(CAPTURE_FOV_SHADER / 2.0)));
	}

	public static void capture(MinecraftClient client, Path dir, int resolution, List<Shot> shots)
			throws IOException {
		Files.createDirectories(dir);

		Window window = client.getWindow();
		int prevWidth = window.getFramebufferWidth();
		int prevHeight = window.getFramebufferHeight();
		// Under a shaderpack, render each face at a WIDER FOV into a proportionally larger buffer, then crop
		// the central `resolution` (the true 90° view) so the pack's edge vignette is discarded, not stitched
		// into cubemap seams. Vanilla (no shaders) captures a plain 90° face as before.
		boolean wide = IrisCompat.shadersActive();
		int captureRes = wide ? Math.round(resolution / cropFraction()) : resolution;
		SimpleFramebuffer framebuffer =
				new SimpleFramebuffer(captureRes, captureRes, true, MinecraftClient.IS_SYSTEM_MAC);

		client.gameRenderer.setBlockOutlineEnabled(false);
		client.gameRenderer.setRenderingPanorama(true);
		shaderCaptureFov = wide ? CAPTURE_FOV_SHADER : 0.0F;
		window.setFramebufferWidth(captureRes);
		window.setFramebufferHeight(captureRes);
		client.worldRenderer.reloadTransparencyPostProcessor();

		// Point the client's MAIN framebuffer at our capture target for the shots: Iris composites the
		// shaded world into MinecraftClient.getFramebuffer() (not whatever we merely bind), so without this
		// the offscreen capture is blank under a shaderpack. Restored in finally. (MinecraftClient is final,
		// so cast through Object to reach the mixin accessor.)
		Framebuffer realFramebuffer = client.getFramebuffer();
		((MinecraftClientAccessor) (Object) client).portalglimpse$setFramebuffer(framebuffer);

		try {
			for (Shot shot : shots) {
				CameraOverride.set(shot.pos(), shot.yaw(), shot.pitch());
				// Sodium only rebuilds its chunk render list when the camera position/projection changes,
				// but every panorama face shares those (it rotates in place) — so force a rebuild per shot,
				// else all but the first face render blank terrain. No-op under vanilla.
				SodiumCompat.scheduleTerrainUpdate();
				// WIPE the target before each face. Under a shaderpack Iris seeds its pipeline from the current
				// framebuffer (our swapped-in offscreen target); left dirty it still holds the PREVIOUS face, so
				// that face's foliage bleeds into this one as the phantom "shadow blocks" — a hard buffer bleed
				// (disabling TAA didn't stop it, and re-rendering can't overwrite pixels this face doesn't draw).
				// Then re-render a few times so any INTERNAL temporal buffers also settle onto this face.
				framebuffer.setClearColor(0.0F, 0.0F, 0.0F, 1.0F);
				framebuffer.clear(MinecraftClient.IS_SYSTEM_MAC);
				int renders = wide ? SHADER_SETTLE_RENDERS + 1 : 1;
				for (int i = 0; i < renders; i++) {
					framebuffer.beginWrite(true);
					client.gameRenderer.renderWorld(RenderTickCounter.ONE);
				}

				NativeImage image = ScreenshotRecorder.takeScreenshot(framebuffer);
				NativeImage out = wide ? cropCentral(image, resolution) : image;
				if (wide) {
					image.close(); // keep only the cropped central 90°
				}
				Path file = dir.resolve(shot.fileName());
				Util.getIoWorkerExecutor().execute(() -> {
					try {
						out.writeTo(file);
					} catch (IOException e) {
						PortalGlimpse.LOGGER.warn("Portal Glimpse: failed to write {}", file, e);
					} finally {
						out.close();
					}
				});
			}
		} finally {
			shaderCaptureFov = 0.0F;
			CameraOverride.clear();
			((MinecraftClientAccessor) (Object) client).portalglimpse$setFramebuffer(realFramebuffer);
			framebuffer.delete();
			client.gameRenderer.setRenderingPanorama(false);
			client.gameRenderer.setBlockOutlineEnabled(true);
			window.setFramebufferWidth(prevWidth);
			window.setFramebufferHeight(prevHeight);
			client.worldRenderer.reloadTransparencyPostProcessor();
			client.getFramebuffer().beginWrite(true);
		}
	}

	// ---------------------------------------------------------------------------------------------------
	// Incremental (multi-frame) capture — used UNDER SHADERS. A shaderpack keeps persistent temporal buffers
	// that only advance per REAL game frame, so rendering all 6 faces back-to-back in one frame bleeds each
	// face's foliage into the next (the "shadow blocks"), and an in-one-frame warmup can't fix it. Instead we
	// hold the camera on each face for several real frames and grab it once the pack has settled onto it —
	// exactly like a static camera settling in normal play (which is clean). Vanilla keeps the instant
	// synchronous capture() above (no shaders → no temporal buffers → no bleed).
	//
	// Crucially we do NOT resize the window to a square or swap in an offscreen buffer here (that made the
	// whole displayed frame a stretched square for the whole settle). We render each face straight into the
	// REAL framebuffer at the native aspect + a 110° FOV, then crop the central 90° SQUARE out of it: a
	// perspective projection has uniform pixels-per-angle, so that central region is exactly square in pixels
	// (side = 0.70·height) and is a correct cube face. The screen keeps its normal aspect during the settle
	// (the travel loading backdrop covers it), and Iris renders at its normal size — no fragile resize dance.

	/** Real frames to hold each face so the pack's temporal effects settle before we grab it (tunable). */
	private static final int SETTLE_FRAMES = 20;

	private static boolean active;
	private static MinecraftClient incClient;
	private static Path incDir;
	private static List<Shot> incShots;
	private static int incIndex;
	private static int incFramesLeft;

	/** True while a multi-frame capture is running (the normal render loop is drawing capture faces). */
	public static boolean isActive() {
		return active;
	}

	/** Start a multi-frame capture. The normal render loop then draws each face into the real framebuffer at
	 * the native aspect + wide FOV (camera forced by {@link CameraOverride}); {@link #onCaptureFrameEnd()},
	 * called post-composite each frame, holds each face {@link #SETTLE_FRAMES} frames then grabs the central
	 * 90° square. When done, {@link #isActive()} flips to false and the caller (polling from a tick) finalises. */
	public static void beginIncremental(MinecraftClient client, Path dir, List<Shot> shots) throws IOException {
		Files.createDirectories(dir);
		incClient = client;
		incDir = dir;
		incShots = shots;
		incIndex = 0;
		incFramesLeft = SETTLE_FRAMES;
		client.gameRenderer.setBlockOutlineEnabled(false);
		client.gameRenderer.setRenderingPanorama(true);
		shaderCaptureFov = CAPTURE_FOV_SHADER; // 110° so the central 90° crop is free of the pack's edge vignette
		applyShot(shots.get(0));
		active = true;
	}

	private static void applyShot(Shot shot) {
		CameraOverride.set(shot.pos(), shot.yaw(), shot.pitch());
		SodiumCompat.scheduleTerrainUpdate();
	}

	/** Called post-composite at the end of every rendered frame while a capture is active. Counts settle
	 * frames, grabs each settled face, and advances; tears down once all are saved. */
	public static void onCaptureFrameEnd() {
		if (!active) {
			return;
		}
		if (--incFramesLeft > 0) {
			return; // still settling this face
		}
		grab(incShots.get(incIndex));
		incIndex++;
		if (incIndex >= incShots.size()) {
			endIncremental();
			return;
		}
		applyShot(incShots.get(incIndex));
		incFramesLeft = SETTLE_FRAMES;
	}

	private static void grab(Shot shot) {
		NativeImage image = ScreenshotRecorder.takeScreenshot(incClient.getFramebuffer());
		// Central 90° square out of the native-aspect 110° frame: side = cropFraction·height (tan(45)/tan(55)),
		// clamped to width for the (unusual) case of a portrait/narrow window.
		int side = Math.min(Math.round(image.getHeight() * cropFraction()), image.getWidth());
		NativeImage out = cropCentral(image, side);
		image.close();
		Path file = incDir.resolve(shot.fileName());
		Util.getIoWorkerExecutor().execute(() -> {
			try {
				out.writeTo(file);
			} catch (IOException e) {
				PortalGlimpse.LOGGER.warn("Portal Glimpse: failed to write {}", file, e);
			} finally {
				out.close();
			}
		});
	}

	private static void endIncremental() {
		active = false;
		shaderCaptureFov = 0.0F;
		CameraOverride.clear();
		incClient.gameRenderer.setRenderingPanorama(false);
		incClient.gameRenderer.setBlockOutlineEnabled(true);
		incClient = null;
		incShots = null;
		incDir = null;
	}

	/** Copy the centred {@code side}×{@code side} square out of {@code src} into a new image — the true 90°
	 * cube face, with the wide-FOV/vignetted rim discarded. Caller owns and closes both images. */
	private static NativeImage cropCentral(NativeImage src, int side) {
		int offX = (src.getWidth() - side) / 2;
		int offY = (src.getHeight() - side) / 2;
		NativeImage dst = new NativeImage(side, side, false);
		for (int y = 0; y < side; y++) {
			for (int x = 0; x < side; x++) {
				dst.setColor(x, y, src.getColor(offX + x, offY + y));
			}
		}
		return dst;
	}
}

package com.rinke.portalglimpse.render;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.rinke.portalglimpse.data.PortalRecord;
import com.rinke.portalglimpse.data.PortalStore;
import com.rinke.portalglimpse.detect.PortalDetection;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OutlineVertexConsumerProvider;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * The manual-capture glow: feeds a solid portal-opening silhouette into vanilla's ENTITY-OUTLINE
 * buffer ({@link OutlineVertexConsumerProvider}) with the flash colour, so the same {@code
 * entity_outline} post-process that glows mobs draws a feathered glow around the portal edges. The
 * green/red 2-blink timing comes from {@link ManualCaptureFlash}. Rendered during
 * {@code WorldRenderEvents.AFTER_ENTITIES} (before the outline buffer is flushed); the glow only
 * composites when {@code WorldRendererMixin} forces the post-process (vanilla skips it with no
 * glowing entity). Fancy/Fabulous graphics only, like all entity glows.
 */
public final class PortalGlowOutline {

	/** Opaque texture used as the silhouette mask — colour comes from setColor, so any opaque one works. */
	private static final Identifier MASK = Identifier.ofVanilla("textures/block/obsidian.png");

	/** The portal's visible plane (6/16 and 10/16), matching where the veil/panorama render, so the
	 * glow traces the edge of the glimpse rather than the block face or the middle. */
	private static final float PLANE_LOW = 6.0F / 16.0F;
	private static final float PLANE_HIGH = 10.0F / 16.0F;

	/** True while this frame emitted glow geometry — read by the mixin to force the post-process. */
	private static volatile boolean composite;

	private PortalGlowOutline() {
	}

	public static boolean shouldComposite() {
		return composite;
	}

	public static void render(WorldRenderContext context) {
		composite = false;
		if (ManualCaptureFlash.activeIds().isEmpty()) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		PortalStore store = PortalDetection.store();
		ClientWorld world = context.world();
		if (store == null || world == null || context.matrixStack() == null) {
			return;
		}
		Identifier dimension = world.getRegistryKey().getValue();
		Vec3d cam = context.camera().getPos();
		OutlineVertexConsumerProvider outline = client.getBufferBuilders().getOutlineVertexConsumers();

		MatrixStack matrices = context.matrixStack();
		matrices.push();
		matrices.translate(-cam.x, -cam.y, -cam.z);
		MatrixStack.Entry entry = matrices.peek();

		boolean any = false;
		for (UUID id : new ArrayList<>(ManualCaptureFlash.activeIds())) {
			int color = ManualCaptureFlash.outlineColor(id);
			if (color < 0) {
				continue;
			}
			PortalRecord record = store.get(id);
			if (record == null || !record.dimension.equals(dimension)) {
				continue;
			}
			outline.setColor((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, 255);
			// getOutline = the outline-ONLY layer (what invisible glowing entities use): nothing is
			// drawn to the main scene, only the silhouette is fed to the glow. No visible texture.
			emitOpening(entry, outline.getBuffer(RenderLayer.getOutline(MASK)),
					record.interior, record.axis == Direction.Axis.X, cam);
			any = true;
		}
		matrices.pop();
		composite = any;
	}

	/**
	 * A solid quad over the whole portal opening — the outline shader glows its rectangular edge. It
	 * sits on the VIEWER-SIDE face of the portal (the block face closest to the camera), chosen per
	 * frame, so the glow traces the edge you're actually looking at rather than the middle.
	 */
	private static void emitOpening(MatrixStack.Entry entry, VertexConsumer vc, List<BlockPos> interior,
			boolean axisX, Vec3d cam) {
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (BlockPos p : interior) {
			minX = Math.min(minX, p.getX());
			maxX = Math.max(maxX, p.getX());
			minY = Math.min(minY, p.getY());
			maxY = Math.max(maxY, p.getY());
			minZ = Math.min(minZ, p.getZ());
			maxZ = Math.max(maxZ, p.getZ());
		}
		float y0 = minY;
		float y1 = maxY + 1;
		if (axisX) {
			// Viewer-side visible plane (6/16 near / 10/16 far), matching the veil — not the block face.
			float z = cam.z < minZ + 0.5 ? minZ + PLANE_LOW : minZ + PLANE_HIGH;
			float x0 = minX;
			float x1 = maxX + 1;
			vertex(entry, vc, x0, y0, z, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F);
			vertex(entry, vc, x1, y0, z, 1.0F, 1.0F, 0.0F, 0.0F, 1.0F);
			vertex(entry, vc, x1, y1, z, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F);
			vertex(entry, vc, x0, y1, z, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F);
		} else {
			// Viewer-side visible plane (6/16 near / 10/16 far), matching the veil — not the block face.
			float x = cam.x < minX + 0.5 ? minX + PLANE_LOW : minX + PLANE_HIGH;
			float z0 = minZ;
			float z1 = maxZ + 1;
			vertex(entry, vc, x, y0, z0, 0.0F, 1.0F, 1.0F, 0.0F, 0.0F);
			vertex(entry, vc, x, y0, z1, 1.0F, 1.0F, 1.0F, 0.0F, 0.0F);
			vertex(entry, vc, x, y1, z1, 1.0F, 0.0F, 1.0F, 0.0F, 0.0F);
			vertex(entry, vc, x, y1, z0, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F);
		}
	}

	private static void vertex(MatrixStack.Entry entry, VertexConsumer vc, float x, float y, float z,
			float u, float v, float nx, float ny, float nz) {
		vc.vertex(entry.getPositionMatrix(), x, y, z)
				.color(255, 255, 255, 255)
				.texture(u, v)
				.overlay(OverlayTexture.DEFAULT_UV)
				.light(LightmapTextureManager.MAX_LIGHT_COORDINATE)
				.normal(entry, nx, ny, nz);
	}
}

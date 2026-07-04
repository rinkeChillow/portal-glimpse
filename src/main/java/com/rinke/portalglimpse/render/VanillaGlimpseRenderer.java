package com.rinke.portalglimpse.render;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.joml.Matrix4fStack;

import com.mojang.blaze3d.systems.RenderSystem;

import com.rinke.portalglimpse.data.PortalRecord;
import com.rinke.portalglimpse.data.PortalStore;
import com.rinke.portalglimpse.detect.PortalDetection;
import com.rinke.portalglimpse.ghost.GhostState;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Default glimpse renderer built on vanilla rendering (design doc §7 strategy "B leaning C").
 *
 * <p>Phase 3: for every portal with a glimpse, the vanilla portal quads are hidden from the chunk
 * mesh ({@link GlimpseRenderState}) and replaced here with two layers per face (§4.3):
 * <ol>
 *   <li><b>The glimpse</b> — the face's postcard, mapped across the full recorded interior so
 *       arbitrary shapes work (§4.6), slightly translucent like portal-stuff.</li>
 *   <li><b>The veil</b> — the living vanilla portal sprite (animation + resource packs intact),
 *       drawn over the glimpse at the configured opacity.</li>
 * </ol>
 * Both faces render their own postcard (§4.4). Portals without a glimpse never reach this path
 * and stay 100% vanilla (§3.1).
 */
public class VanillaGlimpseRenderer implements GlimpseRenderer {

	/** Glimpse translucency — sits in the frame like portal-stuff, not an opaque poster (§4.3). */
	private static final int GLIMPSE_ALPHA = 235;

	// Veil opacity lives in GlimpseSettings (§4.3 slider) — tune in-game with Numpad 9 / 6.

	/** Beyond this distance the glimpse isn't drawn at all (proper zones arrive in Phase 4). */
	private static final double MAX_RENDER_DISTANCE_SQ = 128.0 * 128.0;

	/** Within this distance the parallax panorama renders on the portal (§4.2 close zone). */
	private static final double PANORAMA_DISTANCE = 32.0;

	/** Proximity fade: the 2D postcard is at full strength here, fading as the player approaches… */
	private static final double FADE_START = 20.0;

	/** …and fully gone at this distance. Only the postcard fades — the veil stays (Phase 4's
	 * parallax panorama will own the close range). */
	private static final double FADE_END = 10.0;

	/** Offset of the veil in front of the glimpse plane, avoiding z-fighting. */
	private static final float VEIL_OFFSET = 0.002F;

	/** In-block positions of the portal plane quads, matching the vanilla portal model. */
	private static final float PLANE_LOW = 6.0F / 16.0F;
	private static final float PLANE_HIGH = 10.0F / 16.0F;

	@Override
	public String name() {
		return "vanilla";
	}

	@Override
	public void renderWorld(WorldRenderContext context) {
		PortalStore store = PortalDetection.store();
		ClientWorld world = context.world();
		if (store == null || world == null) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		Identifier dimension = world.getRegistryKey().getValue();
		Vec3d cameraPos = context.camera().getPos();

		// Collect portals that should show a glimpse this frame.
		List<Drawable> drawables = new ArrayList<>();
		Set<Long> hiddenPositions = new HashSet<>();
		for (PortalRecord record : store.all()) {
			if (!record.auto.hasCapture || !record.dimension.equals(dimension)) {
				continue;
			}
			if (record.anchor.getSquaredDistance(cameraPos.x, cameraPos.y, cameraPos.z) > MAX_RENDER_DISTANCE_SQ) {
				continue;
			}
			// A portal being ghosted for a capture must not photobomb it (§3.2 step 3).
			if (GhostState.isActive() && GhostState.isHidden(record.interior.get(0))) {
				continue;
			}

			// Proximity fade (2D postcard only): distance from the camera to the nearest point of
			// the portal plane. The veil is untouched by distance.
			Bounds bounds = Bounds.of(record.interior);
			float fade = 1.0F;
			if (GlimpseSettings.proximityFade) {
				double distance = bounds.distanceTo(cameraPos);
				fade = (float) Math.min(1.0, Math.max(0.0,
						(distance - FADE_END) / (FADE_START - FADE_END)));
			}
			int glimpseAlpha = GlimpseSettings.glimpsesVisible ? Math.round(GLIMPSE_ALPHA * fade) : 0;
			int veilAlpha = GlimpseSettings.veilAlpha;

			// Like the vanilla portal (and glass), only the face toward the viewer is visible —
			// never the far face shining through. Pick it per frame from the camera's side of
			// the plane. Face A = north (axis X) / west (axis Z).
			boolean viewerOnFaceA = record.axis == Direction.Axis.X
					? cameraPos.z < bounds.minZ + 0.5
					: cameraPos.x < bounds.minX + 0.5;

			GlimpseTextures.GlimpseTexture texture = GlimpseTextures.get(client, store.baseDir(), record);
			if (texture == null) {
				continue; // still loading — portal stays vanilla until ready
			}
			// Only blocks that are actually still portal blocks render a glimpse ("there is a
			// portal at these coordinates; render its glimpse when the portal blocks are there",
			// §5.3). AND the record must still OWN those blocks: if the portal was expanded/reshaped
			// it's a new portal that renders fully vanilla until re-captured — otherwise the old
			// sub-shape's glimpse would paint a patch inside the bigger vanilla portal.
			List<BlockPos> present = new ArrayList<>(record.interior.size());
			boolean superseded = false;
			for (BlockPos pos : record.interior) {
				if (!world.getBlockState(pos).isOf(Blocks.NETHER_PORTAL)) {
					continue;
				}
				if (store.recordAt(pos) != record) {
					superseded = true; // a reshaped portal (a different record) now claims this block
					break;
				}
				present.add(pos);
			}
			if (!superseded && !present.isEmpty()) {
				for (BlockPos pos : present) {
					hiddenPositions.add(pos.asLong());
				}
				drawables.add(new Drawable(record, texture, present, bounds, glimpseAlpha, veilAlpha,
						viewerOnFaceA));
			}
		}

		GlimpseRenderState.sync(client, hiddenPositions);
		if (drawables.isEmpty()) {
			return;
		}

		MatrixStack matrices = context.matrixStack();
		matrices.push();
		matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
		MatrixStack.Entry entry = matrices.peek();
		VertexConsumerProvider consumers = context.consumers();

		// Pass 1: the glimpses. Flushed to the framebuffer before the veils are emitted so the
		// veil always composites OVER the glimpse (§4.3 "a ghost dancing over the glimpse").
		for (Drawable drawable : drawables) {
			emitGlimpse(entry, consumers, drawable);
		}
		if (consumers instanceof VertexConsumerProvider.Immediate immediate) {
			immediate.draw();
		}

		// Pass 2: the veils — the living vanilla portal sprite, animation and resource packs intact.
		Sprite portalSprite = client.getBlockRenderManager().getModels()
				.getModelParticleSprite(Blocks.NETHER_PORTAL.getDefaultState());
		VertexConsumer veil = consumers.getBuffer(
				RenderLayer.getItemEntityTranslucentCull(portalSprite.getAtlasId()));
		for (Drawable drawable : drawables) {
			emitVeil(entry, veil, drawable, portalSprite);
		}

		matrices.pop();

		// Pass 3: the parallax panorama for close portals (§4.1) — the living window into the
		// other world. Drawn with our custom shader in camera-relative space; the veil (already
		// on the framebuffer) stays layered over it.
		renderPanoramas(context, store, cameraPos, drawables);
	}

	/**
	 * Draws the cubemap panorama on each nearby portal via the {@code portal_panorama} shader. Each
	 * fragment's view ray (its camera-relative position) selects and samples one of the six faces,
	 * so the view shifts with real perspective as the player moves.
	 */
	private static void renderPanoramas(WorldRenderContext context, PortalStore store, Vec3d cameraPos,
			List<Drawable> drawables) {
		ShaderProgram shader = PortalShaders.panorama();
		if (shader == null) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();

		List<PanoDraw> panos = new ArrayList<>();
		for (Drawable drawable : drawables) {
			if (drawable.bounds().distanceTo(cameraPos) > PANORAMA_DISTANCE) {
				continue;
			}
			Identifier[] faces = PanoramaTextures.get(client, store.baseDir(), drawable.record());
			if (faces != null) {
				panos.add(new PanoDraw(drawable, faces));
			}
		}
		if (panos.isEmpty()) {
			return;
		}

		// Fold the camera rotation (context pose) into the model-view so our camera-relative
		// vertices land in view space, regardless of whether the rotation lived in the pose or in
		// RenderSystem to begin with.
		Matrix4fStack modelView = RenderSystem.getModelViewStack();
		modelView.pushMatrix();
		modelView.mul(context.matrixStack().peek().getPositionMatrix());
		RenderSystem.applyModelViewMatrix();

		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.enableDepthTest();
		RenderSystem.depthMask(true);
		RenderSystem.disableCull();
		RenderSystem.setShader(PortalShaders::panorama);

		GlUniform alpha = shader.getUniform("GlimpseAlpha");
		GlUniform center = shader.getUniform("PortalCenter");
		GlUniform radius = shader.getUniform("SphereRadius");

		for (PanoDraw pano : panos) {
			for (int i = 0; i < 6; i++) {
				RenderSystem.setShaderTexture(i, pano.faces()[i]);
			}
			// Per-portal interior-mapping uniforms: the sphere is centered on this portal.
			Bounds b = pano.drawable().bounds();
			if (alpha != null) {
				alpha.set(1.0F);
			}
			if (center != null) {
				center.set(
						(float) ((b.minX() + b.maxX() + 1) / 2.0 - cameraPos.x),
						(float) ((b.minY() + b.maxY() + 1) / 2.0 - cameraPos.y),
						(float) ((b.minZ() + b.maxZ() + 1) / 2.0 - cameraPos.z));
			}
			if (radius != null) {
				radius.set(GlimpseSettings.panoramaRadius);
			}

			boolean axisX = pano.drawable().record().axis == Direction.Axis.X;
			boolean faceA = pano.drawable().viewerOnFaceA();
			BufferBuilder buffer = Tessellator.getInstance()
					.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION);
			for (BlockPos pos : pano.drawable().blocks()) {
				emitPanoramaQuad(buffer, pos, axisX, faceA, cameraPos);
			}
			BuiltBuffer built = buffer.endNullable();
			if (built != null) {
				BufferRenderer.drawWithGlobalProgram(built);
			}
		}

		RenderSystem.enableCull();
		modelView.popMatrix();
		RenderSystem.applyModelViewMatrix();
	}

	private record PanoDraw(Drawable drawable, Identifier[] faces) {
	}

	/** One portal-plane quad in camera-relative space (POSITION only; the shader does the rest). */
	private static void emitPanoramaQuad(BufferBuilder buffer, BlockPos pos, boolean axisX, boolean faceA,
			Vec3d cam) {
		float y0 = (float) (pos.getY() - cam.y);
		float y1 = (float) (pos.getY() + 1 - cam.y);
		if (axisX) {
			float plane = (float) ((faceA ? pos.getZ() + PLANE_LOW : pos.getZ() + PLANE_HIGH) - cam.z);
			float xa = (float) (pos.getX() - cam.x);
			float xb = (float) (pos.getX() + 1 - cam.x);
			buffer.vertex(xa, y1, plane);
			buffer.vertex(xa, y0, plane);
			buffer.vertex(xb, y0, plane);
			buffer.vertex(xb, y1, plane);
		} else {
			float plane = (float) ((faceA ? pos.getX() + PLANE_LOW : pos.getX() + PLANE_HIGH) - cam.x);
			float za = (float) (pos.getZ() - cam.z);
			float zb = (float) (pos.getZ() + 1 - cam.z);
			buffer.vertex(plane, y1, za);
			buffer.vertex(plane, y0, za);
			buffer.vertex(plane, y0, zb);
			buffer.vertex(plane, y1, zb);
		}
	}

	private record Drawable(PortalRecord record, GlimpseTextures.GlimpseTexture texture,
			List<BlockPos> blocks, Bounds bounds, int glimpseAlpha, int veilAlpha,
			boolean viewerOnFaceA) {
	}

	private static void emitGlimpse(MatrixStack.Entry entry, VertexConsumerProvider consumers,
			Drawable drawable) {
		if (drawable.glimpseAlpha() <= 0) {
			return; // fully faded by proximity — only the veil remains
		}
		Bounds b = drawable.bounds();
		boolean axisX = drawable.record().axis == Direction.Axis.X;

		// Only the face toward the viewer, like vanilla. Face A = north/west, B = south/east.
		Identifier texture = drawable.viewerOnFaceA()
				? drawable.texture().faceA()
				: drawable.texture().faceB();
		if (texture == null) {
			return;
		}
		// Item-entity-translucent WRITES DEPTH (unlike entity-translucent) — so the glimpse occludes
		// clouds, particles, weather and other portals' glimpses behind it instead of being painted
		// over. The layer culls, so we emit both windings and keep the camera-facing one.
		VertexConsumer vc = consumers.getBuffer(RenderLayer.getItemEntityTranslucentCull(texture));
		for (BlockPos pos : drawable.blocks()) {
			emitFace(entry, vc, pos, b, axisX, drawable.viewerOnFaceA(), drawable.glimpseAlpha(),
					0.0F, 0.0F, 1.0F, 1.0F, 0.0F);
		}
	}

	private static void emitVeil(MatrixStack.Entry entry, VertexConsumer vc, Drawable drawable,
			Sprite sprite) {
		int alpha = drawable.veilAlpha();
		if (alpha <= 0) {
			return;
		}
		Bounds b = drawable.bounds();
		boolean axisX = drawable.record().axis == Direction.Axis.X;
		for (BlockPos pos : drawable.blocks()) {
			// The veil tiles per block (like the vanilla portal texture does) rather than
			// stretching across the plane, so the swirl keeps its vanilla scale. Only the
			// viewer-side face is drawn — the far face never shines through (vanilla behavior).
			emitFace(entry, vc, pos, b, axisX, drawable.viewerOnFaceA(), alpha,
					sprite.getMinU(), sprite.getMinV(), sprite.getMaxU(), sprite.getMaxV(), VEIL_OFFSET);
		}
	}

	/**
	 * Emits one block-sized quad of the portal plane.
	 *
	 * @param faceA  true = north face (axis X) or west face (axis Z); false = the opposite face
	 * @param u0..v1 texture window: the sprite bounds for the veil, or 0..1 for the glimpse
	 *               (remapped across the whole plane per block inside)
	 * @param push   extra offset along the face normal (the veil floats in front of the glimpse)
	 */
	private static void emitFace(MatrixStack.Entry entry, VertexConsumer vc, BlockPos pos, Bounds b,
			boolean axisX, boolean faceA, int alpha, float u0, float v0, float u1, float v1, float push) {
		float x = pos.getX();
		float y = pos.getY();
		float z = pos.getZ();
		boolean stretch = push == 0.0F; // glimpse maps across the plane; veil tiles the sprite per block

		// Cover-fit the square postcard to the portal's aspect ratio (zoom to fill, crop the
		// overflow — no stretching/distortion). uSpan/vSpan is the centered window of the texture
		// that gets mapped across the whole plane. The veil (non-stretch) is unaffected.
		float coverAspect = b.width / b.height;
		float uSpan = coverAspect < 1.0F ? coverAspect : 1.0F;
		float vSpan = coverAspect < 1.0F ? 1.0F : 1.0F / coverAspect;
		float uOff = (1.0F - uSpan) / 2.0F;
		float vOff = (1.0F - vSpan) / 2.0F;

		float yTop = y + 1;
		float yBottom = y;
		float vTop;
		float vBottom;
		if (stretch) {
			vTop = vOff + (b.maxY + 1 - yTop) / b.height * vSpan;
			vBottom = vOff + (b.maxY + 1 - yBottom) / b.height * vSpan;
		} else {
			vTop = v0;
			vBottom = v1;
		}

		float[] px = new float[4];
		float[] py = new float[4];
		float[] pz = new float[4];
		float[] uu = new float[4];
		float[] vv = new float[4];

		if (axisX) {
			// Plane spans X; faces point north (-Z) and south (+Z).
			float plane = faceA ? (z + PLANE_LOW - push) : (z + PLANE_HIGH + push);
			// Horizontal mapping matches the postcard camera: image left = camera's left.
			float xLeft = faceA ? x + 1 : x;
			float xRight = faceA ? x : x + 1;
			float uLeft;
			float uRight;
			if (stretch) {
				uLeft = uOff + (faceA ? (b.maxX + 1 - xLeft) / b.width : (xLeft - b.minX) / b.width) * uSpan;
				uRight = uOff + (faceA ? (b.maxX + 1 - xRight) / b.width : (xRight - b.minX) / b.width) * uSpan;
			} else {
				uLeft = u0;
				uRight = u1;
			}
			set(px, py, pz, uu, vv, 0, xLeft, yTop, plane, uLeft, vTop);
			set(px, py, pz, uu, vv, 1, xLeft, yBottom, plane, uLeft, vBottom);
			set(px, py, pz, uu, vv, 2, xRight, yBottom, plane, uRight, vBottom);
			set(px, py, pz, uu, vv, 3, xRight, yTop, plane, uRight, vTop);
		} else {
			// Plane spans Z; faces point west (-X) and east (+X).
			float plane = faceA ? (x + PLANE_LOW - push) : (x + PLANE_HIGH + push);
			float zLeft = faceA ? z : z + 1;
			float zRight = faceA ? z + 1 : z;
			float uLeft;
			float uRight;
			if (stretch) {
				uLeft = uOff + (faceA ? (zLeft - b.minZ) / b.width : (b.maxZ + 1 - zLeft) / b.width) * uSpan;
				uRight = uOff + (faceA ? (zRight - b.minZ) / b.width : (b.maxZ + 1 - zRight) / b.width) * uSpan;
			} else {
				uLeft = u0;
				uRight = u1;
			}
			set(px, py, pz, uu, vv, 0, plane, yTop, zLeft, uLeft, vTop);
			set(px, py, pz, uu, vv, 1, plane, yBottom, zLeft, uLeft, vBottom);
			set(px, py, pz, uu, vv, 2, plane, yBottom, zRight, uRight, vBottom);
			set(px, py, pz, uu, vv, 3, plane, yTop, zRight, uRight, vTop);
		}

		// Both windings — the culling render layer keeps whichever faces the camera, so we never
		// have to guess vertex order per face.
		for (int i = 0; i < 4; i++) {
			vertex(entry, vc, px[i], py[i], pz[i], uu[i], vv[i], alpha);
		}
		for (int i = 3; i >= 0; i--) {
			vertex(entry, vc, px[i], py[i], pz[i], uu[i], vv[i], alpha);
		}
	}

	private static void set(float[] px, float[] py, float[] pz, float[] uu, float[] vv, int i,
			float x, float y, float z, float u, float v) {
		px[i] = x;
		py[i] = y;
		pz[i] = z;
		uu[i] = u;
		vv[i] = v;
	}

	private static void vertex(MatrixStack.Entry entry, VertexConsumer vc, float x, float y, float z,
			float u, float v, int alpha) {
		vc.vertex(entry.getPositionMatrix(), x, y, z)
				.color(255, 255, 255, alpha)
				.texture(u, v)
				.overlay(OverlayTexture.DEFAULT_UV)
				.light(LightmapTextureManager.MAX_LIGHT_COORDINATE)
				.normal(entry, 0.0F, 1.0F, 0.0F); // up → full diffuse brightness on this shaded layer
	}

	/** Interior bounding box, cached per emit batch. */
	private record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
			float width, float height) {

		/** Distance from {@code point} to the nearest point of this box (0 when inside). */
		double distanceTo(Vec3d point) {
			double dx = Math.max(Math.max(minX - point.x, 0.0), point.x - (maxX + 1));
			double dy = Math.max(Math.max(minY - point.y, 0.0), point.y - (maxY + 1));
			double dz = Math.max(Math.max(minZ - point.z, 0.0), point.z - (maxZ + 1));
			return Math.sqrt(dx * dx + dy * dy + dz * dz);
		}
		static Bounds of(List<BlockPos> interior) {
			int minX = Integer.MAX_VALUE;
			int minY = Integer.MAX_VALUE;
			int minZ = Integer.MAX_VALUE;
			int maxX = Integer.MIN_VALUE;
			int maxY = Integer.MIN_VALUE;
			int maxZ = Integer.MIN_VALUE;
			for (BlockPos pos : interior) {
				minX = Math.min(minX, pos.getX());
				maxX = Math.max(maxX, pos.getX());
				minY = Math.min(minY, pos.getY());
				maxY = Math.max(maxY, pos.getY());
				minZ = Math.min(minZ, pos.getZ());
				maxZ = Math.max(maxZ, pos.getZ());
			}
			float width = (maxX - minX) >= (maxZ - minZ) ? (maxX + 1 - minX) : (maxZ + 1 - minZ);
			float height = maxY + 1 - minY;
			return new Bounds(minX, minY, minZ, maxX, maxY, maxZ, width, height);
		}
	}
}

package com.rinke.portalglimpse.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.joml.Matrix4fStack;

import com.mojang.blaze3d.systems.RenderSystem;

import com.rinke.portalglimpse.data.PortalRecord;
import com.rinke.portalglimpse.data.PortalStore;
import com.rinke.portalglimpse.detect.PortalDetection;
import com.rinke.portalglimpse.ghost.GhostState;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
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
import net.minecraft.util.math.Box;
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

	/** The glimpse fades out over the last (1 − fraction) of its render distance, so it's already
	 * invisible by the cutoff instead of popping when the portal (un)loads. */
	private static final double DISTANCE_FADE_FRACTION = 0.8;

	/** How long the glimpse fades back in after teleport-arrival suppression lifts (milliseconds). */
	private static final long ARRIVAL_FADE_MS = 500L;

	/** Portal id → wall-clock time it was last suppressed by {@link PortalArrivalGate}, so the glimpse
	 * fades back in when the player steps clear instead of popping. Render-thread only. */
	private static final Map<UUID, Long> lastSuppressedMillis = new HashMap<>();

	/** Departure push: while the player stands in a portal its panorama plane is kept at least EYE_PUSH
	 * blocks in front of the eye so they can't walk through the flat render plane and see its back. It is
	 * a ONE-WAY ratchet (Math.max in emitPanoramaQuad): on entry the plane STAYS at the portal surface
	 * and only recedes as the eye advances into it — never snapping toward the face. On stepping clear it
	 * does NOT slide back; instead the pushed view FADES OUT (FADE_OUT_MS) then the on-surface view FADES
	 * back IN (FADE_IN_MS) "to where it was" — an alpha crossfade, no swim. lastInsideMillis: portal id ->
	 * wall-clock time last seen inside. Render-thread only. */
	private static final float EYE_PUSH = 0.25F;
	private static final long FADE_OUT_MS = 150L;
	private static final long FADE_IN_MS = 200L;
	/** Only run the exit fade-out/fade-in if the plane was still pushed at least this far (blocks) off
	 * the surface at exit; if you'd already backed off (plane ratcheted home), just end with no fade. */
	private static final float FADE_MIN_PUSH = 0.15F;
	private static final Map<UUID, Long> lastInsideMillis = new HashMap<>();
	private static final Map<UUID, Float> lastPushedDist = new HashMap<>();
	/** Portal id -> the face (viewerOnFaceA) latched when the player ENTERED, held while inside and
	 * through the departure fade so crossing the centre / turning around doesn't flip the panorama.
	 * Only cleared (flip resumes) once fully outside. */
	private static final Map<UUID, Boolean> departureFaceLatch = new HashMap<>();
	/** Portal id -> current encasement (0..1): as the teleport swirl (nausea) builds while you stand in
	 * the portal, the destination panorama also fades in on the OTHER side, wrapping you until fully
	 * encased at teleport. Rises with the swirl; fades out fast when you step clear. Render-thread only. */
	private static final Map<UUID, Float> encasementMap = new HashMap<>();
	/** Per-frame ease for the encasement fade-OUT when you leave (fade-in tracks the nausea directly). */
	private static final float ENCASE_FADE_OUT = 0.35F;

	/** Within this distance the parallax panorama renders on the portal (§4.2 close zone). */
	private static final double PANORAMA_DISTANCE = 32.0;

	/** Proximity fade: the 2D postcard is at full strength here, fading as the player approaches… */
	private static final double FADE_START = 20.0;

	/** …and fully gone at this distance. Only the postcard fades — the veil stays (Phase 4's
	 * parallax panorama will own the close range). */
	private static final double FADE_END = 10.0;

	/** Offset of the veil in front of the glimpse plane, avoiding z-fighting. */
	private static final float VEIL_OFFSET = 0.002F;

	/** Offset of the panorama just BEHIND the postcard plane (postcard sits a hair in front). */
	private static final float PANORAMA_OFFSET = 0.002F;

	/** The pushed box is the portal opening grown by this much on every side (X -> X+1), so its edges sit
	 * inside the surrounding obsidian: hidden by the frame until the push brings them forward, and not
	 * coplanar with the frame's inner faces (which had caused z-fighting). */
	private static final float BOX_MARGIN = 0.5F;

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
			// A portal being ghosted for a capture must not photobomb it (§3.2 step 3).
			if (GhostState.isActive() && GhostState.isHidden(record.interior.get(0))) {
				continue;
			}

			Bounds bounds = Bounds.of(record.interior);

			// Distance visibility + fade, matching vanilla ENTITY render distance (which scales with
			// the object's size — a big portal shows from further, like a big entity). Fade the glimpse
			// out over the last stretch so it's fully gone by the cutoff — no pop when it (un)loads.
			double maxDist = entityRenderDistance(bounds, client);
			double centerX = (bounds.minX() + bounds.maxX() + 1) / 2.0;
			double centerY = (bounds.minY() + bounds.maxY() + 1) / 2.0;
			double centerZ = (bounds.minZ() + bounds.maxZ() + 1) / 2.0;
			double centerDist = Math.sqrt(cameraPos.squaredDistanceTo(centerX, centerY, centerZ));
			if (centerDist >= maxDist) {
				continue;
			}
			double distanceFadeStart = maxDist * DISTANCE_FADE_FRACTION;
			float distanceFade = centerDist <= distanceFadeStart ? 1.0F
					: (float) ((maxDist - centerDist) / (maxDist - distanceFadeStart));

			// Fresh teleport arrival: while the player is still standing in the portal they just came
			// through, hide its glimpse and let the plain vanilla blocks show, until they step clear —
			// so they don't spawn clipping through the panorama plane at point-blank. Only a dimension
			// change arms this (PortalArrivalGate), so walking UP to a portal — even stepping into it
			// right before travelling — keeps the glimpse the whole way.
			long nowMillis = System.currentTimeMillis();
			boolean playerInside = playerInsidePortal(client, bounds);
			if (PortalArrivalGate.isArmed() && playerInside) {
				lastSuppressedMillis.put(record.id, nowMillis);
				continue;
			}
			// Which face's side the panorama renders on. Normally the live camera side (instant flip is
			// fine OUTSIDE). But the moment you enter, latch the entry face and hold it the whole time
			// you're inside AND through the departure fade — so crossing the centre plane or turning around
			// never flips the panorama; the flip only resumes once you're fully back outside.
			boolean freshFaceA = record.axis == Direction.Axis.X
					? cameraPos.z < bounds.minZ + 0.5
					: cameraPos.x < bounds.minX + 0.5;
			boolean viewerOnFaceA;
			if (playerInside) {
				Boolean latched = departureFaceLatch.get(record.id);
				if (latched == null) {
					latched = freshFaceA; // lock the face you entered from
					departureFaceLatch.put(record.id, latched);
				}
				viewerOnFaceA = latched;
			} else if (lastInsideMillis.containsKey(record.id) && departureFaceLatch.containsKey(record.id)) {
				viewerOnFaceA = departureFaceLatch.get(record.id); // hold through the exit fade
			} else {
				departureFaceLatch.remove(record.id);
				viewerOnFaceA = freshFaceA;
			}
			// Departure: while the player's body is inside this portal (and it's not a fresh arrival,
			// handled above) push its panorama plane to just in front of the eye — otherwise they walk
			// through the flat render plane and see its back. On stepping clear, ONLY if the plane was still
			// visibly pushed off the surface at that moment (you didn't first back off — the ratchet returns
			// it to the surface as you retreat), FADE the pushed view out then FADE the on-surface view back
			// in "to where it was"; if it was already home, end with no fade. pushAmount 1 = pushed / 0 =
			// surface; departFade scales alpha.
			// Push the box toward the side you entered from (derived from the locked face), NOT your look
			// direction — so turning around keeps the box planted and reveals the overworld behind you.
			float pushSign = viewerOnFaceA ? 1.0F : -1.0F;
			float pushAmount = 0.0F;
			float departFade = 1.0F;
			if (playerInside) {
				pushAmount = 1.0F;
				lastInsideMillis.put(record.id, nowMillis);
				// How far the plane is currently pushed off the surface (blocks): EYE_PUSH deep inside,
				// falling to 0 once you back off enough that the ratchet has returned it to the surface.
				double surfaceNormal = record.axis == Direction.Axis.X ? bounds.minZ() + 0.5 : bounds.minX() + 0.5;
				double eyeNormal = record.axis == Direction.Axis.X ? cameraPos.z : cameraPos.x;
				float surfaceTravel = (float) ((surfaceNormal - eyeNormal) * pushSign);
				lastPushedDist.put(record.id, Math.max(0.0F, EYE_PUSH - surfaceTravel));
			} else {
				Long insideAt = lastInsideMillis.get(record.id);
				Float pushedDist = lastPushedDist.get(record.id);
				// Only fade if the plane was still meaningfully pushed at the moment of exit.
				if (insideAt != null && pushedDist != null && pushedDist >= FADE_MIN_PUSH
						&& nowMillis - insideAt < FADE_OUT_MS + FADE_IN_MS) {
					long sinceInside = nowMillis - insideAt;
					if (sinceInside < FADE_OUT_MS) {
						pushAmount = 1.0F; // hold the pushed plane while its view fades out
						departFade = 1.0F - sinceInside / (float) FADE_OUT_MS;
					} else {
						departFade = (sinceInside - FADE_OUT_MS) / (float) FADE_IN_MS; // fade back in at surface
					}
				} else {
					lastInsideMillis.remove(record.id);
					lastPushedDist.remove(record.id);
				}
			}
			// Encasement: as the teleport swirl (nausea) builds while you stand in the portal, the destination
			// panorama ALSO fades in on the OTHER side, wrapping around you until it fully encases you at the
			// moment of teleport. Fade-IN tracks the swirl (nausea rises slowly); stepping out collapses the
			// swirl so it fades OUT fast.
			float encaseTarget = playerInside && client.player != null
					? Math.max(0.0F, Math.min(1.0F, client.player.nauseaIntensity))
					: 0.0F;
			float encasement = encasementMap.getOrDefault(record.id, 0.0F);
			if (encaseTarget >= encasement) {
				encasement = encaseTarget;
			} else {
				encasement += (encaseTarget - encasement) * ENCASE_FADE_OUT;
				if (encasement < 0.01F) {
					encasement = 0.0F;
				}
			}
			if (encasement <= 0.0F) {
				encasementMap.remove(record.id);
			} else {
				encasementMap.put(record.id, encasement);
			}
			// When that suppression lifts, fade the glimpse back in over ARRIVAL_FADE_MS instead of
			// popping. Only a portal that was actually suppressed carries an entry, so glimpses that
			// were visible all along (e.g. a distant portal) never flicker.
			float arrivalFade = 1.0F;
			Long suppressedAt = lastSuppressedMillis.get(record.id);
			if (suppressedAt != null) {
				long elapsed = nowMillis - suppressedAt;
				if (elapsed >= ARRIVAL_FADE_MS) {
					lastSuppressedMillis.remove(record.id);
				} else {
					arrivalFade = elapsed / (float) ARRIVAL_FADE_MS;
				}
			}

			// Proximity fade (2D postcard only): distance from the camera to the nearest point of
			// the portal plane. The veil is untouched by distance.
			double distance = bounds.distanceTo(cameraPos);
			float fade = 1.0F;
			if (GlimpseSettings.proximityFade) {
				fade = (float) Math.min(1.0, Math.max(0.0,
						(distance - FADE_END) / (FADE_START - FADE_END)));
			}
			int glimpseAlpha = GlimpseSettings.glimpsesVisible
					? Math.round(GLIMPSE_ALPHA * fade * arrivalFade * distanceFade * departFade)
					: 0;
			int veilAlpha = GlimpseSettings.veilAlpha;

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
						viewerOnFaceA, arrivalFade * distanceFade * departFade, pushAmount, pushSign, encasement));
			}
		}

		GlimpseRenderState.sync(client, hiddenPositions);
		if (drawables.isEmpty()) {
			return;
		}

		// Pass 0: the parallax panorama, drawn FIRST and sitting just BEHIND the postcard plane, so
		// the postcard blends over it and fades to reveal it up close (the crossfade, §4.2).
		renderPanoramas(context, store, cameraPos, drawables);

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
			// Flush the veil inside this event too, otherwise its buffer is drained later by vanilla,
			// which under Fabulous graphics runs after the transparency compositing pass (swirl lost).
			if (consumers instanceof VertexConsumerProvider.Immediate veilImmediate) {
				veilImmediate.draw();
			}

		matrices.pop();
	}

	/**
	 * The distance at which this portal's glimpse should be fully gone, using the same rule vanilla
	 * uses for entities: {@code averageSideLength × 64 × entityDistanceScaling}. So the glimpse's reach
	 * scales with the portal's size (a big portal shows from further) and honours the player's Entity
	 * Distance video setting. Capped at the loaded view distance so it never draws out in the fog.
	 */
	private static double entityRenderDistance(Bounds b, MinecraftClient client) {
		double averageSide = ((b.maxX() + 1 - b.minX()) + (b.maxY() + 1 - b.minY())
				+ (b.maxZ() + 1 - b.minZ())) / 3.0;
		double entityDist = averageSide * 64.0 * Entity.getRenderDistanceMultiplier();
		double viewDist = client.options.getClampedViewDistance() * 16.0;
		return Math.min(entityDist, viewDist);
	}

	/** True while the player's body overlaps the given portal's opening (paired with the arrival gate
	 * to pick which portal to hide on teleport). */
	private static boolean playerInsidePortal(MinecraftClient client, Bounds b) {
		if (client.player == null) {
			return false;
		}
		Box portal = new Box(b.minX(), b.minY(), b.minZ(), b.maxX() + 1, b.maxY() + 1, b.maxZ() + 1);
		return client.player.getBoundingBox().intersects(portal);
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
			// Debug override (K): the labeled test cubemap, so face mapping/orientation is readable.
			Identifier[] faces = PanoramaDebug.isTarget(drawable.record().id)
					? PanoramaDebug.FACES
					: PanoramaTextures.get(client, store.baseDir(), drawable.record());
			if (faces != null) {
				panos.add(new PanoDraw(drawable, faces));
			}
		}
		if (panos.isEmpty()) {
			return;
		}

		// Draw in view space via the world pose (which carries the walk view-bob) so the panorama
		// quad bobs WITH the obsidian frame. The shader re-tracks the sample ray through this same
		// bobbed matrix (CamRot below), so the content stays glued to the frame instead of
		// swimming like a held item. Camera-relative vertices land in view space.
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
			// Per-portal interior-mapping uniforms: a sphere centered on the portal (where the panorama
			// was captured). Its radius is chosen (below) so the portal shows a CONSTANT field of view
			// of the destination at every distance — the sphere SHRINKS as you move away — so the view
			// scales with the portal like a real window instead of telephoto-zooming when you back off.
			Bounds b = pano.drawable().bounds();
			float cx = (float) ((b.minX() + b.maxX() + 1) / 2.0 - cameraPos.x);
			float cy = (float) ((b.minY() + b.maxY() + 1) / 2.0 - cameraPos.y);
			float cz = (float) ((b.minZ() + b.maxZ() + 1) / 2.0 - cameraPos.z);
			if (alpha != null) {
				alpha.set(pano.drawable().glimpseFade());
			}
			if (center != null) {
				center.set(cx, cy, cz);
			}
			if (radius != null) {
				// Constant field-of-view: solve for the sphere radius so the portal's own opening (its
				// half-diagonal h, at distance dist) subtends the fixed half-angle FOV of the panorama.
				//   R = h·dist / (dist·sin(FOV) − h·cos(FOV))
				// R shrinks as dist grows (smaller sphere the further away), yet always covers the
				// opening, so the destination scales with the portal instead of telephoto-zooming.
				// dist is the PERPENDICULAR distance to the portal plane, not to the centre point —
				// standing below/beside a tall portal must not inflate it and delay the effect's onset.
				float dist = Math.abs(pano.drawable().record().axis == Direction.Axis.X ? cz : cx);
				float h = 0.5F * (float) Math.sqrt(b.width() * b.width() + b.height() * b.height());
				float fov = (float) Math.toRadians(GlimpseSettings.panoramaFovDegrees);
				float denom = Math.max(dist * (float) Math.sin(fov) - h * (float) Math.cos(fov), h * 0.02F);
				float sphereRadius = h * dist / denom;
				if (pano.drawable().pushAmount() > 0.0F) {
					// The plane is pushed in front of the eye; the constant-FOV sphere collapses at the
					// portal plane (dist->0) and would discard. Grow it so the eye stays inside the sphere
					// and the destination still renders (a skybox from within) while standing in the portal.
					float eyeToCenter = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
					sphereRadius = Math.max(sphereRadius, eyeToCenter + 1.0F);
				}
				radius.set(sphereRadius);
			}

			boolean axisX = pano.drawable().record().axis == Direction.Axis.X;
			boolean faceA = pano.drawable().viewerOnFaceA();
			BufferBuilder buffer = Tessellator.getInstance()
					.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION);
			if (pano.drawable().pushAmount() > 0.0F) {
				emitPanoramaBox(buffer, b, axisX, faceA, cameraPos, pano.drawable().pushAmount(),
						pano.drawable().pushSign());
			} else {
				for (BlockPos pos : pano.drawable().blocks()) {
					emitPanoramaQuad(buffer, pos, axisX, faceA, cameraPos, pano.drawable().pushAmount(),
							pano.drawable().pushSign());
				}
			}
			BuiltBuffer built = buffer.endNullable();
			if (built != null) {
				BufferRenderer.drawWithGlobalProgram(built);
			}

			// Encasement: the destination ALSO wrapping in from the OTHER side (opposite face, pushed the
			// opposite way) as the teleport swirl builds — fading in until it fully surrounds you.
			float encasement = pano.drawable().encasement();
			if (encasement > 0.0F && alpha != null) {
				alpha.set(encasement);
				BufferBuilder encBuffer = Tessellator.getInstance()
						.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION);
				emitPanoramaBox(encBuffer, b, axisX, !faceA, cameraPos, 1.0F, -pano.drawable().pushSign());
				BuiltBuffer encBuilt = encBuffer.endNullable();
				if (encBuilt != null) {
					BufferRenderer.drawWithGlobalProgram(encBuilt);
				}
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
			Vec3d cam, float pushAmount, float pushSign) {
		float y0 = (float) (pos.getY() - cam.y);
		float y1 = (float) (pos.getY() + 1 - cam.y);
		// Push the panorama plane away from the camera (behind the postcard) — faceA looks toward
		// -axis, faceB toward +axis, so "away" flips sign with the face.
		float back = faceA ? PANORAMA_OFFSET : -PANORAMA_OFFSET;
		if (axisX) {
			float surface = (float) ((faceA ? pos.getZ() + PLANE_LOW : pos.getZ() + PLANE_HIGH) + back - cam.z);
			// One-way push: hold at the portal surface until the eye closes within EYE_PUSH, then stay
			// EYE_PUSH ahead. Math.max makes it a ratchet — entering never snaps it toward the face, it
			// only ever recedes; pushAmount eases it back to the surface on exit.
			float ratchet = Math.max(surface * pushSign, EYE_PUSH) * pushSign;
			float plane = surface + (ratchet - surface) * pushAmount;
			float xa = (float) (pos.getX() - cam.x);
			float xb = (float) (pos.getX() + 1 - cam.x);
			buffer.vertex(xa, y1, plane);
			buffer.vertex(xa, y0, plane);
			buffer.vertex(xb, y0, plane);
			buffer.vertex(xb, y1, plane);
		} else {
			float surface = (float) ((faceA ? pos.getX() + PLANE_LOW : pos.getX() + PLANE_HIGH) + back - cam.x);
			float ratchet = Math.max(surface * pushSign, EYE_PUSH) * pushSign;
			float plane = surface + (ratchet - surface) * pushAmount;
			float za = (float) (pos.getZ() - cam.z);
			float zb = (float) (pos.getZ() + 1 - cam.z);
			buffer.vertex(plane, y1, za);
			buffer.vertex(plane, y0, za);
			buffer.vertex(plane, y0, zb);
			buffer.vertex(plane, y1, zb);
		}
	}

	/**
	 * The pushed panorama "box": one back face at the pushed plane plus four perpendicular side walls
	 * down to the portal opening. The whole box is the opening grown by BOX_MARGIN on every side (X ->
	 * X+1), so its edges sit inside the surrounding obsidian: occluded by the frame until the push brings
	 * them forward, then wrapping the player's peripheral view instead of leaving a gap to the outside
	 * world past the edge. POSITION-only; the shader samples the same sphere on every face.
	 */
	private static void emitPanoramaBox(BufferBuilder buffer, Bounds b, boolean axisX, boolean faceA,
			Vec3d cam, float pushAmount, float pushSign) {
		float m = BOX_MARGIN;
		float back = faceA ? PANORAMA_OFFSET : -PANORAMA_OFFSET;
		float y0 = (float) (b.minY() - m - cam.y);
		float y1 = (float) (b.maxY() + 1 + m - cam.y);
		if (axisX) {
			float surface = (float) ((faceA ? b.minZ() + PLANE_LOW : b.minZ() + PLANE_HIGH) + back - cam.z);
			float ratchet = Math.max(surface * pushSign, EYE_PUSH) * pushSign;
			float pushed = surface + (ratchet - surface) * pushAmount;
			float x0 = (float) (b.minX() - m - cam.x);
			float x1 = (float) (b.maxX() + 1 + m - cam.x);
			quad(buffer, x0, y0, pushed, x1, y0, pushed, x1, y1, pushed, x0, y1, pushed); // back face
			if (Math.abs(pushed - surface) >= 1.0e-3F) { // walls only once the push gives real depth
				quad(buffer, x0, y0, surface, x0, y1, surface, x0, y1, pushed, x0, y0, pushed); // left
				quad(buffer, x1, y0, surface, x1, y1, surface, x1, y1, pushed, x1, y0, pushed); // right
				quad(buffer, x0, y0, surface, x1, y0, surface, x1, y0, pushed, x0, y0, pushed); // bottom
				quad(buffer, x0, y1, surface, x1, y1, surface, x1, y1, pushed, x0, y1, pushed); // top
			}
		} else {
			float surface = (float) ((faceA ? b.minX() + PLANE_LOW : b.minX() + PLANE_HIGH) + back - cam.x);
			float ratchet = Math.max(surface * pushSign, EYE_PUSH) * pushSign;
			float pushed = surface + (ratchet - surface) * pushAmount;
			float z0 = (float) (b.minZ() - m - cam.z);
			float z1 = (float) (b.maxZ() + 1 + m - cam.z);
			quad(buffer, pushed, y0, z0, pushed, y0, z1, pushed, y1, z1, pushed, y1, z0); // back face
			if (Math.abs(pushed - surface) >= 1.0e-3F) {
				quad(buffer, surface, y0, z0, surface, y1, z0, pushed, y1, z0, pushed, y0, z0); // near-Z
				quad(buffer, surface, y0, z1, surface, y1, z1, pushed, y1, z1, pushed, y0, z1); // far-Z
				quad(buffer, surface, y0, z0, surface, y0, z1, pushed, y0, z1, pushed, y0, z0); // bottom
				quad(buffer, surface, y1, z0, surface, y1, z1, pushed, y1, z1, pushed, y1, z0); // top
			}
		}
	}

	/** Emit one POSITION-only quad (4 corners a,b,c,d) into the panorama buffer. */
	private static void quad(BufferBuilder buffer,
			float ax, float ay, float az, float bx, float by, float bz,
			float cornerCx, float cornerCy, float cornerCz, float dx, float dy, float dz) {
		buffer.vertex(ax, ay, az);
		buffer.vertex(bx, by, bz);
		buffer.vertex(cornerCx, cornerCy, cornerCz);
		buffer.vertex(dx, dy, dz);
	}

	private record Drawable(PortalRecord record, GlimpseTextures.GlimpseTexture texture,
			List<BlockPos> blocks, Bounds bounds, int glimpseAlpha, int veilAlpha,
			boolean viewerOnFaceA, float glimpseFade, float pushAmount, float pushSign, float encasement) {
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

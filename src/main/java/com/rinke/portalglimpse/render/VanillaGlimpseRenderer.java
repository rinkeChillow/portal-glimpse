package com.rinke.portalglimpse.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;

import com.rinke.portalglimpse.config.UnsupportedShaderScreen;
import com.rinke.portalglimpse.data.PortalRecord;
import com.rinke.portalglimpse.data.PortalStore;
import com.rinke.portalglimpse.detect.PortalDetection;
import com.rinke.portalglimpse.ghost.GhostState;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.entity.Entity;
import net.minecraft.state.property.Properties;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
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

	/** Relight fade: when a broken portal is relit at the same coords, re-show the glimpse SLOWLY (2× the
	 * arrival fade), so a valid portal shows its veil at once but the panorama eases back. */
	private static final long RELIGHT_FADE_MS = 2L * ARRIVAL_FADE_MS;
	/** After (re)lighting, hold the glimpse fully hidden (veil only) for this long before fading it in. */
	private static final long RELIGHT_HOLD_MS = 3000L;
	private static final Set<UUID> brokenPortals = new HashSet<>();
	private static final Map<UUID, Long> relightFadeStart = new HashMap<>();

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
	/** Portal id -> current smoothed player-recede (blocks). {@code remoteReceedeFor} gives a raw, distance-based
	 * TARGET each frame; we ease this toward it so the glimpse GLIDES into/out of the receded box instead of
	 * snapping (or jittering on small position steps), the same way the camera approach feels smooth. Both
	 * directions eased. Render-thread only. */
	private static final Map<UUID, Float> remoteReceedeMap = new HashMap<>();
	/** Per-frame lerp toward the recede target — lower = slower/smoother glide (≈0.3 s to settle at 60 fps). */
	private static final float REMOTE_RECEDE_EASE = 0.15F;

	/** Within this distance the parallax panorama renders on the portal (§4.2 close zone). */
	// Panorama range and the postcard band past it both live in GlimpseSettings so they can be tuned live.

	private static final double PANORAMA_DISTANCE = 32.0;

	/** Width of the postcard's crossfade, in blocks: it is fully visible at
	 * {@code postcardFadeDistance + FADE_WIDTH} and fully gone at {@code postcardFadeDistance}. */
	private static final double FADE_WIDTH = 10.0;

	/** Offset of the veil in front of the glimpse plane, avoiding z-fighting. */
	private static final float VEIL_OFFSET = 0.002F;

	/** Offset of the panorama just BEHIND the postcard plane (postcard sits a hair in front). */
	private static final float PANORAMA_OFFSET = 0.002F;

	/** The pushed box is the portal opening grown by this much on every side (X -> X+1), so its edges sit
	 * inside the surrounding obsidian: hidden by the frame until the push brings them forward, and not
	 * coplanar with the frame's inner faces (which had caused z-fighting). */
	private static final float BOX_MARGIN = 0.5F;
	/** RTT ONLY: a box surface right up against the camera shades/distorts at grazing angles under Iris, so
	 * in the RTT path the box is kept this much further (blocks) in front of the eye than {@link #EYE_PUSH}
	 * — "at least a block clear". The FBO box AND the sampling quad both use it so their screen footprints
	 * stay matched; the overlay/vanilla paths keep {@link #EYE_PUSH}. Tune up (e.g. 1.5) if it still grazes. */
	private static final float EYE_PUSH_RTT = 1.7F;
	/** RTT ONLY: the box grown wider than {@link #BOX_MARGIN} so that, pushed the extra distance out, it
	 * still fills the view. Deliberately NON-integer (0.9, not 1.0): an integer margin lands the box edges
	 * exactly on adjacent block faces (dirt top, obsidian faces) and z-fights them; 0.9 tucks the edges a
	 * tenth of a block inside the frame so they're occluded/non-coplanar instead. */
	private static final float BOX_MARGIN_RTT = 0.9F;
	/** RTT ONLY: how far (blocks) the glimpse box recedes behind a portal a REMOTE player is standing in — an
	 * absolute, player-relative recede (see {@code emitPanoramaBox} fixedRecede) that ignores our own eye, so a
	 * watched player reads as standing inside the glimpse from any distance. Deliberately a touch deeper than the
	 * camera's {@link #EYE_PUSH_RTT} (1.7) — the player wanted the watched-player glimpse to sit a full 2 blocks
	 * back. Doubles as the range over which the recede EASES in as the player approaches (see remoteReceedeFor).
	 * The occluder is skipped for these portals (no black blocks). */
	private static final float REMOTE_RECEDE = 2.0F;
	/** When a solid block sits in front of a portal's base (e.g. grass), the pushed box's downward margin
	 * would clip through it. Instead lift that box's bottom to this height above the opening bottom
	 * (~1.1 above the block's base) so it clears the block: no z-fight, no poke-through. */
	private static final float BOTTOM_LIFT = 0.11F;
	/** Blocks of leeway outside the portal opening before the RTT box folds away (see {@link #rttOpeningGate}). */
	private static final float RTT_GATE_MARGIN = 0.75F;
	/** God-ray occluder (see {@link #buildOccluders}) is present only while the panorama is at least this solid.
	 * The RTT dither snaps fully opaque at ~0.98, so above this there are no holes for the concrete to show
	 * through; below it the occluder drops out and the dissolving panorama reveals the world+rays (not concrete)
	 * — so the occluder "fades" in and out with the portal instead of alpha-fading (terrain can't be translucent
	 * and still write the opaque depth the rays stop at). */
	private static final float OCCLUDER_FADE_MIN = 0.96F;
	/** How far (blocks) the occluder cage grows OUTWARD on the cross-axes at full push, so the all-around
	 * protection wraps the departure box (which grows by {@link #BOX_MARGIN_RTT}) once the player enters. Scales
	 * with the push: 0 when far (a bare plane exactly behind the opening) → this when fully entered. */
	private static final int OCCLUDER_ENTER_GROW = 2;
	/** The occluder is only built when the eye is looking through the opening — {@code max(openingGate, push)}
	 * must reach this. So stepping to the side or above (where the panorama box also folds away) hides the
	 * occluder blocks instead of leaving them poking out behind the portal. */
	private static final float OCCLUDER_GATE_MIN = 0.5F;

	/** Minimum depth (blocks) of the invisible occluder cage behind an occupied portal — deep enough to
	 * clear a player standing 1 block in (plus his body), so the cage never clips him. Grows with the
	 * portal's size (its "diameter") for wider angular coverage. */
	private static final float DEPTH_CAGE_MIN = 2.0F;

	/** In-block positions of the portal plane quads, matching the vanilla portal model. */
	private static final float PLANE_LOW = 6.0F / 16.0F;
	private static final float PLANE_HIGH = 10.0F / 16.0F;

	// Iris shader path (#4): when a shaderpack is active the panorama can't draw in the deferred pass,
	// so renderWorld stashes what to draw (+ the exact camera view & projection of this frame) and
	// renderAfterShaders paints it as an overlay AFTER Iris's composite (driven by the Iris pipeline
	// mixin, since Fabric's LAST event fires BEFORE the composite). Cleared each frame in renderWorld.
	private static List<Drawable> deferredPanos;
	private static Vec3d deferredCam;
	private static Matrix4f deferredView;
	private static Matrix4f deferredProjection;
	private static boolean lastShadersActive;
	/** Read-only identity, passed as the "world pose" when the overlay sets the full view on the stack. */
	private static final Matrix4f IDENTITY = new Matrix4f();
	// RTT stash: drawables + this frame's view/projection, collected at AFTER_TRANSLUCENT. The FBO is
	// rendered POST-composite (renderAfterShaders — where our shader binds and fb-juggling is safe), then
	// drawn onto the portal at AFTER_ENTITIES (the phase Iris captures into its gbuffers). Portals are
	// static, so the 1-frame-old FBO reads fine.
	private static List<Drawable> rttDrawables;
	private static Vec3d rttCam;
	private static Matrix4f rttView;
	private static Matrix4f rttProjection;
	private static boolean rttFboValid;
	// Previous frame's camera view + position, for motion prediction: the FBO baked this frame isn't sampled
	// until NEXT frame's entity pass, so it's rendered from the linearly-extrapolated next-frame camera to
	// cancel the 1-frame lag on smooth motion (the quad then samples with the real current camera).
	private static Matrix4f rttViewPrev;
	private static Vec3d rttCamPrev;

	// Debug: floating 3x3 panorama previews (PanoramaSummonDebug), RTT method only. Built each frame in
	// renderWorld and merged into rttDrawables so they render through the true RTT path (FBO + entity pass,
	// Iris-shaded). Kept out of the real drawable list so they never touch the occluder / block-hiding / veil.
	private static List<Drawable> frameDebugDrawables;

	@Override
	public String name() {
		return "vanilla";
	}

	@Override
	public void renderWorld(WorldRenderContext context) {
		deferredPanos = null; // reset the shader stashes each frame (set below only under shaders)
		rttDrawables = null;
		panoramaSet = java.util.Collections.emptySet(); // recomputed after the frustum cull below
		PortalStore store = PortalDetection.store();
		ClientWorld world = context.world();
		if (store == null || world == null) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		Identifier dimension = world.getRegistryKey().getValue();
		Vec3d cameraPos = context.camera().getPos();

		// Master on/off: when the mod is disabled, unhide the portal blocks and draw nothing, so
		// portals are 100% vanilla (not just the glimpse hidden — the whole overlay is off).
		if (!GlimpseSettings.glimpsesVisible) {
			GlimpseRenderState.clear(client);
			TerrainOverride.clearPortal(client);
			return;
		}

		// Debug 3x3 panorama previews (Ctrl+Shift + RMB) — RTT method only, per request. Built here (even with
		// no real portals) and merged into rttDrawables below so they render through the true RTT path.
		frameDebugDrawables = null;
		if (IrisCompat.shadersActive() && GlimpseSettings.shaderRenderMethod == ShaderRenderMethod.RTT) {
			List<Drawable> dbg = buildDebugDrawables(store, dimension);
			if (!dbg.isEmpty()) {
				frameDebugDrawables = dbg;
			}
		}

		// Collect portals that should show a glimpse this frame.
		List<Drawable> drawables = new ArrayList<>();
		Set<Long> hiddenPositions = new HashSet<>();
		for (PortalRecord record : store.all()) {
			boolean manualActive = record.manual.hasCapture && record.manual.pinned;
			if ((!record.auto.hasCapture && !manualActive) || !record.dimension.equals(dimension)) {
				continue;
			}
			// A portal being ghosted for a capture must not photobomb it (§3.2 step 3).
			if (GhostState.isActive() && GhostState.isHidden(record.interior.get(0))) {
				continue;
			}

			Bounds bounds = Bounds.of(record);

			// Distance visibility + fade, matching vanilla ENTITY render distance (which scales with
			// the object's size — a big portal shows from further, like a big entity). Fade the glimpse
			// out over the last stretch so it's fully gone by the cutoff — no pop when it (un)loads.
			// Ends a fixed distance past where the postcard takes over (see POSTCARD_RANGE), but never beyond
			// what the game would draw an entity of this size at — a low entity-distance setting still wins.
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
			// RTT-only push amount: the box is kept a big EYE_PUSH_RTT (1.7) clear of the eye, so the binary
			// pushAmount above would SNAP the panorama out to ~2 blocks the instant you cross the boundary
			// (invisible at the overlay/vanilla 0.25 push, jarring at 1.7). Instead EASE it with eye proximity
			// to the surface over EYE_PUSH_RTT blocks, so the box has slid out to full clearance by the time you
			// enter. playerInside pins it to 1. Only the RTT paths read this; overlay/vanilla use pushAmount.
			double rttSurfaceN = record.axis == Direction.Axis.X ? bounds.minZ() + 0.5 : bounds.minX() + 0.5;
			double rttEyeN = record.axis == Direction.Axis.X ? cameraPos.z : cameraPos.x;
			// ...but that is a DEPTH-only measure (distance along the portal normal), so standing BESIDE a portal
			// at the same depth still pushed a full box, whose side walls stick out into open air and show the
			// panorama from outside. Gate it on the eye also being within the opening's own footprint — step
			// aside and the push eases to 0, so emitRttQuad falls back to the flat opening plane and there are
			// no walls left to leak. This is the outside mask. playerInside keeps its hard 1 (you're looking
			// straight through, and the eye can sit fractionally outside the frame while inside the portal).
			float rttPushAmount = playerInside ? 1.0F
					: Math.max(0.0F, Math.min(1.0F, 1.0F - (float) Math.abs(rttEyeN - rttSurfaceN) / EYE_PUSH_RTT))
							* rttOpeningGate(bounds, record.axis, cameraPos);
			// RTT + shaders only: as a player whose BODY is drawn (any remote player, or ourselves in 2nd/3rd
			// person) approaches/stands in this portal, recede the glimpse behind them by an absolute amount that
			// EASES with their distance (independent of our own eye) so it slides back as they come closer and
			// they read as standing inside it. rttPushAmount stays LOCAL, so the god-ray occluder (which reads it)
			// is not dragged into a blackout box; buildOccluders also skips these portals. remoteReceedeFor
			// decides WHO counts (it excludes the local player only in first person, where the camera owns them).
			float remoteReceedeTarget = (GlimpseSettings.shaderRenderMethod == ShaderRenderMethod.RTT
					&& GlimpseSettings.entityOverPanorama && IrisCompat.shadersActive())
					? remoteReceedeFor(client, bounds, record.axis) : 0.0F;
			// Ease the recede toward that target over a few frames so it glides in/out instead of snapping —
			// smooths both the flat->box handoff and any small per-frame step in the player's position.
			float remoteRecede = remoteReceedeMap.getOrDefault(record.id, 0.0F);
			remoteRecede += (remoteReceedeTarget - remoteRecede) * REMOTE_RECEDE_EASE;
			if (remoteRecede <= 0.001F && remoteReceedeTarget <= 0.0F) {
				remoteRecede = 0.0F;
				remoteReceedeMap.remove(record.id);
			} else {
				remoteReceedeMap.put(record.id, remoteRecede);
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
						(distance - GlimpseSettings.postcardFadeDistance) / FADE_WIDTH));
			}
			int glimpseAlpha = GlimpseSettings.glimpsesVisible
					? Math.round(GLIMPSE_ALPHA * fade * arrivalFade * distanceFade * departFade)
					: 0;
			// The postcard alpha for a portal with NO panorama behind it (nearest-N-capped, or past PANORAMA_
			// DISTANCE). Two differences from glimpseAlpha: (1) no proximity crossfade — there's no panorama to
			// reveal, so it must not fade out up close; (2) FULLY OPAQUE (255, not the 235 GLIMPSE_ALPHA). The
			// 235 leaves a deliberate ~8% gap that the panorama normally fills; with nothing behind it, that gap
			// shows the real world THROUGH the portal (the "transparent billboard" bug). Opaque makes the
			// postcard self-sufficient, and alpha≥250 also routes it to the depth-writing layer so it occludes
			// like the panorama would. The other fades (arrival/distance/depart) still apply.
			int postcardAlphaNoFade = GlimpseSettings.glimpsesVisible
					? Math.round(255.0F * arrivalFade * distanceFade * departFade)
					: 0;
			int veilAlpha = GlimpseSettings.veilAlphaForStandingIn(dimension);

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
			// Relight fade: track when this portal's blocks go away (broken) and reappear (relit at the
			// same coords → same deterministic id). On the absent→present transition, start a slow fade
			// so the panorama eases back in instead of popping. The veil is untouched (a valid portal
			// shows its swirl immediately).
			boolean hasBlocks = !superseded && !present.isEmpty();
			if (hasBlocks) {
				if (brokenPortals.remove(record.id)) {
					relightFadeStart.put(record.id, nowMillis);
				}
			} else {
				brokenPortals.add(record.id);
			}
			float relightFade = 1.0F;
			Long relitAt = relightFadeStart.get(record.id);
			if (relitAt != null) {
				long relitElapsed = nowMillis - relitAt;
				if (relitElapsed < RELIGHT_HOLD_MS) {
					relightFade = 0.0F; // hold ~3s: only the veil shows, no glimpse yet
				} else {
					long fadeElapsed = relitElapsed - RELIGHT_HOLD_MS;
					if (fadeElapsed >= RELIGHT_FADE_MS) {
						relightFadeStart.remove(record.id);
					} else {
						relightFade = fadeElapsed / (float) RELIGHT_FADE_MS;
					}
				}
			}

			if (hasBlocks) {
				for (BlockPos pos : present) {
					hiddenPositions.add(pos.asLong());
				}
				drawables.add(new Drawable(record, texture, present, bounds, glimpseAlpha, postcardAlphaNoFade,
						veilAlpha, viewerOnFaceA, arrivalFade * distanceFade * departFade * relightFade,
						pushAmount, rttPushAmount, pushSign, encasement, remoteRecede));
			}
		}

		GlimpseRenderState.sync(client, hiddenPositions);

		// Toggling an Iris shaderpack re-meshes every chunk, which can un-hide a portal (no set-diff to
		// trigger a rebuild), so re-mesh the hidden region whenever the shader state flips.
		boolean shaders = IrisCompat.shadersActive();
		if (shaders != lastShadersActive) {
			lastShadersActive = shaders;
			GlimpseRenderState.reschedule(client);
		}

		// Recognise the active shaderpack and pick up its RTT calibration. Logs on every swap; once per swap,
		// if we have no tuning for it, put the prompt in the player's face (RTT only — the other paths don't use
		// the calibration). It deliberately INTERRUPTS whatever screen is open: pack swaps happen inside the
		// shaderpack menu, so refusing to stomp would mean never showing it when it matters. Whatever it
		// interrupted becomes its parent, so closing the prompt puts the player back where they were. Deferred
		// via execute() because we're mid-render here; skipped only if our own prompt is already up (no re-entry).
		if (shaders && ShaderPackCalibration.noteCurrentPack()
				&& GlimpseSettings.shaderRenderMethod == ShaderRenderMethod.RTT
				&& !ShaderPackCalibration.lastSeenSupported()) {
			String unsupported = ShaderPackCalibration.packName().orElse("?");
			client.execute(() -> {
				if (client.player != null && !(client.currentScreen instanceof UnsupportedShaderScreen)) {
					client.setScreen(new UnsupportedShaderScreen(client.currentScreen, unsupported));
				}
			});
		}

		// GOD-RAY OCCLUDER (RTT + shaders only): inject a hollow cage of opaque terrain wrapping the destination
		// side of each portal's panorama box, so it writes the gbuffer depth the shaderpack's volumetric march
		// reads — the sun's rays then stop there instead of shining through, exactly like a hand-placed block.
		// The cage tracks the RTT push (depth) and the portal's fade (presence). Not during a capture
		// (GhostState) and not on the overlay path (its panorama draws post-composite, over the rays already).
		// See buildOccluders / TerrainOverride.
		boolean rttOccluders = shaders && GlimpseSettings.godRayOccluder
				&& GlimpseSettings.shaderRenderMethod == ShaderRenderMethod.RTT
				&& !GhostState.isActive();
		Map<Long, BlockState> overrides = rttOccluders
				? buildOccluders(world, cameraPos, drawables) : Map.of();

		// TERRAIN VEIL (RTT + shaders): put the REAL nether-portal state back into the chunk mesh at the portal's
		// own blocks, so the shaderpack sees an actual portal block and applies its portal rules — emission,
		// bloom, the whole look it gives a vanilla portal. A quad can never get this: packs key those rules on
		// the block id in `mc_Entity`, which only exists in the TERRAIN pass, so entity-layer geometry can at
		// best pick which PROGRAM it lands in (the beacon-beam trick), never which BLOCK it claims to be.
		//
		// This costs nothing extra to draw: the blocks are hidden by GlimpseRenderState and TerrainOverride
		// takes precedence over that hiding in the mesh hooks, so injecting the same state at the same
		// positions simply un-hides them — through a per-frame channel we control. Being translucent terrain,
		// the pack composites the swirl AFTER the deferred pass, i.e. over our panorama (which draws in the
		// opaque gbuffer stage), which is the layering we want.
		if (shaders && GlimpseSettings.shaderVeil
				&& GlimpseSettings.shaderRenderMethod == ShaderRenderMethod.RTT
				&& GlimpseSettings.rttVeilMode == RttVeilMode.TERRAIN && !GhostState.isActive()) {
			Map<Long, BlockState> merged = new HashMap<>(overrides);
			for (Drawable drawable : drawables) {
				// Carry the portal's own axis — the default (X) would face a Z-axis portal's swirl edge-on,
				// i.e. invisible from where the player actually stands.
				BlockState veilState = Blocks.NETHER_PORTAL.getDefaultState()
						.with(Properties.HORIZONTAL_AXIS, drawable.record().axis);
				for (BlockPos pos : drawable.blocks()) {
					// Last write wins over any occluder that landed here: the swirl belongs on the opening.
					merged.put(pos.asLong(), veilState);
				}
			}
			overrides = merged;
		}
		TerrainOverride.syncPortal(client, overrides);

		// FRUSTUM CULL — for the DRAWING only. Everything above (block-hiding via hiddenPositions, the god-ray
		// occluders, the terrain veil) deliberately used the FULL in-range set: those drive chunk-mesh state, and
		// culling them by view would un-hide / re-inject portal blocks the instant you turned away, flapping chunk
		// re-meshes every frame. Here we drop portals outside the view frustum from the actual panorama/veil draw
		// + RTT tessellation (the expensive part) — a portal behind you no longer renders. Box padded a couple of
		// blocks so the receded glimpse box / veil offset near a screen edge doesn't pop.
		Frustum frustum = context.frustum();
		// DEBUG (Numpad /): an optional SHRUNKEN cull cone, decoupled from the real view — portals outside this
		// narrow angle are culled while still on your normal-FOV screen, so the frustum cull is observable.
		float debugCullFov = GlimpseSettings.debugCullFovDegrees;
		double debugCosHalf = debugCullFov > 0.0F ? Math.cos(Math.toRadians(debugCullFov / 2.0)) : -2.0;
		double lookX = 0, lookY = 0, lookZ = 0;
		if (debugCullFov > 0.0F) {
			double yawR = Math.toRadians(context.camera().getYaw());
			double pitchR = Math.toRadians(context.camera().getPitch());
			lookX = -Math.sin(yawR) * Math.cos(pitchR);
			lookY = -Math.sin(pitchR);
			lookZ = Math.cos(yawR) * Math.cos(pitchR);
		}
		if (frustum != null || debugCullFov > 0.0F) {
			List<Drawable> visible = new ArrayList<>(drawables.size());
			for (Drawable d : drawables) {
				Bounds bb = d.bounds();
				boolean vis = frustum == null || frustum.isVisible(new Box(bb.minX() - 2, bb.minY() - 2,
						bb.minZ() - 2, bb.maxX() + 3, bb.maxY() + 3, bb.maxZ() + 3));
				if (vis && debugCullFov > 0.0F) {
					double dx = (bb.minX() + bb.maxX() + 1) / 2.0 - cameraPos.x;
					double dy = (bb.minY() + bb.maxY() + 1) / 2.0 - cameraPos.y;
					double dz = (bb.minZ() + bb.maxZ() + 1) / 2.0 - cameraPos.z;
					double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
					if (len > 1.0e-3 && (dx * lookX + dy * lookY + dz * lookZ) / len < debugCosHalf) {
						vis = false; // portal centre is outside the narrow debug cone
					}
				}
				if (vis) {
					visible.add(d);
				}
			}
			drawables = visible;
		}

		// NEAREST-N — the parallax panorama is the expensive draw (a per-portal ray-march + RTT tessellation), so
		// cap it to the closest maxPanoramas portals within range; the rest fall back to the flat postcard (kept
		// at full opacity). Computed on the frustum-culled (visible) set, so slots aren't spent on off-screen
		// portals. Shared via the panoramaSet field with every render path (renderPanoramas, emitRttQuad, the
		// postcard emitters), including the 1-frame-lagged RTT stash below.
		int cap = Math.max(0, GlimpseSettings.maxPanoramas);
		if (drawables.size() <= cap) {
			Set<UUID> all = new HashSet<>();
			for (Drawable d : drawables) {
				if (d.bounds().distanceTo(cameraPos) <= PANORAMA_DISTANCE) {
					all.add(d.record().id);
				}
			}
			panoramaSet = all;
		} else {
			List<Drawable> byDist = new ArrayList<>(drawables);
			byDist.sort(Comparator.comparingDouble(d -> d.bounds().distanceTo(cameraPos)));
			Set<UUID> nearest = new HashSet<>();
			for (Drawable d : byDist) {
				if (nearest.size() >= cap) {
					break;
				}
				if (d.bounds().distanceTo(cameraPos) <= PANORAMA_DISTANCE) {
					nearest.add(d.record().id);
				}
			}
			panoramaSet = nearest;
		}

		if (drawables.isEmpty() && frameDebugDrawables == null) {
			return; // nothing visible; the RTT/overlay stashes were already reset to null at the top of the frame
		}

		// Under an Iris shaderpack our custom-shader panorama can't render in this deferred pass (Iris
		// owns the fragment stage). Stash it and bail — renderAfterShaders draws the panorama AND veil as
		// a post-composite overlay instead. The block-hiding above already ran, so the vanilla swirl is
		// gone. Both shader paths draw the postcard too (RTT via emitRttPostcard, overlay via
		// drawShaderPostcards) — it's the baseline glimpse whenever a portal has no panorama that frame.
		if (shaders) {
			if (GlimpseSettings.shaderRenderMethod == ShaderRenderMethod.RTT) {
				// RTT: the FBO is rendered post-composite (renderAfterShaders) and drawn onto the portal
				// at AFTER_ENTITIES (renderInEntityPass, the phase Iris captures). Stash this frame's data.
				// Debug preview panoramas ride the same list so they get the real RTT (Iris-shaded) treatment.
				if (frameDebugDrawables != null) {
					List<Drawable> combined = new ArrayList<>(drawables);
					combined.addAll(frameDebugDrawables);
					rttDrawables = combined;
				} else {
					rttDrawables = drawables;
				}
				rttCam = cameraPos;
				rttView = new Matrix4f(RenderSystem.getModelViewStack())
						.mul(context.matrixStack().peek().getPositionMatrix());
				rttProjection = new Matrix4f(context.projectionMatrix());
			} else {
				// OVERLAY: stash and draw post-composite (see renderAfterShaders). Capture this frame's exact
				// transform so the overlay lands identically: base view (on the stack now) × world pose.
				deferredPanos = drawables;
				deferredCam = cameraPos;
				deferredView = new Matrix4f(RenderSystem.getModelViewStack())
						.mul(context.matrixStack().peek().getPositionMatrix());
				deferredProjection = new Matrix4f(context.projectionMatrix());
			}
			return;
		}

		// Pass 0: the parallax panorama, drawn FIRST and sitting just BEHIND the postcard plane, so
		// the postcard blends over it and fades to reveal it up close (the crossfade, §4.2).
		renderPanoramas(context.matrixStack().peek().getPositionMatrix(), store, cameraPos, drawables, false);

		MatrixStack matrices = context.matrixStack();
		matrices.push();
		matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
		MatrixStack.Entry entry = matrices.peek();
		VertexConsumerProvider consumers = context.consumers();
		VertexConsumerProvider.Immediate immediate =
				consumers instanceof VertexConsumerProvider.Immediate imm ? imm : null;
		boolean anyAffected = false;
		for (Drawable drawable : drawables) {
			if (PortalEntityMask.isAffected(drawable.record().id)) {
				anyAffected = true;
				break;
			}
		}

		// Pass 1: the glimpses. Flushed to the framebuffer before the veils are emitted so the veil always
		// composites OVER the glimpse (§4.3 "a ghost dancing over the glimpse"). A portal a player is
		// standing in is drawn in a SECOND flush WITHOUT depth-write, so the player (re-rendered afterwards
		// at real depth) isn't occluded by its own glimpse while real geometry still is.
		for (Drawable drawable : drawables) {
			if (!PortalEntityMask.isAffected(drawable.record().id)) {
				emitGlimpse(entry, consumers, drawable);
			}
		}
		if (immediate != null) {
			immediate.draw();
		}
		if (anyAffected) {
			for (Drawable drawable : drawables) {
				if (PortalEntityMask.isAffected(drawable.record().id)) {
					emitGlimpse(entry, consumers, drawable);
				}
			}
			if (immediate != null) {
				RenderSystem.depthMask(false);
				immediate.draw();
				RenderSystem.depthMask(true);
			}
		}

		// Pass 2: the veils — the living vanilla portal sprite, animation and resource packs intact. Same
		// affected/non-affected split as the glimpse so a portal's swirl doesn't occlude the player in it.
		Sprite portalSprite = client.getBlockRenderManager().getModels()
				.getModelParticleSprite(Blocks.NETHER_PORTAL.getDefaultState());
		// Under Sodium the real portal blocks are hidden from its mesh, so it stops animating the
		// nether-portal sprite (it only ticks sprites it sees rendered). Keep the veil's swirl alive.
		SodiumCompat.markSpriteActive(portalSprite);
		RenderLayer veilLayer = RenderLayer.getItemEntityTranslucentCull(portalSprite.getAtlasId());
		for (Drawable drawable : drawables) {
			if (!PortalEntityMask.isAffected(drawable.record().id)) {
				emitVeil(entry, consumers.getBuffer(veilLayer), drawable, portalSprite);
			}
		}
		// Flush the veil inside this event too, otherwise its buffer is drained later by vanilla, which
		// under Fabulous graphics runs after the transparency compositing pass (swirl lost).
		if (immediate != null) {
			immediate.draw();
		}
		if (anyAffected) {
			for (Drawable drawable : drawables) {
				if (PortalEntityMask.isAffected(drawable.record().id)) {
					emitVeil(entry, consumers.getBuffer(veilLayer), drawable, portalSprite);
				}
			}
			if (immediate != null) {
				RenderSystem.depthMask(false);
				immediate.draw();
				RenderSystem.depthMask(true);
			}
		}

		matrices.pop();

		// Pass 3: players standing just behind a portal's plane, re-rendered OVER the finished glimpse
		// (panorama + veil) so they read as standing IN the destination (§ pt.14). The entity pass already
		// skipped their normal render (WorldRendererMixin); this consumes what it collected.
		renderNearPlayers(context, cameraPos);
	}

	/**
	 * Iris shader path (#4): draws the panorama AFTER the shaderpack has composited the world (from
	 * {@code WorldRenderEvents.LAST}), as an overlay. Our custom {@code portal_panorama} shader runs here
	 * unmolested (Iris is done for the frame), depth-testing against the finished scene so real blocks in
	 * front of the portal still occlude the glimpse. The trade-off is that the glimpse doesn't receive the
	 * shaderpack's lighting/fog — acceptable for a captured photo of another dimension. No-op unless
	 * renderWorld stashed panos this frame (which only happens when a shaderpack is active).
	 */
	/** Build one 3×3 panorama {@link Drawable} per {@link PanoramaSummonDebug} summon: a vertical grid of nine
	 * air-position quads floating one block above the clicked block, in the FIXED cardinal orientation captured
	 * at summon time (so it keeps its direction rather than swivelling to the camera), all showing the same
	 * chosen glimpse. Empty when the tool is off or nothing is captured for this dimension. Kept out of the
	 * real drawable list on purpose. */
	private static List<Drawable> buildDebugDrawables(PortalStore store, Identifier dimension) {
		if (!PanoramaSummonDebug.isActive()) {
			return List.of();
		}
		PortalRecord chosen = PanoramaSummonDebug.chosen(store, dimension);
		if (chosen == null) {
			return List.of();
		}
		List<Drawable> out = new ArrayList<>();
		for (Map.Entry<BlockPos, PanoramaSummonDebug.Summon> e : PanoramaSummonDebug.summons().entrySet()) {
			BlockPos p = e.getKey();
			boolean axisX = e.getValue().axisX();
			boolean faceA = e.getValue().faceA();
			int baseY = p.getY() + 1; // one block above the clicked block
			List<BlockPos> blocks = new ArrayList<>(9);
			for (int i = -1; i <= 1; i++) {
				for (int j = 0; j < 3; j++) {
					blocks.add(axisX
							? new BlockPos(p.getX() + i, baseY + j, p.getZ())
							: new BlockPos(p.getX(), baseY + j, p.getZ() + i));
				}
			}
			Bounds b = Bounds.of(blocks);
			// Reuse the chosen record's id/version/slots so PanoramaTextures loads its glimpse; override the
			// geometry (axis + interior) to our floating 3×3. No push/veil — a flat preview quad.
			PortalRecord rec = new PortalRecord(chosen.id, dimension,
					new BlockPos(b.minX(), b.minY(), b.minZ()), blocks,
					axisX ? Direction.Axis.X : Direction.Axis.Z,
					chosen.auto, chosen.manual, chosen.linkedId, chosen.createdAt, chosen.updatedAt);
			out.add(new Drawable(rec, null, blocks, b, 255, 255, 0, faceA, 1.0F, 0.0F, 0.0F,
					faceA ? 1.0F : -1.0F, 0.0F, 0.0F));
		}
		return out;
	}

	@Override
	public void renderAfterShaders() {
		// RTT method: render the panorama INTO the offscreen FBO here (post-composite), where our custom
		// shader binds correctly and framebuffer juggling is safe (Iris's pipeline is done for the frame).
		// renderInEntityPass then draws that FBO onto the portal so Iris shades it. (Doing the FBO render in
		// the entity pass produced a void — Iris intercepts our shader there and the raw fb rebind corrupts
		// its target.) rttDrawables is only set (this frame) when the RTT method is active.
		if (rttDrawables != null) {
			renderRttToFbo();
			return;
		}

		List<Drawable> panos = deferredPanos;
		Vec3d cam = deferredCam;
		Matrix4f view = deferredView;
		Matrix4f projection = deferredProjection;
		deferredPanos = null;
		if (panos == null || panos.isEmpty() || view == null || projection == null) {
			return;
		}
		PortalStore store = PortalDetection.store();
		if (store == null) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		// Iris has finished compositing: the main framebuffer now holds the shaded image AND the scene
		// depth (Iris preserves it), so draw onto it with depth-test for correct occlusion. Set this
		// frame's exact view on the stack (composite left ortho matrices behind), draw, restore.
		client.getFramebuffer().beginWrite(false);
		RenderSystem.backupProjectionMatrix();
		RenderSystem.setProjectionMatrix(projection, RenderSystem.getVertexSorting());
		Matrix4fStack modelView = RenderSystem.getModelViewStack();
		modelView.pushMatrix();
		modelView.identity();
		modelView.mul(view);
		RenderSystem.applyModelViewMatrix();

		renderPanoramas(IDENTITY, store, cam, panos, false); // view already on the stack; identity world-pose
		drawShaderPostcards(client, cam, panos);      // the flat postcard, over the panorama (the shader floor)
		if (GlimpseSettings.shaderVeil) {
			drawShaderVeil(client, cam, panos);       // our custom swirl, over both — off by default under shaders
		}

		modelView.popMatrix();
		RenderSystem.applyModelViewMatrix();
		RenderSystem.restoreProjectionMatrix();
	}

	/**
	 * Draws the flat postcard in the Iris overlay pass — the "postcard floor" the shader path was missing.
	 *
	 * <p>The overlay path used to draw the panorama and the veil and nothing else, so any portal without a
	 * panorama that frame rendered NO glimpse at all: just the swirl over a hidden portal block. That is the
	 * common case, not a corner case — {@link #PANORAMA_DISTANCE} caps the panorama at 32 blocks while
	 * portals keep rendering out to {@link #entityRenderDistance}, and nearest-N caps it further. The RTT
	 * path has covered this since {@code emitRttPostcard}; this brings the overlay in line.
	 *
	 * <p>Reuses {@link #emitGlimpse} outright rather than an overlay-specific variant: this draw lands
	 * POST-composite, where Iris is finished and nothing will shade it, so the plain
	 * {@code getItemEntityTranslucentCull} routing the vanilla path uses is already correct here. (The RTT
	 * path needs its own emitter only because its geometry goes through Iris's gbuffers and has to take the
	 * unlit beacon-beam routing to match the panorama.) That reuse also means the alpha rule comes along for
	 * free: a portal WITH a panorama gets the proximity crossfade, one WITHOUT gets the opaque
	 * {@code postcardAlphaNoFade} floor.
	 */
	private static void drawShaderPostcards(MinecraftClient client, Vec3d cameraPos,
			List<Drawable> drawables) {
		VertexConsumerProvider.Immediate immediate = client.getBufferBuilders().getEntityVertexConsumers();
		MatrixStack matrices = new MatrixStack();
		matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
		MatrixStack.Entry entry = matrices.peek();
		for (Drawable drawable : drawables) {
			emitGlimpse(entry, immediate, drawable);
		}
		immediate.draw(); // flush before the veil buffers so the swirl composites over the postcard
	}

	/**
	 * Draws the veil (the living nether-portal swirl) over the panorama in the Iris overlay pass, using an
	 * immediate vertex-consumer + this frame's stashed view. Mirrors the vanilla veil pass but without the
	 * WorldRenderContext (unavailable post-composite). The swirl's animation is kept alive via {@link
	 * SodiumCompat}, same as the vanilla path.
	 */
	private static void drawShaderVeil(MinecraftClient client, Vec3d cameraPos, List<Drawable> drawables) {
		Sprite portalSprite = client.getBlockRenderManager().getModels()
				.getModelParticleSprite(Blocks.NETHER_PORTAL.getDefaultState());
		SodiumCompat.markSpriteActive(portalSprite);
		RenderLayer veilLayer = RenderLayer.getItemEntityTranslucentCull(portalSprite.getAtlasId());
		VertexConsumerProvider.Immediate immediate = client.getBufferBuilders().getEntityVertexConsumers();
		MatrixStack matrices = new MatrixStack();
		matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
		MatrixStack.Entry entry = matrices.peek();
		for (Drawable drawable : drawables) {
			emitVeil(entry, immediate.getBuffer(veilLayer), drawable, portalSprite);
		}
		immediate.draw();
	}

	// --- RTT path (shader-lit): the panorama is rendered to an offscreen texture POST-composite
	// (renderRttToFbo, where our custom shader binds), then drawn (with the veil) as normal geometry in the
	// ENTITY phase so Iris captures it into its gbuffers and lights it. The FBO is one frame old (rendered
	// last frame's post-composite) but portals are static, so it reads fine. ---
	private static Framebuffer rttFbo;
	private static boolean rttTexRegistered;
	private static final Identifier RTT_TEX_ID = Identifier.of("portal-glimpse", "rtt_panorama");
	// TEMP DIAGNOSTIC #1 (block-atlas test): CONFIRMED — the atlas rendered on the portal, so geometry, UVs,
	// entity-pass draw, and Iris gbuffer capture all work; the FBO comes back empty. Flag now off.
	private static final boolean DEBUG_RTT_KNOWN_TEXTURE = false;
	// TEMP DIAGNOSTIC #2 (magenta-clear test): sample the REAL FBO, but clear it to OPAQUE MAGENTA before the
	// panorama render, to split why the FBO is empty:
	//   - portal shows MAGENTA (maybe with the panorama over it) => the FBO IS sampleable; if there's no
	//        panorama on the magenta, renderPanoramas isn't drawing into the offscreen buffer.
	//   - portal shows NOTHING (not even magenta)               => this specific FBO texture isn't sampleable
	//        (color-attachment format / filter / mipmap-completeness), unlike the atlas.
	private static final boolean DEBUG_RTT_SOLID_CLEAR = false;

	/** Post-composite: render the panorama into our offscreen FBO with our own shader (which binds cleanly
	 * only now that Iris's pipeline is done). renderInEntityPass draws that FBO onto the portal next. */
	private void renderRttToFbo() {
		List<Drawable> drawables = rttDrawables;
		Matrix4f projection = rttProjection;
		rttFboValid = false;
		if (drawables == null || drawables.isEmpty() || rttView == null || projection == null) {
			return;
		}
		// Linearly extrapolate the camera one frame forward (new Matrix4f(prev).lerp(cur, 2) = 2·cur − prev)
		// so the FBO — sampled next frame — is rendered from where the camera will be, cancelling the 1-frame
		// lag under smooth motion. The projection carries the walk-bob, so it is NOT extrapolated (that would
		// amplify bob jitter). First frame (no history) falls back to the current view/pos.
		float predict = GlimpseSettings.rttMotionPrediction; // 1.0 = none, 2.0 = full one-frame
		Matrix4f view = rttViewPrev != null ? new Matrix4f(rttViewPrev).lerp(rttView, predict) : rttView;
		Vec3d cam = rttCamPrev != null
				? rttCamPrev.add(rttCam.subtract(rttCamPrev).multiply(predict)) : rttCam;
		rttViewPrev = new Matrix4f(rttView);
		rttCamPrev = rttCam;
		PortalStore store = PortalDetection.store();
		if (store == null) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		Framebuffer main = client.getFramebuffer();
		int w = main.textureWidth;
		int h = main.textureHeight;
		if (rttFbo == null) {
			rttFbo = new SimpleFramebuffer(w, h, true, MinecraftClient.IS_SYSTEM_MAC);
		} else if (rttFbo.textureWidth != w || rttFbo.textureHeight != h) {
			rttFbo.resize(w, h, MinecraftClient.IS_SYSTEM_MAC);
		}
		if (!rttTexRegistered) {
			client.getTextureManager().registerTexture(RTT_TEX_ID, new FboWrapTexture(rttFbo));
			rttTexRegistered = true;
		}

		if (DEBUG_RTT_SOLID_CLEAR) {
			rttFbo.setClearColor(1.0F, 0.0F, 1.0F, 1.0F); // opaque magenta (diagnostic — see DEBUG_RTT_SOLID_CLEAR)
		} else {
			rttFbo.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
		}
		rttFbo.clear(MinecraftClient.IS_SYSTEM_MAC);
		rttFbo.beginWrite(true);
		RenderSystem.backupProjectionMatrix();
		RenderSystem.setProjectionMatrix(projection, RenderSystem.getVertexSorting());
		Matrix4fStack modelView = RenderSystem.getModelViewStack();
		modelView.pushMatrix();
		modelView.identity();
		modelView.mul(view);
		RenderSystem.applyModelViewMatrix();
		// The offscreen buffer has no scene geometry to occlude against, and its cleared depth value need not
		// match the pipeline's depth convention (Iris may use reversed-Z) — so force the depth test to ALWAYS
		// pass. Otherwise every panorama fragment is rejected against the cleared depth and the FBO stays empty
		// (the glClear still lands, which is why the buffer read back as a solid clear colour). renderPanoramas
		// enables the depth test internally but never sets the func, so this sticks through the draw.
		RenderSystem.depthFunc(GL11.GL_ALWAYS);
		renderPanoramas(IDENTITY, store, cam, drawables, true); // RTT: dissolve the fade (opaque gbuffer)
		RenderSystem.depthFunc(GL11.GL_LEQUAL);
		modelView.popMatrix();
		RenderSystem.applyModelViewMatrix();
		RenderSystem.restoreProjectionMatrix();
		main.beginWrite(false); // back to the main framebuffer
		if (GlimpseSettings.debugRttBlit) {
			// DEBUG (Numpad 3): paint the whole offscreen buffer over the screen so its raw contents are
			// visible directly — independent of the portal quad's sampling. All-magenta => the panorama
			// render produced nothing; a sphere anywhere => it rendered but is misaligned with the quad UVs.
			rttFbo.draw(main.textureWidth, main.textureHeight);
		}
		rttFboValid = true;
	}

	@Override
	public void renderInEntityPass(WorldRenderContext context) {
		if (!IrisCompat.shadersActive() || GlimpseSettings.shaderRenderMethod != ShaderRenderMethod.RTT
				|| !rttFboValid) {
			return;
		}
		List<Drawable> drawables = rttDrawables;
		// Sample with the REAL current-frame camera (not the stashed frame-N one) — the FBO was rendered from
		// the predicted current-frame camera (see renderRttToFbo), so this is what the panorama lines up with.
		Vec3d cameraPos = context.camera().getPos();
		VertexConsumerProvider consumers = context.consumers();
		if (drawables == null || drawables.isEmpty() || consumers == null) {
			return;
		}
		// Draw the FBO-textured portal quad (screen-space UVs) via a vanilla RenderType, so Iris patches it
		// into its gbuffers and shades it. No FBO render / framebuffer juggling here — that happens
		// post-composite (renderRttToFbo); doing it here corrupted Iris's target and voided the draw.
		// The veil follows in pass 2 below, gated on GlimpseSettings.rttVeil.
		//
		// The FBO is a SCREEN-SPACE image, so to lock the panorama to the portal each fragment must sample the
		// FBO at its OWN current on-screen position — i.e. the UVs use THIS frame's transform (the one that
		// actually rasterizes the quad), computed before the camera-relative translate below so it matches the
		// geometry path exactly. Using the FBO's stashed matrices instead made the whole view slide/tilt with
		// head motion (the sampled position diverged from the quad's real screen position). Only the 1-frame
		// FBO content lag remains, which is imperceptible when not whipping the camera.
		Matrix4f mvp = new Matrix4f(context.projectionMatrix())
				.mul(new Matrix4f(RenderSystem.getModelViewStack())
						.mul(context.matrixStack().peek().getPositionMatrix()));
		MatrixStack matrices = context.matrixStack();
		matrices.push();
		matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
		MatrixStack.Entry entry = matrices.peek();

		Identifier rttTex = DEBUG_RTT_KNOWN_TEXTURE
				? Identifier.of("minecraft", "textures/atlas/blocks.png") // known-resident, opaque swatches
				: RTT_TEX_ID;
		// UNLIT ROUTING: draw the panorama through the BEACON-BEAM render type. Iris maps it to
		// `gbuffers_beaconbeam`, which both shaderpacks render WITHOUT any surface shading:
		//   BSL  (program/gbuffers_beaconbeam.glsl): `gl_FragData[0] = albedo;` — the texture goes straight to
		//        the colour buffer; no diffuse, no AO, no shadows, and it writes no normal/material buffers.
		//   Photon (include/vertex/utility.glsl, get_material_mask): the beaconbeam program returns material 32
		//        "full emissive" and forces light_levels.x = 1.0.
		// So every face of the box is shaded identically and the pack's AO can't crease the seams. Kept
		// translucent so the FBO's transparent pixels still reveal-behind (the box masking relies on it).
		// Flip RTT_UNLIT to compare against the old lit routing.
		// Our own layer: the unlit beacon-beam program (no AO / no per-face shading) with translucent blending
		// (so the fade dither reveals what's behind instead of going black). Depth is written ONLY while this
		// portal's glimpse is opaque — see PortalRenderLayers.COLOR_ONLY: depth isn't blended, so writing it for
		// transparent fragments stamps the portal plane over whatever shows through and the pack's deferred pass
		// then renders that flat/unshaded. Chosen per portal, since each has its own fade.
		for (Drawable drawable : drawables) {
			if (!rendersPanorama(drawable)) {
				continue; // nearest-N: capped portal shows only the postcard (no FBO content to sample here)
			}
			// FLAT-DEPTH mode hands the colour pass no depth at all; the flat plane below supplies it instead.
			boolean writeDepth = drawable.glimpseFade() >= OCCLUDER_FADE_MIN
					&& !GlimpseSettings.debugRttNoDepthWrite
					&& !GlimpseSettings.rttFlatDepth;
			RenderLayer layer = RTT_UNLIT
					? PortalRenderLayers.unlitGlimpse(rttTex, writeDepth)
					: RenderLayer.getItemEntityTranslucentCull(rttTex);
			emitRttQuad(entry, consumers.getBuffer(layer), drawable, cameraPos, mvp);
		}
		// FLAT DEPTH: a second, colour-less pass that stamps a FLAT plane across each opening into the depth
		// buffer. A pack whose AO reads only depth (Solas) reconstructs its normals from what it finds there —
		// so with the box's real concave corners replaced by one flat wall, there is no crease to draw, while
		// the colour above stays the full parallax box. Everything remains in the OPAQUE stage, which is what
		// the translucent-pass experiment had to give up: reflections still see the glimpse, and the world
		// behind the portal is still occluded instead of showing through.
		if (GlimpseSettings.rttFlatDepth) {
			for (Drawable drawable : drawables) {
				if (!rendersPanorama(drawable) || drawable.glimpseFade() < OCCLUDER_FADE_MIN) {
					continue; // mid-fade the glimpse deliberately writes no depth at all (see COLOR_ONLY)
				}
				VertexConsumer dvc = consumers.getBuffer(PortalRenderLayers.glimpseDepthOnly(rttTex));
				Bounds b = drawable.bounds();
				boolean axisX = drawable.record().axis == Direction.Axis.X;
				for (BlockPos pos : drawable.blocks()) {
					emitFace(entry, dvc, pos, b, axisX, drawable.viewerOnFaceA(), 255,
							255, 255, 255, 0.0F, 0.0F, 1.0F, 1.0F, 0.0F);
				}
			}
		}
		VertexConsumerProvider.Immediate immediate =
				consumers instanceof VertexConsumerProvider.Immediate imm ? imm : null;
		if (immediate != null) {
			immediate.draw(); // flush the panorama FIRST so the postcard blends OVER it (the §4.2 crossfade)
		}
		// Pass 1: the postcard — the flat captured face, sitting a hair in front of the panorama plane and
		// fading out with proximity to reveal it. Same crossfade the vanilla path does; here it rides the same
		// unlit routing as the panorama so the two match while cross-fading.
		for (Drawable drawable : drawables) {
			emitRttPostcard(entry, consumers, drawable);
		}
		if (immediate != null) {
			immediate.draw();
		}
		// Pass 2: the VEIL — the living swirl, drawn over the panorama exactly as the vanilla and overlay paths
		// do it (same atlas sprite, so resource packs and the animation come along for free). This was removed
		// while the panorama quad was debugged on its own and never put back, which left the RTT path — the one
		// actually in use under shaders — with no swirl at all.
		//
		// It goes through the ORDINARY translucent entity layer rather than the unlit beacon-beam routing the
		// panorama uses: the panorama is a photo and must not be shaded, but the veil SHOULD be, because a
		// shaderpack treating this bright quad as an emissive light is precisely what lit the obsidian frame.
		// Whether that survives Iris's opaque gbuffer stage is the open question — see GlimpseSettings.rttVeil.
		if (GlimpseSettings.shaderVeil && GlimpseSettings.rttVeilMode == RttVeilMode.QUAD) {
			MinecraftClient client = MinecraftClient.getInstance();
			Sprite portalSprite = client.getBlockRenderManager().getModels()
					.getModelParticleSprite(Blocks.NETHER_PORTAL.getDefaultState());
			SodiumCompat.markSpriteActive(portalSprite); // Sodium only ticks sprites it sees rendered
			RenderLayer veilLayer = RenderLayer.getItemEntityTranslucentCull(portalSprite.getAtlasId());
			for (Drawable drawable : drawables) {
				emitVeil(entry, consumers.getBuffer(veilLayer), drawable, portalSprite);
			}
			if (immediate != null) {
				immediate.draw();
			}
		}
		matrices.pop();
	}

	/**
	 * The RTT counterpart of {@link #emitGlimpse}: the flat postcard drawn on the portal face, cover-fit and
	 * single-face, fading with proximity so the parallax panorama shows through up close.
	 *
	 * <p>Differences from the vanilla path, so it "respects" RTT: it goes through
	 * {@link PortalRenderLayers#unlitGlimpse} rather than {@code getItemEntityTranslucentCull}, so the pack
	 * leaves it unshaded exactly like the panorama behind it (otherwise the postcard would be lit and the
	 * panorama not, and the crossfade would visibly shift brightness), and it carries the same
	 * per-pack counter-dim ({@link #rttUnlitDim}). Depth is written only while it's essentially opaque, for the same
	 * reason as the panorama (see {@code PortalRenderLayers.COLOR_ONLY}).
	 */
	private static void emitRttPostcard(MatrixStack.Entry entry, VertexConsumerProvider consumers,
			Drawable drawable) {
		// A nearest-N-capped portal has no panorama behind the postcard, so it must not fade out up close —
		// show the postcard at its un-faded alpha instead of the crossfade one.
		int alpha = rendersPanorama(drawable) ? drawable.glimpseAlpha() : drawable.postcardAlphaNoFade();
		if (alpha <= 0 || drawable.texture() == null) {
			return; // fully faded by proximity — the panorama alone is the glimpse here
		}
		Identifier texture = drawable.viewerOnFaceA()
				? drawable.texture().faceA()
				: drawable.texture().faceB();
		if (texture == null) {
			return;
		}
		RenderLayer layer = RTT_UNLIT
				? PortalRenderLayers.unlitGlimpse(texture, alpha >= 250
						&& !GlimpseSettings.debugRttNoDepthWrite)
				: RenderLayer.getItemEntityTranslucentCull(texture);
		VertexConsumer vc = consumers.getBuffer(layer);
		int tint = RTT_UNLIT ? Math.round(255 * rttUnlitDim()) : 255;
		Bounds b = drawable.bounds();
		boolean axisX = drawable.record().axis == Direction.Axis.X;
		for (BlockPos pos : drawable.blocks()) {
			emitFace(entry, vc, pos, b, axisX, drawable.viewerOnFaceA(), alpha,
					tint, tint, tint, 0.0F, 0.0F, 1.0F, 1.0F, 0.0F);
		}
	}

	/** Grid subdivisions per axis for the RTT quad (see {@link #emitRttQuad}). */
	private static final int RTT_TESS = 24;
	/** Draw the RTT panorama through the beacon-beam render type so Iris routes it to the shaderpacks' UNLIT
	 * `gbuffers_beaconbeam` program — killing AO and all per-face surface shading. See renderInEntityPass. */
	private static final boolean RTT_UNLIT = true;
	/** Counter-dim for the unlit routing, now supplied PER SHADERPACK — each pack's beaconbeam applies its own
	 * emissive maths to our texture, so one constant can't serve them all. See {@link ShaderPackCalibration}.
	 * Only consulted when {@link #RTT_UNLIT}. */
	private static float rttUnlitDim() {
		return ShaderPackCalibration.currentOrFallback().unlitDim();
	}

	/**
	 * Emits the portal-opening plane as an {@link #RTT_TESS}×{@code RTT_TESS} grid of small quads, each corner
	 * carrying its own on-screen position as the UV, so the quad samples the offscreen panorama render 1:1.
	 *
	 * <p>Why a grid and not one quad: screen-space UV = clip.xy/clip.w is a <em>projective</em> (non-linear)
	 * function of world position, but a RenderLayer interpolates UVs per triangle using the quad's own W. Across
	 * a big perspective quad the two triangles disagree along the diagonal (a visible seam) and the image warps.
	 * Subdividing keeps W near-constant per cell, so the affine interpolation is ~exact — the standard fix when
	 * the fragment shader can't be touched (here it's Iris's, so we can't). The blit looks perfect for the same
	 * reason a full-screen quad has constant W.
	 */
	private static void emitRttQuad(MatrixStack.Entry entry, VertexConsumer vc, Drawable drawable,
			Vec3d cam, Matrix4f mvp) {
		Bounds b = drawable.bounds();
		boolean axisX = drawable.record().axis == Direction.Axis.X;
		boolean faceA = drawable.viewerOnFaceA();
		// While the player stands in the portal, emit the full pushed BOX (back face + 4 walls). Run the SAME
		// shared box logic the FBO uses (emitPanoramaBox — masking + block-in-front lift included) through a
		// sink that tessellates each quad with screen-space UVs, converting the box's camera-relative corners
		// back to world for emitRttVertex. Identical geometry to the FBO ⇒ exact 1:1 sampling, no leaks.
		if (drawable.rttPushAmount() > 0.0F || drawable.remoteRecede() > 0.0F) {
			ClientWorld world = MinecraftClient.getInstance().world;
			QuadSink sink = (ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz) -> emitRttTessQuad(entry, vc, cam, mvp,
					new float[] {ax + (float) cam.x, ay + (float) cam.y, az + (float) cam.z},
					new float[] {bx + (float) cam.x, by + (float) cam.y, bz + (float) cam.z},
					new float[] {cx + (float) cam.x, cy + (float) cam.y, cz + (float) cam.z},
					new float[] {dx + (float) cam.x, dy + (float) cam.y, dz + (float) cam.z});
			emitPanoramaBox(sink, b, axisX, faceA, cam, drawable.rttPushAmount(), drawable.pushSign(), world,
					EYE_PUSH_RTT, BOX_MARGIN_RTT, drawable.remoteRecede());
			// ENCASEMENT — the destination wrapping in from the OTHER side (opposite face, pushed the opposite
			// way) as the teleport swirl builds, so it has closed around you by the moment of travel. The FBO
			// already bakes this box (see renderPanoramas), but the sampling quad used to cover only the box in
			// FRONT — so on the RTT path those encasement pixels had nothing to show through and the wrap-around
			// was simply invisible. Mirrors the FBO's parameters one-for-one, because screen-space sampling is
			// only 1:1 when both geometries agree exactly. Coverage is all this adds: the FBO's own alpha still
			// owns the fade-in (a dither dissolve here, per the RTT fade split).
			if (drawable.encasement() > 0.0F) {
				emitPanoramaBox(sink, b, axisX, !faceA, cam, 1.0F, -drawable.pushSign(), world,
						EYE_PUSH_RTT, BOX_MARGIN_RTT, 0.0F);
			}
			return;
		}
		// Not pushed: the flat opening plane, tessellated. Offset BEHIND the postcard by PANORAMA_OFFSET, exactly
		// as the FBO's own emitPanoramaQuad does (faceA looks toward -axis, faceB toward +axis, so "away from
		// the viewer" flips sign with the face). Two reasons this must match the FBO: the postcard sits on the
		// bare plane, so without the offset the two quads are COPLANAR and z-fight (which also swallows the
		// crossfade — you see them flicker instead of one dissolving into the other); and the RTT quad samples
		// the FBO by SCREEN position, so sharing the FBO's exact world plane keeps that sampling 1:1.
		float planeFrac = faceA ? PLANE_LOW : PLANE_HIGH;
		float back = faceA ? PANORAMA_OFFSET : -PANORAMA_OFFSET;
		float plane = (axisX ? (b.minZ() + planeFrac) : (b.minX() + planeFrac)) + back;
		float hMin = axisX ? b.minX() : b.minZ();
		float hMax = axisX ? (b.maxX() + 1) : (b.maxZ() + 1);
		float yMin = b.minY();
		float yMax = b.maxY() + 1;
		int n = RTT_TESS;
		for (int gy = 0; gy < n; gy++) {
			float y0 = yMin + (yMax - yMin) * gy / n;
			float y1 = yMin + (yMax - yMin) * (gy + 1) / n;
			for (int gx = 0; gx < n; gx++) {
				float h0 = hMin + (hMax - hMin) * gx / n;
				float h1 = hMin + (hMax - hMin) * (gx + 1) / n;
				// Cell corners mapped to world coords for this axis, emitted in both windings so the culling
				// layer keeps whichever faces the camera (winding doesn't affect the screen-space UV).
				emitRttCell(entry, vc, cam, mvp, axisX, plane, h0, h1, y0, y1);
			}
		}
	}

	/** Tessellate an arbitrary quad (corners p00→p10 = u, p00→p01 = v) into a grid of screen-space-UV cells
	 * (both windings). Bilinear-interpolates the corners; keeps W near-constant per cell so the screen-space
	 * UVs interpolate ~correctly (see {@link #emitRttQuad}). Subdivision count adapts to the quad's world
	 * size (≈2 cells/block, capped at {@link #RTT_TESS}) so small masked wall-cells don't explode. */
	private static void emitRttTessQuad(MatrixStack.Entry entry, VertexConsumer vc, Vec3d cam, Matrix4f mvp,
			float[] p00, float[] p10, float[] p11, float[] p01) {
		float uLen = dist(p00, p10), vLen = dist(p00, p01);
		int nu = Math.max(1, Math.min(RTT_TESS, Math.round(uLen * 2.0F)));
		int nv = Math.max(1, Math.min(RTT_TESS, Math.round(vLen * 2.0F)));
		emitRttTessGrid(entry, vc, cam, mvp, p00, p10, p11, p01, nu, nv);
	}

	private static float dist(float[] a, float[] b) {
		float dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
		return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	private static void emitRttTessGrid(MatrixStack.Entry entry, VertexConsumer vc, Vec3d cam, Matrix4f mvp,
			float[] p00, float[] p10, float[] p11, float[] p01, int nu, int nv) {
		for (int gy = 0; gy < nv; gy++) {
			float va = (float) gy / nv, vb = (float) (gy + 1) / nv;
			for (int gx = 0; gx < nu; gx++) {
				float ua = (float) gx / nu, ub = (float) (gx + 1) / nu;
				float[] c00 = bilerp(p00, p10, p11, p01, ua, va);
				float[] c10 = bilerp(p00, p10, p11, p01, ub, va);
				float[] c11 = bilerp(p00, p10, p11, p01, ub, vb);
				float[] c01 = bilerp(p00, p10, p11, p01, ua, vb);
				for (int pass = 0; pass < 2; pass++) {
					float[][] order = pass == 0
							? new float[][] {c00, c10, c11, c01}
							: new float[][] {c01, c11, c10, c00};
					for (float[] c : order) {
						emitRttVertex(entry, vc, cam, mvp, c[0], c[1], c[2]);
					}
				}
			}
		}
	}

	/** Bilinear interpolation of a quad's four corners at (u, v). */
	private static float[] bilerp(float[] p00, float[] p10, float[] p11, float[] p01, float u, float v) {
		float[] r = new float[3];
		for (int i = 0; i < 3; i++) {
			float a = p00[i] + (p10[i] - p00[i]) * u;
			float c = p01[i] + (p11[i] - p01[i]) * u;
			r[i] = a + (c - a) * v;
		}
		return r;
	}

	/** One tessellation cell of the RTT quad, emitted in both windings. */
	private static void emitRttCell(MatrixStack.Entry entry, VertexConsumer vc, Vec3d cam, Matrix4f mvp,
			boolean axisX, float plane, float h0, float h1, float y0, float y1) {
		float[][] c = new float[4][3];
		rttCorner(c, 0, axisX, plane, h0, y1);
		rttCorner(c, 1, axisX, plane, h0, y0);
		rttCorner(c, 2, axisX, plane, h1, y0);
		rttCorner(c, 3, axisX, plane, h1, y1);
		for (int pass = 0; pass < 2; pass++) {
			for (int i = 0; i < 4; i++) {
				float[] w = c[pass == 0 ? i : 3 - i];
				emitRttVertex(entry, vc, cam, mvp, w[0], w[1], w[2]);
			}
		}
	}

	private static void rttCorner(float[][] c, int i, boolean axisX, float plane, float h, float y) {
		if (axisX) {
			c[i][0] = h;
			c[i][1] = y;
			c[i][2] = plane;
		} else {
			c[i][0] = plane;
			c[i][1] = y;
			c[i][2] = h;
		}
	}

	/** Emits one RTT vertex whose UV is its own on-screen (NDC→[0,1]) position, V-flipped for the bottom-up FBO. */
	private static void emitRttVertex(MatrixStack.Entry entry, VertexConsumer vc, Vec3d cam, Matrix4f mvp,
			float wx, float wy, float wz) {
		Vector4f clip = new Vector4f((float) (wx - cam.x), (float) (wy - cam.y), (float) (wz - cam.z), 1.0F);
		mvp.transform(clip);
		float u = 0.5F;
		float v = 0.5F;
		if (clip.w > 1.0e-4F) {
			u = clip.x / clip.w * 0.5F + 0.5F;
			v = clip.y / clip.w * 0.5F + 0.5F; // framebuffer texture is bottom-up: NDC top (+1) → v=1 (fb top)
		}
		// Vertex colour doubles as the brightness dial on the unlit path (albedo = texture * color in both
		// packs) — see rttUnlitDim(), which is per-shaderpack. Full white on the normal lit path, as before.
		int tint = RTT_UNLIT ? Math.round(255 * rttUnlitDim()) : 255;
		vertex(entry, vc, wx, wy, wz, u, v, 255, tint, tint, tint);
	}

	/**
	 * Re-renders the players collected by {@link PortalEntityMask} at their REAL depth, after the portal's
	 * own overlay passes (panorama/postcard/veil) have drawn WITHOUT writing depth for these portals (see
	 * {@link PortalEntityMask#isAffected}). So the player composites over the glimpse (painter's order) yet
	 * is depth-tested against the real world — real blocks in front occlude the player, and the real
	 * obsidian frame clips them exactly to the opening — the "standing in the destination" look.
	 */
	private static void renderNearPlayers(WorldRenderContext context, Vec3d cameraPos) {
		List<PortalEntityMask.NearPlayer> near = PortalEntityMask.consume();
		if (near.isEmpty()) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		float tickDelta = client.getRenderTickCounter().getTickDelta(true);
		EntityRenderDispatcher dispatcher = client.getEntityRenderDispatcher();
		MatrixStack matrices = context.matrixStack();
		VertexConsumerProvider.Immediate immediate = client.getBufferBuilders().getEntityVertexConsumers();

		for (PortalEntityMask.NearPlayer np : near) {
			PlayerEntity player = np.player();
			double x = MathHelper.lerp((double) tickDelta, player.lastRenderX, player.getX()) - cameraPos.x;
			double y = MathHelper.lerp((double) tickDelta, player.lastRenderY, player.getY()) - cameraPos.y;
			double z = MathHelper.lerp((double) tickDelta, player.lastRenderZ, player.getZ()) - cameraPos.z;
			float yaw = MathHelper.lerp(tickDelta, player.prevYaw, player.getYaw());
			int light = dispatcher.getLight(player, tickDelta);
			dispatcher.render(player, x, y, z, yaw, tickDelta, matrices, immediate, light);
			immediate.draw();
		}
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
	 * The EASED player-relative recede for a portal: how far (blocks) the RTT glimpse should sit behind it for
	 * the nearest REMOTE player approaching/standing in its opening. Ramps smoothly with the player's distance
	 * to the plane — 0 beyond {@link #REMOTE_RECEDE} blocks, growing to the full {@link #REMOTE_RECEDE} as they
	 * reach it — so the glimpse slides back as they come closer rather than snapping (the camera path eases the
	 * same way off our own eye). Only players within the opening footprint count, so someone walking past to the
	 * side doesn't move it. The nearest qualifying player wins.
	 */
	private static float remoteReceedeFor(MinecraftClient client, Bounds b, Direction.Axis axis) {
		if (client.world == null) {
			return 0.0F;
		}
		// In FIRST person the local player isn't drawn and the camera sits at their eye, so the camera system
		// (rttPushAmount) owns them — skip them here. In 2nd/3rd person their BODY is drawn while the camera is
		// offset, so the camera push no longer lines up with the body and clips it; there they must be treated
		// like any other player and have the glimpse recede off their body, so we DO count them.
		boolean localHandledByCamera = client.options.getPerspective().isFirstPerson();
		boolean axisX = axis == Direction.Axis.X;
		double surfaceN = axisX ? b.minZ() + 0.5 : b.minX() + 0.5; // plane along the normal
		double latMin = axisX ? b.minX() : b.minZ();
		double latMax = axisX ? (b.maxX() + 1) : (b.maxZ() + 1);
		double yMin = b.minY();
		double yMax = b.maxY() + 1;
		float best = 0.0F;
		for (var p : client.world.getPlayers()) {
			if (p == client.player && localHandledByCamera) {
				continue; // first person: the camera system (rttPushAmount) handles us, not this one
			}
			double lat = axisX ? p.getX() : p.getZ();
			double footY = p.getY();
			double headY = footY + p.getHeight();
			if (lat < latMin - 0.5 || lat > latMax + 0.5 || headY < yMin - 0.5 || footY > yMax + 0.5) {
				continue; // outside the opening silhouette — not "in" this portal
			}
			double normal = axisX ? p.getZ() : p.getX();
			float ease = (float) Math.max(0.0, Math.min(1.0, 1.0 - Math.abs(normal - surfaceN) / REMOTE_RECEDE));
			best = Math.max(best, ease);
		}
		return best * REMOTE_RECEDE;
	}

	/**
	 * Draws the cubemap panorama on each nearby portal via the {@code portal_panorama} shader. Each
	 * fragment's view ray (its camera-relative position) selects and samples one of the six faces,
	 * so the view shifts with real perspective as the player moves.
	 */
	/** Whether this drawable renders the parallax panorama this frame: it's in the nearest-N set, or it's a
	 * summoned debug preview (no postcard fallback, always renders). Capped portals render only the postcard. */
	private static boolean rendersPanorama(Drawable d) {
		return panoramaSet.contains(d.record().id)
				|| (frameDebugDrawables != null && frameDebugDrawables.contains(d));
	}

	private static void renderPanoramas(Matrix4f worldPose, PortalStore store, Vec3d cameraPos,
			List<Drawable> drawables, boolean ditherFade) {
		ShaderProgram shader = PortalShaders.panorama();
		if (shader == null) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();

		List<PanoDraw> panos = new ArrayList<>();
		for (Drawable drawable : drawables) {
			if (!rendersPanorama(drawable)) {
				continue; // nearest-N: capped portals fall back to the postcard (see panoramaSet)
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
		modelView.mul(worldPose);
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
		GlUniform dither = shader.getUniform("DitherFade");
		if (dither != null) {
			dither.set(ditherFade ? 1.0F : 0.0F); // RTT dissolves the fade; blended paths use smooth alpha
		}
		// Gamma pre-compensation is only meaningful when a shaderpack is going to run its own curve over this
		// texture, i.e. the RTT path (ditherFade doubles as that signal, as it does for the box constants).
		// The overlay and non-shader paths composite the panorama as-is and must stay untouched at 1.0.
		GlUniform gamma = shader.getUniform("RttGamma");
		if (gamma != null) {
			gamma.set(ditherFade ? ShaderPackCalibration.currentOrFallback().gamma() : 1.0F);
		}

		for (PanoDraw pano : panos) {
			// A portal a player is standing in skips depth-write, so the player (re-rendered afterwards at
			// real depth) isn't occluded by its own panorama — real blocks in front still occlude the player.
			RenderSystem.depthMask(!PortalEntityMask.isAffected(pano.drawable().record().id));
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
			// RTT keeps the box a block further out (and wider) so it doesn't graze/distort against the eye
			// under Iris; the sampling quad (emitRttBox) uses the same values so the footprints match.
			float eyePush = ditherFade ? EYE_PUSH_RTT : EYE_PUSH;
			float boxMargin = ditherFade ? BOX_MARGIN_RTT : BOX_MARGIN;
			// RTT uses the proximity-EASED push so the box slides out smoothly; overlay/vanilla use the binary
			// one. Both the FBO box (here) and the sampling quad (emitRttQuad) must read the same value.
			float push = ditherFade ? pano.drawable().rttPushAmount() : pano.drawable().pushAmount();
			float remoteRecede = ditherFade ? pano.drawable().remoteRecede() : 0.0F;
			if (push > 0.0F || remoteRecede > 0.0F) {
				emitPanoramaBox(bufferSink(buffer), b, axisX, faceA, cameraPos, push,
						pano.drawable().pushSign(), client.world, eyePush, boxMargin, remoteRecede);
			} else {
				for (BlockPos pos : pano.drawable().blocks()) {
					emitPanoramaQuad(buffer, pos, axisX, faceA, cameraPos, push,
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
				emitPanoramaBox(bufferSink(encBuffer), b, axisX, !faceA, cameraPos, 1.0F, -pano.drawable().pushSign(),
						client.world, eyePush, boxMargin, 0.0F);
				BuiltBuffer encBuilt = encBuffer.endNullable();
				if (encBuilt != null) {
					BufferRenderer.drawWithGlobalProgram(encBuilt);
				}
			}
		}

		// Depth-only occluder cages for portals a player is standing in. Those portals drew their panorama
		// WITHOUT writing depth (so the player isn't occluded by his own glimpse), which also stopped them
		// occluding the background — clouds and other portals' glimpses would bleed through. Seal each such
		// portal's volume with an invisible box (open front, grown into the obsidian, extending into the
		// destination): color-masked so nothing is drawn, but its back + four walls write depth, so the
		// background is occluded from ANY angle while the open front still shows the panorama and the player.
		boolean anyCage = false;
		for (PanoDraw pano : panos) {
			if (PortalEntityMask.isAffected(pano.drawable().record().id)) {
				anyCage = true;
				break;
			}
		}
		if (anyCage) {
			RenderSystem.setShader(GameRenderer::getPositionProgram);
			RenderSystem.colorMask(false, false, false, false);
			RenderSystem.depthMask(true);
			for (PanoDraw pano : panos) {
				if (!PortalEntityMask.isAffected(pano.drawable().record().id)) {
					continue;
				}
				boolean axisX = pano.drawable().record().axis == Direction.Axis.X;
				boolean faceA = pano.drawable().viewerOnFaceA();
				BufferBuilder buffer = Tessellator.getInstance()
						.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION);
				emitDepthCage(buffer, pano.drawable().bounds(), axisX, faceA, cameraPos);
				BuiltBuffer built = buffer.endNullable();
				if (built != null) {
					BufferRenderer.drawWithGlobalProgram(built);
				}
			}
			RenderSystem.colorMask(true, true, true, true);
		}

		RenderSystem.depthMask(true); // restore for the later passes (a portal above may have cleared it)
		RenderSystem.enableCull();
		modelView.popMatrix();
		RenderSystem.applyModelViewMatrix();
	}

	/**
	 * Emits the invisible depth-cage box for one occupied portal: the opening grown by {@link #BOX_MARGIN}
	 * into the obsidian, open at the front, extending {@code depth} blocks into the destination (away from
	 * the viewer). Back face + four side walls (no front) as POSITION-only quads, camera-relative. Cull is
	 * already disabled, so winding is irrelevant.
	 */
	private static void emitDepthCage(BufferBuilder buffer, Bounds b, boolean axisX, boolean faceA, Vec3d cam) {
		QuadSink quad = bufferSink(buffer);
		float m = BOX_MARGIN;
		float depth = Math.max(DEPTH_CAGE_MIN, Math.max(b.width(), b.height()));
		float dest = faceA ? 1.0F : -1.0F; // destination side = away from the viewer
		float y0 = (float) (b.minY() - m - cam.y);
		float y1 = (float) (b.maxY() + 1 + m - cam.y);
		if (axisX) {
			float front = (float) (b.minZ() + 0.5 - cam.z);
			float back = front + depth * dest;
			float x0 = (float) (b.minX() - m - cam.x);
			float x1 = (float) (b.maxX() + 1 + m - cam.x);
			quad.quad(x0, y1, back, x0, y0, back, x1, y0, back, x1, y1, back); // back
			quad.quad(x0, y1, front, x1, y1, front, x1, y1, back, x0, y1, back); // top
			quad.quad(x0, y0, front, x1, y0, front, x1, y0, back, x0, y0, back); // bottom
			quad.quad(x0, y1, front, x0, y0, front, x0, y0, back, x0, y1, back); // left
			quad.quad(x1, y1, front, x1, y0, front, x1, y0, back, x1, y1, back); // right
		} else {
			float front = (float) (b.minX() + 0.5 - cam.x);
			float back = front + depth * dest;
			float z0 = (float) (b.minZ() - m - cam.z);
			float z1 = (float) (b.maxZ() + 1 + m - cam.z);
			quad.quad(back, y1, z0, back, y0, z0, back, y0, z1, back, y1, z1); // back
			quad.quad(front, y1, z0, front, y1, z1, back, y1, z1, back, y1, z0); // top
			quad.quad(front, y0, z0, front, y0, z1, back, y0, z1, back, y0, z0); // bottom
			quad.quad(front, y1, z0, front, y0, z0, back, y0, z0, back, y1, z0); // near-Z
			quad.quad(front, y1, z1, front, y0, z1, back, y0, z1, back, y1, z1); // far-Z
		}
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
	 * Lateral gate for the RTT box: 1 while the eye is within the portal opening's own footprint (the two axes
	 * ACROSS the portal — its width and its height), easing to 0 over {@link #RTT_GATE_MARGIN} blocks as the eye
	 * moves outside it.
	 *
	 * <p>{@code rttPushAmount} is otherwise driven purely by depth along the portal normal, which says nothing
	 * about whether you are actually looking THROUGH the opening. Standing beside a portal therefore still built
	 * the full pushed box, and its side walls — running up to {@link #EYE_PUSH_RTT} blocks out the back into open
	 * air — rendered the panorama for anyone viewing from outside. Folding the push to 0 out there drops the
	 * walls entirely (the flat opening plane is drawn instead), which is what masks the outside.
	 */
	private static float rttOpeningGate(Bounds b, Direction.Axis axis, Vec3d cam) {
		// axis X ⇒ normal runs along Z, so the portal spans X (width) and Y; axis Z ⇒ normal along X, spans Z and Y.
		double hLo, hHi, hEye;
		if (axis == Direction.Axis.X) {
			hLo = b.minX();
			hHi = b.maxX() + 1;
			hEye = cam.x;
		} else {
			hLo = b.minZ();
			hHi = b.maxZ() + 1;
			hEye = cam.z;
		}
		return Math.min(gateAxis(hEye, hLo, hHi), gateAxis(cam.y, b.minY(), b.maxY() + 1));
	}

	/** 1 inside [lo,hi], easing linearly to 0 over {@link #RTT_GATE_MARGIN} blocks outside it. */
	private static float gateAxis(double c, double lo, double hi) {
		double d = Math.max(lo - c, c - hi); // > 0 only once the eye is outside the span
		if (d <= 0.0) {
			return 1.0F;
		}
		if (d >= RTT_GATE_MARGIN) {
			return 0.0F;
		}
		return 1.0F - (float) (d / RTT_GATE_MARGIN);
	}

	/**
	 * Build the RTT god-ray occluder: for each portal showing a solid glimpse, a hollow cage of the
	 * {@link OccluderBlock} (injected as terrain via {@link TerrainOverride}) tucked BEHIND the panorama on the
	 * destination side — a back plane plus one-block-thick perimeter side walls. It writes the opaque gbuffer
	 * depth a shaderpack's volumetric march stops at, so the sun's rays stop on it instead of shining through.
	 *
	 * <p>Its shape tracks the departure push, so it does two jobs:
	 * <ul>
	 *   <li><b>Far / idle</b> (push ≈ 0): a bare 1-block-thick plane sized to the OPENING exactly, one block
	 *       directly behind the portal blocks. The opaque panorama (which covers the opening on screen) hides
	 *       it, so there is no visible box — it just protects the flat glimpse from the sun.</li>
	 *   <li><b>Entering</b> (push &gt; 0, the glimpse slides back and the box grows): the plane grows into the
	 *       all-around cage — deeper ({@code 1 + round(push·EYE_PUSH_RTT)}) AND wider on the cross-axes
	 *       ({@code round(push·OCCLUDER_ENTER_GROW)}) — so it wraps the expanding, {@link #BOX_MARGIN_RTT}-grown
	 *       departure box and blocks the rays from every side.</li>
	 * </ul>
	 *
	 * <p>Never replaces real blocks (skips occupied positions — they already occlude). {@link OccluderBlock}
	 * culls like glass, so growing/shrinking/removing it never leaves holes in the surrounding terrain. Skipped
	 * below {@link #OCCLUDER_FADE_MIN} so it fades in and out with the portal (see that field). It can't be made
	 * truly invisible — catching the rays REQUIRES an opaque depth-writing surface — but the far plane hides
	 * behind the panorama, and the visible cage only appears once you're entering (where it reads as intended).
	 */
	private static Map<Long, BlockState> buildOccluders(ClientWorld world, Vec3d cam, List<Drawable> drawables) {
		if (drawables.isEmpty() || OccluderBlock.INSTANCE == null || world == null) {
			return Map.of();
		}
		BlockState occluder = OccluderBlock.INSTANCE.getDefaultState();
		Map<Long, BlockState> out = new HashMap<>();
		BlockPos.Mutable probe = new BlockPos.Mutable();
		for (Drawable d : drawables) {
			// Fade with the portal: only occlude while the panorama is essentially opaque (no dither holes).
			if (d.glimpseFade() < OCCLUDER_FADE_MIN) {
				continue;
			}
			// A portal receded behind a REMOTE player we're watching gets no occluder: we're not entering it, so
			// there are no god rays to shield, and an occluder placed at our own (shallow) depth would sit in
			// front of the deep player-relative recede and black it out. This is why no black blocks appear.
			if (d.remoteRecede() > 0.0F) {
				continue;
			}
			Bounds b = d.bounds();
			boolean axisX = d.record().axis == Direction.Axis.X;
			float push = d.rttPushAmount();
			// Outside mask: only show the occluder when the eye is looking through the opening (openingGate) OR
			// pushing in — otherwise (viewing from the side / above) hide it so the blocks don't poke out behind
			// the portal, the same fold-away the panorama box gets.
			if (Math.max(rttOpeningGate(b, d.record().axis, cam), push) < OCCLUDER_GATE_MIN) {
				continue;
			}
			int sign = d.viewerOnFaceA() ? 1 : -1; // destination side (away from viewer), behind the panorama
			int dep = 1 + Math.round(push * EYE_PUSH_RTT);       // depth: 1 when far → deeper on entry
			int grow = Math.round(push * OCCLUDER_ENTER_GROW);   // width: 0 when far → all-around on entry
			// Cross-axes across the portal: U (X for axisX, else Z) and Y; the opening rectangle grown by `grow`.
			int uMin = (axisX ? b.minX() : b.minZ()) - grow;
			int uMax = (axisX ? b.maxX() : b.maxZ()) + grow;
			int yMin = b.minY() - grow;
			int yMax = b.maxY() + grow;
			int normalBase = axisX ? b.minZ() : b.minX(); // portal plane along the normal axis
			for (int depth = 1; depth <= dep; depth++) {
				int n = normalBase + sign * depth;
				boolean back = depth == dep; // deepest layer is a full plane; the layers before it are side walls
				for (int u = uMin; u <= uMax; u++) {
					for (int y = yMin; y <= yMax; y++) {
						boolean perimeter = u == uMin || u == uMax || y == yMin || y == yMax;
						if (!back && !perimeter) {
							continue; // hollow: the panorama box lives inside; only walls + back cap it
						}
						int wx = axisX ? u : n;
						int wz = axisX ? n : u;
						if (!world.getBlockState(probe.set(wx, y, wz)).isAir()) {
							continue; // never replace real blocks (they already occlude anyway)
						}
						out.put(probe.asLong(), occluder);
					}
				}
			}
		}
		return out;
	}

	/** True if any solid block sits along a portal edge's frame line, one block out on the destination
	 * side — a block that would poke through the pushed box on that side. Axes 0=X,1=Y,2=Z: the edge frame
	 * sits at frameAxis=frameCoord, the destination neighbour at normalAxis=normalCoord, scanned along
	 * spanAxis from spanMin..spanMax. */
	private static boolean sideObstructed(ClientWorld world, int frameAxis, int frameCoord,
			int normalAxis, int normalCoord, int spanAxis, int spanMin, int spanMax) {
		BlockPos.Mutable pos = new BlockPos.Mutable();
		int[] bp = new int[3];
		bp[frameAxis] = frameCoord;
		bp[normalAxis] = normalCoord;
		for (int s = spanMin; s <= spanMax; s++) {
			bp[spanAxis] = s;
			if (world.getBlockState(pos.set(bp[0], bp[1], bp[2])).isSolidBlock(world, pos)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The pushed panorama "box": one back face at the pushed plane plus four perpendicular side walls
	 * down to the portal opening, grown by BOX_MARGIN on every side so its edges sit inside the obsidian.
	 * Any side with a solid block in front of its frame (sideObstructed) has that edge pushed INWARD to
	 * clear the block and its wall MASKED per-cell so the obsidian ledge stays exposed while other blocks
	 * stay covered. POSITION-only; the shader samples the same sphere on every face.
	 */

	private static void emitPanoramaBox(QuadSink sink, Bounds b, boolean axisX, boolean faceA,
			Vec3d cam, float pushAmount, float pushSign, ClientWorld world, float eyePush, float m,
			float fixedRecede) {
		float back = faceA ? PANORAMA_OFFSET : -PANORAMA_OFFSET;
		if (axisX) {
			float surface = (float) ((faceA ? b.minZ() + PLANE_LOW : b.minZ() + PLANE_HIGH) + back - cam.z);
			// Normally the box recedes only as far as the ratchet keeps it clear of THE CAMERA's eye. A
			// fixedRecede overrides that with an absolute recede toward the destination — used when a REMOTE
			// player stands in the portal: the glimpse must fall back behind THEM, not behind our eye, so a
			// watched player reads as standing inside the recess no matter how far away we are.
			float ratchet = Math.max(surface * pushSign, eyePush) * pushSign;
			float pushed = fixedRecede > 0.0F ? surface + fixedRecede * pushSign
					: surface + (ratchet - surface) * pushAmount;
			int frontZ = pushSign > 0.0F ? b.maxZ() + 1 : b.minZ() - 1; // one block out on the destination side
			boolean oBottom = sideObstructed(world, 1, b.minY() - 1, 2, frontZ, 0, b.minX(), b.maxX());
			boolean oTop = sideObstructed(world, 1, b.maxY() + 1, 2, frontZ, 0, b.minX(), b.maxX());
			boolean oLeft = sideObstructed(world, 0, b.minX() - 1, 2, frontZ, 1, b.minY(), b.maxY());
			boolean oRight = sideObstructed(world, 0, b.maxX() + 1, 2, frontZ, 1, b.minY(), b.maxY());
			float y0 = (float) ((oBottom ? b.minY() + BOTTOM_LIFT : b.minY() - m) - cam.y);
			float y1 = (float) ((oTop ? b.maxY() + 1 - BOTTOM_LIFT : b.maxY() + 1 + m) - cam.y);
			float x0 = (float) ((oLeft ? b.minX() + BOTTOM_LIFT : b.minX() - m) - cam.x);
			float x1 = (float) ((oRight ? b.maxX() + 1 - BOTTOM_LIFT : b.maxX() + 1 + m) - cam.x);
			sink.quad(x0, y0, pushed, x1, y0, pushed, x1, y1, pushed, x0, y1, pushed); // back face
			if (Math.abs(pushed - surface) >= 1.0e-3F) { // walls only once the push gives real depth
				double zLo = Math.min(surface, pushed) + cam.z, zHi = Math.max(surface, pushed) + cam.z;
				emitWall(sink, world, cam, oLeft, 0, x0, b.minX() - 1, 1, y0 + cam.y, y1 + cam.y, 2, zLo, zHi);
				emitWall(sink, world, cam, oRight, 0, x1, b.maxX() + 1, 1, y0 + cam.y, y1 + cam.y, 2, zLo, zHi);
				emitWall(sink, world, cam, oBottom, 1, y0, b.minY() - 1, 0, x0 + cam.x, x1 + cam.x, 2, zLo, zHi);
				emitWall(sink, world, cam, oTop, 1, y1, b.maxY() + 1, 0, x0 + cam.x, x1 + cam.x, 2, zLo, zHi);
			}
		} else {
			float surface = (float) ((faceA ? b.minX() + PLANE_LOW : b.minX() + PLANE_HIGH) + back - cam.x);
			float ratchet = Math.max(surface * pushSign, eyePush) * pushSign;
			float pushed = fixedRecede > 0.0F ? surface + fixedRecede * pushSign
					: surface + (ratchet - surface) * pushAmount;
			int frontX = pushSign > 0.0F ? b.maxX() + 1 : b.minX() - 1;
			boolean oBottom = sideObstructed(world, 1, b.minY() - 1, 0, frontX, 2, b.minZ(), b.maxZ());
			boolean oTop = sideObstructed(world, 1, b.maxY() + 1, 0, frontX, 2, b.minZ(), b.maxZ());
			boolean oLeft = sideObstructed(world, 2, b.minZ() - 1, 0, frontX, 1, b.minY(), b.maxY());
			boolean oRight = sideObstructed(world, 2, b.maxZ() + 1, 0, frontX, 1, b.minY(), b.maxY());
			float y0 = (float) ((oBottom ? b.minY() + BOTTOM_LIFT : b.minY() - m) - cam.y);
			float y1 = (float) ((oTop ? b.maxY() + 1 - BOTTOM_LIFT : b.maxY() + 1 + m) - cam.y);
			float z0 = (float) ((oLeft ? b.minZ() + BOTTOM_LIFT : b.minZ() - m) - cam.z);
			float z1 = (float) ((oRight ? b.maxZ() + 1 - BOTTOM_LIFT : b.maxZ() + 1 + m) - cam.z);
			sink.quad(pushed, y0, z0, pushed, y0, z1, pushed, y1, z1, pushed, y1, z0); // back face
			if (Math.abs(pushed - surface) >= 1.0e-3F) {
				double xLo = Math.min(surface, pushed) + cam.x, xHi = Math.max(surface, pushed) + cam.x;
				emitWall(sink, world, cam, oLeft, 2, z0, b.minZ() - 1, 1, y0 + cam.y, y1 + cam.y, 0, xLo, xHi);
				emitWall(sink, world, cam, oRight, 2, z1, b.maxZ() + 1, 1, y0 + cam.y, y1 + cam.y, 0, xLo, xHi);
				emitWall(sink, world, cam, oBottom, 1, y0, b.minY() - 1, 2, z0 + cam.z, z1 + cam.z, 0, xLo, xHi);
				emitWall(sink, world, cam, oTop, 1, y1, b.maxY() + 1, 2, z0 + cam.z, z1 + cam.z, 0, xLo, xHi);
			}
		}
	}

	/** Sink for one panorama-box quad (4 corners a,b,c,d, CAMERA-RELATIVE). The FBO path writes POSITION
	 * verts; the RTT path tessellates each quad with screen-space UVs. Sharing this lets the box + wall +
	 * mask + block-in-front lift logic live in ONE place so the RTT box matches the FBO box exactly (1:1
	 * screen-space sampling, no overshoot to reveal-behind and no reliance on it). */
	@FunctionalInterface
	private interface QuadSink {
		void quad(float ax, float ay, float az, float bx, float by, float bz,
				float cx, float cy, float cz, float dx, float dy, float dz);
	}

	/** A {@link QuadSink} that writes POSITION-only verts straight into a panorama {@link BufferBuilder}. */
	private static QuadSink bufferSink(BufferBuilder buffer) {
		return (ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz) -> {
			buffer.vertex(ax, ay, az);
			buffer.vertex(bx, by, bz);
			buffer.vertex(cx, cy, cz);
			buffer.vertex(dx, dy, dz);
		};
	}

	/** Emit one box wall: a rectangle in the plane perpendicular to constAxis at wallCamRel, spanning
	 * uAxis[uMinW,uMaxW] x vAxis[vMinW,vMaxW] (camera-relative from those world ranges). If masked, emit
	 * per block-cell and SKIP cells whose frame block (constAxis=frameCoord) is obsidian — keeping the
	 * obsidian ledge exposed while other blocks stay covered. Axes: 0=X, 1=Y, 2=Z. */
	private static void emitWall(QuadSink sink, ClientWorld world, Vec3d cam, boolean masked,
			int constAxis, float wallCamRel, int frameCoord,
			int uAxis, double uMinW, double uMaxW, int vAxis, double vMinW, double vMaxW) {
		double[] camA = {cam.x, cam.y, cam.z};
		float[] c0 = new float[3], c1 = new float[3], c2 = new float[3], c3 = new float[3];
		c0[constAxis] = c1[constAxis] = c2[constAxis] = c3[constAxis] = wallCamRel;
		if (!masked) {
			emitCell(sink, c0, c1, c2, c3, uAxis, (float) (uMinW - camA[uAxis]), (float) (uMaxW - camA[uAxis]),
					vAxis, (float) (vMinW - camA[vAxis]), (float) (vMaxW - camA[vAxis]));
			return;
		}
		int cuMin = (int) Math.floor(uMinW), cuMax = (int) Math.ceil(uMaxW) - 1;
		int cvMin = (int) Math.floor(vMinW), cvMax = (int) Math.ceil(vMaxW) - 1;
		BlockPos.Mutable pos = new BlockPos.Mutable();
		int[] bp = new int[3];
		bp[constAxis] = frameCoord;
		for (int cu = cuMin; cu <= cuMax; cu++) {
			bp[uAxis] = cu;
			float uu0 = (float) (Math.max(cu, uMinW) - camA[uAxis]);
			float uu1 = (float) (Math.min(cu + 1, uMaxW) - camA[uAxis]);
			for (int cv = cvMin; cv <= cvMax; cv++) {
				bp[vAxis] = cv;
				if (world.getBlockState(pos.set(bp[0], bp[1], bp[2])).isOf(Blocks.OBSIDIAN)) {
					continue; // keep the obsidian ledge exposed
				}
				emitCell(sink, c0, c1, c2, c3, uAxis, uu0, uu1,
						vAxis, (float) (Math.max(cv, vMinW) - camA[vAxis]), (float) (Math.min(cv + 1, vMaxW) - camA[vAxis]));
			}
		}
	}

	/** Write one quad in the given axis frame (constAxis already fixed on c0..c3) to the sink. */
	private static void emitCell(QuadSink sink, float[] c0, float[] c1, float[] c2, float[] c3,
			int uAxis, float u0, float u1, int vAxis, float v0, float v1) {
		c0[uAxis] = u0; c0[vAxis] = v0;
		c1[uAxis] = u1; c1[vAxis] = v0;
		c2[uAxis] = u1; c2[vAxis] = v1;
		c3[uAxis] = u0; c3[vAxis] = v1;
		sink.quad(c0[0], c0[1], c0[2], c1[0], c1[1], c1[2], c2[0], c2[1], c2[2], c3[0], c3[1], c3[2]);
	}

	private record Drawable(PortalRecord record, GlimpseTextures.GlimpseTexture texture,
			List<BlockPos> blocks, Bounds bounds, int glimpseAlpha, int postcardAlphaNoFade, int veilAlpha,
			boolean viewerOnFaceA, float glimpseFade, float pushAmount, float rttPushAmount, float pushSign,
			float encasement, float remoteRecede) {
	}

	/** The portals rendering the parallax panorama THIS frame — the nearest-N cap (see GlimpseSettings.maxPanoramas).
	 * Portals not in it fall back to the flat postcard at full opacity. Set after the frustum cull in renderWorld;
	 * read by renderPanoramas (both paths), emitRttQuad, and the postcard emitters. Render-thread; 1-frame-lagged
	 * on the RTT path alongside rttDrawables. */
	private static Set<UUID> panoramaSet = java.util.Collections.emptySet();

	private static void emitGlimpse(MatrixStack.Entry entry, VertexConsumerProvider consumers,
			Drawable drawable) {
		// A nearest-N-capped portal has no panorama to reveal, so it shows the postcard at its un-faded alpha
		// rather than crossfading out up close (which would blank it). See panoramaSet / postcardAlphaNoFade.
		int alpha = rendersPanorama(drawable) ? drawable.glimpseAlpha() : drawable.postcardAlphaNoFade();
		if (alpha <= 0) {
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
			emitFace(entry, vc, pos, b, axisX, drawable.viewerOnFaceA(), alpha,
					255, 255, 255, 0.0F, 0.0F, 1.0F, 1.0F, 0.0F);
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
					255, 255, 255,
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
			boolean axisX, boolean faceA, int alpha, int red, int green, int blue,
			float u0, float v0, float u1, float v1, float push) {
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
			vertex(entry, vc, px[i], py[i], pz[i], uu[i], vv[i], alpha, red, green, blue);
		}
		for (int i = 3; i >= 0; i--) {
			vertex(entry, vc, px[i], py[i], pz[i], uu[i], vv[i], alpha, red, green, blue);
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
			float u, float v, int alpha, int red, int green, int blue) {
		vc.vertex(entry.getPositionMatrix(), x, y, z)
				.color(red, green, blue, alpha)
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
		/** From a record — uses its cached interior bbox so we don't re-walk the block list every frame. */
		static Bounds of(PortalRecord record) {
			int[] c = record.interiorBounds();
			return fromCorners(c[0], c[1], c[2], c[3], c[4], c[5]);
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
			return fromCorners(minX, minY, minZ, maxX, maxY, maxZ);
		}

		private static Bounds fromCorners(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
			float width = (maxX - minX) >= (maxZ - minZ) ? (maxX + 1 - minX) : (maxZ + 1 - minZ);
			float height = maxY + 1 - minY;
			return new Bounds(minX, minY, minZ, maxX, maxY, maxZ, width, height);
		}
	}
}

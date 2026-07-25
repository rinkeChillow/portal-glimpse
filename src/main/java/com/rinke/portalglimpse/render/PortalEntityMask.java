package com.rinke.portalglimpse.render;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.rinke.portalglimpse.data.PortalRecord;
import com.rinke.portalglimpse.data.PortalStore;
import com.rinke.portalglimpse.detect.PortalDetection;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Entity-over-panorama detection (§ pt.14). A player standing IN a glimpse portal — within half a block
 * of its plane — is re-rendered OVER the panorama so they read as standing IN the captured dimension,
 * seen through the opening.
 *
 * <p>This class only DECIDES + collects: the {@code renderEntity} mixin calls {@link #shouldDefer} during
 * the entity pass (cancelling the normal render for a qualifying player and recording it here, together
 * with the portal opening it belongs to), then {@link VanillaGlimpseRenderer} consumes the collection
 * after the portal is drawn and re-renders each player on top, SCISSORED to that opening so the effect
 * stays within the panorama window (never spilling over the obsidian frame). Players only, current
 * dimension only, and only when the panorama is actually showing.
 */
public final class PortalEntityMask {

	/** Camera-to-portal range within which the panorama is drawn (mirrors {@code PANORAMA_DISTANCE=32},
	 * padded) — no point deferring a player if there's no panorama for them to stand over. */
	private static final double CAMERA_RANGE = 34.0;

	/** How far to each side of the portal plane the effect applies (blocks). Hard-coded: a player is
	 * "in the portal" within one block of its plane in either direction. */
	private static final double BAND = 1.0;

	/** Lateral/vertical slack (blocks) around the opening so a player straddling the frame still counts. */
	private static final double PAD = 0.5;

	/** A player to re-render over the panorama, tagged with the portal record it's standing in. */
	public record NearPlayer(PlayerEntity player, UUID recordId) {
	}

	private static final List<NearPlayer> collected = new ArrayList<>();

	/** TEMP DIAGNOSTIC (behind /pgdebug): the last decision {@link #shouldDefer} reached for a player that was
	 * near a captured, in-dimension portal — "DEFERRED" or the specific check that rejected them. Printed on
	 * change by {@code GlimpseKeybinds}. Lets us see WHY the entity-over-panorama isn't engaging without a
	 * debugger. Only written while {@link GlimpseSettings#debugMode} is on. */
	public static volatile String debugDecision = "(no player evaluated near a captured portal yet)";

	private PortalEntityMask() {
	}

	/**
	 * If {@code player} is standing in a showing glimpse portal (within half a block of its plane and
	 * inside the opening silhouette), record it for the post-panorama re-render and return {@code true}
	 * so the caller skips the normal entity render. Otherwise returns {@code false} (render as usual).
	 */
	public static boolean shouldDefer(PlayerEntity player) {
		if (!GlimpseSettings.glimpsesVisible || !GlimpseSettings.entityOverPanorama) {
			return false;
		}
		// Deferring is a VANILLA-only feature. Under a shaderpack we do NOT defer: every attempt to show the
		// player over the glimpse fought Iris's deferred pipeline (a re-render at AFTER_ENTITIES came out
		// semi-transparent; pushing the panorama behind the player dragged in the god-ray occluder as a blackout
		// box). So under shaders the player renders NORMALLY and is simply clipped by the glimpse plane, like a
		// vanilla portal — an accepted limitation. Only the no-shader path gets the clean player-over-panorama.
		if (IrisCompat.shadersActive()) {
			return false;
		}
		PortalStore store = PortalDetection.store();
		MinecraftClient client = MinecraftClient.getInstance();
		if (store == null || client.world == null || client.gameRenderer == null) {
			return false;
		}
		Identifier dimension = client.world.getRegistryKey().getValue();
		Vec3d cam = client.gameRenderer.getCamera().getPos();

		double ex = player.getX();
		double ez = player.getZ();
		double footY = player.getY();
		double headY = footY + player.getHeight();

		boolean debug = GlimpseSettings.debugMode;
		String name = debug ? player.getName().getString() : null;
		int capturedInDim = 0; // captured, in-dimension portals we found at all (regardless of range)

		for (PortalRecord record : store.all()) {
			boolean hasCapture = record.auto.hasCapture || (record.manual.hasCapture && record.manual.pinned);
			if (!hasCapture || !record.dimension.equals(dimension)) {
				continue;
			}
			capturedInDim++;

			// Cached on the record (its interior never changes), so this no longer re-walks the block list for
			// every player every frame — the reason this loop was a hot spot.
			int[] bb = record.interiorBounds();
			int minX = bb[0], minY = bb[1], minZ = bb[2], maxX = bb[3], maxY = bb[4], maxZ = bb[5];

			// Only when the camera is close enough that the panorama is actually rendering.
			double cx = (minX + maxX + 1) / 2.0;
			double cy = (minY + maxY + 1) / 2.0;
			double cz = (minZ + maxZ + 1) / 2.0;
			if (cam.squaredDistanceTo(cx, cy, cz) > CAMERA_RANGE * CAMERA_RANGE) {
				continue;
			}

			// Within one block of the plane, in either direction (the player is "in the portal").
			boolean axisX = record.axis == Direction.Axis.X;
			double planeCoord = axisX ? (minZ + 0.5) : (minX + 0.5);
			double entPerp = axisX ? ez : ex;
			if (Math.abs(entPerp - planeCoord) > BAND) {
				if (debug) {
					debugDecision = String.format("%s: NOT deferred — off-plane |%.2f-%.2f|=%.2f > BAND %.1f",
							name, entPerp, planeCoord, Math.abs(entPerp - planeCoord), BAND);
				}
				continue;
			}

			// Must fall within the opening's silhouette (lateral + vertical), or there's no panorama pixel
			// there to stand over.
			double latMin = axisX ? minX : minZ;
			double latMax = axisX ? (maxX + 1) : (maxZ + 1);
			double entLat = axisX ? ex : ez;
			if (entLat < latMin - PAD || entLat > latMax + PAD) {
				if (debug) {
					debugDecision = String.format("%s: NOT deferred — outside width (lat %.2f not in [%.1f,%.1f])",
							name, entLat, latMin - PAD, latMax + PAD);
				}
				continue;
			}
			if (headY < minY - PAD || footY > maxY + 1 + PAD) {
				if (debug) {
					debugDecision = String.format("%s: NOT deferred — outside height (foot %.2f head %.2f vs [%d,%d])",
							name, footY, headY, minY, maxY + 1);
				}
				continue;
			}

			collected.add(new NearPlayer(player, record.id));
			if (debug) {
				debugDecision = name + ": DEFERRED (re-rendered over the panorama)";
			}
			return true;
		}
		if (debug && capturedInDim == 0) {
			debugDecision = (name != null ? name : "player")
					+ ": NOT deferred — the observer has NO captured portal in this dimension (hasCapture=false)";
		}
		return false;
	}

	/** True if a player is standing in the given portal this frame — the portal's overlay passes then
	 * skip depth-write so the re-rendered player isn't occluded by its own glimpse. */
	public static boolean isAffected(UUID recordId) {
		for (NearPlayer np : collected) {
			if (np.recordId().equals(recordId)) {
				return true;
			}
		}
		return false;
	}

	/** Take (and clear) the players collected this frame, for the post-panorama re-render. */
	public static List<NearPlayer> consume() {
		if (collected.isEmpty()) {
			return List.of();
		}
		List<NearPlayer> out = new ArrayList<>(collected);
		collected.clear();
		return out;
	}
}

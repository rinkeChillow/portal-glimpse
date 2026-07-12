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
import net.minecraft.util.math.BlockPos;
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

		for (PortalRecord record : store.all()) {
			boolean hasCapture = record.auto.hasCapture || (record.manual.hasCapture && record.manual.pinned);
			if (!hasCapture || !record.dimension.equals(dimension)) {
				continue;
			}

			int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
			int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
			for (BlockPos pos : record.interior) {
				minX = Math.min(minX, pos.getX());
				maxX = Math.max(maxX, pos.getX());
				minY = Math.min(minY, pos.getY());
				maxY = Math.max(maxY, pos.getY());
				minZ = Math.min(minZ, pos.getZ());
				maxZ = Math.max(maxZ, pos.getZ());
			}

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
				continue;
			}

			// Must fall within the opening's silhouette (lateral + vertical), or there's no panorama pixel
			// there to stand over.
			double latMin = axisX ? minX : minZ;
			double latMax = axisX ? (maxX + 1) : (maxZ + 1);
			double entLat = axisX ? ex : ez;
			if (entLat < latMin - PAD || entLat > latMax + PAD) {
				continue;
			}
			if (headY < minY - PAD || footY > maxY + 1 + PAD) {
				continue;
			}

			collected.add(new NearPlayer(player, record.id));
			return true;
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

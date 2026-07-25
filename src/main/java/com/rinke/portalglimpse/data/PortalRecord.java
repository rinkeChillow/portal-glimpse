package com.rinke.portalglimpse.data;

import java.util.List;
import java.util.UUID;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * A single portal's record (design doc §5.2).
 *
 * <p>Captured images live on disk (Phase 2); this object holds identity, geometry, the A↔B link,
 * and the two capture slots' metadata.
 */
public class PortalRecord {

	public static final int FORMAT_VERSION = 1;

	/** Deterministic id derived from dimension + interior block set (§5.3). */
	public final UUID id;
	public final Identifier dimension;

	/** Min corner of the interior — the stable "bottom-left" anchor (§5.2). */
	public final BlockPos anchor;

	/** Every portal-interior block coordinate; feeds arbitrary shapes (§4.6) and defines identity. */
	public final List<BlockPos> interior;

	/** Lazily-computed, cached interior bounding box: {@code [minX, minY, minZ, maxX, maxY, maxZ]}. The interior
	 * is immutable (identity derives from it), so this is constant — computed once instead of walking the block
	 * list every frame in every consumer (the renderer and the entity mask both need it, per player per frame).
	 * Do NOT mutate the returned array. Volatile for safe publication; the lazy race is benign (same result). */
	private volatile int[] interiorBounds;

	/** Portal plane axis (X or Z). */
	public final Direction.Axis axis;

	public final CaptureSlot auto;
	public final CaptureSlot manual;
	public final long createdAt;

	/** Counterpart portal (§5.2). Unknown until the player travels through — nullable, self-heals (§5.4). */
	public UUID linkedId;
	public long updatedAt;

	public PortalRecord(UUID id, Identifier dimension, BlockPos anchor, List<BlockPos> interior,
			Direction.Axis axis, CaptureSlot auto, CaptureSlot manual,
			UUID linkedId, long createdAt, long updatedAt) {
		this.id = id;
		this.dimension = dimension;
		this.anchor = anchor;
		this.interior = interior;
		this.axis = axis;
		this.auto = auto;
		this.manual = manual;
		this.linkedId = linkedId;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	/**
	 * The interior's bounding box as {@code [minX, minY, minZ, maxX, maxY, maxZ]}, computed once and cached
	 * (the interior never changes for a given record). Callers must treat the array as read-only. Cheaper than
	 * re-walking {@link #interior} every frame — the renderer collects it per frame and the entity mask needs it
	 * per player per frame.
	 */
	public int[] interiorBounds() {
		int[] cached = interiorBounds;
		if (cached == null) {
			int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
			int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
			for (BlockPos pos : interior) {
				minX = Math.min(minX, pos.getX());
				maxX = Math.max(maxX, pos.getX());
				minY = Math.min(minY, pos.getY());
				maxY = Math.max(maxY, pos.getY());
				minZ = Math.min(minZ, pos.getZ());
				maxZ = Math.max(maxZ, pos.getZ());
			}
			cached = new int[] {minX, minY, minZ, maxX, maxY, maxZ};
			interiorBounds = cached;
		}
		return cached;
	}

	/**
	 * A fresh record for a newly detected portal — no captures yet, so it renders completely
	 * vanilla until the player travels through it (§3.1).
	 */
	public static PortalRecord create(UUID id, Identifier dimension, BlockPos anchor,
			List<BlockPos> interior, Direction.Axis axis) {
		long now = System.currentTimeMillis();
		return new PortalRecord(id, dimension, anchor, interior, axis,
				new CaptureSlot(), new CaptureSlot(), null, now, now);
	}
}

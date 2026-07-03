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

package com.rinke.portalglimpse.data;

import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.rinke.portalglimpse.PortalGlimpse;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * In-memory registry of portal records for the currently joined world/server, backed by
 * {@link PortalStorage} on disk.
 *
 * <p>Lives on the client thread — created on world join, cleared on disconnect.
 */
public class PortalStore {

	private final Path baseDir;
	private final Map<UUID, PortalRecord> records = new HashMap<>();

	/** Interior block position → owning record, for fast dedup during scans and exact lookups. */
	private final Map<Long, PortalRecord> positionIndex = new HashMap<>();

	private PortalStore(Path baseDir) {
		this.baseDir = baseDir;
	}

	public static PortalStore load(Path baseDir) {
		PortalStore store = new PortalStore(baseDir);
		for (PortalRecord record : PortalStorage.loadAll(baseDir)) {
			store.records.put(record.id, record);
			store.claim(record.interior, record);
		}
		PortalGlimpse.LOGGER.info("Portal Glimpse: loaded {} portal record(s) from {}.",
				store.records.size(), baseDir);
		return store;
	}

	public boolean isClaimed(BlockPos pos) {
		return positionIndex.containsKey(pos.asLong());
	}

	/** The portal whose interior contains {@code pos}, or null. */
	public PortalRecord recordAt(BlockPos pos) {
		return positionIndex.get(pos.asLong());
	}

	public PortalRecord get(UUID id) {
		return records.get(id);
	}

	public Collection<PortalRecord> all() {
		return records.values();
	}

	/** Nearest registered portal to {@code from} within the given dimension, or null if none. */
	public PortalRecord findNearest(BlockPos from, Identifier dimension) {
		PortalRecord best = null;
		double bestSq = Double.MAX_VALUE;
		for (PortalRecord record : records.values()) {
			if (!record.dimension.equals(dimension)) {
				continue;
			}
			double distSq = record.anchor.getSquaredDistance(from);
			if (distSq < bestSq) {
				bestSq = distSq;
				best = record;
			}
		}
		return best;
	}

	public Path baseDir() {
		return baseDir;
	}

	/** Outcome of a {@link #register} call: the record, whether it was newly created, and if it saved. */
	public record RegisterResult(PortalRecord record, boolean isNew, boolean saved) {
	}

	/**
	 * Register a freshly detected portal. If a portal with the same identity already exists
	 * (same coords + same block set, §5.3) its record is reused and nothing is written.
	 */
	public RegisterResult register(Identifier dimension, BlockPos anchor, List<BlockPos> interior,
			Direction.Axis axis) {
		UUID id = PortalIdentity.compute(dimension, interior);

		PortalRecord existing = records.get(id);
		if (existing != null) {
			claim(interior, existing);
			return new RegisterResult(existing, false, true);
		}

		PortalRecord record = PortalRecord.create(id, dimension, anchor, interior, axis);
		records.put(id, record);
		claim(interior, record);
		boolean saved = PortalStorage.save(baseDir, record);
		PortalGlimpse.LOGGER.info("Portal Glimpse: registered portal {} ({} blocks, {} axis) at {}",
				id, interior.size(), axis, anchor);
		return new RegisterResult(record, true, saved);
	}

	/** Persist changes to an existing record (e.g. link re-routing, capture metadata). */
	public void save(PortalRecord record) {
		record.updatedAt = System.currentTimeMillis();
		PortalStorage.save(baseDir, record);
	}

	private void claim(List<BlockPos> interior, PortalRecord record) {
		for (BlockPos pos : interior) {
			positionIndex.put(pos.asLong(), record);
		}
	}
}

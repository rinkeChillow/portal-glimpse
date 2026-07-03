package com.rinke.portalglimpse.data;

import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

	/** Every interior block position already claimed by a known portal, for fast dedup during scans. */
	private final Set<Long> claimedPositions = new HashSet<>();

	private PortalStore(Path baseDir) {
		this.baseDir = baseDir;
	}

	public static PortalStore load(Path baseDir) {
		PortalStore store = new PortalStore(baseDir);
		for (PortalRecord record : PortalStorage.loadAll(baseDir)) {
			store.records.put(record.id, record);
			for (BlockPos pos : record.interior) {
				store.claimedPositions.add(pos.asLong());
			}
		}
		PortalGlimpse.LOGGER.info("Portal Glimpse: loaded {} portal record(s) from {}.",
				store.records.size(), baseDir);
		return store;
	}

	public boolean isClaimed(BlockPos pos) {
		return claimedPositions.contains(pos.asLong());
	}

	public PortalRecord get(UUID id) {
		return records.get(id);
	}

	public Collection<PortalRecord> all() {
		return records.values();
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
		claim(interior);

		PortalRecord existing = records.get(id);
		if (existing != null) {
			return new RegisterResult(existing, false, true);
		}

		PortalRecord record = PortalRecord.create(id, dimension, anchor, interior, axis);
		records.put(id, record);
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

	private void claim(List<BlockPos> interior) {
		for (BlockPos pos : interior) {
			claimedPositions.add(pos.asLong());
		}
	}
}

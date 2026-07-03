package com.rinke.portalglimpse.data;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import com.rinke.portalglimpse.PortalGlimpse;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * On-disk layout and JSON IO for portal records (design doc §5.6).
 *
 * <ul>
 *   <li>Singleplayer: {@code <world folder>/portalglimpse/} — travels with the world when copied.</li>
 *   <li>Multiplayer: {@code .minecraft/portalglimpse/<server-id>/}.</li>
 * </ul>
 *
 * <p>Records are validated on load and written atomically (temp + move) so a crash mid-write can
 * never leave a half-written {@code portal.json} (§3.5).
 */
public final class PortalStorage {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String DATA_DIR = "portalglimpse";
	private static final String RECORD_FILE = "portal.json";

	private PortalStorage() {
	}

	/** Resolve the base directory for the currently joined world/server, or null if unavailable. */
	public static Path resolveBaseDir(MinecraftClient client) {
		IntegratedServer server = client.getServer();
		if (server != null) {
			// Singleplayer: store inside the world folder so it's rename-proof and travels with copies.
			Path worldRoot = server.getSavePath(WorldSavePath.ROOT);
			return worldRoot.resolve(DATA_DIR);
		}
		ServerInfo info = client.getCurrentServerEntry();
		String serverId = sanitize(info != null ? info.address : "unknown");
		return FabricLoader.getInstance().getGameDir().resolve(DATA_DIR).resolve(serverId);
	}

	private static String sanitize(String raw) {
		String cleaned = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
		return cleaned.isEmpty() ? "unknown" : cleaned;
	}

	/** Load and validate every record under {@code base}; corrupt/half-written records are skipped (§3.5). */
	public static List<PortalRecord> loadAll(Path base) {
		List<PortalRecord> out = new ArrayList<>();
		if (base == null || !Files.isDirectory(base)) {
			return out;
		}
		try (Stream<Path> dirs = Files.list(base)) {
			dirs.filter(Files::isDirectory).forEach(dir -> {
				PortalRecord record = loadOne(dir.resolve(RECORD_FILE));
				if (record != null) {
					out.add(record);
				}
			});
		} catch (IOException e) {
			PortalGlimpse.LOGGER.warn("Portal Glimpse: failed to list records in {}", base, e);
		}
		return out;
	}

	private static PortalRecord loadOne(Path file) {
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try (Reader reader = Files.newBufferedReader(file)) {
			PortalRecordDto dto = GSON.fromJson(reader, PortalRecordDto.class);
			PortalRecord record = fromDto(dto);
			if (record == null) {
				PortalGlimpse.LOGGER.warn("Portal Glimpse: discarding invalid record {}", file);
			}
			return record;
		} catch (Exception e) {
			PortalGlimpse.LOGGER.warn("Portal Glimpse: discarding corrupt record {}", file, e);
			return null;
		}
	}

	/**
	 * Write a record atomically so a crash can't leave a half-written portal.json (§3.5).
	 *
	 * @return true if the record was written successfully.
	 */
	public static boolean save(Path base, PortalRecord record) {
		if (base == null) {
			return false;
		}
		Path dir = base.resolve(record.id.toString());
		Path file = dir.resolve(RECORD_FILE);
		Path tmp = dir.resolve(RECORD_FILE + ".tmp");
		try {
			Files.createDirectories(dir);
			try (Writer writer = Files.newBufferedWriter(tmp)) {
				GSON.toJson(toDto(record), writer);
			}
			try {
				Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException unsupported) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
			return true;
		} catch (IOException e) {
			PortalGlimpse.LOGGER.warn("Portal Glimpse: failed to save record {}", record.id, e);
			return false;
		}
	}

	// --- DTO <-> model, with validation on the way in -----------------------------------------

	static PortalRecord fromDto(PortalRecordDto dto) {
		if (dto == null || dto.id == null || dto.dimension == null || dto.axis == null) {
			return null;
		}
		if (dto.anchor == null || dto.anchor.length != 3) {
			return null;
		}
		if (dto.interior == null || dto.interior.isEmpty()) {
			return null;
		}

		UUID id;
		try {
			id = UUID.fromString(dto.id);
		} catch (IllegalArgumentException e) {
			return null;
		}

		Identifier dimension = Identifier.tryParse(dto.dimension);
		if (dimension == null) {
			return null;
		}

		Direction.Axis axis;
		try {
			axis = Direction.Axis.valueOf(dto.axis);
		} catch (IllegalArgumentException e) {
			return null;
		}

		List<BlockPos> interior = new ArrayList<>(dto.interior.size());
		for (int[] coords : dto.interior) {
			if (coords == null || coords.length != 3) {
				return null;
			}
			interior.add(new BlockPos(coords[0], coords[1], coords[2]));
		}

		BlockPos anchor = new BlockPos(dto.anchor[0], dto.anchor[1], dto.anchor[2]);

		UUID linkedId = null;
		if (dto.linkedId != null) {
			try {
				linkedId = UUID.fromString(dto.linkedId);
			} catch (IllegalArgumentException ignored) {
				// A bad link is not fatal — it self-heals on next travel (§5.4).
			}
		}

		CaptureSlot auto = slotFromDto(dto.auto);
		CaptureSlot manual = slotFromDto(dto.manual);
		long created = dto.createdAt > 0 ? dto.createdAt : System.currentTimeMillis();
		long updated = dto.updatedAt > 0 ? dto.updatedAt : created;

		return new PortalRecord(id, dimension, anchor, interior, axis, auto, manual, linkedId, created, updated);
	}

	private static CaptureSlot slotFromDto(SlotDto dto) {
		if (dto == null) {
			return new CaptureSlot();
		}
		return new CaptureSlot(dto.hasCapture, dto.pinned, dto.timestamp);
	}

	static PortalRecordDto toDto(PortalRecord record) {
		PortalRecordDto dto = new PortalRecordDto();
		dto.version = PortalRecord.FORMAT_VERSION;
		dto.id = record.id.toString();
		dto.dimension = record.dimension.toString();
		dto.linkedId = record.linkedId != null ? record.linkedId.toString() : null;
		dto.anchor = new int[] { record.anchor.getX(), record.anchor.getY(), record.anchor.getZ() };
		dto.interior = new ArrayList<>(record.interior.size());
		for (BlockPos pos : record.interior) {
			dto.interior.add(new int[] { pos.getX(), pos.getY(), pos.getZ() });
		}
		dto.axis = record.axis.name();
		dto.auto = slotToDto(record.auto);
		dto.manual = slotToDto(record.manual);
		dto.createdAt = record.createdAt;
		dto.updatedAt = record.updatedAt;
		return dto;
	}

	private static SlotDto slotToDto(CaptureSlot slot) {
		SlotDto dto = new SlotDto();
		dto.hasCapture = slot.hasCapture;
		dto.pinned = slot.pinned;
		dto.timestamp = slot.timestamp;
		return dto;
	}

	// --- Plain JSON shape (Gson serializes these directly) ------------------------------------

	static final class PortalRecordDto {
		int version;
		String id;
		String dimension;
		String linkedId;
		int[] anchor;
		List<int[]> interior;
		String axis;
		SlotDto auto;
		SlotDto manual;
		long createdAt;
		long updatedAt;
	}

	static final class SlotDto {
		boolean hasCapture;
		boolean pinned;
		long timestamp;
	}
}

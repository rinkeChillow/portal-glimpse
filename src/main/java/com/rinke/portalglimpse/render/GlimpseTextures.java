package com.rinke.portalglimpse.render;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.rinke.portalglimpse.PortalGlimpse;
import com.rinke.portalglimpse.data.PortalRecord;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.Direction;

/**
 * Disk → GPU cache for glimpse images. PNGs are decoded on the IO worker pool, registered as
 * textures on the render thread, and invalidated when the record is re-saved (new capture).
 *
 * <p>Face semantics: {@code faceA} is the north/west-side postcard, {@code faceB} the south/east
 * one. A travel glimpse is photographed at the <em>destination</em> portal, whose axis may differ
 * from the displaying portal's — so we prefer the pair matching the displaying portal's own axis
 * and fall back to the other pair.
 */
public final class GlimpseTextures {

	/** Texture pair for the two portal faces; either id may be null if its file is missing. */
	public record GlimpseTexture(Identifier faceA, Identifier faceB) {
	}

	private record Entry(GlimpseTexture texture, long recordVersion) {
	}

	/** Render-thread only. */
	private static final Map<UUID, Entry> CACHE = new HashMap<>();
	private static final Set<UUID> LOADING = ConcurrentHashMap.newKeySet();

	private GlimpseTextures() {
	}

	/**
	 * The textures for {@code record}, or null while not yet loaded. Kicks an async (re)load when
	 * missing or stale; safe to call every frame from the render thread.
	 */
	public static GlimpseTexture get(MinecraftClient client, Path baseDir, PortalRecord record) {
		Entry entry = CACHE.get(record.id);
		if (entry != null && entry.recordVersion() == record.updatedAt) {
			return entry.texture();
		}
		if (!LOADING.add(record.id)) {
			return entry != null ? entry.texture() : null;
		}

		long version = record.updatedAt;
		Path dir = baseDir.resolve(record.id.toString());
		String[][] pairs = record.axis == Direction.Axis.X
				? new String[][] { { "postcard_north.png", "postcard_south.png" },
						{ "postcard_west.png", "postcard_east.png" } }
				: new String[][] { { "postcard_west.png", "postcard_east.png" },
						{ "postcard_north.png", "postcard_south.png" } };

		Util.getIoWorkerExecutor().execute(() -> {
			NativeImage imageA = null;
			NativeImage imageB = null;
			for (String[] pair : pairs) {
				Path fileA = dir.resolve(pair[0]);
				Path fileB = dir.resolve(pair[1]);
				if (Files.isRegularFile(fileA) || Files.isRegularFile(fileB)) {
					imageA = tryRead(fileA);
					imageB = tryRead(fileB);
					break;
				}
			}
			NativeImage finalA = imageA;
			NativeImage finalB = imageB;
			client.execute(() -> store(client, record.id, version, finalA, finalB));
		});
		return entry != null ? entry.texture() : null;
	}

	private static void store(MinecraftClient client, UUID id, long version, NativeImage a, NativeImage b) {
		LOADING.remove(id);
		destroyEntry(client, CACHE.remove(id));
		if (a == null && b == null) {
			// Nothing usable on disk — remember that so we don't hammer the disk every frame.
			CACHE.put(id, new Entry(null, version));
			return;
		}
		Identifier idA = register(client, id, "a", a);
		Identifier idB = register(client, id, "b", b);
		CACHE.put(id, new Entry(new GlimpseTexture(idA, idB), version));
	}

	private static Identifier register(MinecraftClient client, UUID id, String face, NativeImage image) {
		if (image == null) {
			return null;
		}
		Identifier textureId = Identifier.of(PortalGlimpse.MOD_ID, "glimpse/" + id + "/" + face);
		client.getTextureManager().registerTexture(textureId, new NativeImageBackedTexture(image));
		return textureId;
	}

	private static NativeImage tryRead(Path file) {
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try (InputStream in = Files.newInputStream(file)) {
			return NativeImage.read(in);
		} catch (Exception e) {
			// Corrupt/half-written image: discard silently, retry on next capture (§3.5).
			PortalGlimpse.LOGGER.warn("Portal Glimpse: discarding unreadable image {}", file, e);
			return null;
		}
	}

	private static void destroyEntry(MinecraftClient client, Entry entry) {
		if (entry == null || entry.texture() == null) {
			return;
		}
		if (entry.texture().faceA() != null) {
			client.getTextureManager().destroyTexture(entry.texture().faceA());
		}
		if (entry.texture().faceB() != null) {
			client.getTextureManager().destroyTexture(entry.texture().faceB());
		}
	}

	/** Drop everything (world/server left). Render thread. */
	public static void clear(MinecraftClient client) {
		for (Entry entry : CACHE.values()) {
			destroyEntry(client, entry);
		}
		CACHE.clear();
		LOADING.clear();
	}
}

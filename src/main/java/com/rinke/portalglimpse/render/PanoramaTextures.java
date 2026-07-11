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

/**
 * Disk → GPU cache for the six panorama cubemap faces of each portal, mirroring
 * {@link GlimpseTextures} but for {@code panorama_0..5.png}. Async decode on the IO pool, texture
 * registration on the render thread, invalidated when the record is re-saved (new capture).
 */
public final class PanoramaTextures {

	private record Entry(Identifier[] faces, long version) {
	}

	/** Render-thread only. */
	private static final Map<UUID, Entry> CACHE = new HashMap<>();
	private static final Set<UUID> LOADING = ConcurrentHashMap.newKeySet();

	private PanoramaTextures() {
	}

	/**
	 * The six face texture ids for {@code record}, or null while not yet loaded / no complete set on
	 * disk. Indices follow the capture order (0=south, 1=west, 2=north, 3=east, 4=up, 5=down).
	 */
	public static Identifier[] get(MinecraftClient client, Path baseDir, PortalRecord record) {
		Entry entry = CACHE.get(record.id);
		if (entry != null && entry.version() == record.updatedAt) {
			return entry.faces();
		}
		if (!LOADING.add(record.id)) {
			return entry != null ? entry.faces() : null;
		}

		long version = record.updatedAt;
		Path dir = baseDir.resolve(record.id.toString());
		// A pinned manual capture (player-curated) wins over the automatic one; both live in the same
		// folder distinguished by a "manual_" prefix. updatedAt changes when the slot is toggled, so
		// this cache reloads the right set automatically.
		String prefix = record.manual.hasCapture && record.manual.pinned ? "manual_" : "";
		Util.getIoWorkerExecutor().execute(() -> {
			NativeImage[] images = new NativeImage[6];
			for (int i = 0; i < 6; i++) {
				images[i] = tryRead(dir.resolve(prefix + "panorama_" + i + ".png"));
			}
			client.execute(() -> store(client, record.id, version, images));
		});
		return entry != null ? entry.faces() : null;
	}

	private static void store(MinecraftClient client, UUID id, long version, NativeImage[] images) {
		LOADING.remove(id);
		destroy(client, CACHE.remove(id));

		Identifier[] faces = new Identifier[6];
		int loaded = 0;
		for (int i = 0; i < 6; i++) {
			if (images[i] != null) {
				Identifier textureId = Identifier.of(PortalGlimpse.MOD_ID, "panorama/" + id + "/" + i);
				client.getTextureManager().registerTexture(textureId, new NativeImageBackedTexture(images[i]));
				faces[i] = textureId;
				loaded++;
			}
		}
		// The parallax shader samples all six faces — only expose a complete cubemap.
		CACHE.put(id, new Entry(loaded == 6 ? faces : null, version));
	}

	private static NativeImage tryRead(Path file) {
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try (InputStream in = Files.newInputStream(file)) {
			return NativeImage.read(in);
		} catch (Exception e) {
			PortalGlimpse.LOGGER.warn("Portal Glimpse: discarding unreadable panorama face {}", file, e);
			return null;
		}
	}

	private static void destroy(MinecraftClient client, Entry entry) {
		if (entry == null || entry.faces() == null) {
			return;
		}
		for (Identifier face : entry.faces()) {
			if (face != null) {
				client.getTextureManager().destroyTexture(face);
			}
		}
	}

	public static void clear(MinecraftClient client) {
		for (Entry entry : CACHE.values()) {
			destroy(client, entry);
		}
		CACHE.clear();
		LOADING.clear();
	}
}

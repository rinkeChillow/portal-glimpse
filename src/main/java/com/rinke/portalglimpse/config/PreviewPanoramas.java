package com.rinke.portalglimpse.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import com.rinke.portalglimpse.PortalGlimpse;
import com.rinke.portalglimpse.data.PortalRecord;
import com.rinke.portalglimpse.data.PortalStorage;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

/**
 * Loads captured panorama faces straight off disk for the config preview, so the box can show a real
 * captured view even in the main menu (no world loaded). Mirrors {@code PortalStorage}'s layout —
 * every singleplayer world's {@code <world>/portalglimpse/} and the multiplayer
 * {@code .minecraft/portalglimpse/<server>/} — and filters by the panorama's CONTENT dimension:
 * a portal's panorama shows its destination, so an Overworld-located portal has a Nether view and a
 * Nether-located portal has an Overworld view. Loaded faces are registered as textures, cached by path.
 */
public final class PreviewPanoramas {

	private static final Random RANDOM = new Random();
	private static final Map<String, Identifier> LOADED = new HashMap<>();

	private PreviewPanoramas() {
	}

	/**
	 * Whether a portal in {@code dimension} has a Nether-content panorama (it shows its destination):
	 * TRUE for Overworld portals, FALSE for Nether portals, null for anything else.
	 */
	public static Boolean contentIsNether(Identifier dimension) {
		if (World.OVERWORLD.getValue().equals(dimension)) {
			return Boolean.TRUE;
		}
		if (World.NETHER.getValue().equals(dimension)) {
			return Boolean.FALSE;
		}
		return null;
	}

	/** The horizontal faces of a random saved panorama whose content matches {@code wantNether}, or null. */
	public static Identifier[] pickFaces(MinecraftClient client, boolean wantNether) {
		List<Path> portalDirs = new ArrayList<>();
		Path gameDir = FabricLoader.getInstance().getGameDir();
		for (Path world : listDirs(gameDir.resolve("saves"))) {
			collectMatching(world.resolve("portalglimpse"), wantNether, portalDirs);
		}
		for (Path server : listDirs(gameDir.resolve("portalglimpse"))) {
			collectMatching(server, wantNether, portalDirs);
		}
		if (portalDirs.isEmpty()) {
			return null;
		}
		return loadFaces(client, portalDirs.get(RANDOM.nextInt(portalDirs.size())));
	}

	/** Portal folders under {@code base} whose record has a matching-dimension capture on disk. */
	private static void collectMatching(Path base, boolean wantNether, List<Path> out) {
		for (PortalRecord record : PortalStorage.loadAll(base)) {
			if (!record.auto.hasCapture) {
				continue;
			}
			Boolean nether = contentIsNether(record.dimension);
			if (nether == null || nether != wantNether) {
				continue;
			}
			Path dir = base.resolve(record.id.toString());
			if (Files.isRegularFile(dir.resolve("panorama_0.png"))) {
				out.add(dir);
			}
		}
	}

	private static Identifier[] loadFaces(MinecraftClient client, Path dir) {
		List<Identifier> faces = new ArrayList<>();
		for (int i = 0; i < 4; i++) { // horizontal faces only
			Identifier id = load(client, dir.resolve("panorama_" + i + ".png"));
			if (id != null) {
				faces.add(id);
			}
		}
		return faces.isEmpty() ? null : faces.toArray(Identifier[]::new);
	}

	private static List<Path> listDirs(Path root) {
		if (!Files.isDirectory(root)) {
			return Collections.emptyList();
		}
		try (Stream<Path> stream = Files.list(root)) {
			List<Path> dirs = new ArrayList<>();
			stream.filter(Files::isDirectory).forEach(dirs::add);
			return dirs;
		} catch (IOException e) {
			PortalGlimpse.LOGGER.warn("Portal Glimpse: preview scan failed under {}", root, e);
			return Collections.emptyList();
		}
	}

	private static Identifier load(MinecraftClient client, Path file) {
		if (!Files.isRegularFile(file)) {
			return null;
		}
		String key = file.toString();
		Identifier cached = LOADED.get(key);
		if (cached != null) {
			return cached;
		}
		try (InputStream in = Files.newInputStream(file)) {
			NativeImage image = NativeImage.read(in);
			Identifier id = Identifier.of(PortalGlimpse.MOD_ID, "preview/" + Integer.toHexString(key.hashCode()));
			client.getTextureManager().registerTexture(id, new NativeImageBackedTexture(image));
			LOADED.put(key, id);
			return id;
		} catch (Exception e) {
			PortalGlimpse.LOGGER.warn("Portal Glimpse: failed to load preview {}", file, e);
			return null;
		}
	}
}

package com.rinke.portalglimpse.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.rinke.portalglimpse.PortalGlimpse;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Finds every world and server this mod has stored glimpses for, measures them, and deletes them on request.
 *
 * <p>Captures accumulate quietly: six cubemap faces plus two postcards per portal, per world, kept forever.
 * Nothing ever removed them, so the only way to reclaim the space was to know the layout and go digging by
 * hand. This is the plumbing behind the config screen's usage line and its clean-up menu.
 *
 * <p>Two roots to search, because captures live in different places by design — singleplayer inside the world
 * folder so they travel with a copied or renamed save, multiplayer under the game directory keyed by server
 * address, since there is no local world to put them in.
 */
public final class GlimpseStorage {

	private static final String DATA_DIR = "portalglimpse";

	/** One world or server's worth of captures. */
	public record Location(String label, Path dir, long bytes, int portals, boolean singleplayer) {
	}

	private GlimpseStorage() {
	}

	/**
	 * Every location holding captures, largest first. Touches the disk — call it off the render thread.
	 */
	public static List<Location> scan() {
		List<Location> out = new ArrayList<>();
		Path gameDir = FabricLoader.getInstance().getGameDir();

		// Multiplayer: <gameDir>/portalglimpse/<sanitised server address>/
		Path servers = gameDir.resolve(DATA_DIR);
		if (Files.isDirectory(servers)) {
			for (Path dir : listDirs(servers)) {
				add(out, dir.getFileName().toString(), dir, false);
			}
		}

		// Singleplayer: <gameDir>/saves/<world>/portalglimpse/
		Path saves = gameDir.resolve("saves");
		if (Files.isDirectory(saves)) {
			for (Path world : listDirs(saves)) {
				Path dir = world.resolve(DATA_DIR);
				if (Files.isDirectory(dir)) {
					add(out, world.getFileName().toString(), dir, true);
				}
			}
		}

		out.sort(Comparator.comparingLong(Location::bytes).reversed());
		return out;
	}

	public static long totalBytes(List<Location> locations) {
		long total = 0;
		for (Location l : locations) {
			total += l.bytes();
		}
		return total;
	}

	/** Delete a location's captures outright. The folder goes with them, so it stops showing up here. */
	public static boolean delete(Location location) {
		try (Stream<Path> walk = Files.walk(location.dir())) {
			walk.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException ignored) {
					// Best effort — a locked file just means a little space isn't reclaimed.
				}
			});
			return true;
		} catch (IOException e) {
			PortalGlimpse.LOGGER.warn("Portal Glimpse: could not delete captures at {}", location.dir(), e);
			return false;
		}
	}

	/** "1.4 GB", "812 MB", "40 KB" — sized for a config row, not for precision. */
	public static String format(long bytes) {
		if (bytes >= 1024L * 1024L * 1024L) {
			return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
		}
		if (bytes >= 1024L * 1024L) {
			return String.format("%.0f MB", bytes / (1024.0 * 1024.0));
		}
		if (bytes >= 1024L) {
			return String.format("%.0f KB", bytes / 1024.0);
		}
		return bytes + " B";
	}

	private static void add(List<Location> out, String label, Path dir, boolean singleplayer) {
		long bytes = 0;
		int portals = 0;
		try (Stream<Path> walk = Files.walk(dir)) {
			for (Path p : walk.toList()) {
				if (Files.isDirectory(p)) {
					// Each portal is its own UUID-named folder directly under the location.
					if (p.getParent() != null && p.getParent().equals(dir)) {
						portals++;
					}
				} else {
					bytes += Files.size(p);
				}
			}
		} catch (IOException e) {
			return; // unreadable — better to omit it than to show a wrong number
		}
		if (bytes > 0) {
			out.add(new Location(label, dir, bytes, portals, singleplayer));
		}
	}

	private static List<Path> listDirs(Path parent) {
		try (Stream<Path> list = Files.list(parent)) {
			return list.filter(Files::isDirectory).toList();
		} catch (IOException e) {
			return List.of();
		}
	}
}

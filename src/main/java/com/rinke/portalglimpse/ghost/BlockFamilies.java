package com.rinke.portalglimpse.ghost;

import java.util.Random;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.Identifier;

/**
 * Fuzzy "look family" matching for the embedded-portal frame clone
 * ({@code GhostController.frameReplacement}).
 *
 * <p>When a portal is embedded in a wall, a frame obsidian is cloned into the surrounding block so
 * the capture has no hole. It doesn't require the identical block in front and behind — blocks of
 * the same visual FAMILY count, and the clone collapses to a canonical member:
 * <ul>
 *   <li><b>Dirt</b> — dirt / grass / podzol / coarse dirt / dirt path / farmland / mud / moss / …
 *       (the {@code minecraft:dirt} tag plus path &amp; farmland) → {@code dirt}.</li>
 *   <li><b>Planks</b> — per wood type: spruce planks / slab / stairs / fence(_gate) →
 *       {@code spruce_planks} (spruce only ever pairs with spruce). Picks up modded woods that
 *       follow the {@code <wood>_planks} naming.</li>
 *   <li><b>Cobblestone</b> — cobblestone / mossy (+ slab / stairs / wall) → {@code cobblestone}.</li>
 *   <li><b>Stone bricks</b> — stone bricks / mossy / cracked / chiseled (+ slab / stairs / wall) →
 *       {@code stone_bricks}.</li>
 * </ul>
 *
 * <p>If the two sides are unrelated real blocks (no shared family), one is picked at random rather
 * than leaving a hole. A hole (air) only remains when a side isn't a cloneable block at all
 * (air/portal/obsidian) — e.g. a free-standing portal.
 *
 * <p>Deliberately heuristic ("good enough") and easy to extend: add a case to
 * {@link #familyCanonical}.
 */
public final class BlockFamilies {

	/** Shape suffixes that share a wood's plank texture; stripped to find the {@code *_planks} base. */
	private static final String[] PLANK_SUFFIXES = { "_planks", "_slab", "_stairs", "_fence_gate", "_fence" };

	/** Tie-break when the two sides are unrelated real blocks — pick a side at random (see below). */
	private static final Random RANDOM = new Random();

	private static final Set<Block> COBBLESTONE_FAMILY = Set.of(
			Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE,
			Blocks.COBBLESTONE_SLAB, Blocks.MOSSY_COBBLESTONE_SLAB,
			Blocks.COBBLESTONE_STAIRS, Blocks.MOSSY_COBBLESTONE_STAIRS,
			Blocks.COBBLESTONE_WALL, Blocks.MOSSY_COBBLESTONE_WALL);

	private static final Set<Block> STONE_BRICK_FAMILY = Set.of(
			Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS,
			Blocks.STONE_BRICK_SLAB, Blocks.MOSSY_STONE_BRICK_SLAB,
			Blocks.STONE_BRICK_STAIRS, Blocks.MOSSY_STONE_BRICK_STAIRS,
			Blocks.STONE_BRICK_WALL, Blocks.MOSSY_STONE_BRICK_WALL);

	private BlockFamilies() {
	}

	/**
	 * The block to clone into a frame position, given what's directly in front of and behind it, or
	 * {@code null} only if a side isn't cloneable (air/portal/obsidian) — the caller then leaves the
	 * frame as air. When both sides are real blocks a clone is always chosen: identical → verbatim,
	 * same family → the canonical, otherwise a random pick of the two.
	 */
	public static BlockState sharedClone(BlockState front, BlockState back) {
		if (!cloneable(front) || !cloneable(back)) {
			return null;
		}
		// Identical block: clone it verbatim (keeps its exact state, e.g. a rotated log).
		if (front.isOf(back.getBlock())) {
			return front;
		}
		// Same visual family, different variant: clone the family's canonical block.
		Block canon = familyCanonical(front);
		if (canon != null && canon == familyCanonical(back)) {
			return canon.getDefaultState();
		}
		// Two unrelated real blocks (a detailed/mixed build has different blocks front and back) —
		// there's no single right answer, so pick a side at random rather than punching a hole. Each
		// frame cell rolls independently, which reads as a natural blend of the two.
		return RANDOM.nextBoolean() ? front : back;
	}

	/** The canonical block of whatever family the state belongs to, or {@code null} if none. */
	private static Block familyCanonical(BlockState s) {
		if (isDirt(s)) {
			return Blocks.DIRT;
		}
		Block plank = plankBase(s);
		if (plank != null) {
			return plank;
		}
		if (COBBLESTONE_FAMILY.contains(s.getBlock())) {
			return Blocks.COBBLESTONE;
		}
		if (STONE_BRICK_FAMILY.contains(s.getBlock())) {
			return Blocks.STONE_BRICKS;
		}
		return null;
	}

	private static boolean isDirt(BlockState s) {
		return s.isIn(BlockTags.DIRT) || s.isOf(Blocks.DIRT_PATH) || s.isOf(Blocks.FARMLAND);
	}

	/** The {@code *_planks} block a plank-textured block belongs to (spruce_slab → spruce_planks), or null. */
	private static Block plankBase(BlockState s) {
		Identifier id = Registries.BLOCK.getId(s.getBlock());
		String path = id.getPath();
		for (String suffix : PLANK_SUFFIXES) {
			if (path.endsWith(suffix)) {
				String base = path.substring(0, path.length() - suffix.length());
				Identifier plankId = Identifier.of(id.getNamespace(), base + "_planks");
				Block plank = Registries.BLOCK.getOrEmpty(plankId).orElse(null);
				if (plank != null) {
					return plank;
				}
			}
		}
		return null;
	}

	/** A cloned block must not reintroduce a hole (air) or the frame look (portal/obsidian). */
	private static boolean cloneable(BlockState s) {
		return !s.isAir() && !s.isOf(Blocks.NETHER_PORTAL) && !s.isOf(Blocks.OBSIDIAN);
	}
}

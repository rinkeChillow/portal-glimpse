package com.rinke.portalglimpse.render;

import com.rinke.portalglimpse.PortalGlimpse;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.math.Direction;

/**
 * The god-ray occluder block, injected as terrain via {@link TerrainOverride} (never actually placed in the
 * world). It renders as a plain opaque black cube in the SOLID layer, so it writes the opaque gbuffer depth
 * (depthtex1) a shaderpack's volumetric march stops at — the whole reason the occluder works.
 *
 * <p>But its CULLING behaves like glass, which is what keeps the injected box clean:
 * <ul>
 *   <li>{@code nonOpaque()} in its settings ⇒ neighbouring DIFFERENT blocks never cull their faces against
 *       it. So a real block next to the box keeps its face whether or not we're present — removing/moving the
 *       box can never leave a culled-away hole in the surrounding terrain (the artefact from meshes that
 *       don't re-cull).</li>
 *   <li>{@link #isSideInvisible} self-culls against its own kind ⇒ occluder-to-occluder faces are hidden,
 *       saving quads (exactly how glass connects to glass).</li>
 * </ul>
 *
 * <p>Registered from the common entrypoint {@link PortalGlimpse}. It has no item, loot, or collision — it
 * only ever exists inside our chunk-mesh injection, so it is never obtained, dropped, or collided with.
 */
public class OccluderBlock extends Block {

	/** The registered instance (null until {@link #register} runs at mod init). */
	public static Block INSTANCE;

	public OccluderBlock(Settings settings) {
		super(settings);
	}

	public static void register() {
		// 1.21.2 derives the loot-table key from the block's own id while the block is being constructed,
		// so the settings must already carry the registry key — building the block first now throws
		// "Block id not set" before registration ever happens.
		RegistryKey<Block> key = RegistryKey.of(RegistryKeys.BLOCK, PortalGlimpse.id("occluder"));
		INSTANCE = Registry.register(Registries.BLOCK, key,
				new OccluderBlock(AbstractBlock.Settings.create()
						.registryKey(key)
						.mapColor(MapColor.BLACK)
						.nonOpaque()      // neighbours never cull against us ⇒ no leftover holes in real terrain
						.strength(-1.0F)  // unbreakable — it is never really in the world anyway
						.noCollision())); // render-only; collision follows the real (air) block underneath
	}

	@Override
	public boolean isSideInvisible(BlockState state, BlockState stateFrom, Direction direction) {
		return stateFrom.isOf(this) || super.isSideInvisible(state, stateFrom, direction);
	}
}

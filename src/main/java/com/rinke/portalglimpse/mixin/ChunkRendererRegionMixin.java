package com.rinke.portalglimpse.mixin;

import com.rinke.portalglimpse.ghost.GhostState;
import com.rinke.portalglimpse.render.GlimpseRenderState;
import com.rinke.portalglimpse.render.TerrainOverride;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.chunk.ChunkRendererRegion;
import net.minecraft.util.math.BlockPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Renderer-agnostic portal ghosting (design doc §3.2 step 3).
 *
 * <p>Fabric API's Indigo renderer replaces the vanilla terrain meshing loop, so hooking
 * {@code BlockRenderManager.renderBlock} does nothing. But every mesher — vanilla and Indigo —
 * reads block states for a section build through {@link ChunkRendererRegion#getBlockState}.
 * Returning air for a ghosted position removes the block from the mesh regardless of renderer,
 * and because neighbours now see air there, their faces draw normally (no black holes).
 */
@Mixin(ChunkRendererRegion.class)
public class ChunkRendererRegionMixin {

	@Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
	private void portalglimpse$hideGhostedBlocks(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
		// Capture ghosting (§3.2 step 3) and glimpse replacement (the vanilla portal quads make
		// way for our glimpse + veil rendering) share the same technique: mesh a substitute state.
		// Ghosting may clone a wall block for embedded frame obsidian (no hole in the capture);
		// everything else — interior blocks, free-standing frame, glimpse positions — meshes as air.
		BlockState ghostReplacement = GhostState.replacementFor(pos);
		if (ghostReplacement != null) {
			cir.setReturnValue(ghostReplacement);
			return;
		}
		// Terrain injection (TerrainOverride): mesh a substitute state at chosen positions — real terrain
		// to every renderer/shaderpack (god-ray occlusion work; also the shadow-cube debug tool).
		BlockState injected = TerrainOverride.replacementFor(pos.asLong());
		if (injected != null) {
			cir.setReturnValue(injected);
		} else if (GlimpseRenderState.isHidden(pos)) {
			cir.setReturnValue(Blocks.AIR.getDefaultState());
		}
	}
}

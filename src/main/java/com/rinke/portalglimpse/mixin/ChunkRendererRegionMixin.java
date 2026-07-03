package com.rinke.portalglimpse.mixin;

import com.rinke.portalglimpse.ghost.GhostState;

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
		if (GhostState.isHidden(pos)) {
			cir.setReturnValue(Blocks.AIR.getDefaultState());
		}
	}
}

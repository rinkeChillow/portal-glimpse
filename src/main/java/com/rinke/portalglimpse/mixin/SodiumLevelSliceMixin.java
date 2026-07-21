package com.rinke.portalglimpse.mixin;

import com.rinke.portalglimpse.ghost.GhostState;
import com.rinke.portalglimpse.render.GlimpseRenderState;
import com.rinke.portalglimpse.render.TerrainOverride;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sodium port of {@link ChunkRendererRegionMixin}. Sodium's mesher never touches vanilla's
 * {@code ChunkRendererRegion} — its worker threads read block states through its own snapshot,
 * {@code LevelSlice}. Every read funnels into {@code getBlockState(int,int,int)} (the vanilla
 * {@code BlockRenderView} override delegates to it), so substituting there gives us the same
 * renderer-agnostic ghosting/glimpse-hiding we have on the vanilla path.
 *
 * <p>{@code @Pseudo}: the target only exists when Sodium is installed — silently skipped otherwise.
 * {@code remap = false}: {@code getBlockState(int,int,int)} is Sodium's own (never-remapped) method, so
 * we match it literally. Its bare name is ambiguous — in dev, yarn also names the {@code BlockView}
 * override {@code getBlockState(BlockPos)} — so we pin the {@code (III)} descriptor. The return type is
 * yarn {@code BlockState} in a dev run but intermediary {@code class_2680} in production, so BOTH
 * selectors are listed and whichever matches this environment wins. {@code require = 0}: if a future
 * Sodium renames/moves it, degrade to "no hiding" instead of crashing. Both lookups are
 * volatile-snapshot reads (safe from Sodium's chunk-build workers) and allocation-free on this hot path.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.world.LevelSlice", remap = false)
public class SodiumLevelSliceMixin {

	@Inject(method = {
			"getBlockState(III)Lnet/minecraft/block/BlockState;", // dev (yarn-named)
			"getBlockState(III)Lnet/minecraft/class_2680;"        // production (intermediary)
	}, at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private void portalglimpse$hideGhostedBlocks(int x, int y, int z,
			CallbackInfoReturnable<BlockState> cir) {
		long packed = BlockPos.asLong(x, y, z);
		BlockState ghostReplacement = GhostState.replacementFor(packed);
		if (ghostReplacement != null) {
			cir.setReturnValue(ghostReplacement);
			return;
		}
		// Terrain injection (TerrainOverride) — see ChunkRendererRegionMixin; allocation-free packed lookup.
		BlockState injected = TerrainOverride.replacementFor(packed);
		if (injected != null) {
			cir.setReturnValue(injected);
		} else if (GlimpseRenderState.isHidden(packed)) {
			cir.setReturnValue(Blocks.AIR.getDefaultState());
		}
	}
}

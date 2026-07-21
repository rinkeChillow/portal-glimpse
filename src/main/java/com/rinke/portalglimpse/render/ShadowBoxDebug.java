package com.rinke.portalglimpse.render;

import java.util.HashMap;
import java.util.Map;

import com.rinke.portalglimpse.PortalGlimpse;

import org.lwjgl.glfw.GLFW;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

/**
 * God-ray debug tool: Ctrl+Shift+right-click any non-portal block to conjure a SOLID 5×5×5 black
 * concrete cube centred on it; Ctrl+Shift+right-click again (anywhere) to remove it. Ctrl+Shift only,
 * gated on {@code /pgdebug} ({@link GlimpseSettings#debugMode}).
 *
 * <p>The cube is injected as REAL TERRAIN via {@link TerrainOverride} (the same mesh hooks as
 * capture-ghosting), so vanilla/Sodium mesh it into the terrain gbuffer pass — it writes
 * depthtex0+depthtex1 and is treated by any shaderpack exactly like a hand-placed block. Both BSL's
 * and Photon's volumetric god-ray marches are bounded only by that gbuffer depth (verified in the
 * packs' GLSL), so this is the honest test — and the template for the portal fix — of blocking the
 * rays the way a hand-built concrete cube provably does. Render-only: no collision, no world change.
 */
public final class ShadowBoxDebug {

	private static final int HALF = 2; // 5×5×5: center ± 2

	private static volatile BlockPos center;

	private ShadowBoxDebug() {
	}

	public static void register() {
		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			if (!world.isClient || hand != Hand.MAIN_HAND || !GlimpseSettings.debugMode) {
				return ActionResult.PASS;
			}
			MinecraftClient client = MinecraftClient.getInstance();
			long handle = client.getWindow().getHandle();
			boolean ctrl = InputUtil.isKeyPressed(handle, GLFW.GLFW_KEY_LEFT_CONTROL)
					|| InputUtil.isKeyPressed(handle, GLFW.GLFW_KEY_RIGHT_CONTROL);
			boolean shift = InputUtil.isKeyPressed(handle, GLFW.GLFW_KEY_LEFT_SHIFT)
					|| InputUtil.isKeyPressed(handle, GLFW.GLFW_KEY_RIGHT_SHIFT);
			if (!ctrl || !shift) {
				return ActionResult.PASS;
			}
			// Portal blocks belong to ManualCapture (registered before us; consumes those clicks first).
			if (world.getBlockState(hit.getBlockPos()).isOf(Blocks.NETHER_PORTAL)) {
				return ActionResult.PASS;
			}
			toggle(client, hit.getBlockPos());
			return ActionResult.FAIL; // eat the interaction — no block place / item use
		});

		// Auto-remove the cube if its anchor block is broken.
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			BlockPos c = center;
			if (c != null && client.world != null && client.world.getBlockState(c).isAir()) {
				remove(client, c);
			}
		});
	}

	private static void toggle(MinecraftClient client, BlockPos clicked) {
		BlockPos existing = center;
		if (existing != null) {
			remove(client, existing);
			overlay(client, "Shadow cube removed");
			return;
		}
		BlockPos c = clicked.toImmutable();
		BlockState concrete = Blocks.BLACK_CONCRETE.getDefaultState();
		Map<Long, BlockState> cube = new HashMap<>();
		for (int dx = -HALF; dx <= HALF; dx++) {
			for (int dy = -HALF; dy <= HALF; dy++) {
				for (int dz = -HALF; dz <= HALF; dz++) {
					cube.put(BlockPos.asLong(c.getX() + dx, c.getY() + dy, c.getZ() + dz), concrete);
				}
			}
		}
		TerrainOverride.setDebug(cube);
		center = c;
		remesh(client, c);
		overlay(client, "Shadow cube planted at " + c.toShortString());
		PortalGlimpse.LOGGER.info("Shadow cube planted at {}", c.toShortString());
	}

	private static void remove(MinecraftClient client, BlockPos c) {
		TerrainOverride.clearDebug();
		center = null;
		remesh(client, c);
	}

	/** Re-mesh the cube's region (±1 padding so neighbour faces update) — Sodium routes through the same
	 * scheduleBlockRenders override the ghost system already relies on. */
	private static void remesh(MinecraftClient client, BlockPos c) {
		if (client.worldRenderer != null) {
			client.worldRenderer.scheduleBlockRenders(
					c.getX() - HALF - 1, c.getY() - HALF - 1, c.getZ() - HALF - 1,
					c.getX() + HALF + 1, c.getY() + HALF + 1, c.getZ() + HALF + 1);
		}
	}

	private static void overlay(MinecraftClient client, String msg) {
		if (client.player != null) {
			client.inGameHud.setOverlayMessage(Text.literal(msg), false);
		}
	}
}

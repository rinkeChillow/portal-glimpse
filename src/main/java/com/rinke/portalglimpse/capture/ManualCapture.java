package com.rinke.portalglimpse.capture;

import org.lwjgl.glfw.GLFW;

import com.rinke.portalglimpse.data.PortalRecord;
import com.rinke.portalglimpse.data.PortalStore;
import com.rinke.portalglimpse.detect.PortalDetection;
import com.rinke.portalglimpse.render.ManualCaptureFlash;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Manual capture (design doc §3.4, the two-slot system): Ctrl+Shift+right-click a nether portal
 * (its blocks or surrounding frame) to pin a player-curated "custom glimpse" that wins over the
 * automatic one. Cycles per click: capture (veil pulses green) → cancel (pulses red, reverts to the
 * auto glimpse) → capture again, etc. Client-side only.
 */
public final class ManualCapture {

	private ManualCapture() {
	}

	public static void register() {
		UseBlockCallback.EVENT.register(ManualCapture::onUseBlock);
	}

	private static ActionResult onUseBlock(PlayerEntity player, World world, Hand hand, BlockHitResult hit) {
		if (!world.isClient || hand != Hand.MAIN_HAND) {
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
		PortalRecord record = portalAt(client, hit.getBlockPos());
		if (record == null) {
			return ActionResult.PASS;
		}
		toggle(client, record);
		return ActionResult.SUCCESS; // consume, so we don't place a block / use the held item
	}

	private static void toggle(MinecraftClient client, PortalRecord record) {
		PortalStore store = PortalDetection.store();
		if (store == null) {
			return;
		}
		if (CaptureManager.isBusy()) {
			feedback(client, "Capture in progress…", Formatting.YELLOW);
			return;
		}
		// A portal shows its DESTINATION, so a capture of THIS dimension belongs to the LINKED portal
		// in the other dimension (the one that leads here) — same as travel auto-capture (camera here,
		// saved to the linked portal). Requires the pair to have been linked by travelling through once.
		PortalRecord target = record.linkedId != null ? store.get(record.linkedId) : null;
		if (target == null) {
			feedback(client, "Travel through this portal once to link it, then capture", Formatting.YELLOW);
			return;
		}
		if (target.manual.hasCapture && target.manual.pinned) {
			// Cancel: just unpin and show the red glow. No capture happens here — the auto glimpse is
			// re-earned naturally the next time you travel INTO that side's dimension (its normal trip),
			// which then sees the manual is gone, auto-captures, and chimes.
			target.manual.pinned = false;
			store.save(target);
			ManualCaptureFlash.red(record.id);
		} else {
			// Capture THIS dimension into the LINKED portal's manual slot; it pins + messages on success.
			CaptureManager.request(client, record, target, true, () -> ManualCaptureFlash.green(record.id));
		}
	}

	/** The registered portal the clicked block belongs to (interior block or surrounding frame), or null. */
	private static PortalRecord portalAt(MinecraftClient client, BlockPos pos) {
		PortalStore store = PortalDetection.store();
		if (store == null || client.world == null) {
			return null;
		}
		PortalRecord direct = store.recordAt(pos);
		if (direct != null) {
			return direct;
		}
		// Clicked the obsidian frame: accept the nearest portal if this block hugs its opening.
		PortalRecord nearest = store.findNearest(pos, client.world.getRegistryKey().getValue());
		return nearest != null && touchesFrame(nearest, pos) ? nearest : null;
	}

	/** True if {@code pos} is within the portal's interior bounding box expanded by 1 (its frame). */
	private static boolean touchesFrame(PortalRecord record, BlockPos pos) {
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (BlockPos p : record.interior) {
			minX = Math.min(minX, p.getX());
			maxX = Math.max(maxX, p.getX());
			minY = Math.min(minY, p.getY());
			maxY = Math.max(maxY, p.getY());
			minZ = Math.min(minZ, p.getZ());
			maxZ = Math.max(maxZ, p.getZ());
		}
		return pos.getX() >= minX - 1 && pos.getX() <= maxX + 1
				&& pos.getY() >= minY - 1 && pos.getY() <= maxY + 1
				&& pos.getZ() >= minZ - 1 && pos.getZ() <= maxZ + 1;
	}

	private static void feedback(MinecraftClient client, String text, Formatting color) {
		if (client.player != null) {
			client.player.sendMessage(Text.literal("[Portal Glimpse] " + text).formatted(color), true);
		}
	}
}

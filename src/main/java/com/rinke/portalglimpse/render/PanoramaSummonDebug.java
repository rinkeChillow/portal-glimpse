package com.rinke.portalglimpse.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import com.rinke.portalglimpse.data.PortalRecord;
import com.rinke.portalglimpse.data.PortalStore;

import org.lwjgl.glfw.GLFW;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Debug tool: Ctrl+Shift+right-click a (non-portal) block to summon — or clear — a floating 3×3 panorama
 * above it, showing a stored glimpse, for previewing captures without a portal. All summons share ONE
 * randomly-chosen glimpse: a portal in the CURRENT dimension (its capture shows the destination), so in the
 * Overworld you see a Nether panorama and vice versa. Gated on {@code /pgdebug}.
 *
 * <p>The facing is FIXED at summon time (the cardinal axis the player is on when they click), so it keeps
 * that direction and does NOT swivel to face the camera afterwards. {@code VanillaGlimpseRenderer} turns the
 * summons into 3×3 quads and renders them through the real RTT path (Iris-shaded).
 */
public final class PanoramaSummonDebug {

	/** A summon's frozen orientation: which portal-plane axis it lies in, and which side it faces. */
	public record Summon(boolean axisX, boolean faceA) {
	}

	private static final Map<BlockPos, Summon> SUMMONED = new ConcurrentHashMap<>();
	/** The one glimpse (record id) all summons show; re-picked if it becomes invalid for the dimension. */
	private static volatile UUID chosenId;

	private PanoramaSummonDebug() {
	}

	public static void register() {
		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
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
			// Leave real portals to ManualCapture (it's registered first and consumes them); only act on other
			// blocks. From here we always give feedback + eat the click, so a missing message means the modifier
			// wasn't read, not a silent bail.
			if (world.getBlockState(hit.getBlockPos()).isOf(Blocks.NETHER_PORTAL)) {
				return ActionResult.PASS;
			}
			if (!GlimpseSettings.debugMode) {
				overlay(client, "Panorama summon needs /pgdebug ON");
				return ActionResult.FAIL;
			}
			BlockPos pos = hit.getBlockPos().toImmutable();
			if (SUMMONED.remove(pos) != null) {
				overlay(client, "Panorama summons: " + SUMMONED.size());
				return ActionResult.FAIL;
			}
			// Freeze the facing from where the player stands NOW (relative to the panorama centre above the
			// block) — snapped to a cardinal axis. It keeps this direction regardless of later camera movement.
			Vec3d eye = player.getEyePos();
			double dx = eye.x - (pos.getX() + 0.5);
			double dz = eye.z - (pos.getZ() + 0.5);
			boolean axisX = Math.abs(dx) < Math.abs(dz); // player mostly along Z ⇒ plane faces ±Z ⇒ portal axis X
			boolean faceA = axisX ? dz < 0 : dx < 0;
			SUMMONED.put(pos, new Summon(axisX, faceA));
			overlay(client, "Panorama summons: " + SUMMONED.size());
			return ActionResult.FAIL; // eat the interaction
		});
	}

	private static void overlay(MinecraftClient client, String msg) {
		if (client.player != null) {
			client.inGameHud.setOverlayMessage(Text.literal(msg), false);
		}
	}

	public static boolean isActive() {
		return GlimpseSettings.debugMode && !SUMMONED.isEmpty();
	}

	public static Map<BlockPos, Summon> summons() {
		return SUMMONED;
	}

	/**
	 * The chosen glimpse to show on every summon — a portal in {@code currentDim} that has a capture (its
	 * panorama therefore shows the OTHER dimension). Picked once at random and kept until it stops being a
	 * valid candidate. Returns {@code null} if nothing is captured for this dimension yet.
	 */
	public static PortalRecord chosen(PortalStore store, Identifier currentDim) {
		if (store == null) {
			return null;
		}
		UUID id = chosenId;
		if (id != null) {
			PortalRecord cur = store.get(id);
			if (cur != null && cur.dimension.equals(currentDim) && hasCapture(cur)) {
				return cur;
			}
		}
		List<PortalRecord> candidates = new ArrayList<>();
		for (PortalRecord r : store.all()) {
			if (r.dimension.equals(currentDim) && hasCapture(r)) {
				candidates.add(r);
			}
		}
		if (candidates.isEmpty()) {
			chosenId = null;
			return null;
		}
		PortalRecord pick = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
		chosenId = pick.id;
		return pick;
	}

	private static boolean hasCapture(PortalRecord r) {
		return r.auto.hasCapture || r.manual.hasCapture;
	}
}

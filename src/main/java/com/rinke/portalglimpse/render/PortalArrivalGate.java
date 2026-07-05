package com.rinke.portalglimpse.render;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;

/**
 * Tracks whether the player has just been carried between dimensions and is still standing in the
 * portal they arrived through. Only while that's true does the renderer hide that portal's glimpse,
 * so a fresh teleport doesn't drop the player looking straight through the panorama at point-blank.
 *
 * <p>Crucially the gate is armed <b>only by a dimension change</b>, never by proximity — so walking
 * up to a portal (even stepping into it just before travelling) keeps the glimpse the whole way. It
 * disarms the moment the player steps clear of the portal blocks after arrival.
 */
public final class PortalArrivalGate {

	/** How long (ticks) after a dimension change we keep waiting for the player to land in the portal
	 * before giving up and disarming — a safety net for teleports that don't drop you in a portal. */
	private static final int ARRIVAL_GRACE_TICKS = 60;

	/** Armed by a dimension change, disarmed once the player clears the arrival portal. Read from the
	 * render thread, written on the client tick. */
	private static volatile boolean armed = false;

	private static Identifier lastDimension;
	private static boolean enteredPortal;
	private static int ticksSinceArm;

	private PortalArrivalGate() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(PortalArrivalGate::onTick);
	}

	/** True from a dimension change until the player has stepped clear of the arrival portal. */
	public static boolean isArmed() {
		return armed;
	}

	private static void onTick(MinecraftClient client) {
		ClientWorld world = client.world;
		ClientPlayerEntity player = client.player;
		if (world == null || player == null) {
			lastDimension = null; // left the world — a later re-join must not look like a teleport
			armed = false;
			return;
		}

		Identifier dimension = world.getRegistryKey().getValue();
		if (lastDimension != null && !dimension.equals(lastDimension)) {
			armed = true; // changed dimension — suppress until we clear the portal we arrived in
			enteredPortal = false;
			ticksSinceArm = 0;
		}
		lastDimension = dimension;

		if (armed) {
			ticksSinceArm++;
			if (standingInPortal(player, world)) {
				enteredPortal = true;
			} else if (enteredPortal || ticksSinceArm > ARRIVAL_GRACE_TICKS) {
				// Stepped out of the arrival portal (or never landed in one) — back to normal.
				armed = false;
			}
		}
	}

	/** True while any block the player's bounding box overlaps is a nether portal. */
	private static boolean standingInPortal(ClientPlayerEntity player, ClientWorld world) {
		Box box = player.getBoundingBox();
		int minX = MathHelper.floor(box.minX);
		int maxX = MathHelper.floor(box.maxX);
		int minY = MathHelper.floor(box.minY);
		int maxY = MathHelper.floor(box.maxY);
		int minZ = MathHelper.floor(box.minZ);
		int maxZ = MathHelper.floor(box.maxZ);
		BlockPos.Mutable pos = new BlockPos.Mutable();
		for (int x = minX; x <= maxX; x++) {
			for (int y = minY; y <= maxY; y++) {
				for (int z = minZ; z <= maxZ; z++) {
					if (world.getBlockState(pos.set(x, y, z)).isOf(Blocks.NETHER_PORTAL)) {
						return true;
					}
				}
			}
		}
		return false;
	}
}

package com.rinke.portalglimpse.data;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * Deterministic portal identity (design doc §5.3).
 *
 * <p>The id is derived purely from the dimension plus the exact set of portal-interior block
 * positions. Consequences, straight from the spec:
 * <ul>
 *   <li>Same coordinates + same block set = same id — relighting the same shape resumes the
 *       glimpse ("the curtains are the same").</li>
 *   <li>A different shape at the same spot hashes differently = a new portal, vanilla until traveled.</li>
 * </ul>
 */
public final class PortalIdentity {

	private PortalIdentity() {
	}

	public static UUID compute(Identifier dimension, List<BlockPos> interior) {
		List<Long> longs = new ArrayList<>(interior.size());
		for (BlockPos pos : interior) {
			longs.add(pos.asLong());
		}
		longs.sort(Long::compareTo);

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		byte[] dim = dimension.toString().getBytes(StandardCharsets.UTF_8);
		bytes.write(dim, 0, dim.length);
		for (long value : longs) {
			for (int i = 7; i >= 0; i--) {
				bytes.write((int) ((value >> (i * 8)) & 0xFF));
			}
		}
		return UUID.nameUUIDFromBytes(bytes.toByteArray());
	}
}

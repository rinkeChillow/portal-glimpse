package com.rinke.portalglimpse.render;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A glow-outline signal on a portal's opening for manual-capture feedback: the outline blinks TWICE
 * in green when a custom glimpse is pinned, or twice in red when it's cancelled, then disappears.
 * The veil renderer draws the outline; this class just tracks the colour + blink timing per portal.
 */
public final class ManualCaptureFlash {

	private static final long BLINK_ON_MS = 150L;
	private static final long BLINK_PERIOD_MS = 300L; // 150 ms on + 150 ms off
	private static final int BLINKS = 2;
	private static final int GREEN = 0x40FF40;
	private static final int RED = 0xFF4040;

	private record Flash(int colour, long start) {
	}

	private static final Map<UUID, Flash> FLASHES = new ConcurrentHashMap<>();

	private ManualCaptureFlash() {
	}

	/** Blink the portal's outline green twice (custom glimpse set). */
	public static void green(UUID id) {
		FLASHES.put(id, new Flash(GREEN, System.currentTimeMillis()));
	}

	/** Blink the portal's outline red twice (custom glimpse cancelled). */
	public static void red(UUID id) {
		FLASHES.put(id, new Flash(RED, System.currentTimeMillis()));
	}

	/** The portals with a flash in progress (a snapshot copy is safe to iterate). */
	public static Set<UUID> activeIds() {
		return FLASHES.keySet();
	}

	/**
	 * The outline colour for this portal right now, packed 0xRRGGBB while in an "on" blink, or -1 when
	 * off (between blinks) or finished (the entry is then dropped).
	 */
	public static int outlineColor(UUID id) {
		Flash flash = FLASHES.get(id);
		if (flash == null) {
			return -1;
		}
		long elapsed = System.currentTimeMillis() - flash.start();
		if (elapsed >= BLINKS * BLINK_PERIOD_MS) {
			FLASHES.remove(id);
			return -1;
		}
		boolean on = (elapsed % BLINK_PERIOD_MS) < BLINK_ON_MS;
		return on ? flash.colour() : -1;
	}
}

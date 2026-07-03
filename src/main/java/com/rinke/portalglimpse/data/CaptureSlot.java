package com.rinke.portalglimpse.data;

/**
 * One capture slot of a portal record (design doc §3.4, the two-slot system).
 *
 * <p>Every portal holds an automatic slot (refreshed by travel, with cooldown) and a manual slot
 * (player-curated; when pinned it wins over automatic). Phase 1 only tracks the metadata — the
 * actual PNG assets are written in Phase 2.
 */
public class CaptureSlot {

	/** Whether this slot currently has capture assets on disk. */
	public boolean hasCapture;

	/** Manual slot only: when pinned, manual always wins over automatic (§3.4). */
	public boolean pinned;

	/** Epoch millis of the last capture into this slot — feeds the auto-capture cooldown (§3.3). */
	public long timestamp;

	public CaptureSlot() {
	}

	public CaptureSlot(boolean hasCapture, boolean pinned, long timestamp) {
		this.hasCapture = hasCapture;
		this.pinned = pinned;
		this.timestamp = timestamp;
	}
}

package de.kiexperten;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * All timestamps are stored as UTC {@link LocalDateTime} and exposed through the API as {@link Instant}
 * (ISO string ending in "Z"), so clients render them in their own time zone.
 */
public final class Zeit {

	private Zeit() {
	}

	public static LocalDateTime nowUtc() {
		return LocalDateTime.now(ZoneOffset.UTC);
	}

	public static LocalDateTime utc(final Instant instant) {
		return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	public static Instant instant(final LocalDateTime utc) {
		return utc == null ? null : utc.toInstant(ZoneOffset.UTC);
	}
}

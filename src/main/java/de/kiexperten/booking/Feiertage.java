package de.kiexperten.booking;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.Map;

final class Feiertage {
	private Feiertage() {
	}

	static Map<LocalDate, String> imJahr(final int year) {
		final Map<LocalDate, String> days = new LinkedHashMap<>();
		days.put(LocalDate.of(year, 1, 1), "Neujahr");
		days.put(LocalDate.of(year, 1, 6), "Heilige Drei Könige");
		days.put(LocalDate.of(year, 3, 8), "Internationaler Frauentag");
		days.put(LocalDate.of(year, 5, 1), "Tag der Arbeit");
		days.put(LocalDate.of(year, 8, 8), "Augsburger Friedensfest");
		days.put(LocalDate.of(year, 8, 15), "Mariä Himmelfahrt");
		days.put(LocalDate.of(year, 9, 20), "Weltkindertag");
		days.put(LocalDate.of(year, 10, 3), "Tag der Deutschen Einheit");
		days.put(LocalDate.of(year, 10, 31), "Reformationstag");
		days.put(LocalDate.of(year, 11, 1), "Allerheiligen");
		days.put(LocalDate.of(year, 11, 23).with(TemporalAdjusters.previous(DayOfWeek.WEDNESDAY)),
				"Buß- und Bettag");
		days.put(LocalDate.of(year, 12, 24), "Weihnachten");
		days.put(LocalDate.of(year, 12, 25), "1. Weihnachtstag");
		days.put(LocalDate.of(year, 12, 26), "2. Weihnachtstag");
		days.put(LocalDate.of(year, 12, 31), "Silvester");

		final LocalDate easter = ostersonntag(year);
		days.put(easter.minusDays(2), "Karfreitag");
		days.put(easter, "Ostersonntag");
		days.put(easter.plusDays(1), "Ostermontag");
		days.put(easter.plusDays(39), "Christi Himmelfahrt");
		days.put(easter.plusDays(49), "Pfingstsonntag");
		days.put(easter.plusDays(50), "Pfingstmontag");
		days.put(easter.plusDays(60), "Fronleichnam");
		if (year == 2020 || year == 2025) {
			days.put(LocalDate.of(year, 5, 8), "Tag der Befreiung");
		}
		return days;
	}

	// Gregorian computus.
	private static LocalDate ostersonntag(final int year) {
		final int a = year % 19;
		final int b = year / 100;
		final int c = year % 100;
		final int d = b / 4;
		final int e = b % 4;
		final int f = (b + 8) / 25;
		final int g = (b - f + 1) / 3;
		final int h = (19 * a + b - d - g + 15) % 30;
		final int i = c / 4;
		final int k = c % 4;
		final int l = (32 + 2 * e + 2 * i - h - k) % 7;
		final int m = (a + 11 * h + 22 * l) / 451;
		final int month = (h + l - 7 * m + 114) / 31;
		final int day = (h + l - 7 * m + 114) % 31 + 1;
		return LocalDate.of(year, month, day);
	}
}

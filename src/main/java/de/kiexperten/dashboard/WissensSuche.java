package de.kiexperten.dashboard;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;

@Service
public class WissensSuche {
	private static final int MAX_TREFFER = 5;
	private static final int MAX_ZEICHEN = 1200;
	private static final int MIN_BEGRIFFSLAENGE = 3;

	private final DashboardRepository repository;

	public WissensSuche(final DashboardRepository repository) {
		this.repository = repository;
	}

	/** Keyword search over the knowledge sources the admin released for the KI. */
	public String suche(final String suchbegriffe) {
		final List<String> begriffe = Arrays.stream(lower(suchbegriffe).split("[^\\p{L}\\p{N}]+"))
				.filter(b -> b.length() >= MIN_BEGRIFFSLAENGE)
				.distinct()
				.toList();
		if (begriffe.isEmpty())
			return "Keine Treffer: keine verwertbaren Suchbegriffe.";

		final List<Treffer> treffer = this.repository.kiWissen().stream()
				.map(w -> bewerten(w.getTitle(), w.getContent(), begriffe))
				.filter(t -> t.punkte() > 0)
				.sorted(Comparator.comparingInt(Treffer::punkte).reversed())
				.limit(MAX_TREFFER)
				.toList();
		if (treffer.isEmpty())
			return "Keine Treffer in den Wissensquellen.";

		return treffer.stream()
				.map(t -> "Quelle: " + t.titel() + "\n" + kuerzen(t.text()))
				.collect(Collectors.joining("\n\n"));
	}

	private static Treffer bewerten(final String titel, final String html, final List<String> begriffe) {
		final String text = Jsoup.parse(html == null ? "" : html).text();
		final String titelLower = lower(titel);
		final String textLower = lower(text);
		int punkte = 0;
		for (final String begriff : begriffe)
			// Treffer im Titel zählen dreifach
			punkte += 3 * occurrences(titelLower, begriff) + occurrences(textLower, begriff);
		return new Treffer(titel, text, punkte);
	}

	private static String kuerzen(final String text) {
		return text.length() <= MAX_ZEICHEN ? text : text.substring(0, MAX_ZEICHEN) + " ...";
	}

	private static int occurrences(final String haystack, final String needle) {
		int count = 0;
		for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length()))
			count++;
		return count;
	}

	private static String lower(final String value) {
		return value == null ? "" : value.toLowerCase(Locale.GERMAN);
	}

	private record Treffer(String titel, String text, int punkte) {
	}
}

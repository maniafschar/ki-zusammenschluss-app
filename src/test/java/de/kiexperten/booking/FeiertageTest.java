package de.kiexperten.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.DayOfWeek;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class FeiertageTest {
	@Test
	void festeUndBeweglicheFeiertageAuchRegional() {
		final var days = Feiertage.imJahr(2026);
		assertEquals("Heilige Drei Könige", days.get(LocalDate.of(2026, 1, 6)));
		assertEquals("Augsburger Friedensfest", days.get(LocalDate.of(2026, 8, 8)));
		assertEquals("Mariä Himmelfahrt", days.get(LocalDate.of(2026, 8, 15)));
		assertEquals("Weltkindertag", days.get(LocalDate.of(2026, 9, 20)));
		assertEquals("Karfreitag", days.get(LocalDate.of(2026, 4, 3)));
		assertEquals("Ostersonntag", days.get(LocalDate.of(2026, 4, 5)));
		assertEquals("Ostermontag", days.get(LocalDate.of(2026, 4, 6)));
		assertEquals("Christi Himmelfahrt", days.get(LocalDate.of(2026, 5, 14)));
		assertEquals("Pfingstsonntag", days.get(LocalDate.of(2026, 5, 24)));
		assertEquals("Pfingstmontag", days.get(LocalDate.of(2026, 5, 25)));
		assertEquals("Fronleichnam", days.get(LocalDate.of(2026, 6, 4)));
		assertEquals("Buß- und Bettag", days.get(LocalDate.of(2026, 11, 18)));
		assertEquals("Buß- und Bettag", Feiertage.imJahr(2028).get(LocalDate.of(2028, 11, 22)));
		assertFalse(days.containsKey(LocalDate.of(2026, 11, 19)));
	}

	@Test
	void slotsUndBuchungenSchliessenFeiertageAus() {
		final TerminRepository repository = mock(TerminRepository.class);
		final BestaetigungsMailService mail = mock(BestaetigungsMailService.class);
		when(repository.findByAktiverStartZeitGreaterThanEqualAndAktiverStartZeitLessThan(any(), any()))
				.thenReturn(List.of());
		final TerminController controller = new TerminController(repository, mail);
		final var holidays = controller.feiertage();
		controller.slots().forEach(slot -> assertFalse(
				holidays.containsKey(slot.start().atZone(TerminController.ZONE).toLocalDate())));

		final var regionalHoliday = LocalDate.of(2026, 11, 18).atTime(LocalTime.of(10, 0))
				.atZone(TerminController.ZONE);
		assertFalse(TerminController.isValidSlot(regionalHoliday));
		final LocalDate today = LocalDate.now(TerminController.ZONE);
		final var upcoming = holidays.keySet().stream()
				.filter(day -> day.isAfter(today) && !day.isAfter(today.plusDays(TerminController.DAYS_AHEAD)))
				.filter(day -> day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY)
				.findFirst();
		assumeTrue(upcoming.isPresent(), "Kein Feiertag im Buchungszeitraum");
		final var start = upcoming.get().atTime(LocalTime.of(10, 0)).atZone(TerminController.ZONE).toInstant();
		final var request = new TerminController.BuchungRequest(start, null, "Test", "test@example.org", null, null, null);
		final var error = assertThrows(ResponseStatusException.class, () -> controller.book(request));
		assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
		verifyNoInteractions(mail);
	}
}

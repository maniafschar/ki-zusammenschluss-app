package de.kiexperten.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import de.kiexperten.Zeit;

class TerminControllerTest {
	@Test
	void bestaetigungErstelltMeetLinkUndSendetTermindaten() {
		final TerminRepository repository = mock(TerminRepository.class);
		final BestaetigungsMailService mail = mock(BestaetigungsMailService.class);
		final GoogleMeetService googleMeet = mock(GoogleMeetService.class);
		final TerminController controller = new TerminController(repository, mail, googleMeet);
		final LocalDateTime start = Zeit.nowUtc().plusDays(3);
		final Termin termin = new Termin();
		termin.setId(42L);
		termin.setStartZeit(start);
		termin.setGebuchtAm(Zeit.nowUtc());
		termin.setStatus("ANGEFRAGT");
		termin.setName("Ada Beispiel");
		termin.setEmail("ada@example.org");
		termin.setZeitzone("Europe/Berlin");
		termin.setNachricht("Bitte den Projektplan besprechen.");
		when(repository.findByBestaetigungsToken("token")).thenReturn(Optional.of(termin));
		when(googleMeet.createMeetLink(eq(termin), any())).thenReturn("https://meet.google.com/abc-defg-hij");

		final var response = controller.confirm("token");

		assertEquals(303, response.getStatusCode().value());
		assertEquals("BESTAETIGT", termin.getStatus());
		assertEquals("https://meet.google.com/abc-defg-hij", termin.getMeetLink());
		assertTrue(termin.isBestaetigungsMailGesendet());
		verify(googleMeet).createMeetLink(termin, ZoneId.of("Europe/Berlin"));
		verify(mail).sendBestaetigt("ada@example.org", "Ada Beispiel", Zeit.instant(start),
				ZoneId.of("Europe/Berlin"), "Bitte den Projektplan besprechen.",
				"https://meet.google.com/abc-defg-hij");
	}
}

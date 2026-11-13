package de.kiexperten.booking;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import de.kiexperten.Zeit;

class TerminErinnerungsServiceTest {

	@Test
	void sendetEineStundeVorherDieBestaetigungsMailEinmalErneut() {
		final TerminRepository repository = mock(TerminRepository.class);
		final BestaetigungsMailService mail = mock(BestaetigungsMailService.class);
		final TerminErinnerungsService service = new TerminErinnerungsService(repository, mail);
		final LocalDateTime start = Zeit.nowUtc().plusMinutes(59);
		final Termin termin = new Termin();
		termin.setId(42L);
		termin.setStartZeit(start);
		termin.setName("Ada Beispiel");
		termin.setEmail("ada@example.org");
		termin.setZeitzone("Europe/Berlin");
		termin.setNachricht("Bitte den Projektplan besprechen.");
		termin.setMeetLink("https://meet.google.com/abc-defg-hij");
		when(repository
				.findByStatusAndErinnerungsMailGesendetFalseAndStartZeitGreaterThanAndStartZeitLessThanEqual(
						any(), any(), any()))
				.thenReturn(List.of(termin));

		service.sendeFaelligeErinnerungen();

		final ArgumentCaptor<LocalDateTime> now = ArgumentCaptor.forClass(LocalDateTime.class);
		final ArgumentCaptor<LocalDateTime> deadline = ArgumentCaptor.forClass(LocalDateTime.class);
		verify(repository)
				.findByStatusAndErinnerungsMailGesendetFalseAndStartZeitGreaterThanAndStartZeitLessThanEqual(
						org.mockito.ArgumentMatchers.eq("BESTAETIGT"), now.capture(), deadline.capture());
		assertTrue(deadline.getValue().isEqual(now.getValue().plusHours(1)));
		verify(mail).sendBestaetigt("ada@example.org", "Ada Beispiel", Zeit.instant(start),
				ZoneId.of("Europe/Berlin"), "Bitte den Projektplan besprechen.",
				"https://meet.google.com/abc-defg-hij");
		assertTrue(termin.isErinnerungsMailGesendet());
		verify(repository).save(termin);
	}
}

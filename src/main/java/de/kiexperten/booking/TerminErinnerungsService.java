package de.kiexperten.booking;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import de.kiexperten.Zeit;

@Service
public class TerminErinnerungsService {

	private static final Logger log = LoggerFactory.getLogger(TerminErinnerungsService.class);

	private final TerminRepository termine;
	private final BestaetigungsMailService mail;

	public TerminErinnerungsService(final TerminRepository termine, final BestaetigungsMailService mail) {
		this.termine = termine;
		this.mail = mail;
	}

	@Scheduled(fixedDelay = 60_000)
	void sendeFaelligeErinnerungen() {
		final LocalDateTime now = Zeit.nowUtc();
		final List<Termin> faelligeTermine = this.termine
				.findByStatusAndErinnerungsMailGesendetFalseAndStartZeitGreaterThanAndStartZeitLessThanEqual(
						"BESTAETIGT", now, now.plusHours(1));
		for (final Termin termin : faelligeTermine) {
			try {
				this.mail.sendBestaetigt(termin.getEmail(), termin.getName(), Zeit.instant(termin.getStartZeit()),
						TerminController.zone(termin.getZeitzone()), termin.getNachricht(), termin.getMeetLink());
				termin.setErinnerungsMailGesendet(true);
				this.termine.save(termin);
			} catch (final RuntimeException e) {
				log.error("Terminerinnerung für Termin {} konnte nicht gesendet werden", termin.getId(), e);
			}
		}
	}
}

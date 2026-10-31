package de.kiexperten.booking;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/** Sends the confirmation link; without SMTP settings the link is only logged. */
@Service
public class BestaetigungsMailService {

	private static final Logger LOG = LoggerFactory.getLogger(BestaetigungsMailService.class);
	private static final DateTimeFormatter FORMAT = DateTimeFormatter
			.ofPattern("EEEE, dd.MM.yyyy 'um' HH:mm 'Uhr'", Locale.GERMANY);

	private final ObjectProvider<JavaMailSender> mailSender;
	private final String publicUrl;
	private final String from;

	public BestaetigungsMailService(final ObjectProvider<JavaMailSender> mailSender,
			@Value("${app.public-url:http://localhost:8080}") final String publicUrl,
			@Value("${app.mail.from:no-reply@localhost}") final String from) {
		this.mailSender = mailSender;
		this.publicUrl = publicUrl.replaceAll("/+$", "");
		this.from = from;
	}

	public void send(final String to, final String name, final LocalDateTime start, final String token,
			final int minutesValid) {
		final String link = this.publicUrl + "/api/termine/bestaetigen?token=" + token;
		final JavaMailSender sender = this.mailSender.getIfAvailable();
		if (sender == null) {
			LOG.warn("Kein SMTP-Server konfiguriert (spring.mail.host). Bestätigungslink für {}: {}", to, link);
			return;
		}
		final SimpleMailMessage mail = new SimpleMailMessage();
		mail.setFrom(this.from);
		mail.setTo(to);
		mail.setSubject("Bitte bestätigen Sie Ihren Termin");
		mail.setText("Hallo " + name + ",\n\n"
				+ "Sie haben einen Termin (30 Minuten) für " + FORMAT.format(start) + " angefragt.\n"
				+ "Bitte bestätigen Sie Ihre E-Mail-Adresse innerhalb von " + minutesValid + " Minuten:\n\n"
				+ link + "\n\n"
				+ "Wenn Sie den Termin nicht angefragt haben, ignorieren Sie diese Nachricht einfach.\n");
		sender.send(mail);
	}
}

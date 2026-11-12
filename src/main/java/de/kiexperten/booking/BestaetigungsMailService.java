package de.kiexperten.booking;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

/** Sends the confirmation link; without SMTP settings the link is only logged. */
@Service
public class BestaetigungsMailService {

	private static final Logger LOG = LoggerFactory.getLogger(BestaetigungsMailService.class);
	private static final DateTimeFormatter FORMAT = DateTimeFormatter
			.ofPattern("EEEE, dd.MM.yyyy 'um' HH:mm 'Uhr' (zzz)", Locale.GERMANY);

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

	public void send(final String to, final String name, final Instant start, final ZoneId zone,
			final String token, final int minutesValid) {
		final String link = this.publicUrl + "?type=termine/bestaetigen&token=" + token;
		final JavaMailSender sender = this.mailSender.getIfAvailable();
		if (sender == null) {
			LOG.warn("Kein SMTP-Server konfiguriert (spring.mail.host). Bestätigungslink für {}: {}", to, link);
			return;
		}
		final String when = FORMAT.format(start.atZone(zone));
		final String text = "Hallo " + name + ",\n\n"
				+ "Du hast einen Termin (30 Minuten) für " + when + " angefragt.\n"
				+ "Bitte bestätige Deine E-Mail-Adresse innerhalb von " + minutesValid + " Minuten:\n\n"
				+ link + "\n\n"
				+ "Wenn Du den Termin nicht angefragt hast, ignoriere diese Nachricht einfach.\n";
		try {
			final MimeMessage mail = sender.createMimeMessage();
			final MimeMessageHelper helper = new MimeMessageHelper(mail, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED,
					"UTF-8");
			helper.setFrom(this.from);
			helper.setTo(to);
			helper.setSubject("Bitte bestätige Deinen Termin");
			helper.setText(text, html(name, when, link, minutesValid));
			sender.send(mail);
		} catch (final MessagingException e) {
			throw new MailPreparationException(e);
		}
	}

	// Inline styles and tables only: most mail clients ignore <style> blocks and modern layout.
	private static String html(final String name, final String when, final String link, final int minutes) {
		final String n = HtmlUtils.htmlEscape(name);
		final String w = HtmlUtils.htmlEscape(when);
		final String l = HtmlUtils.htmlEscape(link);
		return """
				<!DOCTYPE html>
				<html lang="de"><body style="margin:0;padding:0;background:#f7fbf9;">
				<table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#f7fbf9;padding:24px 12px;">
				<tr><td align="center">
				<table role="presentation" width="560" cellpadding="0" cellspacing="0" style="max-width:560px;width:100%%;background:#ffffff;border-radius:14px;overflow:hidden;font-family:-apple-system,'Segoe UI',Helvetica,Arial,sans-serif;color:#1d2b3a;">
				<tr><td style="background:#0f9d7a;padding:24px 32px;color:#ffffff;font-size:20px;font-weight:700;">KI-Experten Zusammenschluss</td></tr>
				<tr><td style="padding:32px;">
				<h1 style="margin:0 0 16px;font-size:22px;">Hallo %s,</h1>
				<p style="margin:0 0 20px;font-size:16px;line-height:1.6;">Vielen Dank für Deine Terminanfrage. Bitte bestätige Deine E-Mail-Adresse, damit wir den Termin für Dich reservieren.</p>
				<table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#e3f7f0;border-radius:10px;margin:0 0 24px;">
				<tr><td style="padding:16px 20px;font-size:16px;"><strong>Dein Wunschtermin</strong><br>%s<br><span style="color:#5b6b7d;font-size:14px;">Dauer: 30 Minuten</span></td></tr></table>
				<table role="presentation" cellpadding="0" cellspacing="0" style="margin:0 0 24px;"><tr><td style="background:#0f9d7a;border-radius:8px;">
				<a href="%s" style="display:inline-block;padding:14px 28px;color:#ffffff;font-size:16px;font-weight:600;text-decoration:none;">Termin bestätigen</a></td></tr></table>
				<p style="margin:0 0 8px;font-size:14px;color:#5b6b7d;">Der Link ist %d Minuten gültig. Danach wird der Zeitslot wieder freigegeben.</p>
				<p style="margin:0 0 8px;font-size:14px;color:#5b6b7d;">Funktioniert der Button nicht? Kopiere diesen Link in Deinen Browser:<br><a href="%s" style="color:#0f9d7a;word-break:break-all;">%s</a></p>
				<p style="margin:16px 0 0;font-size:13px;color:#5b6b7d;">Wenn Du den Termin nicht angefragt hast, ignoriere diese Nachricht einfach.</p>
				</td></tr></table></td></tr></table></body></html>
				""".formatted(n, w, l, minutes, l, l);
	}
}

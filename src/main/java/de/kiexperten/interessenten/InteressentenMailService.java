package de.kiexperten.interessenten;

import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import de.kiexperten.content.Experte;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

@Service
public class InteressentenMailService {

	private static final String SUBJECT = "Zusammenarbeit mit dem KI-Experten Zusammenschluss";

	private final ObjectProvider<JavaMailSender> mailSender;
	private final String publicUrl;
	private final String from;

	public InteressentenMailService(final ObjectProvider<JavaMailSender> mailSender,
			@Value("${app.public-url:http://localhost:8080}") final String publicUrl,
			@Value("${app.mail.from:no-reply@localhost}") final String from) {
		this.mailSender = mailSender;
		this.publicUrl = publicUrl.replaceAll("/+$", "");
		this.from = from;
	}

	public String trackedLink(final Interessent i) {
		return this.publicUrl + "/api/r/" + i.getMarker();
	}

	public String landingUrl() {
		return this.publicUrl + "/";
	}

	/** Throws if the mail could not be sent, so the caller must not mark it as sent. */
	public void send(final Interessent i, final Experte betreuer) {
		final JavaMailSender sender = this.mailSender.getIfAvailable();
		if (sender == null) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
					"Kein SMTP-Server konfiguriert (spring.mail.host).");
		}
		final String link = trackedLink(i);
		final String greeting = greeting(i);
		final String body = i.getText() == null ? "" : i.getText().strip();
		final String contact = betreuer == null ? null
				: "Ihr Ansprechpartner: " + betreuer.getName() + ", " + betreuer.getRolle();
		final String plain = greeting + "\n\n" + body + "\n\n" + link + "\n"
				+ (contact == null ? "" : "\n" + contact + "\n");
		final String contactHtml = contact == null ? ""
				: "<p style=\"margin:0 0 16px;font-size:15px;color:#1d2b3a;\"><strong>Ihr Ansprechpartner</strong><br>"
						+ HtmlUtils.htmlEscape(betreuer.getName()) + " &middot; "
						+ HtmlUtils.htmlEscape(betreuer.getRolle()) + "</p>";
		final String html = """
				<!DOCTYPE html>
				<html lang="de"><body style="margin:0;padding:0;background:#f7fbf9;">
				<table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#f7fbf9;padding:24px 12px;">
				<tr><td align="center">
				<table role="presentation" width="560" cellpadding="0" cellspacing="0" style="max-width:560px;width:100%%;background:#ffffff;border-radius:14px;overflow:hidden;font-family:-apple-system,'Segoe UI',Helvetica,Arial,sans-serif;color:#1d2b3a;">
				<tr><td style="background:#0f9d7a;padding:24px 32px;color:#ffffff;font-size:20px;font-weight:700;">KI-Experten Zusammenschluss</td></tr>
				<tr><td style="padding:32px;">
				<p style="margin:0 0 16px;font-size:16px;line-height:1.6;">%s</p>
				<p style="margin:0 0 24px;font-size:16px;line-height:1.6;">%s</p>
				<table role="presentation" cellpadding="0" cellspacing="0" style="margin:0 0 24px;"><tr><td style="background:#0f9d7a;border-radius:8px;">
				<a href="%s" style="display:inline-block;padding:14px 28px;color:#ffffff;font-size:16px;font-weight:600;text-decoration:none;">Mehr erfahren</a></td></tr></table>
				%s
				<p style="margin:0;font-size:13px;color:#5b6b7d;">Funktioniert der Button nicht? <a href="%s" style="color:#0f9d7a;word-break:break-all;">%s</a></p>
				</td></tr></table></td></tr></table></body></html>
				""".formatted(HtmlUtils.htmlEscape(greeting), HtmlUtils.htmlEscape(body).replace("\n", "<br>"),
				HtmlUtils.htmlEscape(link), contactHtml, HtmlUtils.htmlEscape(link), HtmlUtils.htmlEscape(link));
		try {
			final MimeMessage mail = sender.createMimeMessage();
			final MimeMessageHelper helper = new MimeMessageHelper(mail, true, StandardCharsets.UTF_8.name());
			helper.setFrom(this.from);
			helper.setTo(i.getEmail());
			helper.setSubject(SUBJECT);
			helper.setText(plain, html);
			sender.send(mail);
		} catch (final MessagingException | MailException e) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "E-Mail konnte nicht gesendet werden.", e);
		}
	}

	private static String greeting(final Interessent i) {
		final String anrede = i.getAnrede() == null ? "" : i.getAnrede().strip();
		final String name = i.getName() == null ? "" : i.getName().strip();
		final String vorname = i.getVorname() == null ? "" : i.getVorname().strip();
		if (anrede.equalsIgnoreCase("Herr")) {
			return "Sehr geehrter Herr " + name + ",";
		}
		if (anrede.equalsIgnoreCase("Frau")) {
			return "Sehr geehrte Frau " + name + ",";
		}
		final String full = (anrede + " " + vorname + " " + name).replaceAll("\\s+", " ").strip();
		return full.isEmpty() ? "Guten Tag," : "Guten Tag " + full + ",";
	}
}

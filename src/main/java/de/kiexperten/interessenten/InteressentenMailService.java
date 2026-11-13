package de.kiexperten.interessenten;

import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;

import de.kiexperten.content.Experte;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

@Service
public class InteressentenMailService {

	private static final String SUBJECT = "Zusammenarbeit mit dem KI-Experten Zusammenschluss";
	private static final String FOTO_CID = "expertenfoto";

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

	/**
	 * Throws if the mail could not be sent, so the caller must not mark it as sent.
	 */
	public void send(final Interessent i, final Experte betreuer) {
		final JavaMailSender sender = this.mailSender.getIfAvailable();
		if (sender == null) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
					"Kein SMTP-Server konfiguriert (spring.mail.host).");
		}
		final String link = this.trackedLink(i);
		final String greeting = greeting(i);
		final String body = i.getText() == null ? "" : i.getText().strip();
		final String contact = betreuer == null ? null
				: "Dein Ansprechpartner: " + betreuer.getName() + ", " + betreuer.getRolle()
						+ (betreuer.getBeschreibung() == null || betreuer.getBeschreibung().isBlank() ? ""
								: "\n" + betreuer.getBeschreibung().strip());
		final String plain = greeting + "\n\n" + body + "\n\n" + link + "\n"
				+ (contact == null ? "" : "\n" + contact + "\n");
		final boolean hasFoto = betreuer != null && betreuer.getBildDaten() != null
				&& betreuer.getBildDaten().length > 0;
		final String contactHtml = contact == null ? ""
				: "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:0 0 24px;width:100%;border-top:1px solid #d9ece6;padding-top:20px;\"><tr>"
						+ (hasFoto ? "<td style=\"width:96px;vertical-align:top;padding-right:16px;\"><img src=\"cid:"
								+ FOTO_CID
								+ "\" alt=\"" + HtmlUtils.htmlEscape(betreuer.getName())
								+ "\" width=\"96\" height=\"96\" style=\"display:block;width:96px;height:96px;border-radius:50%;object-fit:cover;\"></td>"
								: "")
						+ "<td style=\"vertical-align:top;\">"
						+ "<p style=\"margin:0 0 4px;font-size:12px;font-weight:600;letter-spacing:.04em;text-transform:uppercase;color:#1f527c;\">Dein Ansprechpartner</p>"
						+ "<p style=\"margin:0 0 4px;font-size:18px;font-weight:700;color:#1d2b3a;\">"
						+ HtmlUtils.htmlEscape(betreuer.getName()) + "</p>"
						+ "<p style=\"margin:0 0 8px;font-size:14px;color:#1f527c;\">"
						+ HtmlUtils.htmlEscape(betreuer.getRolle()) + "</p>"
						+ (betreuer.getBeschreibung() == null || betreuer.getBeschreibung().isBlank() ? ""
								: "<p style=\"margin:0;font-size:15px;line-height:1.6;color:#1d2b3a;\">"
										+ HtmlUtils.htmlEscape(betreuer.getBeschreibung().strip()).replace("\n", "<br>")
										+ "</p>")
						+ "</td></tr></table>";
		final String bodyHtml = body.isEmpty() ? ""
				: "<div style=\"margin:0 0 24px;padding:16px 18px;background:#eef6fb;border-left:4px solid #1f527c;border-radius:8px;font-size:16px;line-height:1.6;color:#1d2b3a;\">"
						+ HtmlUtils.htmlEscape(body).replace("\n", "<br>") + "</div>";
		final String html = """
				<!DOCTYPE html>
				<html lang="de"><body style="margin:0;padding:0;background:#f7fbf9;">
				<table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#f7fbf9;padding:24px 12px;">
				<tr><td align="center">
				<table role="presentation" width="560" cellpadding="0" cellspacing="0" style="max-width:560px;width:100%%;background:#ffffff;border-radius:14px;overflow:hidden;font-family:-apple-system,'Segoe UI',Helvetica,Arial,sans-serif;color:#1d2b3a;">
				<tr><td style="background:#1f527c;padding:24px 32px;color:#ffffff;font-size:20px;font-weight:700;">KI-Experten Zusammenschluss</td></tr>
				<tr><td style="padding:32px;">
				<p style="margin:0 0 16px;font-size:16px;line-height:1.6;">%s</p>
				%s
				<table role="presentation" cellpadding="0" cellspacing="0" style="margin:0 0 24px;"><tr><td style="background:#1f527c;border-radius:8px;">
				<a href="%s" style="display:inline-block;padding:14px 28px;color:#ffffff;font-size:16px;font-weight:600;text-decoration:none;">Mehr erfahren</a></td></tr></table>
				%s
				<p style="margin:0;font-size:13px;color:#5b6b7d;">Funktioniert der Button nicht? <a href="%s" style="color:#1f527c;word-break:break-all;">%s</a></p>
				</td></tr></table></td></tr></table></body></html>
				"""
				.formatted(HtmlUtils.htmlEscape(greeting), bodyHtml,
						HtmlUtils.htmlEscape(link), contactHtml, HtmlUtils.htmlEscape(link),
						HtmlUtils.htmlEscape(link));
		try {
			final MimeMessage mail = sender.createMimeMessage();
			final MimeMessageHelper helper = new MimeMessageHelper(mail, true, StandardCharsets.UTF_8.name());
			helper.setFrom(this.from);
			helper.setTo(i.getEmail());
			helper.setSubject(SUBJECT);
			helper.setText(plain, html);
			if (hasFoto) {
				helper.addInline(FOTO_CID, new ByteArrayResource(betreuer.getBildDaten()),
						betreuer.getBildContentType());
			}
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

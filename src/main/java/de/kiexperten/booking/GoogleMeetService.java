package de.kiexperten.booking;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Service
public class GoogleMeetService {
	private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
	private static final String CALENDAR_API = "https://www.googleapis.com/calendar/v3/calendars/";

	private final RestClient http;
	private final ObjectMapper objectMapper;
	private final String clientId;
	private final String clientSecret;
	private final String refreshToken;
	private final String calendarId;

	public GoogleMeetService(final RestClient.Builder restClientBuilder, final ObjectMapper objectMapper,
			@Value("${GOOGLE_CLIENT_ID:}") final String clientId,
			@Value("${GOOGLE_CLIENT_SECRET:}") final String clientSecret,
			@Value("${GOOGLE_REFRESH_TOKEN:}") final String refreshToken,
			@Value("${GOOGLE_CALENDAR_ID:primary}") final String calendarId) {
		this.http = restClientBuilder.build();
		this.objectMapper = objectMapper;
		this.clientId = clientId;
		this.clientSecret = clientSecret;
		this.refreshToken = refreshToken;
		this.calendarId = calendarId;
	}

	public String createMeetLink(final Termin termin, final ZoneId zone) {
		if (this.clientId.isBlank() || this.clientSecret.isBlank() || this.refreshToken.isBlank()) {
			throw new IllegalStateException("Google Calendar ist nicht konfiguriert");
		}
		try {
			final String token = accessToken();
			final String eventId = "booking" + termin.getId();
			JsonNode event = createOrReadEvent(termin, zone, token, eventId);
			for (int attempt = 0; attempt < 5; attempt++) {
				final String link = meetLink(event);
				if (link != null) {
					return link;
				}
				if (attempt < 4) {
					Thread.sleep(1000);
					event = readEvent(token, eventId);
				}
			}
			throw new IllegalStateException("Google Meet-Link wurde nicht rechtzeitig bereitgestellt");
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Warten auf den Google Meet-Link wurde unterbrochen", e);
		} catch (final RuntimeException e) {
			if (e instanceof IllegalStateException stateException) {
				throw stateException;
			}
			throw new IllegalStateException("Google Meet-Link konnte nicht erstellt werden", e);
		}
	}

	private JsonNode createOrReadEvent(final Termin termin, final ZoneId zone, final String token,
			final String eventId) {
		final String calendar = UriUtils.encodePathSegment(this.calendarId, StandardCharsets.UTF_8);
		final String uri = CALENDAR_API + calendar + "/events?conferenceDataVersion=1";
		try {
			return this.http.post().uri(uri).headers(headers -> headers.setBearerAuth(token))
					.contentType(MediaType.APPLICATION_JSON).body(event(termin, zone, eventId)).retrieve()
					.body(JsonNode.class);
		} catch (final RestClientResponseException e) {
			if (e.getStatusCode().value() != 409) {
				throw e;
			}
			return readEvent(token, eventId);
		}
	}

	private JsonNode readEvent(final String token, final String eventId) {
		final String calendar = UriUtils.encodePathSegment(this.calendarId, StandardCharsets.UTF_8);
		return this.http.get().uri(CALENDAR_API + calendar + "/events/" + eventId)
				.headers(headers -> headers.setBearerAuth(token)).retrieve().body(JsonNode.class);
	}

	private ObjectNode event(final Termin termin, final ZoneId zone, final String eventId) {
		final ObjectNode event = this.objectMapper.createObjectNode();
		event.put("id", eventId);
		event.put("summary", "Kennenlerngespräch mit " + termin.getName());
		final ObjectNode start = event.putObject("start");
		start.put("dateTime", DateTimeFormatter.ISO_OFFSET_DATE_TIME
				.format(termin.getStartZeit().atZone(ZoneId.of("UTC")).withZoneSameInstant(zone)));
		start.put("timeZone", zone.getId());
		final ObjectNode end = event.putObject("end");
		end.put("dateTime", DateTimeFormatter.ISO_OFFSET_DATE_TIME
				.format(termin.getStartZeit().plusMinutes(30).atZone(ZoneId.of("UTC")).withZoneSameInstant(zone)));
		end.put("timeZone", zone.getId());
		final ObjectNode conference = event.putObject("conferenceData").putObject("createRequest");
		conference.put("requestId", UUID.nameUUIDFromBytes(("booking-" + termin.getId())
				.getBytes(StandardCharsets.UTF_8)).toString());
		conference.putObject("conferenceSolutionKey").put("type", "hangoutsMeet");
		return event;
	}

	private String accessToken() {
		final LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("grant_type", "refresh_token");
		form.add("client_id", this.clientId);
		form.add("client_secret", this.clientSecret);
		form.add("refresh_token", this.refreshToken);
		final JsonNode response = this.http.post().uri(TOKEN_URL).contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(form).retrieve().body(JsonNode.class);
		final String token = response == null ? "" : response.path("access_token").asText();
		if (token.isBlank()) {
			throw new IllegalStateException("Google OAuth hat kein Zugriffstoken geliefert");
		}
		return token;
	}

	private static String meetLink(final JsonNode event) {
		for (final JsonNode entry : event.path("conferenceData").path("entryPoints")) {
			if ("video".equals(entry.path("entryPointType").asText())) {
				final String uri = entry.path("uri").asText();
				if (uri.startsWith("https://meet.google.com/")) {
					return uri;
				}
			}
		}
		return null;
	}
}

package de.kiexperten.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

class GoogleMeetServiceTest {
	@Test
	void erstelltEinenTerminMitGoogleMeetKonferenz() {
		final RestClient.Builder builder = RestClient.builder();
		final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo("https://oauth2.googleapis.com/token"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("grant_type=refresh_token")))
				.andRespond(withSuccess("{\"access_token\":\"access-token\"}", MediaType.APPLICATION_JSON));
		server.expect(requestTo(
				"https://www.googleapis.com/calendar/v3/calendars/primary/events?conferenceDataVersion=1"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(jsonPath("$.start.dateTime").value("2027-01-15T11:00:00+01:00"))
				.andExpect(jsonPath("$.start.timeZone").value("Europe/Berlin"))
				.andExpect(jsonPath("$.end.dateTime").value("2027-01-15T11:30:00+01:00"))
				.andExpect(jsonPath("$.conferenceData.createRequest.conferenceSolutionKey.type")
						.value("hangoutsMeet"))
				.andRespond(withSuccess("""
						{"conferenceData":{"entryPoints":[
						  {"entryPointType":"video","uri":"https://meet.google.com/abc-defg-hij"}
						]}}
						""", MediaType.APPLICATION_JSON));
		final GoogleMeetService service = new GoogleMeetService(builder, new ObjectMapper(), "client-id",
				"client-secret", "refresh-token", "primary");
		final Termin termin = new Termin();
		termin.setId(42L);
		termin.setName("Ada Beispiel");
		termin.setStartZeit(LocalDateTime.of(2027, 1, 15, 10, 0));

		assertEquals("https://meet.google.com/abc-defg-hij",
				service.createMeetLink(termin, ZoneId.of("Europe/Berlin")));
		server.verify();
	}
}

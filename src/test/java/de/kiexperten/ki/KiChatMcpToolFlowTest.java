package de.kiexperten.ki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import de.kiexperten.booking.TerminController;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"spring.datasource.url=jdbc:h2:mem:ki-mcp-chat-test",
		"spring.datasource.username=sa", "spring.datasource.password=",
		"app.admin.username=admin", "app.admin.password=local-test-password",
		"app.ki.api-key=local-test-key", "app.ki.requirements-mcp.enabled=true",
		"spring.ai.mcp.client.enabled=false"
})
class KiChatMcpToolFlowTest {
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final Pattern CONVERSATION_ID = Pattern.compile("Gesprächs-ID für MCP-Werkzeuge: ([0-9a-f-]+)");
	private static final HttpServer upstream;

	static {
		try {
			upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			upstream.createContext("/chat/completions", exchange -> {
				final JsonNode request = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
				final JsonNode messages = request.path("messages");
				final boolean hasToolResult = java.util.stream.StreamSupport.stream(messages.spliterator(), false)
						.anyMatch(message -> "tool".equals(message.path("role").asText()));
				final String response;
				if (hasToolResult) {
					response = "{\"choices\":[{\"message\":{\"content\":\"Schritt 1: Was soll das Projekt erreichen?\"}}]}";
				} else {
					final var matcher = CONVERSATION_ID.matcher(messages.get(0).path("content").asText());
					if (!matcher.find() || !request.path("tools").isArray()) {
						exchange.sendResponseHeaders(400, -1);
						exchange.close();
						return;
					}
					final String userText = lastUserText(messages);
					if (userText.contains("[buchen]")) {
						response = toolCall("termin_buchen",
								"{\"start\":\"2030-01-07T08:00:00Z\",\"name\":\"Ada Lovelace\",\"email\":\"ada@example.org\"}");
					} else if (userText.contains("[zusammenfassung]")) {
						response = toolCall("speichere_zusammenfassung", "{\"text\":\"- Reifegrad: mittel\"}");
					} else {
						final String arguments = MAPPER.writeValueAsString(Map.of("conversationId", matcher.group(1)));
						response = "{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"get_next_requirement_step\",\"arguments\":"
								+ MAPPER.writeValueAsString(arguments) + "}}]}}]}";
					}
				}
				final byte[] body = response.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, body.length);
				try (var output = exchange.getResponseBody()) {
					output.write(body);
				}
			});
			upstream.start();
		} catch (final Exception e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private static String lastUserText(final JsonNode messages) {
		String text = "";
		for (final JsonNode message : messages)
			if ("user".equals(message.path("role").asText()))
				text = message.path("content").asText();
		return text;
	}

	private static String toolCall(final String name, final String argumentsJson) {
		final var function = MAPPER.createObjectNode().put("name", name).put("arguments", argumentsJson);
		final var call = MAPPER.createObjectNode().put("id", "call-1").put("type", "function");
		call.set("function", function);
		final var message = MAPPER.createObjectNode();
		message.putArray("tool_calls").add(call);
		final var root = MAPPER.createObjectNode();
		root.putArray("choices").addObject().set("message", message);
		return root.toString();
	}

	@DynamicPropertySource
	static void properties(final DynamicPropertyRegistry registry) {
		registry.add("app.ki.base-url", () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
	}

	@AfterAll
	static void stopUpstream() {
		upstream.stop(0);
	}

	@LocalServerPort
	int port;

	@Autowired
	ObjectMapper mapper;

	@MockBean
	RequirementsMcpGateway requirementsMcp;

	@MockBean
	TerminController termine;

	@Test
	void executesModelToolCallsAgainstMcpAndReturnsTheFinalAnswer() throws Exception {
		final var definitions = this.mapper.createArrayNode();
		definitions.addObject().put("type", "function").putObject("function").put("name", "get_next_requirement_step");
		when(this.requirementsMcp.toolDefinitions()).thenReturn(definitions);
		when(this.requirementsMcp.call(eq("get_next_requirement_step"), any(JsonNode.class)))
				.thenReturn("{\"completed\":false,\"stepNumber\":1}");
		final var messages = this.mapper.createArrayNode();
		messages.addObject().put("role", "user").put("content", "Ich möchte ein Softwareprojekt planen.");
		final var chatRequest = this.mapper.createObjectNode();
		chatRequest.set("messages", messages);
		final String authorization = Base64.getEncoder()
				.encodeToString("admin:local-test-password".getBytes(StandardCharsets.UTF_8));
		final var response = HttpClient.newHttpClient().send(HttpRequest
				.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/api/ki/chat"))
				.header("Authorization", "Basic " + authorization)
				.header("X-API-Client", "test")
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(this.mapper.writeValueAsString(chatRequest))).build(),
				HttpResponse.BodyHandlers.ofString());

		assertEquals(200, response.statusCode());
		assertTrue(response.body().contains("Schritt 1: Was soll das Projekt erreichen?"));
		final var invocation = forClass(JsonNode.class);
		verify(this.requirementsMcp, atLeastOnce()).call(eq("get_next_requirement_step"), invocation.capture());
		assertTrue(UUID.fromString(invocation.getValue().path("conversationId").asText()) != null);
	}

	@Test
	void welcomeOffersChatAndSurveyOptionsWithoutCallingMcp() throws Exception {
		final var response = HttpClient.newHttpClient().send(HttpRequest
				.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/api/ki/welcome")).GET().build(),
				HttpResponse.BodyHandlers.ofString());

		assertEquals(200, response.statusCode());
		final JsonNode body = this.mapper.readTree(response.body());
		assertTrue(UUID.fromString(body.path("gespraechId").asText()) != null);
		final String text = body.path("text").asText();
		assertTrue(text.contains("- Mit mir weiterchatten"), text);
		assertTrue(text.contains("- Die Umfrage ausfüllen"), text);
		verify(this.requirementsMcp, never()).call(anyString(), any(JsonNode.class));
	}

	@Test
	void umfrageSchrittLiefertMarkdownOhneKi() throws Exception {
		when(this.requirementsMcp.call(eq("get_next_requirement_step"), any(JsonNode.class)))
				.thenReturn("{\"completed\":false,\"stepNumber\":1,\"totalSteps\":6,\"step\":{\"id\":\"maturity\",\"question\":\"Welche Rolle spielt KI?\","
						+ "\"options\":[\"Keine: nichts.\",\"Strategisch: viel.\"],\"selectionMode\":\"single\"}}");
		final var response = HttpClient.newHttpClient().send(HttpRequest
				.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/api/ki/umfrage/schritt?gespraechId="
						+ UUID.randomUUID()))
				.GET().build(), HttpResponse.BodyHandlers.ofString());

		assertEquals(200, response.statusCode());
		final JsonNode body = this.mapper.readTree(response.body());
		assertEquals("maturity", body.path("step").path("id").asText());
		assertTrue(body.path("markdown").asText().contains("**Schritt 1 von 6:** Welche Rolle spielt KI?"));
		verify(this.requirementsMcp, never()).call(eq("record_requirement_answer"), any(JsonNode.class));
	}

	@Test
	void umfrageAntwortLehntUngueltigeAbUndLiefertNaechstenSchritt() throws Exception {
		final String id = UUID.randomUUID().toString();
		final Csrf csrf = this.csrf();
		final String body = "{\"gespraechId\":\"" + id + "\",\"stepId\":\"maturity\",\"answers\":[\"Unbekannt\"]}";
		when(this.requirementsMcp.call(eq("record_requirement_answer"), any(JsonNode.class)))
				.thenReturn("{\"accepted\":false,\"reason\":\"Bitte eine Option wählen.\"}");
		final var rejected = HttpClient.newHttpClient().send(HttpRequest
				.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/api/ki/umfrage/antwort"))
				.header("Content-Type", "application/json")
				.header("Cookie", csrf.cookie()).header("X-XSRF-TOKEN", csrf.token())
				.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(422, rejected.statusCode());

		when(this.requirementsMcp.call(eq("record_requirement_answer"), any(JsonNode.class)))
				.thenReturn("{\"accepted\":true}");
		when(this.requirementsMcp.call(eq("get_next_requirement_step"), any(JsonNode.class)))
				.thenReturn("{\"completed\":true,\"minimumSteps\":6,\"answeredSteps\":6}");
		final var accepted = HttpClient.newHttpClient().send(HttpRequest
				.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/api/ki/umfrage/antwort"))
				.header("Content-Type", "application/json")
				.header("Cookie", csrf.cookie()).header("X-XSRF-TOKEN", csrf.token())
				.POST(HttpRequest.BodyPublishers.ofString(body.replace("Unbekannt", "Keine: nichts."))).build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(200, accepted.statusCode());
		assertTrue(this.mapper.readTree(accepted.body()).path("completed").asBoolean(false), accepted.body());
	}

	@Test
	void chatBooksOnlyTheVideoCallSlotTheCustomerConfirmed() throws Exception {
		when(this.requirementsMcp.call(eq("get_next_requirement_step"), any(JsonNode.class)))
				.thenReturn("{\"completed\":true,\"minimumSteps\":6,\"answeredSteps\":6}");
		when(this.termine.buchenFuerKi(any(TerminController.BuchungRequest.class)))
				.thenReturn(new TerminController.BuchungAntwort(Instant.parse("2030-01-07T08:00:00Z")));
		final var response = this.post("/api/ki/chat", "[buchen] Ja, bitte diesen Termin.");

		assertEquals(200, response.statusCode());
		final var captor = forClass(TerminController.BuchungRequest.class);
		verify(this.termine).buchenFuerKi(captor.capture());
		assertEquals(Instant.parse("2030-01-07T08:00:00Z"), captor.getValue().start());
		assertEquals("Europe/Berlin", captor.getValue().zeitzone());
		assertEquals("Ada Lovelace", captor.getValue().name());
		assertEquals("ada@example.org", captor.getValue().email());
	}

	@Test
	void streamUebergibtZusammenfassungAusDemLokalenWerkzeug() throws Exception {
		when(this.requirementsMcp.call(eq("get_next_requirement_step"), any(JsonNode.class)))
				.thenReturn("{\"completed\":true,\"minimumSteps\":6,\"answeredSteps\":6}");
		final var response = this.post("/api/ki/chat/stream", "[zusammenfassung] Bitte fasse zusammen.");

		assertEquals(200, response.statusCode());
		assertTrue(response.body().contains("event: done\n"), response.body());
		assertTrue(response.body().contains("\"zusammenfassung\":\"- Reifegrad: mittel\""), response.body());
	}

	@Test
	void streamSignalsCompletedRequirementFlowInDoneEvent() throws Exception {
		final var definitions = this.mapper.createArrayNode();
		definitions.addObject().put("type", "function").putObject("function").put("name", "get_next_requirement_step");
		when(this.requirementsMcp.toolDefinitions()).thenReturn(definitions);
		when(this.requirementsMcp.call(eq("get_next_requirement_step"), any(JsonNode.class)))
				.thenReturn("{\"completed\":true,\"minimumSteps\":6,\"answeredSteps\":6}");
		final var messages = this.mapper.createArrayNode();
		messages.addObject().put("role", "user").put("content", "Alle Fragen beantwortet.");
		final var chatRequest = this.mapper.createObjectNode();
		chatRequest.set("messages", messages);
		final String authorization = Base64.getEncoder()
				.encodeToString("admin:local-test-password".getBytes(StandardCharsets.UTF_8));
		final var response = HttpClient.newHttpClient().send(HttpRequest
				.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/api/ki/chat/stream"))
				.header("Authorization", "Basic " + authorization)
				.header("X-API-Client", "test")
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(this.mapper.writeValueAsString(chatRequest))).build(),
				HttpResponse.BodyHandlers.ofString());

		assertEquals(200, response.statusCode());
		assertTrue(response.body().contains("event: done\n"), response.body());
		assertTrue(response.body().contains("\"zusammenfassung\":\"\""), response.body());
	}

	private record Csrf(String cookie, String token) {
	}

	private Csrf csrf() throws Exception {
		final var response = HttpClient.newHttpClient().send(HttpRequest
				.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/api/csrf")).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		final String cookie = response.headers().allValues("Set-Cookie").stream()
				.filter(value -> value.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow();
		final String pair = cookie.split(";", 2)[0];
		return new Csrf(pair, pair.substring("XSRF-TOKEN=".length()));
	}

	private HttpResponse<String> post(final String path, final String userContent) throws Exception {
		when(this.requirementsMcp.toolDefinitions()).thenReturn(this.mapper.createArrayNode());
		final var messages = this.mapper.createArrayNode();
		messages.addObject().put("role", "user").put("content", userContent);
		final var chatRequest = this.mapper.createObjectNode();
		chatRequest.set("messages", messages);
		final String authorization = Base64.getEncoder()
				.encodeToString("admin:local-test-password".getBytes(StandardCharsets.UTF_8));
		return HttpClient.newHttpClient().send(HttpRequest
				.newBuilder(URI.create("http://127.0.0.1:" + this.port + path))
				.header("Authorization", "Basic " + authorization)
				.header("X-API-Client", "test")
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(this.mapper.writeValueAsString(chatRequest))).build(),
				HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void chatAppendsCurrentMcpStepVerbatimAfterToolCalls() throws Exception {
		final var definitions = this.mapper.createArrayNode();
		definitions.addObject().put("type", "function").putObject("function").put("name", "get_next_requirement_step");
		when(this.requirementsMcp.toolDefinitions()).thenReturn(definitions);
		when(this.requirementsMcp.call(eq("get_next_requirement_step"), any(JsonNode.class)))
				.thenReturn("{\"completed\":false,\"stepNumber\":2,\"totalSteps\":6,\"step\":{\"question\":\"Welche Rolle spielt KI?\","
						+ "\"options\":[\"Keine: nichts.\",\"Strategisch: viel.\"],\"selectionMode\":\"single\"}}");
		final var messages = this.mapper.createArrayNode();
		messages.addObject().put("role", "user").put("content", "Ich möchte ein Softwareprojekt planen.");
		final var chatRequest = this.mapper.createObjectNode();
		chatRequest.set("messages", messages);
		final String authorization = Base64.getEncoder()
				.encodeToString("admin:local-test-password".getBytes(StandardCharsets.UTF_8));
		final var response = HttpClient.newHttpClient().send(HttpRequest
				.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/api/ki/chat"))
				.header("Authorization", "Basic " + authorization)
				.header("X-API-Client", "test")
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(this.mapper.writeValueAsString(chatRequest))).build(),
				HttpResponse.BodyHandlers.ofString());

		assertEquals(200, response.statusCode());
		final String reply = this.mapper.readTree(response.body()).path("reply").asText();
		assertTrue(reply.contains("**Schritt 2 von 6:** Welche Rolle spielt KI?"), reply);
		assertTrue(reply.contains("\n- Keine: nichts."), reply);
	}

}


package de.kiexperten.ki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import de.kiexperten.Zeit;
import de.kiexperten.booking.BestaetigungsMailService;
import de.kiexperten.booking.TerminRepository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

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
	private static final Pattern ANSWER = Pattern.compile("\\[antwort:([a-z]+)\\]\\s*(.*)", Pattern.DOTALL);
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
					final String text = lastUserText(messages).contains("[frage-im-text]")
							? "Danke, notiert. Welches Ziel verfolgt ihr?"
							: "Schritt 1: Was soll das Projekt erreichen?";
					response = "{\"choices\":[{\"message\":{\"content\":\"" + text + "\"}}]}";
				} else {
					final var matcher = CONVERSATION_ID.matcher(messages.get(0).path("content").asText());
					if (!matcher.find() || !request.path("tools").isArray()) {
						exchange.sendResponseHeaders(400, -1);
						exchange.close();
						return;
					}
					final String userText = lastUserText(messages);
					final var answer = ANSWER.matcher(userText);
					if (userText.contains("[zusammenfassung]")) {
						final String summary = userText.substring(userText.indexOf("[zusammenfassung]") + "[zusammenfassung]".length()).trim();
						response = toolCall("speichere_zusammenfassung", MAPPER.writeValueAsString(Map.of("text", summary)));
					} else if (answer.find()) {
						final String arguments = MAPPER.writeValueAsString(Map.of("conversationId", matcher.group(1),
								"stepId", answer.group(1), "sufficient", true, "answers", List.of(answer.group(2).trim())));
						response = toolCall("record_requirement_answer", arguments);
					} else {
						final String arguments = MAPPER.writeValueAsString(Map.of("conversationId", matcher.group(1)));
						response = "{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"get_next_requirement_step\",\"arguments\":"
								+ MAPPER.writeValueAsString(arguments) + "}}]}}]}";
					}
				}
				final byte[] body = (request.path("stream").asBoolean(false) ? toSse(response) : response)
						.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type",
						request.path("stream").asBoolean(false) ? "text/event-stream" : "application/json");
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

	/** Zerlegt eine Nicht-Stream-Antwort in SSE-Chunks; Text und Tool-Argumente werden absichtlich geteilt. */
	private static String toSse(final String json) throws IOException {
		final JsonNode message = MAPPER.readTree(json).at("/choices/0/message");
		final StringBuilder sse = new StringBuilder();
		final String content = message.path("content").asText("");
		for (int start = 0; start < content.length(); start += 12) {
			final String part = content.substring(start, Math.min(content.length(), start + 12));
			sse.append("data: ").append(MAPPER.writeValueAsString(Map.of("choices",
					List.of(Map.of("delta", Map.of("content", part)))))).append("\n\n");
		}
		int index = 0;
		for (final JsonNode call : message.path("tool_calls")) {
			final String name = call.path("function").path("name").asText();
			final String arguments = call.path("function").path("arguments").asText();
			final int half = arguments.length() / 2;
			sse.append("data: ").append(MAPPER.writeValueAsString(Map.of("choices", List.of(Map.of("delta",
					Map.of("tool_calls", List.of(Map.of("index", index, "id", call.path("id").asText(), "type",
							"function", "function", Map.of("name", name, "arguments", arguments.substring(0, half))))))))))
					.append("\n\n");
			sse.append("data: ").append(MAPPER.writeValueAsString(Map.of("choices", List.of(Map.of("delta",
					Map.of("tool_calls", List.of(Map.of("index", index, "function",
							Map.of("arguments", arguments.substring(half))))))))))
					.append("\n\n");
			index++;
		}
		return sse.append("data: [DONE]\n\n").toString();
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
	BestaetigungsMailService mail;

	@Autowired
	TerminRepository termine;

	@Autowired
	KiChatController controller;

	/** Die Tests teilen sich einen Kontext; das Minuten-Limit pro IP darf nicht zwischen Tests greifen. */
	@BeforeEach
	void rateLimitZuruecksetzen() {
		((Map<?, ?>) ReflectionTestUtils.getField(this.controller, "hits")).clear();
	}

	@Test
	void rateLimitLehntUeberDemKontingentMitRetryAfterAb() throws Exception {
		stubUmfrage();
		for (int i = 0; i < 30; i++)
			assertEquals(200, get("/api/ki/welcome").statusCode());
		final HttpResponse<String> abgelehnt = get("/api/ki/welcome");
		assertEquals(429, abgelehnt.statusCode());
		assertTrue(Long.parseLong(abgelehnt.headers().firstValue("Retry-After").orElseThrow()) >= 1);
	}

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

	private final AtomicInteger beantwortet = new AtomicInteger();

	private static final String SCHRITT_ROLLE = "{\"completed\":false,\"stepNumber\":1,\"totalSteps\":2,\"step\":{\"id\":\"maturity\","
			+ "\"question\":\"Welche Rolle spielt KI?\",\"options\":[\"Keine: nichts.\",\"Strategisch: viel.\"],\"selectionMode\":\"single\"}}";
	private static final String SCHRITT_ZIEL = "{\"completed\":false,\"stepNumber\":2,\"totalSteps\":2,\"step\":{\"id\":\"goal\","
			+ "\"question\":\"Welches Ziel verfolgt ihr?\",\"selectionMode\":\"text\",\"inputPlaceholder\":\"Dein Ziel in einem Satz\"}}";
	private static final String FERTIG = "{\"completed\":true,\"minimumSteps\":2,\"answeredSteps\":2}";

	/** Stateful MCP-Stub: liefert den nächsten offenen Schritt, nachdem Antworten gespeichert wurden. */
	private void stubUmfrage() throws Exception {
		when(this.requirementsMcp.toolDefinitions()).thenReturn(this.mapper.createArrayNode()
				.add(this.mapper.createObjectNode().put("type", "function")
						.set("function", this.mapper.createObjectNode().put("name", "get_next_requirement_step")))
				.add(this.mapper.createObjectNode().put("type", "function")
						.set("function", this.mapper.createObjectNode().put("name", "record_requirement_answer"))));
		when(this.requirementsMcp.call(eq("get_next_requirement_step"), any(JsonNode.class)))
				.thenAnswer(invocation -> this.beantwortet.get() == 0 ? SCHRITT_ROLLE
						: this.beantwortet.get() == 1 ? SCHRITT_ZIEL : FERTIG);
		when(this.requirementsMcp.call(eq("record_requirement_answer"), any(JsonNode.class)))
				.thenAnswer(invocation -> {
					this.beantwortet.incrementAndGet();
					return "{\"accepted\":true}";
				});
	}

	private HttpResponse<String> chat(final String gespraechId, final String userContent) throws Exception {
		final var messages = this.mapper.createArrayNode();
		messages.addObject().put("role", "user").put("content", userContent);
		final var chatRequest = this.mapper.createObjectNode().put("gespraechId", gespraechId);
		chatRequest.set("messages", messages);
		final String authorization = Base64.getEncoder()
				.encodeToString("admin:local-test-password".getBytes(StandardCharsets.UTF_8));
		return HttpClient.newHttpClient().send(HttpRequest
				.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/api/ki/chat/stream"))
				.header("Authorization", "Basic " + authorization)
				.header("X-API-Client", "test")
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(this.mapper.writeValueAsString(chatRequest))).build(),
				HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void welcomeNenntKurzenIntroUndStelltDieErsteFrageOhneKiAufruf() throws Exception {
		stubUmfrage();
		final var response = this.get("/api/ki/welcome");

		assertEquals(200, response.statusCode());
		final JsonNode body = this.mapper.readTree(response.body());
		assertTrue(UUID.fromString(body.path("gespraechId").asText()) != null);
		final String text = body.path("text").asText();
		assertTrue(text.contains("**Schritt 1 von 2:** Welche Rolle spielt KI?"), text);
		assertFalse(text.contains("Die Umfrage ausfüllen"), text);
		verify(this.requirementsMcp, never()).call(eq("record_requirement_answer"), any(JsonNode.class));
	}

	@Test
	void streamUebergibtZusammenfassungAusDemLokalenWerkzeug() throws Exception {
		when(this.requirementsMcp.call(eq("get_next_requirement_step"), any(JsonNode.class)))
				.thenReturn("{\"completed\":true,\"minimumSteps\":6,\"answeredSteps\":6}");
		final var response = this.post("/api/ki/chat/stream", "[zusammenfassung] - Reifegrad: mittel");

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

	@Test
	void frageDesModellsWirdNichtDoppeltAngezeigt() throws Exception {
		this.beantwortet.set(0);
		stubUmfrage();
		final String id = this.mapper.readTree(this.get("/api/ki/welcome").body()).path("gespraechId").asText();

		final String body = this.chat(id, "[frage-im-text] [antwort:maturity] Strategisch: viel.").body();
		final String text = doneText(body);

		assertEquals(0, countOccurrences(text, "Welche Rolle spielt KI?"), text);
		assertEquals(1, countOccurrences(text, "Welches Ziel verfolgt ihr?"), text);
	}

	@Test
	void streamLiefertDeltasUndKanonischenEndtextImDoneEvent() throws Exception {
		this.beantwortet.set(0);
		stubUmfrage();
		final String id = this.mapper.readTree(this.get("/api/ki/welcome").body()).path("gespraechId").asText();

		final String body = this.chat(id, "[frage-im-text] [antwort:maturity] Strategisch: viel.").body();

		assertTrue(countOccurrences(body, "event: delta\n") >= 2, body);
		assertTrue(doneText(body).contains("**Schritt 2 von 2:** Welches Ziel verfolgt ihr?"), body);
	}

	@Test
	void optionAuswahlWirdOhneModellGespeichertUndNaechsteFrageGeliefert() throws Exception {
		this.beantwortet.set(0);
		stubUmfrage();
		final String id = this.mapper.readTree(this.get("/api/ki/welcome").body()).path("gespraechId").asText();

		final String body = this.chatMitAuswahl(id, "Strategisch: viel.", "Strategisch: viel.").body();

		final String text = doneText(body);
		assertTrue(text.startsWith("**Schritt 2 von 2:** Welches Ziel verfolgt ihr?"), text);
		assertTrue(text.contains("Freitextfeld: Dein Ziel in einem Satz"), text);
		verify(this.requirementsMcp).call(eq("record_requirement_answer"), argThat(args ->
				"maturity".equals(args.path("stepId").asText())
						&& "Strategisch: viel.".equals(args.path("answers").path(0).asText())));
	}

	@Test
	void unbekannteOptionWirdNichtGespeichert() throws Exception {
		this.beantwortet.set(0);
		stubUmfrage();
		final String id = this.mapper.readTree(this.get("/api/ki/welcome").body()).path("gespraechId").asText();

		this.chatMitAuswahl(id, "Irgendwas", "Irgendwas");

		verify(this.requirementsMcp, never()).call(eq("record_requirement_answer"), argThat(args ->
				args.path("answers").toString().contains("Irgendwas")));
	}

	private HttpResponse<String> chatMitAuswahl(final String gespraechId, final String userContent,
			final String... auswahl) throws Exception {
		final var messages = this.mapper.createArrayNode();
		messages.addObject().put("role", "user").put("content", userContent);
		final var chatRequest = this.mapper.createObjectNode().put("gespraechId", gespraechId);
		chatRequest.set("messages", messages);
		final var antworten = chatRequest.putArray("auswahl");
		for (final String antwort : auswahl)
			antworten.add(antwort);
		final String authorization = Base64.getEncoder()
				.encodeToString("admin:local-test-password".getBytes(StandardCharsets.UTF_8));
		return HttpClient.newHttpClient().send(HttpRequest
				.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/api/ki/chat/stream"))
				.header("Authorization", "Basic " + authorization)
				.header("X-API-Client", "test")
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(this.mapper.writeValueAsString(chatRequest))).build(),
				HttpResponse.BodyHandlers.ofString());
	}

	private String doneText(final String sse) throws IOException {
		final String prefix = "event: done\ndata: ";
		final int start = sse.indexOf(prefix);
		assertTrue(start >= 0, sse);
		final int end = sse.indexOf('\n', start + prefix.length());
		return this.mapper.readTree(sse.substring(start + prefix.length(), end)).path("text").asText();
	}

	private static int countOccurrences(final String text, final String part) {
		int count = 0;
		for (int index = text.indexOf(part); index >= 0; index = text.indexOf(part, index + part.length()))
			count++;
		return count;
	}

	@Test
	void umfrageUndTerminbuchungUebernehmenDieUmfrageergebnisseInDasTextfeld() throws Exception {
		this.beantwortet.set(0);
		stubUmfrage();
		final String id = this.mapper.readTree(this.get("/api/ki/welcome").body()).path("gespraechId").asText();
		final Csrf csrf = this.csrf();

		assertEquals(200, this.chat(id, "[antwort:maturity] Strategisch: viel.").statusCode());
		assertEquals(200, this.chat(id, "[antwort:goal] Prozesse automatisieren.").statusCode());
		assertEquals(2, this.beantwortet.get());

		final var answers = forClass(JsonNode.class);
		verify(this.requirementsMcp, times(2)).call(eq("record_requirement_answer"), answers.capture());
		final List<JsonNode> sent = answers.getAllValues();
		assertEquals("maturity", sent.get(0).path("stepId").asText());
		assertEquals("Strategisch: viel.", sent.get(0).path("answers").get(0).asText());
		assertEquals("goal", sent.get(1).path("stepId").asText());
		assertEquals("Prozesse automatisieren.", sent.get(1).path("answers").get(0).asText());
		assertEquals(id, sent.get(0).path("conversationId").asText());

		// Die KI fasst die Antworten zusammen; das Frontend übernimmt die Zusammenfassung aus dem done-Event.
		final String summary = "- Reifegrad: Strategisch: viel.\n- Ziel: Prozesse automatisieren.";
		final var stream = this.chat(id, "[zusammenfassung] " + summary);
		assertEquals(200, stream.statusCode());
		final String zusammenfassung = this.doneEvent(stream.body()).path("zusammenfassung").asText();
		assertEquals(summary, zusammenfassung);

		final JsonNode slots = this.mapper.readTree(this.get("/api/termine").body());
		String start = null;
		for (final JsonNode slot : slots)
			if (slot.path("frei").asBoolean(false)) {
				start = slot.path("start").asText();
				break;
			}
		assertNotNull(start);
		final String booking = this.mapper.createObjectNode().put("start", start).put("zeitzone", "Europe/Berlin")
				.put("name", "Max Muster").put("email", "max@example.com").put("telefon", "")
				.put("nachricht", zusammenfassung).toString();
		final var booked = this.postJson("/api/termine", csrf, booking);
		assertEquals(201, booked.statusCode(), booked.body());

		final var termin = this.termine.findByAktiverStartZeit(Zeit.utc(Instant.parse(start))).orElseThrow();
		assertEquals("max@example.com", termin.getEmail());
		assertEquals(summary, termin.getNachricht());
		assertTrue(termin.getNachricht().contains("Strategisch: viel."), termin.getNachricht());
		assertTrue(termin.getNachricht().contains("Prozesse automatisieren."), termin.getNachricht());
	}

	private HttpResponse<String> get(final String path) throws Exception {
		return HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.port + path)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> postJson(final String path, final Csrf csrf, final String body) throws Exception {
		return HttpClient.newHttpClient().send(HttpRequest
				.newBuilder(URI.create("http://127.0.0.1:" + this.port + path))
				.header("Content-Type", "application/json")
				.header("Cookie", csrf.cookie()).header("X-XSRF-TOKEN", csrf.token())
				.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
	}

	private JsonNode doneEvent(final String sse) throws Exception {
		final int eventStart = sse.indexOf("event: done\n");
		assertTrue(eventStart >= 0, sse);
		final int dataStart = sse.indexOf("data:", eventStart) + "data:".length();
		final int lineEnd = sse.indexOf('\n', dataStart);
		return this.mapper.readTree(sse.substring(dataStart, lineEnd < 0 ? sse.length() : lineEnd).trim());
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


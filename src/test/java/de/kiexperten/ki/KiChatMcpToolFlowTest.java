package de.kiexperten.ki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
					final String arguments = MAPPER.writeValueAsString(Map.of("conversationId", matcher.group(1)));
					response = "{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"get_next_requirement_step\",\"arguments\":"
							+ MAPPER.writeValueAsString(arguments) + "}}]}}]}";
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
		verify(this.requirementsMcp).call(eq("get_next_requirement_step"), invocation.capture());
		assertTrue(UUID.fromString(invocation.getValue().path("conversationId").asText()) != null);
	}

	@Test
	void welcomeContainsFirstRequirementStepFromMcp() throws Exception {
		when(this.requirementsMcp.call(eq("get_next_requirement_step"), any(JsonNode.class)))
				.thenReturn("{\"completed\":false,\"stepNumber\":1,\"totalSteps\":6,\"step\":{\"question\":\"Welche Rolle spielt KI?\","
						+ "\"options\":[\"Keine: nichts.\",\"Strategisch: viel.\"],\"selectionMode\":\"single\"}}");
		final var response = HttpClient.newHttpClient().send(HttpRequest
				.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/api/ki/welcome")).GET().build(),
				HttpResponse.BodyHandlers.ofString());

		assertEquals(200, response.statusCode());
		final JsonNode body = this.mapper.readTree(response.body());
		assertTrue(UUID.fromString(body.path("gespraechId").asText()) != null);
		assertTrue(body.path("text").asText().contains("**Schritt 1 von 6:** Welche Rolle spielt KI?"));
		assertTrue(body.path("text").asText().contains("\n- Keine: nichts."));
	}

	@Test
	void welcomeFallsBackToStaticQuestionWhenMcpFails() throws Exception {
		final var response = HttpClient.newHttpClient().send(HttpRequest
				.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/api/ki/welcome")).GET().build(),
				HttpResponse.BodyHandlers.ofString());

		assertEquals(200, response.statusCode());
		assertTrue(this.mapper.readTree(response.body()).path("text").asText()
				.contains("Wie würden Sie den Einsatz von KI in Ihrem Unternehmen heute beschreiben?"));
	}
}

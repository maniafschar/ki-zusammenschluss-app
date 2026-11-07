package de.kiexperten.requirementsmcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
class RequirementsMcpProtocolTest {
	@LocalServerPort
	int port;

	@Autowired
	ObjectMapper mapper;

	private McpSyncClient client;

	@BeforeEach
	void connect() {
		final var transport = HttpClientStreamableHttpTransport
				.builder("http://127.0.0.1:" + this.port).build();
		this.client = McpClient.sync(transport).build();
		this.client.initialize();
	}

	@AfterEach
	void close() {
		if (this.client != null)
			this.client.close();
	}

	@Test
	void publishesQuestionAndAnswerToolsAndKeepsInsufficientAnswersOnCurrentStep() throws Exception {
		assertTrue(this.client.listTools().tools().stream().map(McpSchema.Tool::name).toList()
				.containsAll(java.util.List.of("get_next_requirement_step", "record_requirement_answer")));

		final var firstStep = this.call("get_next_requirement_step", Map.of("conversationId", "protocol-test"));
		assertEquals(1, firstStep.path("stepNumber").asInt());
		assertEquals(6, firstStep.path("totalSteps").asInt());

		final var evaluation = this.call("record_requirement_answer", Map.of(
				"conversationId", "protocol-test", "stepId", "maturity", "answers", List.of("Keine"),
				"sufficient", false, "clarificationQuestion", "Welches Ergebnis soll sich verbessern?"));
		assertFalse(evaluation.path("accepted").asBoolean());
		assertEquals(1, this.call("get_next_requirement_step", Map.of("conversationId", "protocol-test"))
				.path("stepNumber").asInt());
	}

	private JsonNode call(final String toolName, final Map<String, Object> arguments) throws Exception {
		final var result = this.client.callTool(new McpSchema.CallToolRequest(toolName, arguments));
		assertFalse(Boolean.TRUE.equals(result.isError()));
		final String text = result.content().stream().filter(McpSchema.TextContent.class::isInstance)
				.map(McpSchema.TextContent.class::cast).map(McpSchema.TextContent::text).findFirst().orElseThrow();
		return this.mapper.readTree(text);
	}
}
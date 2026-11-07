package de.kiexperten.ki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

class RequirementsMcpGatewayTest {
	@Test
	void exposesMcpSchemaAndForwardsToolArguments() throws Exception {
		final McpSyncClient client = org.mockito.Mockito.mock(McpSyncClient.class);
		final var schema = new McpSchema.JsonSchema("object", Map.of("conversationId", Map.of("type", "string")),
				List.of("conversationId"), null, Map.of(), Map.of());
		final var tool = new McpSchema.Tool("get_next_requirement_step", null, "Return the next step", schema,
				null, null, null);
		when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tool), null));
		when(client.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(
				new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("{\"completed\":false}")), false));
		final var gateway = new RequirementsMcpGateway(List.of(client), new ObjectMapper());

		final var definitions = gateway.toolDefinitions();
		assertEquals("get_next_requirement_step", definitions.get(0).at("/function/name").asText());
		assertEquals("object", definitions.get(0).at("/function/parameters/type").asText());
		assertEquals("{\"completed\":false}", gateway.call("get_next_requirement_step",
				new ObjectMapper().readTree("{\"conversationId\":\"dialog-1\"}")));
		final var request = forClass(McpSchema.CallToolRequest.class);
		verify(client).callTool(request.capture());
		assertEquals("dialog-1", request.getValue().arguments().get("conversationId"));
		assertTrue(definitions.get(0).at("/function/parameters/required/0").asText().equals("conversationId"));
	}
}
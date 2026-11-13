package de.kiexperten.ki;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

@Component
@ConditionalOnProperty(name = "app.ki.requirements-mcp.enabled", havingValue = "true")
public class RequirementsMcpGateway {
	private final McpSyncClient client;
	private final ObjectMapper mapper;

	public RequirementsMcpGateway(final List<McpSyncClient> clients, final ObjectMapper mapper) {
		if (clients.size() != 1)
			throw new IllegalStateException("Genau ein Requirements-MCP-Server muss konfiguriert sein, gefunden: "
					+ clients.size() + ". Prüfe APP_REQUIREMENTS_MCP_URL und spring.ai.mcp.client.*");
		this.client = clients.get(0);
		this.mapper = mapper;
	}

	ArrayNode toolDefinitions() {
		final ArrayNode definitions = this.mapper.createArrayNode();
		for (final McpSchema.Tool tool : this.client.listTools().tools()) {
			if ("rewind_requirement_step".equals(tool.name()))
				continue;
			final ObjectNode function = this.mapper.createObjectNode();
			function.put("name", tool.name());
			function.put("description", tool.description());
			function.set("parameters", this.mapper.valueToTree(tool.inputSchema()));
			definitions.addObject().put("type", "function").set("function", function);
		}
		return definitions;
	}

	String call(final String toolName, final JsonNode arguments) throws IOException {
		final Map<String, Object> input = this.mapper.convertValue(arguments, new TypeReference<Map<String, Object>>() {
		});
		final McpSchema.CallToolResult result = this.client.callTool(new McpSchema.CallToolRequest(toolName, input));
		if (Boolean.TRUE.equals(result.isError()))
			throw new IllegalStateException("MCP-Werkzeug meldet einen Fehler: " + toolName);
		final String text = result.content().stream().filter(McpSchema.TextContent.class::isInstance)
				.map(McpSchema.TextContent.class::cast).map(McpSchema.TextContent::text)
				.collect(java.util.stream.Collectors.joining("\n"));
		if (!text.isBlank())
			return text;
		return this.mapper.writeValueAsString(result.structuredContent());
	}
}
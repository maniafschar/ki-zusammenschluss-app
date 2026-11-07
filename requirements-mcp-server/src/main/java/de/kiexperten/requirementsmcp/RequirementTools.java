package de.kiexperten.requirementsmcp;

import java.util.Map;

import org.springframework.stereotype.Component;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;

@Component
public class RequirementTools {
	private final RequirementFlowService flow;

	public RequirementTools(final RequirementFlowService flow) {
		this.flow = flow;
	}

	@McpTool(name = "get_next_requirement_step", description = "Liefert den aktuellen oder nächsten Pflichtschritt des Anforderungsdialogs.")
	public Map<String, Object> getNextStep(
			@McpToolParam(description = "Eindeutige Gesprächs-ID", required = true) final String conversationId) {
		return this.flow.nextStep(conversationId);
	}

	@McpTool(name = "record_requirement_answer", description = "Speichert die KI-Bewertung für die Antwort zum aktuellen Schritt. Bei unzureichenden Antworten wird der Schritt nicht abgeschlossen.")
	public Map<String, Object> recordAnswer(
			@McpToolParam(description = "Eindeutige Gesprächs-ID", required = true) final String conversationId,
			@McpToolParam(description = "ID des aktuell bewerteten Schritts", required = true) final String stepId,
			@McpToolParam(description = "Zusammengefasste Antwort des Nutzers", required = true) final String answer,
			@McpToolParam(description = "Ob die Antwort nach den Akzeptanzkriterien ausreichend ist", required = true) final boolean sufficient,
			@McpToolParam(description = "Eine konkrete Rückfrage, falls die Antwort noch nicht genügt", required = false) final String clarificationQuestion) {
		return this.flow.recordAnswer(conversationId, stepId, answer, sufficient, clarificationQuestion);
	}
}
package de.kiexperten.requirementsmcp;

import java.util.List;
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

	@McpTool(name = "record_requirement_answer", description = "Speichert die KI-Bewertung für die Antwortliste des aktuellen Schritts. Mehrfachauswahl muss aus den vorgegebenen Optionen bestehen und deren Auswahlmaximum einhalten.")
	public Map<String, Object> recordAnswer(
			@McpToolParam(description = "Eindeutige Gesprächs-ID", required = true) final String conversationId,
			@McpToolParam(description = "ID des aktuell bewerteten Schritts", required = true) final String stepId,
			@McpToolParam(description = "Eine oder mehrere Antworten des Nutzers; Mehrfachauswahl als einzelne Listeneinträge", required = true) final List<String> answers,
			@McpToolParam(description = "Ob die Antwort nach den Akzeptanzkriterien ausreichend ist", required = true) final boolean sufficient,
			@McpToolParam(description = "Eine konkrete Rückfrage, falls die Antwort noch nicht genügt", required = false) final String clarificationQuestion) {
		return this.flow.recordAnswer(conversationId, stepId, answers, sufficient, clarificationQuestion);
	}
}
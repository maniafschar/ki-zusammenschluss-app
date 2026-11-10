package de.kiexperten.requirementsmcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class RequirementFlowServiceTest {
	private final RequirementFlowService flow = new RequirementFlowService();

	@Test
	void requiresSixSequentiallyEvaluatedStepsBeforeCompletion() {
		final String conversationId = "conversation-1";
		final var first = this.flow.nextStep(conversationId);
		assertEquals(1, first.get("stepNumber"));
		assertEquals(6, first.get("totalSteps"));

		final var insufficient = this.flow.recordAnswer(conversationId, "maturity", List.of("Keine"), false,
				"Was genau soll besser werden?");
		assertFalse((boolean) insufficient.get("accepted"));
		assertEquals(1, this.flow.nextStep(conversationId).get("stepNumber"));

		this.answer(conversationId, "maturity",
				List.of("Keine: Wir nutzen bisher keine KI-Tools und haben es kurzfristig auch nicht geplant."));
		this.answer(conversationId, "timeline",
				List.of("Sofort: Wir suchen aktuell aktiv nach passenden Lösungen (innerhalb der nächsten 3 Monate)."));
		this.answer(conversationId, "areas", List.of(
				"Verwaltung & Backoffice: Automatisierung von Dokumentenverarbeitung, Rechnungen oder HR-Prozessen.",
				"Marketing & Content: Erstellung von Texten, Bildmaterial oder Marktanalysen."));
		this.answer(conversationId, "infrastructure", List.of(
				"Datenschutz & Compliance: Absolute Datensouveränität (Daten dürfen das Unternehmen nicht verlassen).",
				"Kostenstruktur: Planbare Einmalkosten/Lizenzen statt unvorhersehbarer API-Nutzungsgebühren."));
		this.answer(conversationId, "barrier",
				List.of("Budget: Die Befürchtung, dass maßgeschneiderte Lösungen für KMU zu teuer sind."));
		assertEquals(6, this.flow.nextStep(conversationId).get("stepNumber"));

		final var completed = this.flow.recordAnswer(conversationId, "use_case",
				List.of("Rechnungen automatisch zuordnen und prüfen"), true, null);
		assertTrue((boolean) completed.get("completed"));
		assertEquals(6, completed.get("answeredSteps"));
		final List<?> answerDetails = (List<?>) completed.get("answerDetails");
		final Map<?, ?> firstAnswer = (Map<?, ?>) answerDetails.get(0);
		assertEquals("Welche Rolle spielt Künstliche Intelligenz (KI) aktuell in Deinem Unternehmen?",
				firstAnswer.get("question"));
		assertEquals(List.of("Keine: Wir nutzen bisher keine KI-Tools und haben es kurzfristig auch nicht geplant."),
				firstAnswer.get("answers"));
		final Map<?, ?> lastAnswer = (Map<?, ?>) answerDetails.get(5);
		assertEquals("Beschreibe kurz einen Prozess oder eine tägliche Aufgabe in Deinem Unternehmen, die Du am liebsten an eine KI auslagern oder damit beschleunigen würdest:",
				lastAnswer.get("question"));
		assertEquals(List.of("Rechnungen automatisch zuordnen und prüfen"), lastAnswer.get("answers"));
		assertTrue((boolean) this.flow.nextStep(conversationId).get("completed"));
	}

	@Test
	void doesNotAllowSkippingTheCurrentStep() {
		final var result = this.flow.recordAnswer("conversation-2", "timeline", List.of("Antwort"), true, null);
		assertFalse((boolean) result.get("accepted"));
		assertEquals("maturity", result.get("expectedStepId"));
	}

	@Test
	void preservesTextAnswerExactly() {
		final String answer = "  Bitte genau so übernehmen. \n";
		this.answer("conversation-verbatim", "maturity", List.of(
				"Keine: Wir nutzen bisher keine KI-Tools und haben es kurzfristig auch nicht geplant."));
		this.answer("conversation-verbatim", "timeline", List.of(
				"Sofort: Wir suchen aktuell aktiv nach passenden Lösungen (innerhalb der nächsten 3 Monate)."));
		this.answer("conversation-verbatim", "areas", List.of(
				"Verwaltung & Backoffice: Automatisierung von Dokumentenverarbeitung, Rechnungen oder HR-Prozessen."));
		this.answer("conversation-verbatim", "infrastructure", List.of(
				"Datenschutz & Compliance: Absolute Datensouveränität (Daten dürfen das Unternehmen nicht verlassen)."));
		this.answer("conversation-verbatim", "barrier",
				List.of("Budget: Die Befürchtung, dass maßgeschneiderte Lösungen für KMU zu teuer sind."));

		final var completed = this.flow.recordAnswer("conversation-verbatim", "use_case", List.of(answer), true, null);
		final List<?> answerDetails = (List<?>) completed.get("answerDetails");
		final Map<?, ?> lastAnswer = (Map<?, ?>) answerDetails.get(5);
		assertEquals(List.of(answer), lastAnswer.get("answers"));
	}

	@Test
	void enforcesTheTwoAnswerLimit() {
		final String conversationId = "conversation-3";
		this.answer(conversationId, "maturity", List
				.of("Experimentell: Einzelne Mitarbeiter nutzen öffentliche Tools (z.B. ChatGPT) auf eigene Faust."));
		this.answer(conversationId, "timeline",
				List.of("Mittelfristig: Wir planen eine Einführung in den nächsten 6 bis 12 Monaten."));
		this.answer(conversationId, "areas", List.of(
				"Verwaltung & Backoffice: Automatisierung von Dokumentenverarbeitung, Rechnungen oder HR-Prozessen."));
		final var result = this.flow.recordAnswer(conversationId, "infrastructure", List.of("a", "b", "c"), true,
				null);
		assertFalse((boolean) result.get("accepted"));
		assertEquals(2, result.get("maxSelections"));
		assertEquals(4, this.flow.nextStep(conversationId).get("stepNumber"));
	}

	private void answer(final String conversationId, final String stepId, final List<String> answers) {
		final var result = this.flow.recordAnswer(conversationId, stepId, answers, true, null);
		assertTrue((boolean) result.get("accepted"));
		assertFalse((boolean) result.get("completed"));
	}
}
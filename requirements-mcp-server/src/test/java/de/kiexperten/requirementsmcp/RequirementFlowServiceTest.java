package de.kiexperten.requirementsmcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RequirementFlowServiceTest {
	private final RequirementFlowService flow = new RequirementFlowService();

	@Test
	void requiresSevenSequentiallyEvaluatedStepsBeforeCompletion() {
		final String conversationId = "conversation-1";
		final var first = this.flow.nextStep(conversationId);
		assertEquals(1, first.get("stepNumber"));
		assertEquals(7, first.get("totalSteps"));

		final var insufficient = this.flow.recordAnswer(conversationId, "project_goal", "", false, "Was genau soll besser werden?");
		assertFalse((boolean) insufficient.get("accepted"));
		assertEquals(1, this.flow.nextStep(conversationId).get("stepNumber"));

		for (final String stepId : new String[] { "project_goal", "users", "problem", "workflow", "systems_data", "constraints" }) {
			final var result = this.flow.recordAnswer(conversationId, stepId, "Konkrete Antwort", true, null);
			assertTrue((boolean) result.get("accepted"));
			assertFalse((boolean) result.get("completed"));
		}
		assertEquals(7, this.flow.nextStep(conversationId).get("stepNumber"));

		final var completed = this.flow.recordAnswer(conversationId, "acceptance", "Bearbeitungszeit halbieren", true, null);
		assertTrue((boolean) completed.get("completed"));
		assertEquals(7, completed.get("answeredSteps"));
		assertTrue((boolean) this.flow.nextStep(conversationId).get("completed"));
	}

	@Test
	void doesNotAllowSkippingTheCurrentStep() {
		final var result = this.flow.recordAnswer("conversation-2", "users", "Antwort", true, null);
		assertFalse((boolean) result.get("accepted"));
		assertEquals("project_goal", result.get("expectedStepId"));
	}
}
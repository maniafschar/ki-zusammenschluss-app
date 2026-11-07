package de.kiexperten.requirementsmcp;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class RequirementFlowService {
	static final int MINIMUM_STEPS = 6;
	private static final List<RequirementStep> STEPS = List.of(
			new RequirementStep("project_goal", "Ziel", "Was soll das Softwareprojekt erreichen?",
					List.of("Einen Ablauf automatisieren", "Informationen schneller finden", "Daten auswerten",
							"Kund:innen oder Mitarbeitende unterstützen", "Sonstiges"),
					"Das gewünschte Ergebnis oder der geschäftliche Nutzen ist erkennbar."),
			new RequirementStep("users", "Nutzer:innen", "Wer wird die Lösung hauptsächlich verwenden?",
					List.of("Geschäftsführung", "Fachteam", "Kund:innen", "Mehrere Gruppen", "Noch offen"),
					"Mindestens eine Nutzergruppe oder ausdrücklich noch offene Zielgruppe ist benannt."),
			new RequirementStep("problem", "Ausgangslage", "Welches Problem oder welcher Aufwand soll gelöst werden?",
					List.of("Viele manuelle Schritte", "Wiederkehrende Kommunikation", "Schwierige Informationssuche",
							"Fehler oder Verzögerungen", "Sonstiges"),
					"Ein konkreter heutiger Schmerzpunkt, Prozess oder die offene Frage ist beschrieben."),
			new RequirementStep("workflow", "Ablauf",
					"Wie läuft der betroffene Vorgang heute ab und was soll künftig anders sein?",
					List.of("Eingabe automatisieren", "Entscheidungen vorbereiten", "Entwürfe erzeugen",
							"Prüfen und freigeben",
							"Noch nicht festgelegt"),
					"Mindestens der relevante heutige oder gewünschte Ablauf ist verständlich."),
			new RequirementStep("systems_data", "Systeme und Daten", "Welche Software und Datenquellen sind beteiligt?",
					List.of("E-Mail oder Dokumente", "Excel oder Tabellen", "CRM oder ERP",
							"Interne Datenbank oder API",
							"Noch unbekannt"),
					"Betroffene Systeme oder Daten sind benannt; unbekannte Systeme dürfen ausdrücklich offenbleiben."),
			new RequirementStep("constraints", "Rahmenbedingungen",
					"Welche Grenzen oder Vorgaben müssen wir berücksichtigen?",
					List.of("Datenschutz oder sensible Daten", "Bestehende Systeme", "Budget", "Zeitplan",
							"Keine bekannt"),
					"Wichtige Einschränkungen sind benannt oder der Nutzer bestätigt, dass noch keine bekannt sind."),
			new RequirementStep("acceptance", "Erfolgskriterien",
					"Woran würden Sie erkennen, dass die Lösung erfolgreich ist?",
					List.of("Weniger Bearbeitungszeit", "Weniger Fehler", "Bessere Servicequalität",
							"Höhere Auslastung",
							"Noch zu definieren"),
					"Mindestens ein beobachtbares Erfolgskriterium oder ausdrücklich noch zu definieren ist festgehalten."));

	private final Map<String, Progress> progressByConversation = new ConcurrentHashMap<>();

	public Map<String, Object> nextStep(final String conversationId) {
		final Progress progress = this.progressByConversation.computeIfAbsent(this.requireId(conversationId),
				ignored -> new Progress());
		synchronized (progress) {
			progress.lastUpdated = Instant.now();
			if (progress.nextStepIndex == STEPS.size())
				return Map.of("completed", true, "minimumSteps", MINIMUM_STEPS, "answeredSteps",
						progress.answers.size(), "answers", Map.copyOf(progress.answers));
			return Map.of("completed", false, "stepNumber", progress.nextStepIndex + 1, "totalSteps", STEPS.size(),
					"step", STEPS.get(progress.nextStepIndex));
		}
	}

	public Map<String, Object> recordAnswer(final String conversationId, final String stepId, final String answer,
			final boolean sufficient, final String clarificationQuestion) {
		final Progress progress = this.progressByConversation.computeIfAbsent(this.requireId(conversationId),
				ignored -> new Progress());
		synchronized (progress) {
			progress.lastUpdated = Instant.now();
			if (progress.nextStepIndex >= STEPS.size())
				return Map.of("accepted", false, "completed", true, "reason",
						"Der Fragenkatalog ist bereits abgeschlossen.");
			final RequirementStep current = STEPS.get(progress.nextStepIndex);
			if (!current.id().equals(stepId))
				return Map.of("accepted", false, "completed", false, "reason",
						"Nur der aktuelle Schritt darf bewertet werden.",
						"expectedStepId", current.id());
			if (answer == null || answer.isBlank())
				return Map.of("accepted", false, "completed", false, "reason",
						"Eine leere Antwort kann nicht übernommen werden.");
			if (!sufficient)
				return Map.of("accepted", false, "completed", false, "step", current,
						"clarificationQuestion",
						clarificationQuestion == null ? "Bitte präzisieren Sie Ihre Antwort." : clarificationQuestion);
			progress.answers.put(current.id(), answer.trim());
			progress.nextStepIndex++;
			if (progress.nextStepIndex == STEPS.size())
				return Map.of("accepted", true, "completed", true, "minimumSteps", MINIMUM_STEPS, "answeredSteps",
						progress.answers.size(), "answers", Map.copyOf(progress.answers));
			return Map.of("accepted", true, "completed", false, "answeredSteps", progress.answers.size(),
					"nextStep", STEPS.get(progress.nextStepIndex));
		}
	}

	@Scheduled(fixedDelay = 3_600_000)
	void removeExpiredProgress() {
		final Instant expiry = Instant.now().minusSeconds(86_400);
		this.progressByConversation.entrySet().removeIf(entry -> entry.getValue().lastUpdated.isBefore(expiry));
	}

	private String requireId(final String conversationId) {
		if (conversationId == null || conversationId.isBlank() || conversationId.length() > 100)
			throw new IllegalArgumentException("conversationId muss gesetzt sein.");
		return conversationId;
	}

	public record RequirementStep(String id, String title, String question, List<String> options,
			String acceptanceCriteria) {
	}

	private static final class Progress {
		private int nextStepIndex;
		private Instant lastUpdated = Instant.now();
		private final Map<String, String> answers = new LinkedHashMap<>();
	}
}
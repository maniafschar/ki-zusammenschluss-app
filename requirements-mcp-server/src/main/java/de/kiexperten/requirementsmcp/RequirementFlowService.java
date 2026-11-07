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
			new RequirementStep("maturity", "Reifegrad & aktuelle Nutzung (Wo steht das Unternehmen?)",
					"Welche Rolle spielt Künstliche Intelligenz (KI) aktuell in Ihrem Unternehmen?",
					List.of("Keine: Wir nutzen bisher keine KI-Tools und haben es kurzfristig auch nicht geplant.",
							"Experimentell: Einzelne Mitarbeiter nutzen öffentliche Tools (z.B. ChatGPT) auf eigene Faust.",
							"Punktuell: Wir setzen KI bereits gezielt in ausgewählten Prozessen ein (z.B. Marketing-Texte, Kundenservice).",
							"Strategisch: KI ist fester Bestandteil unserer IT-/Unternehmensstrategie."),
					"Die aktuelle KI-Nutzung ist einer der vier Reifegradstufen zugeordnet.", "single", 1, ""),
			new RequirementStep("timeline", "Zeitrahmen & Absicht (Wann wird es relevant?)",
					"In welchem Zeitraum planen Sie, (weitere) KI-Lösungen fest in Ihre Geschäftsprozesse zu integrieren?",
					List.of("Sofort: Wir suchen aktuell aktiv nach passenden Lösungen (innerhalb der nächsten 3 Monate).",
							"Mittelfristig: Wir planen eine Einführung in den nächsten 6 bis 12 Monaten.",
							"Langfristig: Das Thema ist interessant, aber erst in über einem Jahr realistisch.",
							"Gar nicht: Wir sehen in absehbarer Zeit keinen Einsatzbereich für uns."),
					"Der geplante Zeitraum ist einer der vier Zeitrahmen zugeordnet.", "single", 1, ""),
			new RequirementStep("areas", "Einsatzbereiche & Maße (Wo drückt der Schuh?)",
					"In welchen Unternehmensbereichen sehen Sie das größte Potenzial für eine KI-Unterstützung?",
					List.of("Verwaltung & Backoffice: Automatisierung von Dokumentenverarbeitung, Rechnungen oder HR-Prozessen.",
							"Vertrieb & Kundenservice: Intelligente Chatbots, CRM-Analysen oder automatisierte Mail-Beantwortung.",
							"Marketing & Content: Erstellung von Texten, Bildmaterial oder Marktanalysen.",
							"Produktion, Logistik & IT: Prozessoptimierung, vorausschauende Wartung (Predictive Maintenance) oder Code-Generierung."),
					"Mindestens ein passender Unternehmensbereich wurde ausgewählt.", "multiple", 4, ""),
			new RequirementStep("infrastructure", "Präferenz der Infrastruktur (Das In-House-Argument)",
					"Welche Kriterien sind Ihnen bei der Einführung von KI-Lösungen am wichtigsten?",
					List.of("Datenschutz & Compliance: Absolute Datensouveränität (Daten dürfen das Unternehmen nicht verlassen).",
							"Maßschneiderung: Die KI muss perfekt auf unsere internen Daten und Fachbegriffe trainiert sein.",
							"Einfachheit: Schnelle Integration ohne tiefes technisches Vorwissen unseres Teams.",
							"Kostenstruktur: Planbare Einmalkosten/Lizenzen statt unvorhersehbarer API-Nutzungsgebühren."),
					"Mindestens ein wichtiges Einführungskriterium wurde ausgewählt.", "multiple", 2, ""),
			new RequirementStep("barrier", "Größte Hürde (Hebel für Ihr Vertriebsgespräch)",
					"Was hält Sie aktuell noch am meisten von einer KI-Einführung ab?",
					List.of("Fehlendes Know-how: Wir wissen nicht genau, wie und wo wir anfangen sollen.",
							"Sicherheitsbedenken: Unklarheiten bezüglich DSGVO und dem Schutz sensibler Firmendaten.",
							"Ressourcenmangel: Uns fehlen die internen IT-Kapazitäten für die Implementierung und Wartung.",
							"Budget: Die Befürchtung, dass maßgeschneiderte Lösungen für KMU zu teuer sind."),
					"Die größte Hürde wurde einem der vier Bereiche zugeordnet.", "single", 1, ""),
			new RequirementStep("use_case", "Freitext für den konkreten Use-Case",
					"Beschreiben Sie kurz einen Prozess oder eine tägliche Aufgabe in Ihrem Unternehmen, die Sie am liebsten an eine KI auslagern oder damit beschleunigen würden:",
					List.of(),
					"Ein konkreter Prozess oder eine tägliche Aufgabe ist beschrieben.", "text", 1,
					"Wenn Sie eine konkrete Vorstellung haben oder eine bestimmte Herausforderung lösen möchten, beschreiben Sie diese bitte kurz hier..."));

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

	public Map<String, Object> recordAnswer(final String conversationId, final String stepId,
			final List<String> answers,
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
			final List<String> cleanAnswers = answers == null ? List.of()
					: answers.stream().filter(answer -> answer != null && !answer.isBlank()).map(String::trim).toList();
			if (cleanAnswers.isEmpty())
				return Map.of("accepted", false, "completed", false, "reason",
						"Eine leere Antwort kann nicht übernommen werden.");
			if ("multiple".equals(current.selectionMode())) {
				if (cleanAnswers.size() > current.maxSelections())
					return Map.of("accepted", false, "completed", false, "reason",
							"Es dürfen höchstens " + current.maxSelections() + " Antworten ausgewählt werden.",
							"maxSelections", current.maxSelections());
				if (cleanAnswers.stream().anyMatch(answer -> !current.options().contains(answer)))
					return Map.of("accepted", false, "completed", false, "reason",
							"Mehrfachauswahl muss aus den vorgegebenen Antwortoptionen bestehen.");
			} else if (cleanAnswers.size() != 1) {
				return Map.of("accepted", false, "completed", false, "reason",
						"Für diesen Schritt wird genau eine Antwort erwartet.");
			}
			if (!sufficient)
				return Map.of("accepted", false, "completed", false, "step", current,
						"clarificationQuestion",
						clarificationQuestion == null ? "Bitte präzisieren Sie Ihre Antwort." : clarificationQuestion);
			progress.answers.put(current.id(), cleanAnswers);
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
			String acceptanceCriteria, String selectionMode, int maxSelections, String inputPlaceholder) {
	}

	private static final class Progress {
		private int nextStepIndex;
		private Instant lastUpdated = Instant.now();
		private final Map<String, List<String>> answers = new LinkedHashMap<>();
	}
}
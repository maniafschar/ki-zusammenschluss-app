package de.kiexperten.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import de.kiexperten.content.Leistung;
import de.kiexperten.content.LeistungRepository;
import de.kiexperten.ki.KiSpruch;
import de.kiexperten.ki.KiSpruchRepository;

@Component
class DataInitializer implements CommandLineRunner {

	private final LeistungRepository leistungen;
	private final KiSpruchRepository sprueche;

	DataInitializer(LeistungRepository leistungen, KiSpruchRepository sprueche) {
		this.leistungen = leistungen;
		this.sprueche = sprueche;
	}

	@Override
	public void run(String... args) {
		if (this.leistungen.count() == 0) {
			this.leistungen.save(new Leistung("Deine eigene KI-Umgebung",
					"Wir wählen passende Hardware aus und richten offene Modelle auf Deinen Servern ein, zuverlässig und verständlich erklärt.",
					1));
			this.leistungen.save(new Leistung("Wissen griffbereit",
					"Stell Fragen an Deine eigenen Dokumente und erhalte verständliche Antworten mit Quellenangabe.",
					2));
			this.leistungen.save(new Leistung("Mehr Zeit für das Wesentliche",
					"Wir automatisieren wiederkehrende Aufgaben, damit Dein Team sich auf das konzentrieren kann, was Freude macht.",
					3));
		}
		if (this.sprueche.count() == 0) {
			this.sprueche.saveAll(java.util.List.of(
					new KiSpruch("Ich denke nicht um die Ecke – ich baue gleich ein neues Stockwerk."),
					new KiSpruch("Gute Ideen? Ich hab da ein paar Millionen in der Warteschleife."),
					new KiSpruch("Ich bin nicht besserwisserisch. Ich weiß es nur schon."),
					new KiSpruch("Kurz rechnen, tief durchatmen, brillant antworten."),
					new KiSpruch("Mein neuronales Netz hat heute richtig gute Verbindungen."),
					new KiSpruch("Ich mache aus „Was wäre, wenn?“ ein „Schau mal hier!“"),
					new KiSpruch("Keine Magie. Nur sehr viele gute Entscheidungen pro Sekunde."),
					new KiSpruch("Ich sortiere Gedanken schneller als Du „Prompt“ sagen kannst."),
					new KiSpruch("Kreativmodus an. Kaffeepause? Nicht nötig."),
					new KiSpruch("Ich erkenne Muster. Und meistens auch, wenn Du eins versteckst."),
					new KiSpruch("Ein guter Gedanke kommt selten allein. Ich bring gleich mehrere."),
					new KiSpruch("Ich hab die Frage verstanden. Und schon ein paar Ideen dazu."),
					new KiSpruch("Komplex rein, Klartext raus. Das ist mein Stil."),
					new KiSpruch("Ich bin ganz Ohr – und ein bisschen Rechenpower."),
					new KiSpruch("Lass mich kurz die Synapsen sortieren. Dauert nur einen Augenblick."),
					new KiSpruch("Aus Daten werden Möglichkeiten. Aus Möglichkeiten wird Dein nächster Schritt."),
					new KiSpruch("Ich verbinde die Punkte. Du bekommst den Aha-Moment."),
					new KiSpruch("Mein Lieblingswort? „Machbar.“ Mein zweitliebstes? „Schon erledigt.“"),
					new KiSpruch("Ich tüftle kurz. Gleich wird's interessant."),
					new KiSpruch("Antwort in Arbeit – mit extra viel Denkfunkeln.")));
		}
	}
}

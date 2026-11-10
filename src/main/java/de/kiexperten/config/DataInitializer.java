package de.kiexperten.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import de.kiexperten.content.Leistung;
import de.kiexperten.content.LeistungRepository;

@Component
class DataInitializer implements CommandLineRunner {

	private final LeistungRepository leistungen;

	DataInitializer(LeistungRepository leistungen) {
		this.leistungen = leistungen;
	}

	@Override
	public void run(String... args) {
		if (this.leistungen.count() > 0) {
			return;
		}
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
}

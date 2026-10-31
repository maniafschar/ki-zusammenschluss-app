package de.kiexperten.content;

import java.util.Base64;

public record ExpertenProfil(Long id, String name, String rolle, String beschreibung, int position, String bild) {

	static ExpertenProfil from(Experte experte) {
		String dataUri = "data:" + experte.getBildContentType() + ";base64,"
				+ Base64.getEncoder().encodeToString(experte.getBildDaten());
		return new ExpertenProfil(experte.getId(), experte.getName(), experte.getRolle(), experte.getBeschreibung(),
				experte.getPosition(), dataUri);
	}
}

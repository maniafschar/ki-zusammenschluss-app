package de.kiexperten.ki;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class KiSpruchController {

	private final KiSpruchRepository sprueche;

	public KiSpruchController(KiSpruchRepository sprueche) {
		this.sprueche = sprueche;
	}

	@GetMapping("/api/ki/sprueche")
	List<SpruchAntwort> sprueche() {
		return this.sprueche.findAllByOrderByIdAsc().stream()
				.map(spruch -> new SpruchAntwort(spruch.getId(), spruch.getText()))
				.toList();
	}

	public record SpruchAntwort(Long id, String text) {
	}
}

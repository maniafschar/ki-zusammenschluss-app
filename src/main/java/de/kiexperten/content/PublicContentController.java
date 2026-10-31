package de.kiexperten.content;

import java.util.List;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PublicContentController {

	private final LeistungRepository leistungen;
	private final ProduktRepository produkte;
	private final ExperteRepository experten;

	public PublicContentController(LeistungRepository leistungen, ProduktRepository produkte,
			ExperteRepository experten) {
		this.leistungen = leistungen;
		this.produkte = produkte;
		this.experten = experten;
	}

	@GetMapping("/leistungen")
	List<Leistung> leistungen() {
		return this.leistungen.findAllByOrderByPositionAscIdAsc();
	}

	@GetMapping("/produkte")
	List<Produkt> produkte() {
		return this.produkte.findAllByOrderByPositionAscIdAsc();
	}

	@GetMapping("/experten")
	List<ExpertenProfil> experten() {
		return this.experten.findAllByOrderByPositionAscIdAsc().stream().map(ExpertenProfil::from).toList();
	}

	// Forces the CSRF cookie to be written for the admin page.
	@GetMapping("/csrf")
	CsrfToken csrf(CsrfToken token) {
		return token;
	}
}

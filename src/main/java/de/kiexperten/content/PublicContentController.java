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
	private final ExpertenRollenService rollen;

	public PublicContentController(LeistungRepository leistungen, ProduktRepository produkte,
			ExperteRepository experten, ExpertenRollenService rollen) {
		this.leistungen = leistungen;
		this.produkte = produkte;
		this.experten = experten;
		this.rollen = rollen;
	}

	@GetMapping("/leistungen")
	List<Leistung> leistungen() {
		return this.leistungen.findAllByOrderByPositionAscIdAsc();
	}

	@GetMapping("/produkte")
	List<Produkt> produkte() {
		return this.produkte.findAllByOrderByPositionAscIdAsc();
	}

	// Former experts are not shown on the public site
	@GetMapping("/experten")
	List<ExpertenProfil> experten() {
		return this.experten.findAllByOrderByPositionAscIdAsc().stream()
				.filter(e -> this.rollen.aktuelle(e.getId()) != ExpertenRolle.AUSGESCHIEDEN)
				.map(ExpertenProfil::from).toList();
	}

	// Forces the CSRF cookie to be written for the admin page.
	@GetMapping("/csrf")
	CsrfToken csrf(CsrfToken token) {
		return token;
	}
}

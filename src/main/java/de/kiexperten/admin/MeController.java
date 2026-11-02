package de.kiexperten.admin;

import java.security.Principal;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import de.kiexperten.content.ExperteRepository;

@RestController
public class MeController {
	private final ExperteRepository experten;

	public MeController(final ExperteRepository experten) {
		this.experten = experten;
	}

	/** The logged-in user: expert name if the login belongs to an expert, otherwise the plain username. */
	@GetMapping("/api/admin/me")
	Map<String, String> me(final Principal principal) {
		final String name = this.experten.findByEmailIgnoreCase(principal.getName()).map(e -> e.getName())
				.orElse(principal.getName());
		return Map.of("username", principal.getName(), "name", name);
	}
}

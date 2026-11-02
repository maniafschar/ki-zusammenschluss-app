package de.kiexperten.admin;

import java.security.Principal;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import de.kiexperten.content.Experte;
import de.kiexperten.content.ExperteRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
public class MeController {
	private final ExperteRepository experten;
	private final PasswordEncoder encoder;
	private final String adminName;

	public MeController(final ExperteRepository experten, final PasswordEncoder encoder,
			@Value("${app.admin.username}") final String adminName) {
		this.adminName = adminName;
		this.experten = experten;
		this.encoder = encoder;
	}

	record PasswortRequest(@NotBlank @Size(max = 200) String aktuell, @NotBlank @Size(max = 200) String neu) {
	}

	/** The logged-in user: expert name if the login belongs to an expert, otherwise the plain username. */
	@GetMapping("/api/admin/me")
	Map<String, Object> me(final Authentication principal) {
		final var experte = this.experten.findByEmailIgnoreCase(principal.getName());
		return Map.of("username", principal.getName(), "name",
				experte.map(Experte::getName).orElse(principal.getName()),
				// the configured emergency account has its password in the server configuration
				"passwortAenderbar", experte.isPresent(),
				// the configured admin account may set the passwords of all experts
				"darfPasswoerterSetzen", principal.getName().equals(this.adminName),
				// Berater may only read
				"nurLesen", principal.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
	}

	@PostMapping("/api/admin/me/passwort")
	@Transactional
	ResponseEntity<Map<String, String>> passwort(final Principal principal,
			@Valid @RequestBody final PasswortRequest request) {
		final Experte experte = this.experten.findByEmailIgnoreCase(principal.getName()).orElse(null);
		if (experte == null) {
			return fehler(HttpStatus.FORBIDDEN, "Das Passwort dieses Zugangs wird in der Serverkonfiguration gepflegt.");
		}
		if (experte.getPasswortHash() == null
				|| !this.encoder.matches(request.aktuell(), experte.getPasswortHash())) {
			return fehler(HttpStatus.BAD_REQUEST, "Das aktuelle Passwort ist falsch.");
		}
		if (request.neu().length() < 10) {
			return fehler(HttpStatus.BAD_REQUEST, "Das neue Passwort muss mindestens 10 Zeichen haben.");
		}
		experte.setPasswortHash(this.encoder.encode(request.neu()));
		this.experten.save(experte);
		return ResponseEntity.noContent().build();
	}

	private static ResponseEntity<Map<String, String>> fehler(final HttpStatus status, final String text) {
		return ResponseEntity.status(status).body(Map.of("message", text));
	}
}

package de.kiexperten.content;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Admin API for expert roles (with history) and the expert's login data. */
@RestController
@RequestMapping("/api/admin/experten")
public class ExpertenZugangController {
	private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

	private final ExperteRepository experten;
	private final ExpertenRollenRepository rollen;
	private final ExpertenRollenService service;
	private final PasswordEncoder encoder;

	public ExpertenZugangController(final ExperteRepository experten, final ExpertenRollenRepository rollen,
			final ExpertenRollenService service, final PasswordEncoder encoder) {
		this.experten = experten;
		this.rollen = rollen;
		this.service = service;
		this.encoder = encoder;
	}

	public record AdminProfil(ExpertenProfil profil, String email, boolean zugang, ExpertenRolle aktuelleRolle) {
	}

	public record RolleRequest(@NotNull ExpertenRolle rolle, @NotNull LocalDate seit) {
	}

	public record ZugangRequest(@Size(max = 254) String email, @Size(max = 200) String passwort) {
	}

	@GetMapping
	List<AdminProfil> liste() {
		return this.experten.findAllByOrderByPositionAscIdAsc().stream()
				.map(e -> new AdminProfil(ExpertenProfil.from(e), e.getEmail(), e.getPasswortHash() != null,
						this.service.aktuelle(e.getId())))
				.toList();
	}

	@GetMapping("/{id}/rollen")
	List<ExpertenRollenEintrag> verlauf(@PathVariable final Long id) {
		pruefe(id);
		return this.rollen.findByExperteIdOrderBySeitDescIdDesc(id);
	}

	@PostMapping("/{id}/rollen")
	ExpertenRollenEintrag vergeben(@PathVariable final Long id, @Valid @RequestBody final RolleRequest request,
			final Principal principal) {
		pruefe(id);
		return this.rollen
				.save(new ExpertenRollenEintrag(id, request.rolle(), request.seit(), principal.getName()));
	}

	@DeleteMapping("/{id}/rollen/{eintragId}")
	ResponseEntity<Void> eintragLoeschen(@PathVariable final Long id, @PathVariable final Long eintragId) {
		final ExpertenRollenEintrag eintrag = this.rollen.findById(eintragId)
				.filter(e -> e.getExperteId().equals(id)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		this.rollen.delete(eintrag);
		return ResponseEntity.noContent().build();
	}

	/** An empty e-mail removes the login; an empty password keeps the existing one. */
	@PutMapping("/{id}/zugang")
	@Transactional
	AdminProfil zugang(@PathVariable final Long id, @Valid @RequestBody final ZugangRequest request) {
		final Experte experte = pruefe(id);
		final String email = request.email() == null ? "" : request.email().trim().toLowerCase();
		final String passwort = request.passwort() == null ? "" : request.passwort();
		if (email.isEmpty()) {
			experte.setEmail(null);
			experte.setPasswortHash(null);
		} else {
			if (!EMAIL.matcher(email).matches()) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ungültige E-Mail-Adresse");
			}
			this.experten.findByEmailIgnoreCase(email).filter(e -> !e.getId().equals(id)).ifPresent(e -> {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "E-Mail wird bereits verwendet");
			});
			if (passwort.isEmpty() && experte.getPasswortHash() == null) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bitte ein Passwort vergeben");
			}
			if (!passwort.isEmpty()) {
				if (passwort.length() < 10) {
					throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
							"Das Passwort muss mindestens 10 Zeichen haben");
				}
				experte.setPasswortHash(this.encoder.encode(passwort));
			}
			experte.setEmail(email);
		}
		this.experten.saveAndFlush(experte);
		return new AdminProfil(ExpertenProfil.from(experte), experte.getEmail(), experte.getPasswortHash() != null,
				this.service.aktuelle(id));
	}

	private Experte pruefe(final Long id) {
		return this.experten.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}
}

package de.kiexperten.interessenten;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import de.kiexperten.Zeit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
public class InteressentController {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final InteressentRepository repository;
	private final InteressentenMailService mail;

	public InteressentController(final InteressentRepository repository, final InteressentenMailService mail) {
		this.repository = repository;
		this.mail = mail;
	}

	record InteressentRequest(
			@Size(max = 120) String vorname,
			@Size(max = 120) String name,
			@Size(max = 200) String firmenname,
			@NotBlank @Email @Size(max = 200) String email,
			@Size(max = 500) String url,
			@Size(max = 40) String anrede,
			@Size(max = 10000) String text) {
	}

	record InteressentAnsicht(Long id, String vorname, String name, String firmenname, String email, String url,
			String anrede, String text, Instant createdAt, Instant gesendetAm, String link, int klickAnzahl,
			Instant ersterKlick, Instant letzterKlick) {
	}

	@GetMapping("/api/admin/interessenten")
	List<InteressentAnsicht> all() {
		return this.repository.findAll().stream()
				.sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
				.map(this::view).toList();
	}

	@GetMapping("/api/admin/interessenten/{id}")
	InteressentAnsicht one(@PathVariable final Long id) {
		return view(find(id));
	}

	@PostMapping("/api/admin/interessenten")
	ResponseEntity<InteressentAnsicht> create(@Valid @RequestBody final InteressentRequest request) {
		final Interessent i = new Interessent();
		i.setCreatedAt(Zeit.nowUtc());
		i.setMarker(newMarker());
		apply(i, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(view(this.repository.save(i)));
	}

	@PutMapping("/api/admin/interessenten/{id}")
	InteressentAnsicht update(@PathVariable final Long id, @Valid @RequestBody final InteressentRequest request) {
		final Interessent i = find(id);
		apply(i, request);
		return view(this.repository.save(i));
	}

	@DeleteMapping("/api/admin/interessenten/{id}")
	ResponseEntity<Void> delete(@PathVariable final Long id) {
		find(id);
		this.repository.deleteById(id);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/api/admin/interessenten/{id}/senden")
	InteressentAnsicht send(@PathVariable final Long id) {
		final Interessent i = find(id);
		if (i.getText() == null || i.getText().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bitte zuerst einen Text eintragen.");
		}
		this.mail.send(i);
		i.setGesendetAm(Zeit.nowUtc());
		return view(this.repository.save(i));
	}

	/** Public tracked link: counts the click and forwards to the website. Unknown markers also just forward. */
	@Transactional
	@GetMapping("/api/r/{marker}")
	ResponseEntity<Void> click(@PathVariable final String marker) {
		this.repository.registerClick(marker, Zeit.nowUtc());
		return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(this.mail.landingUrl())).build();
	}

	private Interessent find(final Long id) {
		return this.repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	private static void apply(final Interessent i, final InteressentRequest r) {
		i.setVorname(clean(r.vorname()));
		i.setName(clean(r.name()));
		i.setFirmenname(clean(r.firmenname()));
		i.setEmail(r.email().trim());
		i.setUrl(clean(r.url()));
		i.setAnrede(clean(r.anrede()));
		i.setText(r.text() == null || r.text().isBlank() ? null : r.text().strip());
	}

	private static String clean(final String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	private static String newMarker() {
		final byte[] bytes = new byte[18];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private InteressentAnsicht view(final Interessent i) {
		return new InteressentAnsicht(i.getId(), i.getVorname(), i.getName(), i.getFirmenname(), i.getEmail(),
				i.getUrl(), i.getAnrede(), i.getText(), Zeit.instant(i.getCreatedAt()), Zeit.instant(i.getGesendetAm()),
				this.mail.trackedLink(i), i.getKlickAnzahl(), Zeit.instant(i.getErsterKlick()),
				Zeit.instant(i.getLetzterKlick()));
	}
}

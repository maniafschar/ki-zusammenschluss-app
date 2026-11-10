package de.kiexperten.ki;

import java.security.Principal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import de.kiexperten.audit.CreateGuard;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/admin/ki-sprueche")
public class KiSpruchAdminController {

	private final KiSpruchRepository sprueche;
	private final CreateGuard guard;

	public KiSpruchAdminController(KiSpruchRepository sprueche, CreateGuard guard) {
		this.sprueche = sprueche;
		this.guard = guard;
	}

	@PostMapping
	ResponseEntity<KiSpruch> create(@Valid @RequestBody SpruchRequest request, Principal principal) {
		this.guard.require(principal);
		String text = request.text().trim();
		if (this.sprueche.existsByTextIgnoreCase(text)) {
			throw duplicate();
		}
		return ResponseEntity.status(HttpStatus.CREATED).body(this.sprueche.save(new KiSpruch(text)));
	}

	@PutMapping("/{id}")
	KiSpruch update(@PathVariable Long id, @Valid @RequestBody SpruchRequest request) {
		KiSpruch spruch = this.sprueche.findById(id).orElseThrow(KiSpruchAdminController::notFound);
		String text = request.text().trim();
		if (this.sprueche.existsByTextIgnoreCaseAndIdNot(text, id)) {
			throw duplicate();
		}
		spruch.setText(text);
		return this.sprueche.save(spruch);
	}

	@DeleteMapping("/{id}")
	ResponseEntity<Void> delete(@PathVariable Long id) {
		this.sprueche.delete(this.sprueche.findById(id).orElseThrow(KiSpruchAdminController::notFound));
		return ResponseEntity.noContent().build();
	}

	public record SpruchRequest(@NotBlank @Size(max = 240) String text) {
	}

	private static ResponseStatusException notFound() {
		return new ResponseStatusException(HttpStatus.NOT_FOUND);
	}

	private static ResponseStatusException duplicate() {
		return new ResponseStatusException(HttpStatus.CONFLICT, "Dieser Spruch ist bereits vorhanden.");
	}
}

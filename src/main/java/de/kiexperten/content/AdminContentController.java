package de.kiexperten.content;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import de.kiexperten.content.ContentRequests.LeistungRequest;
import de.kiexperten.content.ContentRequests.ProduktRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/admin")
public class AdminContentController {

	private final LeistungRepository leistungen;
	private final ProduktRepository produkte;
	private final ExperteRepository experten;
	private final ExpertenRollenRepository rollen;

	public AdminContentController(LeistungRepository leistungen, ProduktRepository produkte,
			ExperteRepository experten, ExpertenRollenRepository rollen) {
		this.leistungen = leistungen;
		this.produkte = produkte;
		this.experten = experten;
		this.rollen = rollen;
	}

	@PostMapping("/leistungen")
	ResponseEntity<Leistung> createLeistung(@Valid @RequestBody LeistungRequest request) {
		Leistung leistung = new Leistung(request.titel(), request.text(), request.position());
		return ResponseEntity.status(HttpStatus.CREATED).body(this.leistungen.save(leistung));
	}

	@PutMapping("/leistungen/{id}")
	Leistung updateLeistung(@PathVariable Long id, @Valid @RequestBody LeistungRequest request) {
		Leistung leistung = this.leistungen.findById(id).orElseThrow(AdminContentController::notFound);
		leistung.setTitel(request.titel());
		leistung.setText(request.text());
		leistung.setPosition(request.position());
		return this.leistungen.save(leistung);
	}

	@DeleteMapping("/leistungen/{id}")
	ResponseEntity<Void> deleteLeistung(@PathVariable Long id) {
		if (!this.leistungen.existsById(id)) {
			throw notFound();
		}
		this.leistungen.deleteById(id);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/produkte")
	ResponseEntity<Produkt> createProdukt(@Valid @RequestBody ProduktRequest request) {
		Produkt produkt = new Produkt(request.name(), request.beschreibung(), request.preis(), request.position());
		return ResponseEntity.status(HttpStatus.CREATED).body(this.produkte.save(produkt));
	}

	@PutMapping("/produkte/{id}")
	Produkt updateProdukt(@PathVariable Long id, @Valid @RequestBody ProduktRequest request) {
		Produkt produkt = this.produkte.findById(id).orElseThrow(AdminContentController::notFound);
		produkt.setName(request.name());
		produkt.setBeschreibung(request.beschreibung());
		produkt.setPreis(request.preis());
		produkt.setPosition(request.position());
		return this.produkte.save(produkt);
	}

	@DeleteMapping("/produkte/{id}")
	ResponseEntity<Void> deleteProdukt(@PathVariable Long id) {
		if (!this.produkte.existsById(id)) {
			throw notFound();
		}
		this.produkte.deleteById(id);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/experten")
	ResponseEntity<Experte> createExperte(@RequestParam String name, @RequestParam String rolle,
			@RequestParam String beschreibung, @RequestParam int position, @RequestParam MultipartFile bild,
			java.security.Principal principal) {
		validateExperte(name, rolle, beschreibung, position);
		ValidatedImage image = validateImage(bild);
		Experte experte = new Experte(name.trim(), rolle.trim(), beschreibung.trim(), position, image.data(),
				image.contentType());
		Experte saved = this.experten.save(experte);
		this.rollen.save(new ExpertenRollenEintrag(saved.getId(), ExpertenRolle.BERATER,
				ExpertenRollenService.heute(), principal.getName()));
		return ResponseEntity.status(HttpStatus.CREATED).body(saved);
	}

	@PutMapping("/experten/{id}")
	Experte updateExperte(@PathVariable Long id, @RequestParam String name, @RequestParam String rolle,
			@RequestParam String beschreibung, @RequestParam int position,
			@RequestParam(required = false) MultipartFile bild) {
		validateExperte(name, rolle, beschreibung, position);
		Experte experte = this.experten.findById(id).orElseThrow(AdminContentController::notFound);
		experte.setName(name.trim());
		experte.setRolle(rolle.trim());
		experte.setBeschreibung(beschreibung.trim());
		experte.setPosition(position);
		if (bild != null && !bild.isEmpty()) {
			ValidatedImage image = validateImage(bild);
			experte.setBildDaten(image.data());
			experte.setBildContentType(image.contentType());
		}
		return this.experten.save(experte);
	}

	@DeleteMapping("/experten/{id}")
	ResponseEntity<Void> deleteExperte(@PathVariable Long id) {
		if (!this.experten.existsById(id)) {
			throw notFound();
		}
		this.experten.deleteById(id);
		this.rollen.deleteByExperteId(id);
		return ResponseEntity.noContent().build();
	}

	private static void validateExperte(String name, String rolle, String beschreibung, int position) {
		if (name.isBlank() || name.length() > 120 || rolle.isBlank() || rolle.length() > 120
				|| beschreibung.isBlank() || beschreibung.length() > 2000 || position < 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bitte prüfen Sie die Expertendaten.");
		}
	}

	private static ValidatedImage validateImage(MultipartFile image) {
		if (image.isEmpty() || image.getSize() > 5 * 1024 * 1024) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Das Bild muss zwischen 1 Byte und 5 MB groß sein.");
		}
		try {
			byte[] data = image.getBytes();
			try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
				if (input == null) {
					throw invalidImage();
				}
				Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
				if (!readers.hasNext()) {
					throw invalidImage();
				}
				ImageReader reader = readers.next();
				try {
					reader.setInput(input);
					int width = reader.getWidth(0);
					int height = reader.getHeight(0);
					if ((long) width * height > 20_000_000L) {
						throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Das Bild ist zu groß.");
					}
					String format = reader.getFormatName().toLowerCase();
					String contentType = switch (format) {
						case "jpeg", "jpg" -> "image/jpeg";
						case "png" -> "image/png";
						case "gif" -> "image/gif";
						default -> throw invalidImage();
					};
					if (!contentType.equalsIgnoreCase(image.getContentType())) {
						throw invalidImage();
					}
					return new ValidatedImage(data, contentType);
				} finally {
					reader.dispose();
				}
			}
		} catch (IOException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Das Bild konnte nicht gelesen werden.",
					exception);
		}
	}

	private static ResponseStatusException invalidImage() {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bitte laden Sie ein PNG-, JPEG- oder GIF-Bild hoch.");
	}

	private record ValidatedImage(byte[] data, String contentType) {
	}

	private static ResponseStatusException notFound() {
		return new ResponseStatusException(HttpStatus.NOT_FOUND);
	}
}

package de.kiexperten.booking;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
public class TerminController {

	static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
	static final LocalTime FIRST_SLOT = LocalTime.of(10, 0);
	static final LocalTime LAST_SLOT = LocalTime.of(15, 30);
	static final int SLOT_MINUTES = 30;
	static final int DAYS_AHEAD = 60;

	public record Slot(LocalDateTime start, boolean frei) {
	}

	public record BuchungRequest(
			@NotNull LocalDateTime start,
			@NotBlank @Size(max = 120) String name,
			@NotBlank @Email @Size(max = 200) String email,
			@Pattern(regexp = "^$|^[0-9+()/\\-. ]{5,40}$") String telefon,
			@Size(max = 2000) String nachricht) {
	}

	public record BuchungAntwort(LocalDateTime start) {
	}

	public record TerminAnsicht(Long id, LocalDateTime start, String name, String email, String telefon,
			String nachricht, String status) {
	}

	static final int CONFIRM_MINUTES = 10;
	private static final SecureRandom RANDOM = new SecureRandom();

	private final TerminRepository termine;
	private final BestaetigungsMailService mail;

	public TerminController(final TerminRepository termine, final BestaetigungsMailService mail) {
		this.termine = termine;
		this.mail = mail;
	}

	private static boolean isExpired(final Termin t, final LocalDateTime now) {
		return "ANGEFRAGT".equals(t.getStatus()) && t.getGebuchtAm().plusMinutes(CONFIRM_MINUTES).isBefore(now);
	}

	private static String newToken() {
		final byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	@GetMapping("/api/termine")
	List<Slot> slots() {
		final LocalDateTime now = LocalDateTime.now(ZONE);
		final LocalDate firstDay = now.toLocalDate();
		final LocalDate lastDay = firstDay.plusDays(DAYS_AHEAD);
		final Set<LocalDateTime> booked = new HashSet<>();
		this.termine.findByStartZeitGreaterThanEqualAndStartZeitLessThan(firstDay.atStartOfDay(),
				lastDay.plusDays(1).atStartOfDay()).stream().filter(t -> !isExpired(t, now))
				.forEach(t -> booked.add(t.getStartZeit()));

		final List<Slot> result = new ArrayList<>();
		for (LocalDate day = firstDay; !day.isAfter(lastDay); day = day.plusDays(1)) {
			if (!isWorkday(day)) {
				continue;
			}
			for (LocalTime time = FIRST_SLOT; !time.isAfter(LAST_SLOT); time = time.plusMinutes(SLOT_MINUTES)) {
				final LocalDateTime start = day.atTime(time);
				if (start.isAfter(now)) {
					result.add(new Slot(start, !booked.contains(start)));
				}
			}
		}
		return result;
	}

	@PostMapping("/api/termine")
	ResponseEntity<BuchungAntwort> book(@Valid @RequestBody final BuchungRequest request) {
		final LocalDateTime start = request.start();
		final LocalDateTime now = LocalDateTime.now(ZONE);
		if (!isValidSlot(start) || !start.isAfter(now)
				|| start.toLocalDate().isAfter(now.toLocalDate().plusDays(DAYS_AHEAD))) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ungültiger Zeitraum");
		}

		// An unconfirmed request whose time ran out no longer blocks the slot.
		this.termine.findByStartZeit(start).filter(t -> isExpired(t, now)).ifPresent(t -> {
			this.termine.delete(t);
			this.termine.flush();
		});

		final Termin termin = new Termin();
		termin.setStartZeit(start);
		termin.setName(request.name().trim());
		termin.setEmail(request.email().trim());
		termin.setTelefon(request.telefon() == null || request.telefon().isBlank() ? null : request.telefon().trim());
		termin.setNachricht(request.nachricht() == null ? null : request.nachricht().trim());
		termin.setGebuchtAm(now);
		termin.setStatus("ANGEFRAGT");
		termin.setBestaetigungsToken(newToken());
		try {
			this.termine.saveAndFlush(termin);
		} catch (final DataIntegrityViolationException e) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Termin bereits vergeben");
		}
		try {
			this.mail.send(termin.getEmail(), termin.getName(), start, termin.getBestaetigungsToken(), CONFIRM_MINUTES);
		} catch (final RuntimeException e) {
			this.termine.delete(termin);
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "E-Mail konnte nicht gesendet werden");
		}
		return ResponseEntity.status(HttpStatus.CREATED).body(new BuchungAntwort(start));
	}

	@GetMapping("/api/termine/bestaetigen")
	ResponseEntity<Void> confirm(@RequestParam final String token) {
		final LocalDateTime now = LocalDateTime.now(ZONE);
		final Optional<Termin> found = this.termine.findByBestaetigungsToken(token);
		String result = "ungueltig";
		if (found.isPresent()) {
			final Termin t = found.get();
			if ("BESTAETIGT".equals(t.getStatus())) {
				result = "bestaetigt";
			} else if (isExpired(t, now)) {
				this.termine.delete(t);
				result = "abgelaufen";
			} else {
				t.setStatus("BESTAETIGT");
				this.termine.save(t);
				result = "bestaetigt";
			}
		}
		return ResponseEntity.status(HttpStatus.SEE_OTHER).header("Location", "/?termin=" + result + "#kontakt").build();
	}

	@GetMapping("/api/admin/termine")
	List<TerminAnsicht> all() {
		return this.termine.findAllByOrderByStartZeitAsc().stream()
				.map(t -> new TerminAnsicht(t.getId(), t.getStartZeit(), t.getName(), t.getEmail(), t.getTelefon(),
						t.getNachricht(), isExpired(t, LocalDateTime.now(ZONE)) ? "ABGELAUFEN" : t.getStatus()))
				.toList();
	}

	// Deleting a booking frees the slot again.
	@DeleteMapping("/api/admin/termine/{id}")
	ResponseEntity<Void> cancel(@PathVariable final Long id) {
		if (!this.termine.existsById(id)) {
			return ResponseEntity.notFound().build();
		}
		this.termine.deleteById(id);
		return ResponseEntity.noContent().build();
	}

	private static boolean isWorkday(final LocalDate day) {
		return day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY;
	}

	private static boolean isValidSlot(final LocalDateTime start) {
		final LocalTime time = start.toLocalTime();
		return isWorkday(start.toLocalDate())
				&& start.getSecond() == 0 && start.getNano() == 0
				&& start.getMinute() % SLOT_MINUTES == 0
				&& !time.isBefore(FIRST_SLOT) && !time.isAfter(LAST_SLOT);
	}
}

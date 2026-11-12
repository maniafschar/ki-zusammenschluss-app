package de.kiexperten.booking;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import de.kiexperten.Zeit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
public class TerminController {
	private static final Logger log = LoggerFactory.getLogger(TerminController.class);

	static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
	static final LocalTime FIRST_SLOT = LocalTime.of(10, 0);
	static final LocalTime LAST_SLOT = LocalTime.of(15, 30);
	static final int SLOT_MINUTES = 30;
	static final int DAYS_AHEAD = 60;

	public record Slot(Instant start, boolean frei) {
	}

	public record BuchungRequest(
			@NotNull Instant start,
			@Size(max = 64) String zeitzone,
			@NotBlank @Size(max = 120) String name,
			@NotBlank @Email @Size(max = 200) String email,
			@Pattern(regexp = "^$|^[0-9+()/\\-. ]{5,40}$") String telefon,
			@Size(max = 2000) String nachricht,
			@Size(max = 36) String gespraechId) {
	}

	public record BuchungAntwort(Instant start) {
	}

	public record TerminAnsicht(Long id, Instant start, String name, String email, String telefon,
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

	private static String gespraechParam(final Termin t) {
		return t.getGespraechId() == null ? ""
				: "&gespraech=" + URLEncoder.encode(t.getGespraechId(), StandardCharsets.UTF_8);
	}

	private static String newToken() {
		final byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	@GetMapping("/api/termine")
	List<Slot> slots() {
		final LocalDateTime now = Zeit.nowUtc();
		final Instant nowInstant = Zeit.instant(now);
		// Opening hours are Berlin business hours; only the stored/transferred values
		// are UTC.
		final LocalDate firstDay = LocalDate.now(ZONE);
		final LocalDate lastDay = firstDay.plusDays(DAYS_AHEAD);
		final Map<LocalDate, String> feiertage = feiertage(firstDay);
		final Set<Instant> booked = new HashSet<>();
		this.termine.findByAktiverStartZeitGreaterThanEqualAndAktiverStartZeitLessThan(
				Zeit.utc(firstDay.atStartOfDay(ZONE).toInstant()),
				Zeit.utc(lastDay.plusDays(1).atStartOfDay(ZONE).toInstant())).stream().filter(t -> !isExpired(t, now))
				.forEach(t -> booked.add(Zeit.instant(t.getStartZeit())));

		final List<Slot> result = new ArrayList<>();
		for (LocalDate day = firstDay; !day.isAfter(lastDay); day = day.plusDays(1)) {
			if (!isWorkday(day) || feiertage.containsKey(day)) {
				continue;
			}
			for (LocalTime time = FIRST_SLOT; !time.isAfter(LAST_SLOT); time = time.plusMinutes(SLOT_MINUTES)) {
				final Instant start = day.atTime(time).atZone(ZONE).toInstant();
				if (start.isAfter(nowInstant)) {
					result.add(new Slot(start, !booked.contains(start)));
				}
			}
		}
		return result;
	}

	@GetMapping("/api/termine/feiertage")
	Map<LocalDate, String> feiertage() {
		return feiertage(LocalDate.now(ZONE));
	}

	private static Map<LocalDate, String> feiertage(final LocalDate firstDay) {
		final Map<LocalDate, String> days = Feiertage.imJahr(firstDay.getYear());
		final int lastYear = firstDay.plusDays(DAYS_AHEAD).getYear();
		if (lastYear != firstDay.getYear()) {
			days.putAll(Feiertage.imJahr(lastYear));
		}
		return days;
	}

	@PostMapping("/api/termine")
	ResponseEntity<BuchungAntwort> book(@Valid @RequestBody final BuchungRequest request) {
		final Instant startInstant = request.start();
		final ZonedDateTime berlin = startInstant.atZone(ZONE);
		final LocalDateTime start = Zeit.utc(startInstant);
		final LocalDateTime now = Zeit.nowUtc();
		if (!isValidSlot(berlin) || !startInstant.isAfter(Zeit.instant(now))
				|| berlin.toLocalDate().isAfter(LocalDate.now(ZONE).plusDays(DAYS_AHEAD))) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ungültiger Zeitraum");
		}

		// An unconfirmed request whose time ran out no longer blocks the slot.
		this.termine.findByAktiverStartZeit(start).filter(t -> isExpired(t, now)).ifPresent(t -> {
			this.termine.delete(t);
			this.termine.flush();
		});

		final Termin termin = new Termin();
		termin.setStartZeit(start);
		termin.setName(request.name().trim());
		termin.setEmail(request.email().trim());
		termin.setTelefon(request.telefon() == null || request.telefon().isBlank() ? null : request.telefon().trim());
		termin.setNachricht(request.nachricht() == null ? null : request.nachricht().trim());
		termin.setGespraechId(request.gespraechId() == null || request.gespraechId().isBlank()
				? UUID.randomUUID().toString()
				: request.gespraechId().trim());
		termin.setGebuchtAm(now);
		termin.setStatus("ANGEFRAGT");
		termin.setBestaetigungsToken(newToken());
		try {
			this.termine.saveAndFlush(termin);
		} catch (final DataIntegrityViolationException e) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Termin bereits vergeben");
		}
		try {
			this.mail.send(termin.getEmail(), termin.getName(), startInstant, zone(request.zeitzone()),
					termin.getBestaetigungsToken(), CONFIRM_MINUTES, termin.getGespraechId());
		} catch (final RuntimeException e) {
			this.termine.delete(termin);
			log.error("Email Termin", e);
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "E-Mail konnte nicht gesendet werden");
		}
		return ResponseEntity.status(HttpStatus.CREATED).body(new BuchungAntwort(startInstant));
	}

	@GetMapping("/api/termine/bestaetigen")
	ResponseEntity<Void> confirm(@RequestParam final String token) {
		final LocalDateTime now = Zeit.nowUtc();
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
		final String ziel = "bestaetigt".equals(result)
				? "/?termin=bestaetigt&start=" + Zeit.instant(found.get().getStartZeit()) + gespraechParam(found.get())
						+ "#kontakt"
				: "/?termin=" + result + "#kontakt";
		return ResponseEntity.status(HttpStatus.SEE_OTHER).header("Location", ziel).build();
	}

	@GetMapping("/api/admin/termine")
	List<TerminAnsicht> all() {
		return this.termine.findAllByDeletedAtIsNullOrderByStartZeitAsc().stream()
				.map(t -> new TerminAnsicht(t.getId(), Zeit.instant(t.getStartZeit()), t.getName(), t.getEmail(),
						t.getTelefon(),
						t.getNachricht(), isExpired(t, Zeit.nowUtc()) ? "ABGELAUFEN" : t.getStatus()))
				.toList();
	}

	// Moving a booking to the trash frees its slot while keeping it recoverable.
	@DeleteMapping("/api/admin/termine/{id}")
	ResponseEntity<Void> cancel(@PathVariable final Long id) {
		final Termin termin = this.termine.findById(id).orElse(null);
		if (termin == null) {
			return ResponseEntity.notFound().build();
		}
		termin.trash();
		this.termine.save(termin);
		return ResponseEntity.noContent().build();
	}

	private static boolean isWorkday(final LocalDate day) {
		return day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY;
	}

	// Unknown or missing zone ids fall back to the business time zone.
	private static ZoneId zone(final String id) {
		try {
			return id == null || id.isBlank() ? ZONE : ZoneId.of(id);
		} catch (final java.time.DateTimeException e) {
			return ZONE;
		}
	}

	static boolean isValidSlot(final ZonedDateTime start) {
		final LocalTime time = start.toLocalTime();
		return isWorkday(start.toLocalDate())
				&& !Feiertage.imJahr(start.getYear()).containsKey(start.toLocalDate())
				&& start.getSecond() == 0 && start.getNano() == 0
				&& start.getMinute() % SLOT_MINUTES == 0
				&& !time.isBefore(FIRST_SLOT) && !time.isAfter(LAST_SLOT);
	}
}

package de.kiexperten.booking;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TerminRepository extends JpaRepository<Termin, Long> {

	List<Termin> findByAktiverStartZeitGreaterThanEqualAndAktiverStartZeitLessThan(LocalDateTime from,
			LocalDateTime to);

	List<Termin> findAllByDeletedAtIsNullOrderByStartZeitAsc();

	Optional<Termin> findByAktiverStartZeit(LocalDateTime startZeit);

	Optional<Termin> findByBestaetigungsToken(String token);
}

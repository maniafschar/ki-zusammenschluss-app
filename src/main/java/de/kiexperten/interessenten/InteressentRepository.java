package de.kiexperten.interessenten;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface InteressentRepository extends JpaRepository<Interessent, Long> {

	Optional<Interessent> findByMarker(String marker);

	// Atomic, so parallel clicks are all counted.
	@Modifying
	@Query("update Interessent i set i.klickAnzahl = i.klickAnzahl + 1, i.letzterKlick = :now, "
			+ "i.ersterKlick = coalesce(i.ersterKlick, :now) where i.marker = :marker")
	int registerClick(@Param("marker") String marker, @Param("now") LocalDateTime now);
}

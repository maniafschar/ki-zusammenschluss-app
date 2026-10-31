package de.kiexperten.content;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LeistungRepository extends JpaRepository<Leistung, Long> {

	List<Leistung> findAllByOrderByPositionAscIdAsc();
}

package de.kiexperten.content;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProduktRepository extends JpaRepository<Produkt, Long> {

	List<Produkt> findAllByOrderByPositionAscIdAsc();
}

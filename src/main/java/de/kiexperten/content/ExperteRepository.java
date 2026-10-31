package de.kiexperten.content;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
public interface ExperteRepository extends JpaRepository<Experte, Long> {

	List<Experte> findAllByOrderByPositionAscIdAsc();
}

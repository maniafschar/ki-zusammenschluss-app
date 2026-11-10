package de.kiexperten.ki;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface KiSpruchRepository extends JpaRepository<KiSpruch, Long> {

	List<KiSpruch> findAllByOrderByIdAsc();

	boolean existsByTextIgnoreCase(String text);

	boolean existsByTextIgnoreCaseAndIdNot(String text, Long id);
}

package de.kiexperten.ki;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface KiSpruchRepository extends JpaRepository<KiSpruch, Long> {

	List<KiSpruch> findAllByOrderByIdAsc();
}

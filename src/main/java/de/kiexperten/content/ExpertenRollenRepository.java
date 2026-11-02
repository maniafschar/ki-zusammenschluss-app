package de.kiexperten.content;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface ExpertenRollenRepository extends JpaRepository<ExpertenRollenEintrag, Long> {

	List<ExpertenRollenEintrag> findByExperteIdOrderBySeitDescIdDesc(Long experteId);

	Optional<ExpertenRollenEintrag> findFirstByExperteIdAndSeitLessThanEqualOrderBySeitDescIdDesc(Long experteId,
			LocalDate stichtag);

	@Transactional
	void deleteByExperteId(Long experteId);
}

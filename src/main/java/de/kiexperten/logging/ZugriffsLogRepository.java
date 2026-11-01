package de.kiexperten.logging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ZugriffsLogRepository
		extends JpaRepository<ZugriffsLog, Long>, JpaSpecificationExecutor<ZugriffsLog> {
}

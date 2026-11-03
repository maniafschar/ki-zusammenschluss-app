package de.kiexperten.audit;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

/** Fills createdAt/createdBy and modifiedAt/modifiedBy of {@link Auditable} entities. */
public class AuditListener {

	@PrePersist
	void created(final Auditable entity) {
		final Timestamp now = now();
		if (entity.getCreatedAt() == null)
			entity.setCreatedAt(now);
		if (entity.getCreatedBy() == null)
			entity.setCreatedBy(currentUser());
	}

	@PreUpdate
	void modified(final Auditable entity) {
		entity.setModifiedAt(now());
		entity.setModifiedBy(currentUser());
	}

	static Timestamp now() {
		return Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC));
	}

	static String currentUser() {
		final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		return auth == null || !auth.isAuthenticated() ? "system" : auth.getName();
	}
}

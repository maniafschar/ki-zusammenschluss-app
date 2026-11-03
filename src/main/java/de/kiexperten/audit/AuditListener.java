package de.kiexperten.audit;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import de.kiexperten.config.ExperteUserDetails;
import de.kiexperten.content.Experte;

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
			entity.setCreatedBy(currentExperte());
	}

	@PreUpdate
	void modified(final Auditable entity) {
		entity.setModifiedAt(now());
		entity.setModifiedBy(currentExperte());
	}

	static Timestamp now() {
		return Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC));
	}

	static Experte currentExperte() {
		final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || !auth.isAuthenticated()
				|| !(auth.getPrincipal() instanceof ExperteUserDetails userDetails)) {
			return null;
		}
		return userDetails.getExperte();
	}
}

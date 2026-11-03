package de.kiexperten.audit;

import java.sql.Timestamp;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;

/**
 * Audit and trash columns of every user created item. Timestamps are UTC wall-clock time; the user columns hold the
 * login name. An item with {@code deletedAt} set is in the trash and invisible to all regular queries.
 */
@MappedSuperclass
@EntityListeners(AuditListener.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@Setter
public abstract class Auditable {
	private Timestamp createdAt;
	private Timestamp modifiedAt;
	private String createdBy;
	private String modifiedBy;
	private Timestamp deletedAt;
	private String deletedBy;

	/** Moves the item to the trash; the caller persists it. */
	public void trash() {
		this.deletedAt = AuditListener.now();
		this.deletedBy = AuditListener.currentUser();
	}
}

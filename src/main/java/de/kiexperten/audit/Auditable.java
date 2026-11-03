package de.kiexperten.audit;

import java.sql.Timestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import de.kiexperten.content.Experte;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;

/**
 * Audit and trash columns of every user created item. Timestamps are UTC wall-clock time; the user columns reference
 * the expert responsible. An item with {@code deletedAt} set is in the trash and invisible to all regular queries.
 */
@MappedSuperclass
@EntityListeners(AuditListener.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@Setter
public abstract class Auditable {
	private Timestamp createdAt;
	private Timestamp modifiedAt;
	@JsonIgnore
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "created_by_id")
	private Experte createdBy;
	@JsonIgnore
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "modified_by_id")
	private Experte modifiedBy;
	private Timestamp deletedAt;
	@JsonIgnore
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "deleted_by_id")
	private Experte deletedBy;

	/** Moves the item to the trash; the caller persists it. */
	public void trash() {
		this.deletedAt = AuditListener.now();
		this.deletedBy = AuditListener.currentExperte();
	}
}

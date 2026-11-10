package de.kiexperten.dashboard;

import java.math.BigInteger;

import org.hibernate.annotations.SQLRestriction;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import de.kiexperten.audit.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;

/**
 * Knowledge source: a title plus rich text (sanitized HTML). Attachments are
 * stored in {@link Anhang}.
 */
@Entity
@JsonIgnoreProperties({ "createdBy", "modifiedBy", "deletedAt", "deletedBy" })
@SQLRestriction("deleted_at is null")
public class Wissensquelle extends Auditable {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(columnDefinition = "BIGINT")
	private BigInteger id;

	@Column(nullable = false, length = 200)
	private String title;

	@Lob
	private String content;

	/** Only sources flagged here are searchable by the public KI chat. */
	@Column(nullable = false)
	private boolean kiVerfuegbar;

	public BigInteger getId() {
		return this.id;
	}

	public void setId(final BigInteger id) {
		this.id = id;
	}

	public String getTitle() {
		return this.title;
	}

	public void setTitle(final String title) {
		this.title = title;
	}

	public String getContent() {
		return this.content;
	}

	public void setContent(final String content) {
		this.content = content;
	}

	public boolean isKiVerfuegbar() {
		return this.kiVerfuegbar;
	}

	public void setKiVerfuegbar(final boolean kiVerfuegbar) {
		this.kiVerfuegbar = kiVerfuegbar;
	}
}

package de.kiexperten.dashboard;

import org.hibernate.annotations.SQLRestriction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;

/** Knowledge source: a title plus rich text (sanitized HTML). Attachments are stored in {@link Anhang}. */
@Entity
@SQLRestriction("deleted_at is null")
public class Wissensquelle extends BaseEntity {
	@Column(nullable = false, length = 200)
	private String title;

	@Lob
	private String content;

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
}

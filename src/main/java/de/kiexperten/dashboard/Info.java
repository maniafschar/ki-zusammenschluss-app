package de.kiexperten.dashboard;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;

/** Single row holding the free text of the dashboard. */
@Entity
public class Info extends BaseEntity {
	@Column(nullable = false, length = 1_000_000)
	private String note = "";

	public String getNote() {
		return this.note;
	}

	public void setNote(final String note) {
		this.note = note;
	}
}

package de.kiexperten.dashboard;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;

@Entity
public class Todo extends BaseEntity {
	@Column(nullable = false, length = 200)
	private String note;

	@Column(nullable = false)
	private boolean completed;

	// Id of an Experte (main database table); no foreign key, a deleted expert just leaves the todo unassigned.
	private Long expertId;

	// "Erledigen bis" is a calendar date without time zone
	private LocalDate dueDate;

	public String getNote() {
		return this.note;
	}

	public void setNote(final String note) {
		this.note = note;
	}

	public Long getExpertId() {
		return this.expertId;
	}

	public void setExpertId(final Long expertId) {
		this.expertId = expertId;
	}

	public LocalDate getDueDate() {
		return this.dueDate;
	}

	public void setDueDate(final LocalDate dueDate) {
		this.dueDate = dueDate;
	}

	public boolean isCompleted() {
		return this.completed;
	}

	public void setCompleted(final boolean completed) {
		this.completed = completed;
	}
}

package de.kiexperten.content;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.hibernate.annotations.SQLRestriction;

import de.kiexperten.audit.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@JsonIgnoreProperties({ "createdBy", "modifiedBy", "deletedAt", "deletedBy" })
@SQLRestriction("deleted_at is null")
@Getter
@Setter
@NoArgsConstructor
public class Leistung extends Auditable {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 120)
	private String titel;

	@Column(nullable = false, length = 1000)
	private String text;

	private int position;

	public Leistung(String titel, String text, int position) {
		this.titel = titel;
		this.text = text;
		this.position = position;
	}
}

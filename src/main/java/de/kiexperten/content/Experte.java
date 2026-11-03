package de.kiexperten.content;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.hibernate.annotations.SQLRestriction;

import de.kiexperten.audit.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@JsonIgnoreProperties({ "createdBy", "modifiedBy", "deletedAt", "deletedBy" })
@SQLRestriction("deleted_at is null")
@Getter
@Setter
@NoArgsConstructor
public class Experte extends Auditable {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 120)
	private String name;

	@Column(nullable = false, length = 120)
	private String rolle;

	@Column(nullable = false, length = 2000)
	private String beschreibung;

	private int position;

	// Login of the expert (only effective while the current role is ADMIN)
	@Column(unique = true, length = 254)
	private String email;

	@JsonIgnore
	@Column(length = 100)
	private String passwortHash;

	@JsonIgnore
	@Lob
	@Column(nullable = false)
	private byte[] bildDaten;

	@JsonIgnore
	@Column(nullable = false, length = 40)
	private String bildContentType;

	public Experte(String name, String rolle, String beschreibung, int position, byte[] bildDaten,
			String bildContentType) {
		this.name = name;
		this.rolle = rolle;
		this.beschreibung = beschreibung;
		this.position = position;
		this.bildDaten = bildDaten;
		this.bildContentType = bildContentType;
	}
}

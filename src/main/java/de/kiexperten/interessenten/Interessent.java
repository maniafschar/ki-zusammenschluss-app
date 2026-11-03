package de.kiexperten.interessenten;

import java.time.LocalDateTime;

import org.hibernate.annotations.SQLRestriction;

import de.kiexperten.audit.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A prospective customer. The marker identifies this person in the tracked link of the mail. */
@Entity
@SQLRestriction("deleted_at is null")
@Table(uniqueConstraints = @UniqueConstraint(columnNames = "marker"))
@Getter
@Setter
@NoArgsConstructor
public class Interessent extends Auditable {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(length = 120)
	private String vorname;

	@Column(length = 120)
	private String name;

	@Column(length = 200)
	private String firmenname;

	@Column(nullable = false, length = 200)
	private String email;

	@Column(length = 40)
	private String telefon;

	// Id of an Experte; no foreign key, a deleted expert simply resolves to no Betreuer.
	private Long betreuerId;

	@Column(length = 500)
	private String url;

	@Column(length = 40)
	private String anrede;

	@Column(length = 10000)
	private String text;

	private LocalDateTime gesendetAm;

	@Column(nullable = false, length = 64)
	private String marker;

	@Column(columnDefinition = "integer default 0")
	private int klickAnzahl;

	private LocalDateTime ersterKlick;

	private LocalDateTime letzterKlick;
}

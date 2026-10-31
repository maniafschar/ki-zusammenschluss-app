package de.kiexperten.booking;

import java.time.LocalDateTime;

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

/** A booked 30 minute slot. The unique start time guarantees a slot can only be booked once. */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = "startZeit"))
@Getter
@Setter
@NoArgsConstructor
public class Termin {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private LocalDateTime startZeit;

	@Column(nullable = false, length = 120)
	private String name;

	@Column(nullable = false, length = 200)
	private String email;

	@Column(length = 40)
	private String telefon;

	@Column(length = 2000)
	private String nachricht;

	@Column(nullable = false)
	private LocalDateTime gebuchtAm;

	// BESTAETIGT or ANGEFRAGT (waiting for the e-mail confirmation link).
	@Column(nullable = false, length = 12, columnDefinition = "varchar(12) default 'BESTAETIGT'")
	private String status = "BESTAETIGT";

	@Column(length = 64)
	private String bestaetigungsToken;
}

package de.kiexperten.booking;

import java.time.LocalDateTime;

import org.hibernate.annotations.SQLRestriction;

import de.kiexperten.audit.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A booked 30 minute slot; cancelled bookings remain recoverable in the trash.
 */
@Entity
@SQLRestriction("deleted_at is null")
@Table
@Getter
@Setter
@NoArgsConstructor
public class Termin extends Auditable {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private LocalDateTime startZeit;

	@Column(name = "active_start_zeit", unique = true)
	private LocalDateTime aktiverStartZeit;

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

	// Chat conversation in which the booking was made (null for direct bookings).
	@Column(length = 36)
	private String gespraechId;

	@Column(length = 64)
	private String zeitzone;

	@Column(length = 512)
	private String meetLink;

	@Column(nullable = false)
	private boolean bestaetigungsMailGesendet;

	@PrePersist
	@PreUpdate
	private void synchronisiereAktivenStart() {
		this.aktiverStartZeit = this.getDeletedAt() == null ? this.startZeit : null;
	}
}

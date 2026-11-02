package de.kiexperten.content;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonIgnore;

import de.kiexperten.Zeit;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** History of role assignments: from {@code seit} on the expert holds {@code rolle}. */
@Entity
@Table(indexes = @Index(columnList = "experteId"))
@Getter
@Setter
@NoArgsConstructor
public class ExpertenRollenEintrag {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long experteId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ExpertenRolle rolle;

	@Column(nullable = false)
	private LocalDate seit;

	@Column(length = 254)
	private String vergebenVon;

	@JsonIgnore
	@Column(nullable = false)
	private LocalDateTime erfasstAm = Zeit.nowUtc();

	public Instant getErfasstAmUtc() {
		return Zeit.instant(this.erfasstAm);
	}

	public ExpertenRollenEintrag(Long experteId, ExpertenRolle rolle, LocalDate seit, String vergebenVon) {
		this.experteId = experteId;
		this.rolle = rolle;
		this.seit = seit;
		this.vergebenVon = vergebenVon;
	}
}

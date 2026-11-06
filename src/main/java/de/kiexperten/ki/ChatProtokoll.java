package de.kiexperten.ki;

import java.time.Instant;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import de.kiexperten.Zeit;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(indexes = { @Index(columnList = "zeitpunkt"), @Index(columnList = "gespraechId") })
@Getter
@Setter
@NoArgsConstructor
public class ChatProtokoll {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 36)
	private String gespraechId;

	@JsonIgnore
	@Column(nullable = false)
	private LocalDateTime zeitpunkt;

	@Column(nullable = false, length = 20)
	private String rolle;

	@Column(nullable = false, length = 2000)
	private String nachricht;

	@Lob
	private String antwort;

	@Column(nullable = false, length = 20)
	private String status;

	@JsonProperty("zeitpunkt")
	public Instant getZeitpunktUtc() {
		return Zeit.instant(this.zeitpunkt);
	}
}

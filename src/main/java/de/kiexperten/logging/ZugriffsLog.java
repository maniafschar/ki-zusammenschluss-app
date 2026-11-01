package de.kiexperten.logging;

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
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One HTTP request, written by {@link ZugriffsLogFilter}. */
@Entity
@Table(indexes = @Index(columnList = "zeitpunkt"))
@Getter
@Setter
@NoArgsConstructor
public class ZugriffsLog {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// Stored as UTC; the API exposes it as an Instant (see getZeitpunktUtc).
	@JsonIgnore
	@Column(nullable = false)
	private LocalDateTime zeitpunkt;

	@Column(length = 10)
	private String methode;

	@Column(length = 500)
	private String pfad;

	@Column(length = 1000)
	private String query;

	private int status;

	private long dauerMs;

	@Column(length = 64)
	private String ip;

	@Column(length = 300)
	private String weitergeleitetVon;

	@Column(length = 120)
	private String benutzer;

	@Column(length = 500)
	private String userAgent;

	@Column(length = 500)
	private String referer;

	@Column(length = 100)
	private String sprache;

	@Column(length = 100)
	private String contentType;

	private long antwortBytes;

	@JsonProperty("zeitpunkt")
	public Instant getZeitpunktUtc() {
		return Zeit.instant(this.zeitpunkt);
	}

	@Column(length = 4000)
	private String fehler;
}

package de.kiexperten.content;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
@Getter
@Setter
@NoArgsConstructor
public class Experte {

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

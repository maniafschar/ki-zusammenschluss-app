package de.kiexperten.content;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class Produkt {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 120)
	private String name;

	@Column(nullable = false, length = 1000)
	private String beschreibung;

	@Column(length = 60)
	private String preis;

	private int position;

	public Produkt(String name, String beschreibung, String preis, int position) {
		this.name = name;
		this.beschreibung = beschreibung;
		this.preis = preis;
		this.position = position;
	}
}

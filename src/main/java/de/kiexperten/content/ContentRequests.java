package de.kiexperten.content;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class ContentRequests {

	private ContentRequests() {
	}

	public record LeistungRequest(
			@NotBlank @Size(max = 120) String titel,
			@NotBlank @Size(max = 1000) String text,
			@PositiveOrZero int position) {
	}

	public record ProduktRequest(
			@NotBlank @Size(max = 120) String name,
			@NotBlank @Size(max = 1000) String beschreibung,
			@Size(max = 60) String preis,
			@PositiveOrZero int position) {
	}
}

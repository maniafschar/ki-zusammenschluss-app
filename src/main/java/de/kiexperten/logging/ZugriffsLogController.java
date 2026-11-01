package de.kiexperten.logging;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.persistence.criteria.Predicate;

@RestController
public class ZugriffsLogController {

	private static final int MAX_ROWS = 1000;

	private final ZugriffsLogRepository repository;

	public ZugriffsLogController(final ZugriffsLogRepository repository) {
		this.repository = repository;
	}

	record LogSeite(long gesamt, boolean abgeschnitten, List<ZugriffsLog> eintraege) {
	}

	@GetMapping("/api/admin/logs")
	LogSeite search(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) final LocalDateTime von,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) final LocalDateTime bis,
			@RequestParam(required = false) final String text) {
		final Specification<ZugriffsLog> spec = (root, query, cb) -> {
			final List<Predicate> all = new ArrayList<>();
			if (von != null) {
				all.add(cb.greaterThanOrEqualTo(root.get("zeitpunkt"), von));
			}
			if (bis != null) {
				all.add(cb.lessThanOrEqualTo(root.get("zeitpunkt"), bis));
			}
			if (text != null && !text.isBlank()) {
				// Every word must appear in at least one text column.
				for (final String word : text.trim().toLowerCase().split("\\s+")) {
					final String like = "%" + word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
					final List<Predicate> any = new ArrayList<>();
					for (final String col : List.of("methode", "pfad", "query", "ip", "weitergeleitetVon", "benutzer",
							"userAgent", "referer", "sprache", "contentType")) {
						any.add(cb.like(cb.lower(cb.coalesce(root.<String>get(col), "")), like, '\\'));
					}
					any.add(cb.like(root.get("status").as(String.class), like, '\\'));
					all.add(cb.or(any.toArray(new Predicate[0])));
				}
			}
			return cb.and(all.toArray(new Predicate[0]));
		};
		final long total = this.repository.count(spec);
		final List<ZugriffsLog> rows = this.repository
				.findAll(spec, PageRequest.of(0, MAX_ROWS, Sort.by(Sort.Direction.DESC, "zeitpunkt", "id")))
				.getContent();
		return new LogSeite(total, total > rows.size(), rows);
	}
}

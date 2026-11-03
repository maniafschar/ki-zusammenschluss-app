package de.kiexperten.audit;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import de.kiexperten.Zeit;
import de.kiexperten.content.Experte;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/** Trash: lists deleted items of all types and restores them. Regular queries never see trashed rows. */
@RestController
public class PapierkorbController {

	/** type -> {table, column that identifies the item for the user}; fixed values, never client input. */
	private static final Map<String, String[]> TYPES = Map.of(
			"wissen", new String[] { "wissensquelle", "title" },
			"todo", new String[] { "todo", "note" },
			"leistung", new String[] { "leistung", "titel" },
			"produkt", new String[] { "produkt", "name" },
			"experte", new String[] { "experte", "name" },
			"interessent", new String[] { "interessent", "email" },
			"termin", new String[] { "termin",
					"concat(t.start_zeit, ' - ', t.name, ' (', t.email, ')')" });

	@PersistenceContext
	private EntityManager em;

	public record Eintrag(String typ, BigInteger id, String titel, Instant geloeschtAm, String geloeschtVon) {
	}

	@GetMapping("/api/admin/papierkorb")
	@Transactional(readOnly = true)
	public List<Eintrag> liste() {
		final List<Eintrag> result = new ArrayList<>();
		TYPES.forEach((typ, def) -> {
			final List<?> rows = this.em.createNativeQuery("select t.id, " + (typ.equals("termin") ? def[1] : "t." + def[1])
					+ ", t.deleted_at, e.email from " + def[0]
					+ " t left join experte e on e.id = t.deleted_by_id where t.deleted_at is not null")
					.getResultList();
			for (final Object row : rows) {
				final Object[] c = (Object[]) row;
				result.add(new Eintrag(typ, new BigInteger(c[0].toString()), c[1] == null ? "" : c[1].toString(),
						Zeit.instant(c[2] instanceof final Timestamp t ? t.toLocalDateTime() : (LocalDateTime) c[2]), (String) c[3]));
			}
		});
		result.sort(Comparator.comparing(Eintrag::geloeschtAm).reversed());
		return result;
	}

	@PostMapping("/api/admin/papierkorb/{typ}/{id}/wiederherstellen")
	@Transactional
	public ResponseEntity<Void> wiederherstellen(@PathVariable final String typ, @PathVariable final BigInteger id) {
		final String[] def = TYPES.get(typ);
		if (def == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		final int updated;
		try {
			final Experte experte = AuditListener.currentExperte();
			final var restore = this.em.createNativeQuery("update " + def[0]
					+ " set deleted_at = null, deleted_by_id = null, modified_at = :now"
					+ (typ.equals("termin") ? ", active_start_zeit = start_zeit" : "")
					+ ", modified_by_id = "
					+ (experte == null ? "null" : ":user") + " where id = :id and deleted_at is not null")
					.setParameter("now", AuditListener.now()).setParameter("id", id);
			if (experte != null) {
				restore.setParameter("user", experte.getId());
			}
			updated = restore.executeUpdate();
		} catch (final DataIntegrityViolationException e) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"Wiederherstellen nicht möglich: Der Eintrag kollidiert mit einem vorhandenen.");
		}
		if (updated == 0) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		return ResponseEntity.noContent().build();
	}
}

package de.kiexperten.config;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One-time conversion of timestamps written before the switch to UTC, which were Europe/Berlin local times.
 * Runs once per database (tracked in app_migration) before the web server starts accepting requests.
 */
@Component
public class UtcMigration implements SmartInitializingSingleton {

	private static final Logger LOG = LoggerFactory.getLogger(UtcMigration.class);
	private static final String NAME = "timestamps-to-utc";
	private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

	// table -> columns; ordered so the unique start time of termin never collides while shifting.
	private static final Map<String, List<String>> TARGETS = Map.of(
			"termin", List.of("start_zeit", "gebucht_am"),
			"zugriffs_log", List.of("zeitpunkt"),
			"interessent", List.of("created_at", "gesendet_am", "ersterklick", "letzter_klick"));

	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;

	public UtcMigration(final JdbcTemplate jdbc, final TransactionTemplate tx) {
		this.jdbc = jdbc;
		this.tx = tx;
	}

	@Override
	public void afterSingletonsInstantiated() {
		this.jdbc.execute("create table if not exists app_migration (name varchar(100) primary key)");
		final Integer done = this.jdbc.queryForObject("select count(*) from app_migration where name = ?",
				Integer.class, NAME);
		if (done != null && done > 0) {
			return;
		}
		this.tx.executeWithoutResult(status -> {
			TARGETS.forEach((table, columns) -> columns.forEach(column -> convert(table, column)));
			this.jdbc.update("insert into app_migration (name) values (?)", NAME);
		});
		LOG.info("Bestehende Zeitstempel wurden von Europe/Berlin nach UTC umgestellt.");
	}

	private void convert(final String table, final String column) {
		final Integer exists = this.jdbc.queryForObject(
				"select count(*) from information_schema.columns where lower(table_name) = ? and lower(column_name) = ?",
				Integer.class, table, column);
		if (exists == null || exists == 0) {
			return;
		}
		final List<Map<String, Object>> rows = this.jdbc
				.queryForList("select id, " + column + " as t from " + table + " where " + column
						+ " is not null order by " + column + " asc");
		this.jdbc.batchUpdate("update " + table + " set " + column + " = ? where id = ?", rows, 500,
				(ps, row) -> {
					final LocalDateTime local = ((Timestamp) row.get("T")).toLocalDateTime();
					final LocalDateTime utc = local.atZone(BERLIN).withZoneSameInstant(ZoneOffset.UTC)
							.toLocalDateTime();
					ps.setTimestamp(1, Timestamp.valueOf(utc));
					ps.setObject(2, row.get("ID"));
				});
	}
}

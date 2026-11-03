package de.kiexperten.config;

import java.sql.Timestamp;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.ConnectionCallback;
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
	private static final String TERMIN_TRASH_NAME = "termin-trash-slots";
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
		if (done == null || done == 0) {
			this.tx.executeWithoutResult(status -> {
				TARGETS.forEach((table, columns) -> columns.forEach(column -> convert(table, column)));
				this.jdbc.update("insert into app_migration (name) values (?)", NAME);
			});
			LOG.info("Bestehende Zeitstempel wurden von Europe/Berlin nach UTC umgestellt.");
		}
		migrateTerminTrash();
	}

	private void migrateTerminTrash() {
		final Integer done = this.jdbc.queryForObject("select count(*) from app_migration where name = ?",
				Integer.class, TERMIN_TRASH_NAME);
		if (done != null && done > 0) {
			return;
		}
		this.tx.executeWithoutResult(status -> {
			Map.of("created_at", "timestamp null", "modified_at", "timestamp null",
					"created_by_id", "bigint null", "modified_by_id", "bigint null",
					"deleted_at", "timestamp null", "deleted_by_id", "bigint null",
					"active_start_zeit", "timestamp null")
					.forEach((column, definition) -> ensureColumn("termin", column, definition));
			for (final Map.Entry<String, List<String>> index : uniqueIndexes("termin").entrySet()) {
				if (index.getValue().equals(List.of("start_zeit"))) {
					dropIndex("termin", index.getKey());
				}
			}
			if (uniqueIndexes("termin").values().stream()
					.noneMatch(columns -> columns.equals(List.of("active_start_zeit")))) {
				this.jdbc.execute("create unique index termin_active_start_uq on termin (active_start_zeit)");
			}
			this.jdbc.update("update termin set active_start_zeit = start_zeit where deleted_at is null");
			this.jdbc.update("insert into app_migration (name) values (?)", TERMIN_TRASH_NAME);
		});
		LOG.info("Aktive Terminslots wurden von stornierten Terminen getrennt.");
	}

	private void ensureColumn(final String table, final String column, final String definition) {
		final boolean exists = this.jdbc.execute((ConnectionCallback<Boolean>) connection -> {
			final DatabaseMetaData metadata = connection.getMetaData();
			for (final String tableName : List.of(table, table.toUpperCase(Locale.ROOT))) {
				try (ResultSet columns = metadata.getColumns(connection.getCatalog(), connection.getSchema(),
						tableName, null)) {
					while (columns.next()) {
						if (column.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
							return true;
						}
					}
				}
			}
			return false;
		});
		if (!exists) {
			this.jdbc.execute("alter table " + table + " add column " + column + " " + definition);
		}
	}

	private Map<String, List<String>> uniqueIndexes(final String table) {
		return this.jdbc.execute((ConnectionCallback<Map<String, List<String>>>) connection -> {
			final DatabaseMetaData metadata = connection.getMetaData();
			final Map<String, List<String>> indexes = new LinkedHashMap<>();
			for (final String tableName : List.of(table, table.toUpperCase(Locale.ROOT))) {
				try (ResultSet rows = metadata.getIndexInfo(connection.getCatalog(), connection.getSchema(),
						tableName, true, false)) {
					while (rows.next()) {
						final String indexName = rows.getString("INDEX_NAME");
						final String columnName = rows.getString("COLUMN_NAME");
						if (indexName != null && columnName != null) {
							final List<String> columns = indexes.computeIfAbsent(indexName, key -> new ArrayList<>());
							final String normalizedColumn = columnName.toLowerCase(Locale.ROOT);
							if (!columns.contains(normalizedColumn)) {
								columns.add(normalizedColumn);
							}
						}
					}
				}
			}
			return indexes;
		});
	}

	private void dropIndex(final String table, final String indexName) {
		this.jdbc.execute((ConnectionCallback<Void>) connection -> {
			final DatabaseMetaData metadata = connection.getMetaData();
			final String quote = metadata.getIdentifierQuoteString().trim();
			final String quotedIndex = quote + indexName.replace(quote, quote + quote) + quote;
			final String product = metadata.getDatabaseProductName();
			final String sql;
			if (product.contains("MySQL") || product.contains("MariaDB")) {
				sql = "alter table " + table + " drop index " + quotedIndex;
			} else if (product.contains("H2")) {
				sql = "alter table " + table + " drop constraint " + quotedIndex;
			} else {
				throw new SQLException("Unsupported database for appointment trash migration: " + product);
			}
			try (Statement statement = connection.createStatement()) {
				statement.execute(sql);
			}
			return null;
		});
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

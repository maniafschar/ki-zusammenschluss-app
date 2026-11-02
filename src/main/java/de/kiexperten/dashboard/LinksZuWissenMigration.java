package de.kiexperten.dashboard;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/** Once per database: turns the former dashboard links into knowledge sources. The old table stays untouched. */
@Component
class LinksZuWissenMigration implements SmartInitializingSingleton {
	private static final String NAME = "links-to-wissensquelle";

	private final JdbcTemplate jdbc;

	LinksZuWissenMigration(final JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void afterSingletonsInstantiated() {
		this.jdbc.execute("create table if not exists app_migration (name varchar(100) primary key)");
		if (count("select count(*) from app_migration where name = ?", NAME) > 0) {
			return;
		}
		if (count("select count(*) from information_schema.tables where upper(table_name) = ?", "LINK") > 0) {
			final List<Map<String, Object>> links = this.jdbc
					.queryForList("select title, url, note, created_at from link order by id");
			for (final Map<String, Object> link : links) {
				final String url = HtmlUtils.htmlEscape(String.valueOf(link.get("URL")));
				final Object note = link.get("NOTE");
				final String html = "<p><a href=\"" + url + "\" target=\"_blank\" rel=\"noopener noreferrer\">" + url
						+ "</a></p>" + (note == null ? "" : "<p>" + HtmlUtils.htmlEscape(note.toString()) + "</p>");
				this.jdbc.update("insert into wissensquelle (title, content, created_at) values (?, ?, ?)",
						link.get("TITLE"), html, (Timestamp) link.get("CREATED_AT"));
			}
		}
		this.jdbc.update("insert into app_migration (name) values (?)", NAME);
	}

	private int count(final String sql, final String arg) {
		final Integer n = this.jdbc.queryForObject(sql, Integer.class, arg);
		return n == null ? 0 : n;
	}
}

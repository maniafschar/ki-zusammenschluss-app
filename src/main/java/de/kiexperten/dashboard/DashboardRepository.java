package de.kiexperten.dashboard;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/** JPA access to the dashboard tables (knowledge sources, todos, info) in the main database. */
@Repository
@Transactional
public class DashboardRepository {
	@PersistenceContext
	private EntityManager em;

	public record AnhangInfo(BigInteger id, BigInteger wissensId, String name, String contentType, long size) {
	}

	public List<Wissensquelle> wissen() {
		return this.em.createQuery("from Wissensquelle order by lower(title), id", Wissensquelle.class)
				.getResultList();
	}

	public List<AnhangInfo> anhaenge() {
		return this.em.createQuery("select new de.kiexperten.dashboard.DashboardRepository$AnhangInfo("
				+ "a.id, a.wissensId, a.name, a.contentType, a.size) from Anhang a order by a.id",
				AnhangInfo.class).getResultList();
	}

	public Wissensquelle addWissen(final String title, final String content) {
		final Wissensquelle w = new Wissensquelle();
		w.setTitle(title);
		w.setContent(content);
		w.setCreatedAt(now());
		this.em.persist(w);
		return w;
	}

	public Wissensquelle updateWissen(final BigInteger id, final String title, final String content) {
		final Wissensquelle w = this.em.find(Wissensquelle.class, id);
		if (w == null)
			return null;
		w.setTitle(title);
		w.setContent(content);
		w.setModifiedAt(now());
		return w;
	}

	public boolean deleteWissen(final BigInteger id) {
		if (!this.delete(Wissensquelle.class, id))
			return false;
		this.em.createQuery("delete from Anhang a where a.wissensId = :id").setParameter("id", id)
				.executeUpdate();
		return true;
	}

	public boolean wissenExists(final BigInteger id) {
		return this.em.find(Wissensquelle.class, id) != null;
	}

	public AnhangInfo addAnhang(final BigInteger wissensId, final String name, final String contentType,
			final byte[] data) {
		final Anhang a = new Anhang();
		a.setWissensId(wissensId);
		a.setName(name);
		a.setContentType(contentType);
		a.setSize(data.length);
		a.setData(data);
		a.setCreatedAt(now());
		this.em.persist(a);
		return new AnhangInfo(a.getId(), wissensId, name, contentType, data.length);
	}

	public Anhang anhang(final BigInteger wissensId, final BigInteger id) {
		final Anhang a = this.em.find(Anhang.class, id);
		return a != null && wissensId.equals(a.getWissensId()) ? a : null;
	}

	public boolean deleteAnhang(final BigInteger wissensId, final BigInteger id) {
		final Anhang a = this.anhang(wissensId, id);
		if (a == null)
			return false;
		this.em.remove(a);
		return true;
	}

	public List<Todo> todos() {
		return this.em.createQuery("from Todo order by dueDate asc nulls last, id", Todo.class).getResultList();
	}

	public Todo addTodo(final String note, final Long expertId, final LocalDate dueDate) {
		final Todo todo = new Todo();
		todo.setNote(note);
		todo.setExpertId(expertId);
		todo.setDueDate(dueDate);
		todo.setCreatedAt(now());
		this.em.persist(todo);
		return todo;
	}

	/** Changes only what the caller supplies; expert and due date can be cleared explicitly. */
	public Todo updateTodo(final BigInteger id, final Boolean completed, final boolean changeAssignment,
			final Long expertId, final LocalDate dueDate) {
		final Todo todo = this.em.find(Todo.class, id);
		if (todo == null)
			return null;
		if (completed != null)
			todo.setCompleted(completed);
		if (changeAssignment) {
			todo.setExpertId(expertId);
			todo.setDueDate(dueDate);
		}
		todo.setModifiedAt(now());
		return todo;
	}

	public boolean deleteTodo(final BigInteger id) {
		return this.delete(Todo.class, id);
	}

	public String info() {
		final Info info = this.infoEntity();
		return info == null ? "" : info.getNote();
	}

	public void saveInfo(final String note) {
		final Info info = this.infoEntity();
		if (info == null) {
			final Info created = new Info();
			created.setCreatedAt(now());
			created.setNote(note);
			this.em.persist(created);
		} else {
			info.setNote(note);
			info.setModifiedAt(now());
		}
	}

	private Info infoEntity() {
		final List<Info> result = this.em.createQuery("from Info order by id", Info.class)
				.setMaxResults(1).getResultList();
		return result.isEmpty() ? null : result.get(0);
	}

	private <T> boolean delete(final Class<T> type, final BigInteger id) {
		final T entity = this.em.find(type, id);
		if (entity == null)
			return false;
		this.em.remove(entity);
		return true;
	}

	// UTC wall-clock time, like all other timestamps in the database
	private static Timestamp now() {
		return Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC));
	}
}

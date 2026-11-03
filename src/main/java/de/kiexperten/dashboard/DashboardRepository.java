package de.kiexperten.dashboard;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import de.kiexperten.audit.Auditable;
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
				+ "a.id, a.wissensId, a.name, a.contentType, a.size) from Anhang a "
				+ "where a.wissensId in (select w.id from Wissensquelle w) order by a.id",
				AnhangInfo.class).getResultList();
	}

	public Wissensquelle addWissen(final String title, final String content) {
		final Wissensquelle w = new Wissensquelle();
		w.setTitle(title);
		w.setContent(content);
		this.em.persist(w);
		return w;
	}

	public Wissensquelle updateWissen(final BigInteger id, final String title, final String content) {
		final Wissensquelle w = this.em.find(Wissensquelle.class, id);
		if (w == null)
			return null;
		w.setTitle(title);
		w.setContent(content);
		return w;
	}

	public boolean deleteWissen(final BigInteger id) {
		return this.delete(Wissensquelle.class, id);
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
		this.em.persist(a);
		return new AnhangInfo(a.getId(), wissensId, name, contentType, data.length);
	}

	public Anhang anhang(final BigInteger wissensId, final BigInteger id) {
		final Anhang a = this.em.find(Anhang.class, id);
		return a != null && this.wissenExists(wissensId) && wissensId.equals(a.getWissensId()) ? a : null;
	}

	public boolean deleteAnhang(final BigInteger wissensId, final BigInteger id) {
		final Anhang a = this.anhang(wissensId, id);
		if (a == null)
			return false;
		a.trash();
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
			created.setNote(note);
			this.em.persist(created);
		} else {
			info.setNote(note);
		}
	}

	private Info infoEntity() {
		final List<Info> result = this.em.createQuery("from Info order by id", Info.class)
				.setMaxResults(1).getResultList();
		return result.isEmpty() ? null : result.get(0);
	}

	/** Moves the item to the trash. */
	private <T extends Auditable> boolean delete(final Class<T> type, final BigInteger id) {
		final T entity = this.em.find(type, id);
		if (entity == null)
			return false;
		entity.trash();
		return true;
	}

}

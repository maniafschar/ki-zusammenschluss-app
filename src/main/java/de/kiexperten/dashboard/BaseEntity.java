package de.kiexperten.dashboard;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

import org.springframework.data.annotation.Transient;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

@MappedSuperclass
@JsonInclude(JsonInclude.Include.NON_NULL)
public abstract class BaseEntity {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(columnDefinition = "BIGINT")
	private BigInteger id;

	private Timestamp createdAt;
	private Timestamp modifiedAt;
	@Transient
	private transient Map<String, Object> old = null;

	public BigInteger getId() {
		return this.id;
	}

	public void setId(final BigInteger id) {
		this.id = id;
	}

	public Timestamp getCreatedAt() {
		return this.createdAt;
	}

	public void setCreatedAt(final Timestamp createdAt) {
		this.createdAt = createdAt;
	}

	public Timestamp getModifiedAt() {
		return this.modifiedAt;
	}

	public void setModifiedAt(final Timestamp modifiedAt) {
		this.modifiedAt = modifiedAt;
	}

	@Transient
	public final boolean modified() {
		if (this.old == null)
			return true;
		final Iterator<String> i = this.old.keySet().iterator();
		while (i.hasNext()) {
			final String field = i.next();
			if (this.old(field) != null)
				return true;
			if (this.old.get(field) == null) {
				try {
					final Field f = this.getClass().getDeclaredField(field);
					f.setAccessible(true);
					if (f.get(this) != null)
						return true;
				} catch (final Exception e) {
					throw new RuntimeException(e);
				}
			}
		}
		return false;
	}

	@Transient
	public final void historize() {
		if (this.id == null)
			return;
		if (this.old == null)
			this.old = new HashMap<>();
		for (final Field field : this.getClass().getDeclaredFields()) {
			try {
				if ((field.getModifiers() & Modifier.TRANSIENT) == 0) {
					field.setAccessible(true);
					this.old.put(field.getName(), field.get(this));
				}
			} catch (final Exception e) {
				throw new RuntimeException("Failed to historize on " + field.getName(), e);
			}
		}
	}

	@Transient
	public final Object old(final String name) {
		if (this.old == null)
			return null;
		final Object valueOld = this.old.get(name);
		if (valueOld == null)
			return null;
		try {
			final Field field = this.getClass().getDeclaredField(name);
			field.setAccessible(true);
			return Objects.equals(field.get(this), valueOld) ? null : valueOld;
		} catch (final Exception ex) {
			throw new RuntimeException("Failed to read " + name, ex);
		}
	}
}
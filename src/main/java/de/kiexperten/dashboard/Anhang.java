package de.kiexperten.dashboard;

import java.math.BigInteger;

import org.hibernate.annotations.SQLRestriction;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import de.kiexperten.audit.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/** File attached to a {@link Wissensquelle}; the content lives in the database. */
@Entity
@JsonIgnoreProperties({ "createdBy", "modifiedBy", "deletedAt", "deletedBy" })
@SQLRestriction("deleted_at is null")
@Table(indexes = @Index(columnList = "wissensId"))
public class Anhang extends Auditable {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(columnDefinition = "BIGINT")
	private BigInteger id;

	@Column(nullable = false, columnDefinition = "BIGINT")
	private BigInteger wissensId;

	@Column(nullable = false, length = 255)
	private String name;

	@Column(length = 150)
	private String contentType;

	private long size;

	@Lob
	@Column(nullable = false)
	private byte[] data;

	public BigInteger getId() {
		return this.id;
	}

	public void setId(final BigInteger id) {
		this.id = id;
	}

	public BigInteger getWissensId() {
		return this.wissensId;
	}

	public void setWissensId(final BigInteger wissensId) {
		this.wissensId = wissensId;
	}

	public String getName() {
		return this.name;
	}

	public void setName(final String name) {
		this.name = name;
	}

	public String getContentType() {
		return this.contentType;
	}

	public void setContentType(final String contentType) {
		this.contentType = contentType;
	}

	public long getSize() {
		return this.size;
	}

	public void setSize(final long size) {
		this.size = size;
	}

	public byte[] getData() {
		return this.data;
	}

	public void setData(final byte[] data) {
		this.data = data;
	}
}

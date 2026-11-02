package de.kiexperten.dashboard;

import java.math.BigInteger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/** File attached to a {@link Wissensquelle}; the content lives in the database. */
@Entity
@Table(indexes = @Index(columnList = "wissensId"))
public class Anhang extends BaseEntity {
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

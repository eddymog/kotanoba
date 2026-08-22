package com.kotanoba.text;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An imported document. "text" is a non-reserved word in Postgres so it works
 * unquoted.
 *
 * <p>lemmaIds maps V1__initial_schema.sql's native {@code BIGINT[]} column —
 * the durable, inspectable copy of the distinct lemma set (design.md §2: a
 * plain array, not a serialized bitmap). The RoaringBitmap used for
 * difficulty scoring (Slice 3) is a derived Redis artifact built from this
 * column, not the other way around. {@code @JdbcTypeCode(SqlTypes.ARRAY)} is
 * what makes Hibernate 6 map a plain {@code List<Long>} onto a real Postgres
 * array instead of expecting a join table.
 */
@Entity
@Table(name = "text")
public class TextDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String title;

    // No @Lob: Postgres TEXT already holds arbitrary-length content with no
    // size cap. @Lob makes Hibernate expect the "oid" large-object type
    // instead of the plain TEXT column V1__initial_schema.sql actually
    // defines — a real schema-validation failure, not a hypothetical one.
    @Column(nullable = false)
    private String body;

    @Column(name = "source_url")
    private String sourceUrl;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "lemma_ids", nullable = false)
    private List<Long> lemmaIds;

    @Column(name = "token_count", nullable = false)
    private int tokenCount;

    // JPA's INSERT writes every mapped column explicitly, so an unset field
    // here sends NULL and overrides the column's DB-side DEFAULT now() —
    // violates NOT NULL. @CreationTimestamp sets it client-side instead.
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TextDocument() {
        // JPA
    }

    public TextDocument(Long userId, String title, String body, String sourceUrl, List<Long> lemmaIds, int tokenCount) {
        this.userId = userId;
        this.title = title;
        this.body = body;
        this.sourceUrl = sourceUrl;
        this.lemmaIds = lemmaIds;
        this.tokenCount = tokenCount;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public List<Long> getLemmaIds() {
        return lemmaIds;
    }

    public int getTokenCount() {
        return tokenCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

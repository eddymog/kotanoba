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
 * the distinct lemma set for this text (design.md §2: a plain array). Slice
 * 3's difficulty scoring queries this column directly with plain SQL; no
 * bitmap library, no cache (see design.md's over-engineering review).
 * {@code @JdbcTypeCode(SqlTypes.ARRAY)} is what makes Hibernate 6 map a plain
 * {@code List<Long>} onto a real Postgres array instead of expecting a join
 * table.
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

    // Null until the text is first opened (design.md §14: recency signal
    // only, not exact resume position). Set by TextDocumentRepository's
    // touchLastOpenedAt, not JPA save() — the read path (GET /{id}) is the
    // only writer, and it doesn't otherwise load this entity for mutation.
    @Column(name = "last_opened_at")
    private Instant lastOpenedAt;

    // Null until the reader saves one — a text_token.position value, not a
    // char offset (design.md §15). Set only when leaving the reader, not on
    // every click, same "recency signal, don't over-write" reasoning as
    // lastOpenedAt but for exact position instead of just a timestamp.
    @Column(name = "last_read_position")
    private Integer lastReadPosition;

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

    public Instant getLastOpenedAt() {
        return lastOpenedAt;
    }

    public Integer getLastReadPosition() {
        return lastReadPosition;
    }
}

package com.kotanoba.lemma;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;

/**
 * The dictionary form. Everything a user "knows" hangs off this row — see
 * design.md §2 for why normalizedForm (not dictionaryForm) is the unique key.
 *
 * <p>Read-mostly outside import, so plain JPA is the right tool here. The
 * bulk upsert used during import lives in {@link LemmaBulkUpsertRepository}
 * instead, since Spring Data JPA has no good way to express
 * "upsert N rows, get back all N ids, in one round trip."
 */
@Entity
@Table(name = "lemma")
public class Lemma {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "normalized_form", nullable = false)
    private String normalizedForm;

    @Column(name = "dictionary_form", nullable = false)
    private String dictionaryForm;

    @Column(name = "reading_form")
    private String readingForm;

    @Column(name = "part_of_speech", nullable = false)
    private String partOfSpeech;

    // Not currently written via JPA (import bulk-upserts through raw SQL,
    // relying on Postgres's DEFAULT now()), but @CreationTimestamp here
    // pre-empts the same NOT NULL violation TextDocument hit the moment
    // anything does save() this entity through JPA.
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Lemma() {
        // JPA
    }

    public Long getId() {
        return id;
    }

    public String getNormalizedForm() {
        return normalizedForm;
    }

    public String getDictionaryForm() {
        return dictionaryForm;
    }

    public String getReadingForm() {
        return readingForm;
    }

    public String getPartOfSpeech() {
        return partOfSpeech;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

package com.kotanoba.lemma;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The hot path (design.md §10): a user marks a word's status thousands of
 * times per reading session, continuously, forever — orders of magnitude more
 * than any other write in the app. Deliberately not a Spring Data JPA
 * repository.
 *
 * <p>A word click is an upsert (no row exists the first time, a row exists
 * every time after), which JPA can only express as find-then-save — two round
 * trips plus a race window if two requests land together. One statement
 * instead, hitting the (user_id, lemma_id) primary key directly.
 */
@Repository
public class UserLemmaStatusRepository {

    private static final String UPSERT_SQL = """
        INSERT INTO user_lemma_status (user_id, lemma_id, status, updated_at)
        VALUES (?, ?, ?, ?)
        ON CONFLICT (user_id, lemma_id)
            DO UPDATE SET status = EXCLUDED.status, updated_at = EXCLUDED.updated_at
        """;

    private final JdbcTemplate jdbcTemplate;

    public UserLemmaStatusRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void setStatus(long userId, long lemmaId, LemmaStatus status) {
        jdbcTemplate.update(
            UPSERT_SQL,
            userId,
            lemmaId,
            status.name(),
            Timestamp.from(Instant.now())
        );
    }
}

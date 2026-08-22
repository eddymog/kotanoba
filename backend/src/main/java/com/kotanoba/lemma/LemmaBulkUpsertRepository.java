package com.kotanoba.lemma;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Bulk upsert for the lemma table, used only during import.
 *
 * <p>A text has ~700 distinct lemmas (design.md's read-path numbers). Doing
 * that as 700 find-or-create round trips through JPA would be exactly the
 * anti-pattern claude.md's performance constraint exists to prevent — so this
 * upserts every distinct lemma from one text in a single statement using
 * Postgres's {@code unnest} over array parameters, and returns the ids in one
 * result set.
 *
 * <p>{@code dictionary_form} is first-write-wins (design.md §1: normalized_form
 * is sometimes an archaic spelling users shouldn't see, so once a lemma has a
 * human-legible dictionary_form we don't want a later import silently
 * replacing it) — the {@code ON CONFLICT} clause is a no-op update purely so
 * {@code RETURNING} still fires for rows that already existed.
 */
@Repository
public class LemmaBulkUpsertRepository {

    private static final String UPSERT_SQL = """
        WITH input AS (
            SELECT * FROM unnest(?, ?, ?, ?)
                AS t(normalized_form, dictionary_form, reading_form, part_of_speech)
        ),
        upserted AS (
            INSERT INTO lemma (normalized_form, dictionary_form, reading_form, part_of_speech)
            SELECT normalized_form, dictionary_form, reading_form, part_of_speech FROM input
            ON CONFLICT (normalized_form, part_of_speech)
                DO UPDATE SET normalized_form = EXCLUDED.normalized_form
            RETURNING id, normalized_form, part_of_speech
        )
        SELECT id, normalized_form, part_of_speech FROM upserted
        """;

    private final JdbcTemplate jdbcTemplate;

    public LemmaBulkUpsertRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param candidates distinct lemmas to upsert; normalizedForm+partOfSpeech
     *                   must be unique within the list (the caller dedupes)
     * @return id for every candidate, keyed by (normalizedForm, partOfSpeech)
     */
    public Map<LemmaKey, Long> upsertAll(List<LemmaCandidate> candidates) {
        if (candidates.isEmpty()) {
            return Map.of();
        }

        String[] normalizedForms = candidates.stream().map(LemmaCandidate::normalizedForm).toArray(String[]::new);
        String[] dictionaryForms = candidates.stream().map(LemmaCandidate::dictionaryForm).toArray(String[]::new);
        String[] readingForms = candidates.stream().map(LemmaCandidate::readingForm).toArray(String[]::new);
        String[] partsOfSpeech = candidates.stream().map(LemmaCandidate::partOfSpeech).toArray(String[]::new);

        Map<LemmaKey, Long> result = new HashMap<>();
        jdbcTemplate.query(
            (Connection connection) -> {
                // Bind the arrays through the same connection the statement runs
                // on — Connection.createArrayOf must not reach back into the
                // DataSource for a second connection, or every import leaks one.
                PreparedStatement ps = connection.prepareStatement(UPSERT_SQL);
                ps.setArray(1, connection.createArrayOf("text", normalizedForms));
                ps.setArray(2, connection.createArrayOf("text", dictionaryForms));
                ps.setArray(3, connection.createArrayOf("text", readingForms));
                ps.setArray(4, connection.createArrayOf("text", partsOfSpeech));
                return ps;
            },
            (ResultSet rs) -> {
                while (rs.next()) {
                    result.put(
                        new LemmaKey(rs.getString("normalized_form"), rs.getString("part_of_speech")),
                        rs.getLong("id")
                    );
                }
                return null;
            }
        );
        return result;
    }

    public record LemmaCandidate(
        String normalizedForm, String dictionaryForm, String readingForm, String partOfSpeech
    ) {}

    public record LemmaKey(String normalizedForm, String partOfSpeech) {}
}

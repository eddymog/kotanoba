package com.kotanoba.lemma;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Records observed surface-form -> lemma pairs during import.
 *
 * <p>Per design.md §2, word_form is a record, not a lookup: Sudachi already
 * resolved each token's lemma with sentence context, so nothing on the import
 * or read path ever queries this table to resolve anything. It exists for
 * future surface-text search ("which forms of 食べる have I seen?"). Fire-and-
 * forget insert — {@code DO NOTHING} on conflict, no ids needed back.
 */
@Repository
public class WordFormWriter {

    private static final String INSERT_SQL = """
        INSERT INTO word_form (surface_form, lemma_id)
        SELECT * FROM unnest(?, ?)
        ON CONFLICT (surface_form, lemma_id) DO NOTHING
        """;

    private final JdbcTemplate jdbcTemplate;

    public WordFormWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void recordAll(List<String> surfaceForms, List<Long> lemmaIds) {
        if (surfaceForms.isEmpty()) {
            return;
        }
        jdbcTemplate.execute((java.sql.Connection connection) -> {
            var ps = connection.prepareStatement(INSERT_SQL);
            ps.setArray(1, connection.createArrayOf("text", surfaceForms.toArray()));
            ps.setArray(2, connection.createArrayOf("bigint", lemmaIds.toArray()));
            return ps.execute();
        });
    }
}

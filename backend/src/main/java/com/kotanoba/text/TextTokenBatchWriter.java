package com.kotanoba.text;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persists text_token rows. claude.md: JDBC batch, not saveAll() — a 2,000-word
 * article is 2,000 rows, and saveAll() through Hibernate means 2,000 managed
 * entities and (absent careful batching config) 2,000 round trips.
 *
 * <p>text_token is deliberately never a JPA @Entity (design.md §10): if the
 * entity doesn't exist, nobody can write the loop that lazy-loads a lemma per
 * token and turns this into 700 extra queries.
 */
@Repository
public class TextTokenBatchWriter {

    private static final String INSERT_SQL = """
        INSERT INTO text_token
            (text_id, position, char_start, char_end, surface_text, reading, lemma_id, is_word)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private final JdbcTemplate jdbcTemplate;

    public TextTokenBatchWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertAll(long textId, List<TokenRecord> tokens) {
        jdbcTemplate.batchUpdate(INSERT_SQL, tokens, tokens.size(), (ps, token) -> {
            ps.setLong(1, textId);
            ps.setInt(2, token.position());
            ps.setInt(3, token.charStart());
            ps.setInt(4, token.charEnd());
            ps.setString(5, token.surfaceText());
            ps.setString(6, token.reading());
            if (token.lemmaId() != null) {
                ps.setLong(7, token.lemmaId());
            } else {
                ps.setNull(7, java.sql.Types.BIGINT);
            }
            ps.setBoolean(8, token.isWord());
        });
    }
}

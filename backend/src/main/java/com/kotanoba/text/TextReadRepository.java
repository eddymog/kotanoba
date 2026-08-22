package com.kotanoba.text;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The read-path query (design.md decision #5): two queries, merged in Java,
 * not one join. Opening a 2,000-token article costs one ordered scan for the
 * tokens plus one indexed lookup for ~700 distinct statuses — never one
 * status lookup per token.
 *
 * <p>JdbcTemplate, not JPA — text_token is deliberately never a JPA entity
 * (see TextTokenBatchWriter), so nothing here can accidentally lazy-load a
 * lemma per token and turn this into 700 extra queries.
 */
@Repository
public class TextReadRepository {

    private static final String TOKENS_SQL = """
        SELECT position, char_start, char_end, surface_text, reading, lemma_id, is_word
        FROM text_token
        WHERE text_id = ?
        ORDER BY position
        """;

    private static final String STATUSES_SQL = """
        SELECT lemma_id, status
        FROM user_lemma_status
        WHERE user_id = ? AND lemma_id = ANY(?)
        """;

    private final JdbcTemplate jdbcTemplate;

    public TextReadRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<TokenView> loadTokensWithStatus(long textId, long userId) {
        List<TokenRow> rows = jdbcTemplate.query(
            TOKENS_SQL,
            (ResultSet rs, int rowNum) -> new TokenRow(
                rs.getInt("position"),
                rs.getInt("char_start"),
                rs.getInt("char_end"),
                rs.getString("surface_text"),
                rs.getString("reading"),
                (Long) rs.getObject("lemma_id"), // nullable for punctuation/whitespace
                rs.getBoolean("is_word")
            ),
            textId
        );

        List<Long> distinctLemmaIds = rows.stream()
            .map(TokenRow::lemmaId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();

        Map<Long, String> statusByLemmaId = loadStatuses(userId, distinctLemmaIds);

        return rows.stream()
            .map(row -> new TokenView(
                row.position(),
                row.charStart(),
                row.charEnd(),
                row.surfaceText(),
                row.reading(),
                row.lemmaId(),
                row.isWord(),
                // Absence of a user_lemma_status row means NEW (design.md §2)
                // — but only for tokens that have a lemma at all.
                row.lemmaId() == null ? null : statusByLemmaId.getOrDefault(row.lemmaId(), "NEW")
            ))
            .toList();
    }

    private Map<Long, String> loadStatuses(long userId, List<Long> lemmaIds) {
        if (lemmaIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> result = new HashMap<>();
        jdbcTemplate.query(
            (Connection connection) -> {
                PreparedStatement ps = connection.prepareStatement(STATUSES_SQL);
                ps.setLong(1, userId);
                ps.setArray(2, connection.createArrayOf("bigint", lemmaIds.toArray()));
                return ps;
            },
            (ResultSet rs) -> {
                while (rs.next()) {
                    result.put(rs.getLong("lemma_id"), rs.getString("status"));
                }
                return null;
            }
        );
        return result;
    }

    private record TokenRow(
        int position, int charStart, int charEnd, String surfaceText, String reading, Long lemmaId, boolean isWord
    ) {}
}

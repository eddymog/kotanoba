package com.kotanoba.text;

import com.kotanoba.lemma.VocabularyBrowseRepository;
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
 * tokens plus one indexed lookup for ~700 distinct lemmas' status/senses/POS
 * — never one lookup per token.
 *
 * <p>JdbcTemplate, not JPA — text_token is deliberately never a JPA entity
 * (see TextTokenBatchWriter), so nothing here can accidentally lazy-load a
 * lemma per token and turn this into 700 extra queries.
 *
 * <p>senses/example sentence come from dictionary_entry/word_example
 * (design.md §18), part_of_speech still from word_frequency (design.md
 * §13) — all three joined on lemma.normalized_form/reading_form, not
 * dictionary_form (design.md §16) — できる and 出来る share a
 * normalized_form but not a dictionary_form (first-write-wins per decision
 * #1), so joining on the displayed spelling would give the same word a
 * different definition/rank depending on which spelling this particular
 * text happened to use. Every lemma here already came from a real import,
 * so this is the simple case of that join: no "lemma might not exist yet"
 * fallback like the vocabulary browse page needs.
 */
@Repository
public class TextReadRepository {

    private static final String TOKENS_SQL = """
        SELECT position, char_start, char_end, surface_text, reading, lemma_id, is_word
        FROM text_token
        WHERE text_id = ?
        ORDER BY position
        """;

    private static final String LEMMA_ENRICHMENT_SQL = """
        SELECT
            l.id AS lemma_id,
            uls.status,
            de.senses,
            wf.part_of_speech,
            we.japanese_text,
            we.english_text
        FROM lemma l
        LEFT JOIN user_lemma_status uls ON uls.lemma_id = l.id AND uls.user_id = ?
        LEFT JOIN word_frequency wf ON wf.normalized_form = l.normalized_form AND wf.reading = l.reading_form
        LEFT JOIN dictionary_entry de ON de.normalized_form = l.normalized_form AND de.reading = l.reading_form
        LEFT JOIN word_example we ON we.normalized_form = l.normalized_form AND we.reading = l.reading_form
        WHERE l.id = ANY(?)
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

        Map<Long, LemmaEnrichment> enrichmentByLemmaId = loadLemmaEnrichment(userId, distinctLemmaIds);

        return rows.stream()
            .map(row -> {
                if (row.lemmaId() == null) {
                    return new TokenView(
                        row.position(), row.charStart(), row.charEnd(), row.surfaceText(), row.reading(),
                        null, row.isWord(), null, null, null, null, null
                    );
                }
                // Absence of a user_lemma_status row means NEW (design.md §2).
                LemmaEnrichment enrichment = enrichmentByLemmaId.getOrDefault(
                    row.lemmaId(), new LemmaEnrichment("NEW", null, null, null, null)
                );
                return new TokenView(
                    row.position(), row.charStart(), row.charEnd(), row.surfaceText(), row.reading(),
                    row.lemmaId(), row.isWord(), enrichment.status(), enrichment.senses(), enrichment.partOfSpeech(),
                    enrichment.exampleJapanese(), enrichment.exampleEnglish()
                );
            })
            .toList();
    }

    private Map<Long, LemmaEnrichment> loadLemmaEnrichment(long userId, List<Long> lemmaIds) {
        if (lemmaIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, LemmaEnrichment> result = new HashMap<>();
        jdbcTemplate.query(
            (Connection connection) -> {
                PreparedStatement ps = connection.prepareStatement(LEMMA_ENRICHMENT_SQL);
                ps.setLong(1, userId);
                ps.setArray(2, connection.createArrayOf("bigint", lemmaIds.toArray()));
                return ps;
            },
            (ResultSet rs) -> {
                while (rs.next()) {
                    String status = rs.getString("status");
                    result.put(rs.getLong("lemma_id"), new LemmaEnrichment(
                        status == null ? "NEW" : status,
                        VocabularyBrowseRepository.toSenses(rs.getArray("senses")),
                        rs.getString("part_of_speech"),
                        rs.getString("japanese_text"),
                        rs.getString("english_text")
                    ));
                }
                return null;
            }
        );
        return result;
    }

    private record TokenRow(
        int position, int charStart, int charEnd, String surfaceText, String reading, Long lemmaId, boolean isWord
    ) {}

    private record LemmaEnrichment(
        String status, List<String> senses, String partOfSpeech, String exampleJapanese, String exampleEnglish
    ) {}
}

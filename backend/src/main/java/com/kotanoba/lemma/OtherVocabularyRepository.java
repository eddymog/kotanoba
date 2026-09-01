package com.kotanoba.lemma;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Words you've actually encountered (real lemma rows, from real imports)
 * that fall outside the top 10k frequency list — the counterpart to
 * VocabularyBrowseRepository, which only ever shows words *inside* that
 * list. Deliberately a separate list, not merged into the ranked one:
 * there is no rank to sort these by.
 *
 * <p>senses/example sentence come from dictionary_entry/word_example
 * (design.md §18) rather than word_frequency, which has no row for these
 * words by definition — dictionary_entry/word_example cover any word
 * JMdict/Tatoeba know about, not just the top 10k, so a word being outside
 * the frequency list no longer means it has no definition.
 *
 * <p>"Actually encountered" means the lemma appears in one of this user's
 * own texts' lemma_ids — the same notion of ownership `text.lemma_ids`
 * already establishes, not every lemma row that has ever existed globally.
 *
 * <p>Sorted by lemma.created_at DESC (most recently encountered first) —
 * the closest available proxy to "when did I read this," since lemma rows
 * are global, not per-user-timestamped.
 */
@Repository
public class OtherVocabularyRepository {

    private static final String SCORED_CTE = """
        WITH my_lemmas AS (
            SELECT DISTINCT unnest(t.lemma_ids) AS lemma_id
            FROM text t
            WHERE t.user_id = ?
        ),
        scored AS (
            SELECT
                l.id AS lemma_id,
                l.dictionary_form,
                l.reading_form,
                l.part_of_speech,
                l.created_at,
                de.senses,
                we.japanese_text,
                we.english_text,
                COALESCE(uls.status, 'NEW') AS status
            FROM my_lemmas ml
            JOIN lemma l ON l.id = ml.lemma_id
            LEFT JOIN word_frequency wf
                   ON wf.normalized_form = l.normalized_form AND wf.reading = l.reading_form
            LEFT JOIN dictionary_entry de
                   ON de.normalized_form = l.normalized_form AND de.reading = l.reading_form
            LEFT JOIN word_example we
                   ON we.normalized_form = l.normalized_form AND we.reading = l.reading_form
            LEFT JOIN user_lemma_status uls ON uls.lemma_id = l.id AND uls.user_id = ?
            WHERE wf.normalized_form IS NULL
        )
        """;

    private final JdbcTemplate jdbcTemplate;

    public OtherVocabularyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<OtherVocabularyWordResponse> listPage(
        long userId, LemmaStatus statusFilter, String posCategoryFilter, int page, int pageSize
    ) {
        List<Object> params = new ArrayList<>(List.of(userId, userId));

        StringBuilder sql = new StringBuilder(SCORED_CTE)
            .append("SELECT lemma_id, dictionary_form, reading_form, part_of_speech, senses, japanese_text, english_text, status FROM scored");
        appendFilters(sql, params, statusFilter, posCategoryFilter);
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        params.add(pageSize);
        params.add((page - 1) * pageSize);

        return jdbcTemplate.query(
            sql.toString(),
            (ResultSet rs, int rowNum) -> new OtherVocabularyWordResponse(
                rs.getLong("lemma_id"),
                rs.getString("dictionary_form"),
                rs.getString("reading_form"),
                LemmaStatus.valueOf(rs.getString("status")),
                rs.getString("part_of_speech"),
                VocabularyBrowseRepository.toSenses(rs.getArray("senses")),
                rs.getString("japanese_text"),
                rs.getString("english_text")
            ),
            params.toArray()
        );
    }

    public int countMatching(long userId, LemmaStatus statusFilter, String posCategoryFilter) {
        List<Object> params = new ArrayList<>(List.of(userId, userId));

        StringBuilder sql = new StringBuilder(SCORED_CTE).append("SELECT count(*) FROM scored");
        appendFilters(sql, params, statusFilter, posCategoryFilter);

        Integer count = jdbcTemplate.queryForObject(sql.toString(), Integer.class, params.toArray());
        return count == null ? 0 : count;
    }

    private void appendFilters(StringBuilder sql, List<Object> params, LemmaStatus statusFilter, String posCategoryFilter) {
        List<String> conditions = new ArrayList<>();
        if (statusFilter != null) {
            conditions.add("status = ?");
            params.add(statusFilter.name());
        }
        if (posCategoryFilter != null && !posCategoryFilter.isBlank()) {
            // lemma.part_of_speech is always a single real Sudachi tag here
            // (never the "+"-joined multi-token kind word_frequency's
            // jpdb-derived data has, per V8) — the top-level category is
            // just the text before the first comma.
            conditions.add("split_part(part_of_speech, ',', 1) = ?");
            params.add(posCategoryFilter);
        }
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
    }
}

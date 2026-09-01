package com.kotanoba.text;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Library listing, difficulty-sorted by default (design.md decision #10:
 * "moves to SQL in Slice 3 when difficulty sorting lands"), with optional
 * title search and a choice of sort order. One query computing and sorting,
 * not a JPA list plus a separate score lookup merged in Java — the sort
 * itself is the DB's job here, same reasoning as decision #5's read path.
 *
 * <p>Score formula is design.md §9d: for each distinct lemma in a text,
 * weight it by 1/ln(rank + 1) — rarer words count for less — then
 * known-weight / total-weight. Words outside the top 10k (no word_frequency
 * row) get a floor weight of 0.05, deliberately below any ranked word's
 * weight. Both constants are open tuning per §9d.
 *
 * <p>Pagination and search were added ahead of an immediate scale need
 * (claude.md's own stated ceiling is "hundreds of documents") — built
 * because they were explicitly requested, not because the app needs them
 * yet at this size.
 */
@Repository
public class TextLibraryRepository {

    private static final String BASE_SELECT = """
        SELECT
            t.id,
            t.title,
            t.token_count,
            COALESCE(array_length(t.lemma_ids, 1), 0) AS distinct_lemma_count,
            t.created_at,
            t.last_opened_at,
            COALESCE(scored.difficulty_score, 1.0) AS difficulty_score
        FROM text t
        LEFT JOIN (
            SELECT
                text_id,
                SUM(CASE WHEN status = 'KNOWN' THEN weight ELSE 0 END) / NULLIF(SUM(weight), 0) AS difficulty_score
            FROM (
                SELECT
                    t2.id AS text_id,
                    uls.status,
                    CASE WHEN wf.rank IS NOT NULL THEN 1.0 / ln(wf.rank + 1) ELSE 0.05 END AS weight
                FROM text t2
                -- text_lemma_id, not lemma_id: a bare `lemma_id` alias here
                -- collides with user_lemma_status.lemma_id below and
                -- Postgres rejects the join as ambiguous.
                CROSS JOIN LATERAL unnest(t2.lemma_ids) AS text_lemma_id
                JOIN lemma l ON l.id = text_lemma_id
                -- normalized_form, not dictionary_form: できる and 出来る are
                -- the same word but different lemma.dictionary_form values
                -- (first-write-wins per decision #1) — joining on the
                -- displayed spelling gave the same word a different rank
                -- depending on which text happened to use which spelling.
                -- word_frequency is deduped to match (design.md §16).
                LEFT JOIN word_frequency wf
                       ON wf.normalized_form = l.normalized_form AND wf.reading = l.reading_form
                LEFT JOIN user_lemma_status uls
                       ON uls.lemma_id = text_lemma_id AND uls.user_id = t2.user_id
                WHERE t2.user_id = ?
            ) per_lemma
            GROUP BY text_id
        ) scored ON scored.text_id = t.id
        WHERE t.user_id = ?
        """;

    private static final String COUNT_SQL = "SELECT count(*) FROM text t WHERE t.user_id = ?";

    private final JdbcTemplate jdbcTemplate;

    public TextLibraryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<TextSummaryResponse> listForUser(
        long userId, String titleSearch, TextSortOrder sort, int page, int pageSize
    ) {
        List<Object> params = new ArrayList<>(List.of(userId, userId));

        StringBuilder sql = new StringBuilder(BASE_SELECT);
        appendTitleFilter(sql, params, titleSearch);
        sql.append(orderByClause(sort));
        sql.append(" LIMIT ? OFFSET ?");
        params.add(pageSize);
        params.add((page - 1) * pageSize);

        return jdbcTemplate.query(
            sql.toString(),
            (ResultSet rs, int rowNum) -> new TextSummaryResponse(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getInt("token_count"),
                rs.getInt("distinct_lemma_count"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("last_opened_at") == null ? null : rs.getTimestamp("last_opened_at").toInstant(),
                rs.getDouble("difficulty_score")
            ),
            params.toArray()
        );
    }

    public int countForUser(long userId, String titleSearch) {
        List<Object> params = new ArrayList<>(List.of(userId));
        StringBuilder sql = new StringBuilder(COUNT_SQL);
        appendTitleFilter(sql, params, titleSearch);

        Integer count = jdbcTemplate.queryForObject(sql.toString(), Integer.class, params.toArray());
        return count == null ? 0 : count;
    }

    private void appendTitleFilter(StringBuilder sql, List<Object> params, String titleSearch) {
        if (titleSearch != null && !titleSearch.isBlank()) {
            sql.append(" AND t.title ILIKE ?");
            params.add("%" + titleSearch + "%");
        }
    }

    // Never build ORDER BY from a raw request string, even a validated one —
    // switch on the enum so the SQL text itself is always one of these two
    // literals, not request-controlled.
    private String orderByClause(TextSortOrder sort) {
        return switch (sort) {
            case RECENT -> " ORDER BY t.created_at DESC";
            case DIFFICULTY -> " ORDER BY difficulty_score DESC, t.created_at DESC";
        };
    }
}

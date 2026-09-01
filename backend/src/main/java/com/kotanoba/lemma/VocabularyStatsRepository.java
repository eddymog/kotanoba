package com.kotanoba.lemma;

import java.sql.ResultSet;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * design.md §19: NEW/LEARNING/KNOWN/IGNORED counts for the statistics page,
 * one query per category instead of one row per word — the same status
 * computation VocabularyBrowseRepository and OtherVocabularyRepository
 * already do, grouped instead of listed. Deliberately its own repository
 * rather than added to either of those: neither needs pagination or the
 * senses/example joins here, just a count per status.
 */
@Repository
public class VocabularyStatsRepository {

    private static final String TOP_WORDS_SQL = """
        WITH scored AS (
            SELECT COALESCE(best.status, 'NEW') AS status
            FROM word_frequency wf
            LEFT JOIN LATERAL (
                SELECT uls.status
                FROM lemma l
                LEFT JOIN user_lemma_status uls ON uls.lemma_id = l.id AND uls.user_id = ?
                WHERE l.normalized_form = wf.normalized_form AND l.reading_form = wf.reading
                ORDER BY (uls.status IS NOT NULL) DESC, l.id ASC
                LIMIT 1
            ) best ON true
        )
        SELECT status, count(*) AS total FROM scored GROUP BY status
        """;

    private static final String OTHER_WORDS_SQL = """
        WITH my_lemmas AS (
            SELECT DISTINCT unnest(t.lemma_ids) AS lemma_id
            FROM text t
            WHERE t.user_id = ?
        ),
        scored AS (
            SELECT COALESCE(uls.status, 'NEW') AS status
            FROM my_lemmas ml
            JOIN lemma l ON l.id = ml.lemma_id
            LEFT JOIN word_frequency wf
                   ON wf.normalized_form = l.normalized_form AND wf.reading = l.reading_form
            LEFT JOIN user_lemma_status uls ON uls.lemma_id = l.id AND uls.user_id = ?
            WHERE wf.normalized_form IS NULL
        )
        SELECT status, count(*) AS total FROM scored GROUP BY status
        """;

    private final JdbcTemplate jdbcTemplate;

    public VocabularyStatsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public StatusCounts topWordCounts(long userId) {
        return toStatusCounts(jdbcTemplate.query(TOP_WORDS_SQL, VocabularyStatsRepository::mapRow, userId));
    }

    public StatusCounts otherWordCounts(long userId) {
        return toStatusCounts(jdbcTemplate.query(OTHER_WORDS_SQL, VocabularyStatsRepository::mapRow, userId, userId));
    }

    private static StatusRow mapRow(ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new StatusRow(LemmaStatus.valueOf(rs.getString("status")), rs.getInt("total"));
    }

    private static StatusCounts toStatusCounts(List<StatusRow> rows) {
        int newCount = 0;
        int learningCount = 0;
        int knownCount = 0;
        int ignoredCount = 0;
        for (StatusRow row : rows) {
            switch (row.status()) {
                case NEW -> newCount = row.count();
                case LEARNING -> learningCount = row.count();
                case KNOWN -> knownCount = row.count();
                case IGNORED -> ignoredCount = row.count();
            }
        }
        return new StatusCounts(newCount + learningCount + knownCount + ignoredCount, newCount, learningCount, knownCount, ignoredCount);
    }

    private record StatusRow(LemmaStatus status, int count) {}
}

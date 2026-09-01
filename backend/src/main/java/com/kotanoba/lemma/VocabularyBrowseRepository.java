package com.kotanoba.lemma;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Browsing the frequency reference (word_frequency, design.md §9b) with the
 * current user's status for each word — a triage view: "which of the most
 * common words do I already know," optionally filtered to one status
 * (e.g. KNOWN) and/or one part-of-speech category (e.g. Verb), combined.
 *
 * <p>word_frequency has no relationship to lemma (deliberately — see V4's
 * migration comment), so a word here may or may not have a lemma row yet.
 * The LATERAL join picks, per word, the best matching lemma if more than one
 * exists for the same (normalized_form, reading_form) — preferring one with
 * a status already set, so a homograph doesn't arbitrarily show NEW when the
 * user has actually marked one of its lemma rows. Matching on normalized_form
 * rather than dictionary_form (design.md §16) is what makes marking 出来る
 * KNOWN while reading correctly show できる as KNOWN here too, even though
 * word_frequency's surviving row after dedup is spelled できる and the
 * lemma's own dictionary_form is whichever spelling was actually imported.
 *
 * <p>Offset pagination, not rank-band: a filter matches an arbitrary,
 * scattered subset of ranks, so "page N = ranks (N-1)*100+1..N*100" (the
 * unfiltered original) can't paginate a filtered view sensibly — most bands
 * would come back empty. LIMIT/OFFSET over the same computed-status CTE
 * works for filtered and unfiltered requests uniformly, one query shape
 * instead of several.
 *
 * <p>senses/example sentence come from dictionary_entry/word_example
 * (design.md §18), not word_frequency.meaning — those two tables cover any
 * word JMdict/Tatoeba know about, not just the top 10k, and keep every
 * sense instead of one collapsed gloss. Joined on the same
 * (normalized_form, reading) key word_frequency's own rows already carry.
 */
@Repository
public class VocabularyBrowseRepository {

    private static final String SCORED_CTE = """
        WITH scored AS (
            SELECT
                wf.term,
                wf.reading,
                wf.rank,
                wf.part_of_speech,
                de.senses,
                we.japanese_text,
                we.english_text,
                wf.pos_categories,
                COALESCE(best.status, 'NEW') AS status
            FROM word_frequency wf
            LEFT JOIN dictionary_entry de ON de.normalized_form = wf.normalized_form AND de.reading = wf.reading
            LEFT JOIN word_example we ON we.normalized_form = wf.normalized_form AND we.reading = wf.reading
            LEFT JOIN LATERAL (
                SELECT uls.status
                FROM lemma l
                LEFT JOIN user_lemma_status uls ON uls.lemma_id = l.id AND uls.user_id = ?
                WHERE l.normalized_form = wf.normalized_form AND l.reading_form = wf.reading
                ORDER BY (uls.status IS NOT NULL) DESC, l.id ASC
                LIMIT 1
            ) best ON true
        )
        """;

    private final JdbcTemplate jdbcTemplate;

    public VocabularyBrowseRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<VocabularyWordResponse> listPage(
        long userId, LemmaStatus statusFilter, String posCategoryFilter, int page, int pageSize
    ) {
        List<Object> params = new ArrayList<>();
        params.add(userId);

        StringBuilder sql = new StringBuilder(SCORED_CTE)
            .append("SELECT term, reading, rank, part_of_speech, senses, japanese_text, english_text, status FROM scored");
        appendFilters(sql, params, statusFilter, posCategoryFilter);
        sql.append(" ORDER BY rank LIMIT ? OFFSET ?");
        params.add(pageSize);
        params.add((page - 1) * pageSize);

        return jdbcTemplate.query(
            sql.toString(),
            (ResultSet rs, int rowNum) -> new VocabularyWordResponse(
                rs.getString("term"),
                rs.getString("reading"),
                rs.getInt("rank"),
                LemmaStatus.valueOf(rs.getString("status")),
                rs.getString("part_of_speech"),
                toSenses(rs.getArray("senses")),
                rs.getString("japanese_text"),
                rs.getString("english_text")
            ),
            params.toArray()
        );
    }

    public static List<String> toSenses(Array senses) throws SQLException {
        if (senses == null) {
            return null;
        }
        return Arrays.asList((String[]) senses.getArray());
    }

    public int countMatching(long userId, LemmaStatus statusFilter, String posCategoryFilter) {
        List<Object> params = new ArrayList<>();
        params.add(userId);

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
            conditions.add("? = ANY(pos_categories)");
            params.add(posCategoryFilter);
        }
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
    }
}

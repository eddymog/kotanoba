package db.migration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Fills word_frequency.meaning (V9) from a one-time local batch match against
 * JMdict (jmdict-simplified, "common-only" English JSON, CC BY-SA / EDRDG
 * license) — design.md §13. Same "static reference data, resolved offline,
 * loaded by a plain migration" shape as V5 (ranks) and V8 (part of speech):
 * this migration has no runtime dependency on JMdict or any live service.
 *
 * <p>Matching strategy, ~75.4% overall coverage (81.1% of words with kanji,
 * 57.5% of pure-kana words):
 * <ol>
 *   <li>Term has kanji: exact match against a JMdict entry's kanji headword,
 *       preferring one whose kana reading agrees with ours if more than one
 *       kanji-headword match exists.
 *   <li>No kanji match (or none to try): match by kana reading instead. If
 *       more than one JMdict entry shares that reading (the same problem the
 *       browse page's POS column exists to help with — の is both a
 *       possessive particle and, under different kanji, "field"), pick the
 *       sense whose part-of-speech agrees with the real Sudachi POS V8
 *       already resolved for this word, rather than guessing.
 *   <li>No match either way: left null. Mostly multi-morpheme entries jpdb
 *       counts as one frequency unit that don't correspond to a single
 *       dictionary headword (ような, なので, 気がする) — the same phenomenon
 *       V8's "+"-joined part_of_speech exists for, not a matching bug.
 * </ol>
 */
public class V10__SeedWordFrequencyMeaning extends BaseJavaMigration {

    private static final String SEED_RESOURCE = "seed-data/jpdb_v2.2_freq_top10k_meaning.tsv";

    private static final String UPDATE_SQL = """
        UPDATE word_frequency SET meaning = ? WHERE term = ? AND reading = ?
        """;

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        try (InputStream in = getClass().getClassLoader().getResourceAsStream(SEED_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Seed resource not found on classpath: " + SEED_RESOURCE);
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                 PreparedStatement statement = connection.prepareStatement(UPDATE_SQL)) {

                String line = reader.readLine(); // header row, discarded
                int batched = 0;

                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    String[] fields = line.split("\t", -1);
                    String meaning = fields[3];
                    if (meaning.isEmpty()) {
                        continue; // no confident JMdict match, leave column null
                    }
                    statement.setString(1, meaning);
                    statement.setString(2, fields[0]);
                    statement.setString(3, fields[1]);
                    statement.addBatch();
                    batched++;
                }

                if (batched > 0) {
                    statement.executeBatch();
                }
            }
        } catch (IOException e) {
            throw new SQLException("Failed reading seed resource: " + SEED_RESOURCE, e);
        }
    }
}

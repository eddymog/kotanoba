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
 * Fills word_frequency.part_of_speech (V7) from a one-time local batch
 * resolution, not a live NLP call — the same reasoning as V5's Java
 * migration for the ranks themselves: this is static reference data, and
 * claude.md's hard architectural rule keeps the NLP service off the read
 * path (and, by the same logic, off migration-time too — a migration
 * shouldn't need another service to be up to run).
 *
 * <p>Source file was produced by tokenizing all 9,944 seed words through the
 * real local NLP service (mode C, same as production) and taking the
 * word-token(s)' part_of_speech. ~1,774 entries are multi-morpheme
 * combinations jpdb counts as a frequency unit but Sudachi correctly splits
 * (には → 助詞+助詞, でもない → 助詞+助詞+形容詞) — their part_of_speech is
 * each constituent token's top-level category joined with "+", not a single
 * tag. A couple of symbol-like entries (○, ヶ) resolved to no word token at
 * all and are left null.
 */
public class V8__SeedWordFrequencyPartOfSpeech extends BaseJavaMigration {

    private static final String SEED_RESOURCE = "seed-data/jpdb_v2.2_freq_top10k_pos.tsv";

    private static final String UPDATE_SQL = """
        UPDATE word_frequency SET part_of_speech = ? WHERE term = ? AND reading = ?
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
                    String partOfSpeech = fields[3];
                    if (partOfSpeech.isEmpty()) {
                        continue; // ○, ヶ — no word token resolved, leave column null
                    }
                    statement.setString(1, partOfSpeech);
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

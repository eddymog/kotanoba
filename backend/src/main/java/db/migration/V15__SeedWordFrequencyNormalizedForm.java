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
 * Fills word_frequency.normalized_form (V14) from a one-time local batch —
 * same shape and same reasoning as V8 (part of speech) and V10 (meaning):
 * static reference data, resolved offline through the real local NLP
 * service, no live dependency at migration time.
 *
 * <p>Multi-morpheme entries (には, でもない — see V8's comment) have no
 * single normalized form to resolve; the source TSV falls back to the raw
 * term for those, same as V8's part-of-speech fallback.
 *
 * <p>V16 depends on this data to actually collapse script-variant duplicates
 * (できる/出来る, ある/在る/有る, and 496 others found in the real top 10k —
 * design.md §16) into one row each.
 */
public class V15__SeedWordFrequencyNormalizedForm extends BaseJavaMigration {

    private static final String SEED_RESOURCE = "seed-data/word_frequency_normalized_form.tsv";

    private static final String UPDATE_SQL = """
        UPDATE word_frequency SET normalized_form = ? WHERE term = ? AND reading = ?
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
                    statement.setString(1, fields[3]); // normalized_form
                    statement.setString(2, fields[0]); // term
                    statement.setString(3, fields[1]); // reading
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

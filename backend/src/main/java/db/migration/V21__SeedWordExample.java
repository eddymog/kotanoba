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
 * Fills word_example (V20) from a one-time local batch against Tatoeba's
 * Japanese/English sentence pairs (via manythings.org/anki's pre-filtered
 * jpn-eng set, CC BY 2.0 FR, ~117k pairs) — design.md §18. Same "resolved
 * offline, no runtime dependency" shape as the other seed migrations.
 *
 * <p>Every Japanese sentence was tokenized once through the real local NLP
 * service; for each distinct (normalized_form, reading) it produced, only
 * the shortest sentence containing that word survived into the seed file —
 * the simplest available example for a learner, and the reason this table
 * needs no ranking or many-to-many link table at read time, just one row
 * per word.
 */
public class V21__SeedWordExample extends BaseJavaMigration {

    private static final String SEED_RESOURCE = "seed-data/word_examples.tsv";

    private static final String INSERT_SQL = """
        INSERT INTO word_example (normalized_form, reading, japanese_text, english_text) VALUES (?, ?, ?, ?)
        """;

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        try (InputStream in = getClass().getClassLoader().getResourceAsStream(SEED_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Seed resource not found on classpath: " + SEED_RESOURCE);
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                 PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {

                String line = reader.readLine(); // header row, discarded
                int batched = 0;

                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    String[] fields = line.split("\t", -1);
                    statement.setString(1, fields[0]); // normalized_form
                    statement.setString(2, fields[1]); // reading
                    statement.setString(3, fields[2]); // japanese_text
                    statement.setString(4, fields[3]); // english_text
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

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
 * Fills dictionary_entry (V18) from a one-time local batch against JMdict
 * (jmdict-simplified, "eng-common" JSON — EDRDG, CC BY-SA 4.0) — design.md
 * §18. Same "static reference data, resolved offline, loaded by a plain
 * migration" shape as V5/V8/V10/V15: no runtime dependency on JMdict or any
 * live service.
 *
 * <p>Unlike V10 (which only matched terms already in word_frequency's fixed
 * top-10k list), this covers JMdict's entire "common" set (~22.6k entries,
 * 20,686 resolved) independent of frequency rank — any word encountered in
 * a future import, ranked or not, gets a real match here the moment it's
 * looked up, no re-seeding required (design.md §18).
 *
 * <p>Each JMdict entry was tokenized once through the real local NLP
 * service to learn Sudachi's own normalized_form/reading for it, bridging
 * JMdict's headword convention with Sudachi's (the same bridge V15 built
 * for word_frequency). Entries whose headword didn't tokenize to exactly
 * one word token spanning the whole string (compounds/phrases JMdict counts
 * as one entry but Sudachi would segment further) were skipped rather than
 * guessed at — same reasoning as V8's multi-morpheme fallback.
 */
public class V19__SeedDictionaryEntry extends BaseJavaMigration {

    private static final String SEED_RESOURCE = "seed-data/dictionary_entries.tsv";

    private static final String INSERT_SQL = """
        INSERT INTO dictionary_entry (normalized_form, reading, senses) VALUES (?, ?, ?)
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
                    String[] senses = fields[2].split("\\|");
                    statement.setString(1, fields[0]); // normalized_form
                    statement.setString(2, fields[1]); // reading
                    statement.setArray(3, connection.createArrayOf("text", senses));
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

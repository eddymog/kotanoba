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
 * Loads word_frequency (V4) from the jpdb v2.2 top-10k seed data. A Java
 * migration rather than 9,944 literal INSERT rows in SQL, for the same
 * reason TextTokenBatchWriter uses JDBC batch instead of saveAll() —
 * claude.md's "drop to JDBC when bulk-loading" standard, applied to a
 * one-time load instead of a runtime one. design.md §9b.
 */
public class V5__SeedWordFrequency extends BaseJavaMigration {

    private static final String SEED_RESOURCE = "seed-data/jpdb_v2.2_freq_top10k.tsv";

    private static final String INSERT_SQL = """
        INSERT INTO word_frequency (term, reading, rank)
        VALUES (?, ?, ?)
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
                    statement.setString(1, fields[0]);
                    statement.setString(2, fields[1]);
                    statement.setInt(3, Integer.parseInt(fields[2]));
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

package com.elitale.coldbirds.coldcalling.storage.sqlite;

import com.elitale.coldbirds.coldcalling.storage.repository.ImportBatchRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** SQLite implementation of {@link ImportBatchRepository}. */
public final class SqliteImportBatchRepository implements ImportBatchRepository {

    private final Connection connection;

    public SqliteImportBatchRepository(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
    }

    @Override
    public void record(ImportBatch batch) {
        Objects.requireNonNull(batch, "batch must not be null");
        final String sql = """
            INSERT INTO import_batches
                (id, file_name, default_country, created_count, updated_count,
                 skipped_count, error_count, created_at)
            VALUES (?,?,?,?,?,?,?,?)
            """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, batch.id());
            stmt.setString(2, batch.fileName());
            stmt.setString(3, batch.defaultCountry().orElse(null));
            stmt.setInt(4, batch.created());
            stmt.setInt(5, batch.updated());
            stmt.setInt(6, batch.skipped());
            stmt.setInt(7, batch.errors());
            stmt.setLong(8, batch.createdAt().toEpochMilli());
            stmt.executeUpdate();
        } catch (SQLException ignored) {
            // summary persistence is best-effort; the import itself already committed
        }
    }

    @Override
    public int undo(String batchId) {
        Objects.requireNonNull(batchId, "batchId must not be null");
        final long now = Instant.now().toEpochMilli();
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement detach = connection.prepareStatement(
                    "DELETE FROM call_list_leads WHERE lead_id IN "
                    + "(SELECT id FROM leads WHERE import_batch_id=? AND deleted_at IS NULL)")) {
                detach.setString(1, batchId);
                detach.executeUpdate();
            }
            final int removed;
            try (PreparedStatement del = connection.prepareStatement(
                    "UPDATE leads SET deleted_at=?, updated_at=? "
                    + "WHERE import_batch_id=? AND deleted_at IS NULL")) {
                del.setLong(1, now);
                del.setLong(2, now);
                del.setString(3, batchId);
                removed = del.executeUpdate();
            }
            connection.commit();
            return removed;
        } catch (SQLException e) {
            rollbackQuietly();
            return 0;
        } finally {
            restoreAutoCommit();
        }
    }

    @Override
    public List<ImportBatch> recentBatches(int limit) {
        final String sql = "SELECT * FROM import_batches ORDER BY created_at DESC LIMIT ?";
        final List<ImportBatch> out = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, Math.max(0, limit));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    out.add(map(rs));
                }
            }
        } catch (SQLException ignored) {
            return List.of();
        }
        return List.copyOf(out);
    }

    private static ImportBatch map(ResultSet rs) throws SQLException {
        return new ImportBatch(
                rs.getString("id"),
                rs.getString("file_name"),
                Optional.ofNullable(rs.getString("default_country")),
                rs.getInt("created_count"),
                rs.getInt("updated_count"),
                rs.getInt("skipped_count"),
                rs.getInt("error_count"),
                Instant.ofEpochMilli(rs.getLong("created_at")));
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // best effort
        }
    }

    private void restoreAutoCommit() {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
            // best effort
        }
    }
}

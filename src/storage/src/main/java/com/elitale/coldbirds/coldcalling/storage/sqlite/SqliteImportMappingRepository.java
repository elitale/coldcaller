package com.elitale.coldbirds.coldcalling.storage.sqlite;

import com.elitale.coldbirds.coldcalling.storage.repository.ImportMappingRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** SQLite implementation of {@link ImportMappingRepository}. */
public final class SqliteImportMappingRepository implements ImportMappingRepository {

    private final Connection connection;

    public SqliteImportMappingRepository(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
    }

    @Override
    public ImportMapping save(ImportMapping mapping) {
        Objects.requireNonNull(mapping, "mapping must not be null");
        return findByHeaderSignature(mapping.headerSignature())
                .map(existing -> update(existing.id().orElseThrow(), mapping))
                .orElseGet(() -> insert(mapping));
    }

    private ImportMapping insert(ImportMapping m) {
        final String sql = """
            INSERT INTO import_mappings
                (name, header_signature, mapping_json, default_country,
                 target_list_id, created_at, updated_at)
            VALUES (?,?,?,?,?,?,?)
            """;
        final long now = Instant.now().toEpochMilli();
        try (PreparedStatement stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, m.name());
            stmt.setString(2, m.headerSignature());
            stmt.setString(3, m.mappingJson());
            stmt.setString(4, m.defaultCountry().orElse(null));
            setNullableLong(stmt, 5, m.targetListId());
            stmt.setLong(6, now);
            stmt.setLong(7, now);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                final long id = keys.next() ? keys.getLong(1) : 0L;
                return stored(id, m, now, now);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save import mapping: " + e.getMessage(), e);
        }
    }

    private ImportMapping update(long id, ImportMapping m) {
        final String sql = """
            UPDATE import_mappings
               SET name=?, mapping_json=?, default_country=?, target_list_id=?, updated_at=?
             WHERE id=?
            """;
        final long now = Instant.now().toEpochMilli();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, m.name());
            stmt.setString(2, m.mappingJson());
            stmt.setString(3, m.defaultCountry().orElse(null));
            setNullableLong(stmt, 4, m.targetListId());
            stmt.setLong(5, now);
            stmt.setLong(6, id);
            stmt.executeUpdate();
            return stored(id, m, m.createdAt().toEpochMilli(), now);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update import mapping: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<ImportMapping> findByHeaderSignature(String signature) {
        Objects.requireNonNull(signature, "signature must not be null");
        final String sql = "SELECT * FROM import_mappings WHERE header_signature=? LIMIT 1";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, signature);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<ImportMapping> findAll() {
        final String sql = "SELECT * FROM import_mappings ORDER BY updated_at DESC";
        final List<ImportMapping> out = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                out.add(map(rs));
            }
        } catch (SQLException ignored) {
            return List.of();
        }
        return List.copyOf(out);
    }

    @Override
    public void delete(long id) {
        try (PreparedStatement stmt = connection.prepareStatement("DELETE FROM import_mappings WHERE id=?")) {
            stmt.setLong(1, id);
            stmt.executeUpdate();
        } catch (SQLException ignored) {
            // best effort
        }
    }

    private static void setNullableLong(PreparedStatement stmt, int idx, Optional<Long> value)
            throws SQLException {
        if (value.isPresent()) {
            stmt.setLong(idx, value.get());
        } else {
            stmt.setNull(idx, java.sql.Types.INTEGER);
        }
    }

    private static ImportMapping stored(long id, ImportMapping m, long createdAt, long updatedAt) {
        return new ImportMapping(
                Optional.of(id), m.name(), m.headerSignature(), m.mappingJson(),
                m.defaultCountry(), m.targetListId(),
                Instant.ofEpochMilli(createdAt), Instant.ofEpochMilli(updatedAt));
    }

    private static ImportMapping map(ResultSet rs) throws SQLException {
        final long targetListId = rs.getLong("target_list_id");
        final Optional<Long> target = rs.wasNull() ? Optional.empty() : Optional.of(targetListId);
        return new ImportMapping(
                Optional.of(rs.getLong("id")),
                rs.getString("name"),
                rs.getString("header_signature"),
                rs.getString("mapping_json"),
                Optional.ofNullable(rs.getString("default_country")),
                target,
                Instant.ofEpochMilli(rs.getLong("created_at")),
                Instant.ofEpochMilli(rs.getLong("updated_at")));
    }
}

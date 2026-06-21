package com.elitale.coldbirds.coldcalling.storage.sqlite;

import com.elitale.coldbirds.coldcalling.storage.repository.SettingsRepository;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class SqliteSettingsRepository implements SettingsRepository {

    private final Connection connection;

    public SqliteSettingsRepository(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
    }

    @Override
    public Optional<String> get(String key) {
        Objects.requireNonNull(key, "key must not be null");
        String sql = "SELECT value FROM settings WHERE key=?";
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, key);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString("value")) : Optional.empty();
            }
        } catch (SQLException e) {
            return Optional.empty();
        }
    }

    @Override
    public void set(String key, String value) {
        Objects.requireNonNull(key,   "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
        String sql = """
            INSERT INTO settings (key, value, updated_at) VALUES (?,?,?)
             ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at
            """;
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, key);
            stmt.setString(2, value);
            stmt.setLong(3, Instant.now().toEpochMilli());
            stmt.executeUpdate();
        } catch (SQLException ignored) {}
    }

    @Override
    public Map<String, String> getAll() {
        Map<String, String> result = new HashMap<>();
        String sql = "SELECT key, value FROM settings";
        try (var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) result.put(rs.getString("key"), rs.getString("value"));
        } catch (SQLException ignored) {}
        return Map.copyOf(result);
    }

    @Override
    public void delete(String key) {
        Objects.requireNonNull(key, "key must not be null");
        String sql = "DELETE FROM settings WHERE key=?";
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, key);
            stmt.executeUpdate();
        } catch (SQLException ignored) {}
    }
}

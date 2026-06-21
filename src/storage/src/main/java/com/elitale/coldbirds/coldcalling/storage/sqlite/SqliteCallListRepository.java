package com.elitale.coldbirds.coldcalling.storage.sqlite;

import com.elitale.coldbirds.coldcalling.domain.model.CallList;
import com.elitale.coldbirds.coldcalling.domain.model.CallListEntry;
import com.elitale.coldbirds.coldcalling.domain.value.CallListId;
import com.elitale.coldbirds.coldcalling.domain.value.ContactId;
import com.elitale.coldbirds.coldcalling.domain.value.Result;
import com.elitale.coldbirds.coldcalling.storage.repository.CallListRepository;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class SqliteCallListRepository implements CallListRepository {

    private final Connection connection;

    public SqliteCallListRepository(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
    }

    @Override
    public Result<CallList> save(NewCallList n) {
        String sql = """
            INSERT INTO call_lists (name, description, created_at, updated_at)
            VALUES (?,?,?,?)
            """;
        long now = Instant.now().toEpochMilli();
        try (var stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, n.name());
            stmt.setString(2, n.description().orElse(null));
            stmt.setLong(3, now);
            stmt.setLong(4, now);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (keys.next()) {
                    return findById(new CallListId(keys.getLong(1)))
                            .map(Result::ok)
                            .orElse(Result.err("CallList not found after insert"));
                }
                return Result.err("No generated key returned");
            }
        } catch (SQLException e) {
            return Result.err("Failed to save call list: " + e.getMessage(), e);
        }
    }

    @Override
    public Result<CallList> update(CallList l) {
        String sql = """
            UPDATE call_lists SET name=?, description=?, updated_at=?
             WHERE id=? AND deleted_at IS NULL
            """;
        long now = Instant.now().toEpochMilli();
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, l.name());
            stmt.setString(2, l.description().orElse(null));
            stmt.setLong(3, now);
            stmt.setLong(4, l.id().value());
            int rows = stmt.executeUpdate();
            if (rows == 0) return Result.err("CallList not found or deleted: " + l.id().value());
            return findById(l.id())
                    .map(Result::ok)
                    .orElse(Result.err("CallList not found after update"));
        } catch (SQLException e) {
            return Result.err("Failed to update call list: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<CallList> findById(CallListId id) {
        String listSql = "SELECT * FROM call_lists WHERE id=? AND deleted_at IS NULL";
        try (var stmt = connection.prepareStatement(listSql)) {
            stmt.setLong(1, id.value());
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapWithEntries(rs));
            }
        } catch (SQLException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<CallList> findAll() {
        String sql = "SELECT * FROM call_lists WHERE deleted_at IS NULL ORDER BY name";
        List<CallList> result = new ArrayList<>();
        try (var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) result.add(mapWithEntries(rs));
        } catch (SQLException ignored) {}
        return List.copyOf(result);
    }

    @Override
    public Result<Void> delete(CallListId id) {
        String sql = "UPDATE call_lists SET deleted_at=? WHERE id=? AND deleted_at IS NULL";
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, Instant.now().toEpochMilli());
            stmt.setLong(2, id.value());
            int rows = stmt.executeUpdate();
            if (rows == 0) return Result.err("CallList not found or already deleted: " + id.value());
            return Result.ok(null);
        } catch (SQLException e) {
            return Result.err("Failed to delete call list: " + e.getMessage(), e);
        }
    }

    @Override
    public Result<Void> addEntry(CallListId listId, ContactId contactId) {
        String sql = """
            INSERT INTO call_list_contacts (list_id, contact_id, position, status, created_at, updated_at)
            VALUES (?, ?,
                (SELECT COALESCE(MAX(position), -1) + 1 FROM call_list_contacts WHERE list_id=?),
                'pending', ?, ?)
            """;
        long now = Instant.now().toEpochMilli();
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, listId.value());
            stmt.setLong(2, contactId.value());
            stmt.setLong(3, listId.value());
            stmt.setLong(4, now);
            stmt.setLong(5, now);
            stmt.executeUpdate();
            return Result.ok(null);
        } catch (SQLException e) {
            return Result.err("Failed to add entry: " + e.getMessage(), e);
        }
    }

    @Override
    public Result<Void> updateEntryStatus(long entryId, CallListEntry.DialStatus status) {
        String sql = "UPDATE call_list_contacts SET status=?, updated_at=? WHERE id=?";
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, status.name().toLowerCase());
            stmt.setLong(2, Instant.now().toEpochMilli());
            stmt.setLong(3, entryId);
            stmt.executeUpdate();
            return Result.ok(null);
        } catch (SQLException e) {
            return Result.err("Failed to update entry status: " + e.getMessage(), e);
        }
    }

    private CallList mapWithEntries(ResultSet rs) throws SQLException {
        long listId = rs.getLong("id");
        List<CallListEntry> entries = loadEntries(listId);
        return new CallList(
                new CallListId(listId),
                rs.getString("name"),
                Optional.ofNullable(rs.getString("description")),
                entries,
                Instant.ofEpochMilli(rs.getLong("created_at")),
                Instant.ofEpochMilli(rs.getLong("updated_at"))
        );
    }

    private List<CallListEntry> loadEntries(long listId) {
        String sql = "SELECT * FROM call_list_contacts WHERE list_id=? ORDER BY position";
        List<CallListEntry> entries = new ArrayList<>();
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, listId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    entries.add(new CallListEntry(
                            rs.getLong("id"),
                            new ContactId(rs.getLong("contact_id")),
                            rs.getInt("position"),
                            CallListEntry.DialStatus.valueOf(rs.getString("status").toUpperCase())
                    ));
                }
            }
        } catch (SQLException ignored) {}
        return entries;
    }
}

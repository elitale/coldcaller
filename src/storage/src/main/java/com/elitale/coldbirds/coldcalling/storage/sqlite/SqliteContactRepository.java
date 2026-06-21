package com.elitale.coldbirds.coldcalling.storage.sqlite;

import com.elitale.coldbirds.coldcalling.domain.model.Contact;
import com.elitale.coldbirds.coldcalling.domain.value.ContactId;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.Result;
import com.elitale.coldbirds.coldcalling.storage.repository.ContactRepository;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class SqliteContactRepository implements ContactRepository {

    private final Connection connection;

    public SqliteContactRepository(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
    }

    @Override
    public Result<Contact> save(NewContact c) {
        String sql = """
            INSERT INTO contacts
                (first_name, last_name, phone, company, title, email, tags, notes,
                 dnc, created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,0,?,?)
            """;
        long now = Instant.now().toEpochMilli();
        try (var stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, c.firstName().orElse(null));
            stmt.setString(2, c.lastName().orElse(null));
            stmt.setString(3, c.phone().value());
            stmt.setString(4, c.company().orElse(null));
            stmt.setString(5, c.title().orElse(null));
            stmt.setString(6, c.email().orElse(null));
            stmt.setString(7, DomainMappers.tagsToJson(c.tags()));
            stmt.setString(8, c.notes().orElse(null));
            stmt.setLong(9, now);
            stmt.setLong(10, now);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (keys.next()) {
                    return findById(new ContactId(keys.getLong(1)))
                            .map(Result::ok)
                            .orElse(Result.err("Contact not found after insert"));
                }
                return Result.err("No generated key returned");
            }
        } catch (SQLException e) {
            return Result.err("Failed to save contact: " + e.getMessage(), e);
        }
    }

    @Override
    public Result<Contact> update(Contact c) {
        String sql = """
            UPDATE contacts
               SET first_name=?, last_name=?, phone=?, company=?, title=?, email=?,
                   tags=?, notes=?, dnc=?, updated_at=?
             WHERE id=? AND deleted_at IS NULL
            """;
        long now = Instant.now().toEpochMilli();
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, c.firstName().orElse(null));
            stmt.setString(2, c.lastName().orElse(null));
            stmt.setString(3, c.phone().value());
            stmt.setString(4, c.company().orElse(null));
            stmt.setString(5, c.title().orElse(null));
            stmt.setString(6, c.email().orElse(null));
            stmt.setString(7, DomainMappers.tagsToJson(c.tags()));
            stmt.setString(8, c.notes().orElse(null));
            stmt.setInt(9, c.dnc() ? 1 : 0);
            stmt.setLong(10, now);
            stmt.setLong(11, c.id().value());
            int rows = stmt.executeUpdate();
            if (rows == 0) return Result.err("Contact not found or deleted: " + c.id().value());
            return findById(c.id())
                    .map(Result::ok)
                    .orElse(Result.err("Contact not found after update"));
        } catch (SQLException e) {
            return Result.err("Failed to update contact: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<Contact> findById(ContactId id) {
        String sql = "SELECT * FROM contacts WHERE id=? AND deleted_at IS NULL";
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, id.value());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<Contact> findByPhone(PhoneNumber phone) {
        String sql = "SELECT * FROM contacts WHERE phone=? AND deleted_at IS NULL LIMIT 1";
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, phone.value());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<Contact> findAll() {
        return query("SELECT * FROM contacts WHERE deleted_at IS NULL ORDER BY last_name, first_name");
    }

    @Override
    public List<Contact> search(String query) {
        String like = "%" + query.replace("%", "\\%").replace("_", "\\_") + "%";
        String sql = """
            SELECT * FROM contacts
             WHERE deleted_at IS NULL
               AND (first_name LIKE ? OR last_name LIKE ? OR company LIKE ? OR phone LIKE ?)
             ORDER BY last_name, first_name
            """;
        List<Contact> result = new ArrayList<>();
        try (var stmt = connection.prepareStatement(sql)) {
            for (int i = 1; i <= 4; i++) stmt.setString(i, like);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) result.add(map(rs));
            }
        } catch (SQLException ignored) {}
        return List.copyOf(result);
    }

    @Override
    public Result<Void> delete(ContactId id) {
        String sql = "UPDATE contacts SET deleted_at=? WHERE id=? AND deleted_at IS NULL";
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, Instant.now().toEpochMilli());
            stmt.setLong(2, id.value());
            int rows = stmt.executeUpdate();
            if (rows == 0) return Result.err("Contact not found or already deleted: " + id.value());
            return Result.ok(null);
        } catch (SQLException e) {
            return Result.err("Failed to delete contact: " + e.getMessage(), e);
        }
    }

    private List<Contact> query(String sql) {
        List<Contact> result = new ArrayList<>();
        try (var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) result.add(map(rs));
        } catch (SQLException ignored) {}
        return List.copyOf(result);
    }

    private static Contact map(ResultSet rs) throws SQLException {
        return new Contact(
                new ContactId(rs.getLong("id")),
                Optional.ofNullable(rs.getString("first_name")),
                Optional.ofNullable(rs.getString("last_name")),
                new PhoneNumber(rs.getString("phone")),
                Optional.ofNullable(rs.getString("company")),
                Optional.ofNullable(rs.getString("title")),
                Optional.ofNullable(rs.getString("email")),
                DomainMappers.jsonToTags(rs.getString("tags")),
                Optional.ofNullable(rs.getString("notes")),
                rs.getInt("dnc") == 1,
                Instant.ofEpochMilli(rs.getLong("created_at")),
                Instant.ofEpochMilli(rs.getLong("updated_at"))
        );
    }
}

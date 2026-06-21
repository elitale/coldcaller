package com.elitale.coldbirds.coldcalling.storage.sqlite;

import com.elitale.coldbirds.coldcalling.domain.model.OwnedNumber;
import com.elitale.coldbirds.coldcalling.domain.value.AreaCode;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumberId;
import com.elitale.coldbirds.coldcalling.domain.value.Result;
import com.elitale.coldbirds.coldcalling.storage.repository.PhoneNumberRepository;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class SqlitePhoneNumberRepository implements PhoneNumberRepository {

    private final Connection connection;

    public SqlitePhoneNumberRepository(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
    }

    @Override
    public Result<OwnedNumber> save(NewOwnedNumber n) {
        String sql = """
            INSERT INTO phone_numbers
                (number, friendly_name, area_code, provider, reputation, daily_calls, active,
                 created_at, updated_at)
            VALUES (?,?,?,?,?,0,1,?,?)
            """;
        long now = Instant.now().toEpochMilli();
        try (var stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, n.number().value());
            stmt.setString(2, n.friendlyName().orElse(null));
            stmt.setString(3, n.areaCode().value());
            stmt.setString(4, n.provider());
            stmt.setString(5, DomainMappers.reputationToString(n.reputation()));
            stmt.setLong(6, now);
            stmt.setLong(7, now);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (keys.next()) {
                    long id = keys.getLong(1);
                    return findById(new PhoneNumberId(id))
                            .map(Result::ok)
                            .orElse(Result.err("Phone number not found after insert"));
                }
                return Result.err("No generated key returned");
            }
        } catch (SQLException e) {
            return Result.err("Failed to save phone number: " + e.getMessage(), e);
        }
    }

    @Override
    public Result<OwnedNumber> update(OwnedNumber n) {
        String sql = """
            UPDATE phone_numbers
               SET friendly_name=?, reputation=?, daily_calls=?, active=?, updated_at=?
             WHERE id=?
            """;
        long now = Instant.now().toEpochMilli();
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, n.friendlyName().orElse(null));
            stmt.setString(2, DomainMappers.reputationToString(n.reputation()));
            stmt.setInt(3, n.dailyCalls());
            stmt.setInt(4, n.active() ? 1 : 0);
            stmt.setLong(5, now);
            stmt.setLong(6, n.id().value());
            int rows = stmt.executeUpdate();
            if (rows == 0) return Result.err("Phone number not found: " + n.id().value());
            return findById(n.id())
                    .map(Result::ok)
                    .orElse(Result.err("Phone number not found after update"));
        } catch (SQLException e) {
            return Result.err("Failed to update phone number: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<OwnedNumber> findById(PhoneNumberId id) {
        String sql = "SELECT * FROM phone_numbers WHERE id=?";
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
    public Optional<OwnedNumber> findByNumber(PhoneNumber number) {
        String sql = "SELECT * FROM phone_numbers WHERE number=?";
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, number.value());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<OwnedNumber> findAll() {
        return query("SELECT * FROM phone_numbers ORDER BY created_at DESC");
    }

    @Override
    public List<OwnedNumber> findAllActive() {
        return query("SELECT * FROM phone_numbers WHERE active=1 ORDER BY created_at DESC");
    }

    @Override
    public Result<Void> delete(PhoneNumberId id) {
        String sql = "DELETE FROM phone_numbers WHERE id=?";
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, id.value());
            stmt.executeUpdate();
            return Result.ok(null);
        } catch (SQLException e) {
            return Result.err("Failed to delete phone number: " + e.getMessage(), e);
        }
    }

    private List<OwnedNumber> query(String sql) {
        List<OwnedNumber> result = new ArrayList<>();
        try (var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) result.add(map(rs));
        } catch (SQLException ignored) {}
        return List.copyOf(result);
    }

    private static OwnedNumber map(ResultSet rs) throws SQLException {
        long reputationUpdated = rs.getLong("updated_at");
        return new OwnedNumber(
                new PhoneNumberId(rs.getLong("id")),
                new PhoneNumber(rs.getString("number")),
                Optional.ofNullable(rs.getString("friendly_name")),
                new AreaCode(rs.getString("area_code")),
                rs.getString("provider"),
                DomainMappers.reputationFromString(rs.getString("reputation")),
                rs.getInt("daily_calls"),
                rs.getInt("active") == 1,
                Instant.ofEpochMilli(rs.getLong("created_at")),
                Instant.ofEpochMilli(reputationUpdated)
        );
    }
}

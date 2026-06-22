package com.elitale.coldbirds.coldcalling.storage.sqlite;

import com.elitale.coldbirds.coldcalling.domain.model.SmsMessage;
import com.elitale.coldbirds.coldcalling.domain.value.CallDirection;
import com.elitale.coldbirds.coldcalling.domain.value.ContactId;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumberId;
import com.elitale.coldbirds.coldcalling.domain.value.Result;
import com.elitale.coldbirds.coldcalling.domain.value.SmsId;
import com.elitale.coldbirds.coldcalling.storage.repository.SmsRepository;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class SqliteSmsRepository implements SmsRepository {

    private final Connection connection;

    public SqliteSmsRepository(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
    }

    @Override
    public Result<SmsMessage> save(NewSmsMessage s) {
        String sql = """
            INSERT INTO sms_messages
                (direction, phone_number_id, contact_id, remote_number, body, status,
                 sent_at, created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?)
            """;
        long now = Instant.now().toEpochMilli();
        try (var stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, s.direction().name().toLowerCase());
            stmt.setLong(2, s.phoneNumberId().value());
            if (s.contactId().isPresent()) stmt.setLong(3, s.contactId().get().value());
            else stmt.setNull(3, java.sql.Types.INTEGER);
            stmt.setString(4, s.remoteNumber().value());
            stmt.setString(5, s.body());
            stmt.setString(6, DomainMappers.smsStatusToString(s.status()));
            stmt.setLong(7, s.sentAt().toEpochMilli());
            stmt.setLong(8, now);
            stmt.setLong(9, now);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (keys.next()) {
                    return findById(new SmsId(keys.getLong(1)))
                            .map(Result::ok)
                            .orElse(Result.err("SMS not found after insert"));
                }
                return Result.err("No generated key returned");
            }
        } catch (SQLException e) {
            return Result.err("Failed to save SMS: " + e.getMessage(), e);
        }
    }

    @Override
    public Result<SmsMessage> update(SmsMessage s) {
        String sql = "UPDATE sms_messages SET status=?, updated_at=? WHERE id=?";
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, DomainMappers.smsStatusToString(s.status()));
            stmt.setLong(2, Instant.now().toEpochMilli());
            stmt.setLong(3, s.id().value());
            int rows = stmt.executeUpdate();
            if (rows == 0) return Result.err("SMS not found: " + s.id().value());
            return findById(s.id())
                    .map(Result::ok)
                    .orElse(Result.err("SMS not found after update"));
        } catch (SQLException e) {
            return Result.err("Failed to update SMS: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<SmsMessage> findById(SmsId id) {
        String sql = "SELECT * FROM sms_messages WHERE id=?";
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
    public List<SmsMessage> findByContact(ContactId contactId) {
        return queryParam("SELECT * FROM sms_messages WHERE contact_id=? ORDER BY sent_at DESC",
                contactId.value());
    }

    @Override
    public List<SmsMessage> findByRemoteNumber(PhoneNumber remoteNumber) {
        String sql = "SELECT * FROM sms_messages WHERE remote_number=? ORDER BY sent_at DESC";
        List<SmsMessage> result = new ArrayList<>();
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, remoteNumber.value());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) result.add(map(rs));
            }
        } catch (SQLException ignored) {}
        return List.copyOf(result);
    }

    @Override
    public List<SmsMessage> findRecent(int limit) {
        String sql = "SELECT * FROM sms_messages ORDER BY sent_at DESC LIMIT " + limit;
        List<SmsMessage> result = new ArrayList<>();
        try (var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) result.add(map(rs));
        } catch (SQLException ignored) {}
        return List.copyOf(result);
    }

    private List<SmsMessage> queryParam(String sql, long param) {
        List<SmsMessage> result = new ArrayList<>();
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, param);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) result.add(map(rs));
            }
        } catch (SQLException ignored) {}
        return List.copyOf(result);
    }

    private static SmsMessage map(ResultSet rs) throws SQLException {
        long contactIdRaw = rs.getLong("contact_id");
        boolean contactIsNull = rs.wasNull();
        return new SmsMessage(
                new SmsId(rs.getLong("id")),
                CallDirection.valueOf(rs.getString("direction").toUpperCase()),
                new PhoneNumberId(rs.getLong("phone_number_id")),
                contactIsNull ? Optional.empty() : Optional.of(new ContactId(contactIdRaw)),
                new PhoneNumber(rs.getString("remote_number")),
                rs.getString("body"),
                DomainMappers.smsStatusFromString(rs.getString("status")),
                Instant.ofEpochMilli(rs.getLong("sent_at")),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                Instant.ofEpochMilli(rs.getLong("updated_at"))
        );
    }
}

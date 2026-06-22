package com.elitale.coldbirds.coldcalling.storage.sqlite;

import com.elitale.coldbirds.coldcalling.domain.model.Call;
import com.elitale.coldbirds.coldcalling.domain.value.CallDirection;
import com.elitale.coldbirds.coldcalling.domain.value.CallDisposition;
import com.elitale.coldbirds.coldcalling.domain.value.CallId;
import com.elitale.coldbirds.coldcalling.domain.value.ContactId;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumberId;
import com.elitale.coldbirds.coldcalling.domain.value.Result;
import com.elitale.coldbirds.coldcalling.storage.repository.CallRepository;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class SqliteCallRepository implements CallRepository {

    private final Connection connection;

    public SqliteCallRepository(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
    }

    @Override
    public Result<Call> save(NewCall c) {
        String sql = """
            INSERT INTO calls
                (direction, phone_number_id, contact_id, remote_number, status, disposition,
                 started_at, answered_at, ended_at, duration_ms, recording_path, notes,
                 created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;
        long now = Instant.now().toEpochMilli();
        try (var stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, c.direction().name().toLowerCase());
            stmt.setLong(2, c.phoneNumberId().value());
            if (c.contactId().isPresent()) stmt.setLong(3, c.contactId().get().value());
            else stmt.setNull(3, java.sql.Types.INTEGER);
            stmt.setString(4, c.remoteNumber().value());
            stmt.setString(5, deriveStatus(c));
            if (c.disposition().isPresent())
                stmt.setString(6, DomainMappers.dispositionToString(c.disposition().get()));
            else stmt.setNull(6, java.sql.Types.VARCHAR);
            stmt.setLong(7, c.startedAt().toEpochMilli());
            if (c.answeredAt().isPresent()) stmt.setLong(8, c.answeredAt().get().toEpochMilli());
            else stmt.setNull(8, java.sql.Types.INTEGER);
            if (c.endedAt().isPresent()) stmt.setLong(9, c.endedAt().get().toEpochMilli());
            else stmt.setNull(9, java.sql.Types.INTEGER);
            if (c.durationMs().isPresent()) stmt.setLong(10, c.durationMs().get());
            else stmt.setNull(10, java.sql.Types.INTEGER);
            stmt.setString(11, c.recordingPath().orElse(null));
            stmt.setString(12, c.notes().orElse(null));
            stmt.setLong(13, now);
            stmt.setLong(14, now);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (keys.next()) {
                    return findById(new CallId(keys.getLong(1)))
                            .map(Result::ok)
                            .orElse(Result.err("Call not found after insert"));
                }
                return Result.err("No generated key returned");
            }
        } catch (SQLException e) {
            return Result.err("Failed to save call: " + e.getMessage(), e);
        }
    }

    @Override
    public Result<Call> update(Call c) {
        String sql = """
            UPDATE calls
               SET disposition=?, answered_at=?, ended_at=?, duration_ms=?,
                   recording_path=?, notes=?, status=?, updated_at=?
             WHERE id=?
            """;
        long now = Instant.now().toEpochMilli();
        try (var stmt = connection.prepareStatement(sql)) {
            if (c.disposition().isPresent())
                stmt.setString(1, DomainMappers.dispositionToString(c.disposition().get()));
            else stmt.setNull(1, java.sql.Types.VARCHAR);
            if (c.answeredAt().isPresent()) stmt.setLong(2, c.answeredAt().get().toEpochMilli());
            else stmt.setNull(2, java.sql.Types.INTEGER);
            if (c.endedAt().isPresent()) stmt.setLong(3, c.endedAt().get().toEpochMilli());
            else stmt.setNull(3, java.sql.Types.INTEGER);
            if (c.durationMs().isPresent()) stmt.setLong(4, c.durationMs().get());
            else stmt.setNull(4, java.sql.Types.INTEGER);
            stmt.setString(5, c.recordingPath().orElse(null));
            stmt.setString(6, c.notes().orElse(null));
            stmt.setString(7, deriveStatusFromCall(c));
            stmt.setLong(8, now);
            stmt.setLong(9, c.id().value());
            int rows = stmt.executeUpdate();
            if (rows == 0) return Result.err("Call not found: " + c.id().value());
            return findById(c.id())
                    .map(Result::ok)
                    .orElse(Result.err("Call not found after update"));
        } catch (SQLException e) {
            return Result.err("Failed to update call: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<Call> findById(CallId id) {
        String sql = "SELECT * FROM calls WHERE id=?";
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
    public List<Call> findByContact(ContactId contactId) {
        String sql = "SELECT * FROM calls WHERE contact_id=? ORDER BY started_at DESC";
        return queryParam(sql, contactId.value());
    }

    @Override
    public List<Call> findByPhoneNumber(PhoneNumberId phoneNumberId) {
        String sql = "SELECT * FROM calls WHERE phone_number_id=? ORDER BY started_at DESC";
        return queryParam(sql, phoneNumberId.value());
    }

    @Override
    public List<Call> findByRemoteNumber(PhoneNumber remoteNumber) {
        String sql = "SELECT * FROM calls WHERE remote_number=? ORDER BY started_at DESC";
        List<Call> result = new ArrayList<>();
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, remoteNumber.value());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) result.add(map(rs));
            }
        } catch (SQLException ignored) {}
        return List.copyOf(result);
    }

    @Override
    public List<Call> findRecent(int limit) {
        String sql = "SELECT * FROM calls ORDER BY started_at DESC LIMIT " + limit;
        List<Call> result = new ArrayList<>();
        try (var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) result.add(map(rs));
        } catch (SQLException ignored) {}
        return List.copyOf(result);
    }

    private List<Call> queryParam(String sql, long param) {
        List<Call> result = new ArrayList<>();
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, param);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) result.add(map(rs));
            }
        } catch (SQLException ignored) {}
        return List.copyOf(result);
    }

    private static String deriveStatus(NewCall c) {
        if (c.disposition().isPresent() && c.disposition().get() instanceof CallDisposition.Failed)
            return "failed";
        if (c.durationMs().isPresent()) return "ended";
        if (c.answeredAt().isPresent()) return "active";
        return "missed";
    }

    private static String deriveStatusFromCall(Call c) {
        if (c.disposition().isPresent() && c.disposition().get() instanceof CallDisposition.Failed)
            return "failed";
        if (c.durationMs().isPresent()) return "ended";
        if (c.answeredAt().isPresent()) return "active";
        return "missed";
    }

    private static Call map(ResultSet rs) throws SQLException {
        long contactIdRaw = rs.getLong("contact_id");
        String dispositionStr = rs.getString("disposition");
        long answeredAtRaw = rs.getLong("answered_at");
        long endedAtRaw = rs.getLong("ended_at");
        long durationMsRaw = rs.getLong("duration_ms");
        return new Call(
                new CallId(rs.getLong("id")),
                CallDirection.valueOf(rs.getString("direction").toUpperCase()),
                new PhoneNumberId(rs.getLong("phone_number_id")),
                rs.wasNull() ? Optional.empty() : Optional.of(new ContactId(contactIdRaw)),
                new PhoneNumber(rs.getString("remote_number")),
                dispositionStr != null
                        ? Optional.of(DomainMappers.dispositionFromString(dispositionStr))
                        : Optional.empty(),
                Instant.ofEpochMilli(rs.getLong("started_at")),
                answeredAtRaw == 0 && rs.wasNull() ? Optional.empty()
                        : Optional.of(Instant.ofEpochMilli(answeredAtRaw)),
                endedAtRaw == 0 && rs.wasNull() ? Optional.empty()
                        : Optional.of(Instant.ofEpochMilli(endedAtRaw)),
                durationMsRaw == 0 && rs.wasNull() ? Optional.empty()
                        : Optional.of(durationMsRaw),
                Optional.ofNullable(rs.getString("recording_path")),
                Optional.ofNullable(rs.getString("notes")),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                Instant.ofEpochMilli(rs.getLong("updated_at"))
        );
    }
}

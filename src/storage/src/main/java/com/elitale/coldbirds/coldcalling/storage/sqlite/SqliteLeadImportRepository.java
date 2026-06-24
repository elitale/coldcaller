package com.elitale.coldbirds.coldcalling.storage.sqlite;

import com.elitale.coldbirds.coldcalling.storage.repository.LeadImportRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** SQLite implementation of the CSV import write-path. */
public final class SqliteLeadImportRepository implements LeadImportRepository {

    private static final String UPSERT = """
        INSERT INTO leads
            (first_name, last_name, phone, company, title, email, tags,
             custom_fields, lead_status, dnc, import_batch_id, created_at, updated_at)
        VALUES (?,?,?,?,?,?,?,?,?,0,?,?,?)
        ON CONFLICT(phone) WHERE deleted_at IS NULL DO UPDATE SET
            first_name    = COALESCE(excluded.first_name,    leads.first_name),
            last_name     = COALESCE(excluded.last_name,     leads.last_name),
            company       = COALESCE(excluded.company,       leads.company),
            title         = COALESCE(excluded.title,         leads.title),
            email         = COALESCE(excluded.email,         leads.email),
            custom_fields = COALESCE(excluded.custom_fields, leads.custom_fields),
            updated_at    = excluded.updated_at
        """;

    private final Connection connection;

    public SqliteLeadImportRepository(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
    }

    @Override
    public UpsertResult bulkUpsert(List<UpsertRow> rows, String importBatchId) {
        Objects.requireNonNull(rows, "rows must not be null");
        Objects.requireNonNull(importBatchId, "importBatchId must not be null");
        if (rows.isEmpty()) {
            return new UpsertResult(0, 0);
        }

        final Set<String> phones = new LinkedHashSet<>();
        for (UpsertRow row : rows) {
            phones.add(row.phone().value());
        }
        final Set<String> existingLive = livePhones(phones);

        int created = 0;
        int updated = 0;
        final long now = Instant.now().toEpochMilli();
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement stmt = connection.prepareStatement(UPSERT)) {
                for (UpsertRow row : rows) {
                    final boolean exists = existingLive.contains(row.phone().value());
                    stmt.setString(1, row.firstName().orElse(null));
                    stmt.setString(2, row.lastName().orElse(null));
                    stmt.setString(3, row.phone().value());
                    stmt.setString(4, row.company().orElse(null));
                    stmt.setString(5, row.title().orElse(null));
                    stmt.setString(6, row.email().orElse(null));
                    stmt.setString(7, DomainMappers.tagsToJson(row.tags()));
                    stmt.setString(8, DomainMappers.customFieldsToJson(row.customFields()));
                    stmt.setString(9, row.leadStatus().dbValue());
                    stmt.setString(10, importBatchId);
                    stmt.setLong(11, now);
                    stmt.setLong(12, now);
                    stmt.executeUpdate();
                    if (exists) {
                        updated++;
                    } else {
                        created++;
                    }
                }
            }
            connection.commit();
        } catch (SQLException e) {
            rollbackQuietly();
            return new UpsertResult(0, 0);
        } finally {
            restoreAutoCommit();
        }
        return new UpsertResult(created, updated);
    }

    @Override
    public Set<String> findDncPhones(Set<String> phones) {
        Objects.requireNonNull(phones, "phones must not be null");
        if (phones.isEmpty()) {
            return Set.of();
        }
        final List<String> ordered = List.copyOf(phones);
        final String placeholders = String.join(",", Collections.nCopies(ordered.size(), "?"));
        final String sql = "SELECT phone FROM leads WHERE dnc=1 AND deleted_at IS NULL AND phone IN ("
                + placeholders + ")";
        final Set<String> out = new HashSet<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            for (int i = 0; i < ordered.size(); i++) {
                stmt.setString(i + 1, ordered.get(i));
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        } catch (SQLException ignored) {
            return Set.of();
        }
        return Set.copyOf(out);
    }

    @Override
    public java.util.List<com.elitale.coldbirds.coldcalling.domain.value.LeadId> findLiveIdsByPhones(
            Set<String> phones) {
        Objects.requireNonNull(phones, "phones must not be null");
        if (phones.isEmpty()) {
            return java.util.List.of();
        }
        final List<String> ordered = List.copyOf(phones);
        final String placeholders = String.join(",", Collections.nCopies(ordered.size(), "?"));
        final String sql = "SELECT id FROM leads WHERE deleted_at IS NULL AND phone IN ("
                + placeholders + ")";
        final List<com.elitale.coldbirds.coldcalling.domain.value.LeadId> out = new java.util.ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            for (int i = 0; i < ordered.size(); i++) {
                stmt.setString(i + 1, ordered.get(i));
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    out.add(new com.elitale.coldbirds.coldcalling.domain.value.LeadId(rs.getLong(1)));
                }
            }
        } catch (SQLException ignored) {
            return java.util.List.of();
        }
        return List.copyOf(out);
    }

    private Set<String> livePhones(Set<String> phones) {
        final List<String> ordered = List.copyOf(phones);
        final String placeholders = String.join(",", Collections.nCopies(ordered.size(), "?"));
        final String sql = "SELECT phone FROM leads WHERE deleted_at IS NULL AND phone IN ("
                + placeholders + ")";
        final Set<String> out = new HashSet<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            for (int i = 0; i < ordered.size(); i++) {
                stmt.setString(i + 1, ordered.get(i));
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        } catch (SQLException ignored) {
            return Set.of();
        }
        return out;
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

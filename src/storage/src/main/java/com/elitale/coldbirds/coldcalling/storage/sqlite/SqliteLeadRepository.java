package com.elitale.coldbirds.coldcalling.storage.sqlite;

import com.elitale.coldbirds.coldcalling.domain.model.Lead;
import com.elitale.coldbirds.coldcalling.domain.value.CallListId;
import com.elitale.coldbirds.coldcalling.domain.value.Cursor;
import com.elitale.coldbirds.coldcalling.domain.value.LeadColumn;
import com.elitale.coldbirds.coldcalling.domain.value.LeadFilter;
import com.elitale.coldbirds.coldcalling.domain.value.LeadId;
import com.elitale.coldbirds.coldcalling.domain.value.LeadStatus;
import com.elitale.coldbirds.coldcalling.domain.value.Page;
import com.elitale.coldbirds.coldcalling.domain.value.PhoneNumber;
import com.elitale.coldbirds.coldcalling.domain.value.Result;
import com.elitale.coldbirds.coldcalling.storage.repository.LeadRepository;
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

public final class SqliteLeadRepository implements LeadRepository {

    private final Connection connection;

    public SqliteLeadRepository(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
    }

    @Override
    public Result<Lead> save(NewLead c) {
        String sql = """
            INSERT INTO leads
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
                    return findById(new LeadId(keys.getLong(1)))
                            .map(Result::ok)
                            .orElse(Result.err("Lead not found after insert"));
                }
                return Result.err("No generated key returned");
            }
        } catch (SQLException e) {
            return Result.err("Failed to save lead: " + e.getMessage(), e);
        }
    }

    @Override
    public Result<Lead> update(Lead c) {
        String sql = """
            UPDATE leads
               SET first_name=?, last_name=?, phone=?, company=?, title=?, email=?,
                   tags=?, notes=?, dnc=?, custom_fields=?, lead_status=?, updated_at=?
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
            stmt.setString(10, DomainMappers.customFieldsToJson(c.customFields()));
            stmt.setString(11, c.leadStatus().dbValue());
            stmt.setLong(12, now);
            stmt.setLong(13, c.id().value());
            int rows = stmt.executeUpdate();
            if (rows == 0) return Result.err("Lead not found or deleted: " + c.id().value());
            return findById(c.id())
                    .map(Result::ok)
                    .orElse(Result.err("Lead not found after update"));
        } catch (SQLException e) {
            return Result.err("Failed to update lead: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<Lead> findById(LeadId id) {
        String sql = "SELECT * FROM leads WHERE id=? AND deleted_at IS NULL";
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
    public Optional<Lead> findByPhone(PhoneNumber phone) {
        String sql = "SELECT * FROM leads WHERE phone=? AND deleted_at IS NULL LIMIT 1";
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
    public List<Lead> findAll() {
        return query("SELECT * FROM leads WHERE deleted_at IS NULL ORDER BY last_name, first_name");
    }

    @Override
    public List<Lead> search(String query) {
        String like = "%" + query.replace("%", "\\%").replace("_", "\\_") + "%";
        String sql = """
            SELECT * FROM leads
             WHERE deleted_at IS NULL
               AND (first_name LIKE ? OR last_name LIKE ? OR company LIKE ? OR phone LIKE ?)
             ORDER BY last_name, first_name
            """;
        List<Lead> result = new ArrayList<>();
        try (var stmt = connection.prepareStatement(sql)) {
            for (int i = 1; i <= 4; i++) stmt.setString(i, like);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) result.add(map(rs));
            }
        } catch (SQLException ignored) {}
        return List.copyOf(result);
    }

    @Override
    public Result<Void> delete(LeadId id) {
        String sql = "UPDATE leads SET deleted_at=? WHERE id=? AND deleted_at IS NULL";
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, Instant.now().toEpochMilli());
            stmt.setLong(2, id.value());
            int rows = stmt.executeUpdate();
            if (rows == 0) return Result.err("Lead not found or already deleted: " + id.value());
            return Result.ok(null);
        } catch (SQLException e) {
            return Result.err("Failed to delete lead: " + e.getMessage(), e);
        }
    }

    @Override
    public int bulkSoftDelete(List<LeadId> ids) {
        Objects.requireNonNull(ids, "ids must not be null");
        if (ids.isEmpty()) return 0;
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        String sql = "UPDATE leads SET deleted_at=?, updated_at=? WHERE deleted_at IS NULL AND id IN ("
                + placeholders + ")";
        long now = Instant.now().toEpochMilli();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, now);
            stmt.setLong(2, now);
            for (int i = 0; i < ids.size(); i++) {
                stmt.setLong(i + 3, ids.get(i).value());
            }
            return stmt.executeUpdate();
        } catch (SQLException e) {
            return 0;
        }
    }

    @Override
    public int bulkSetStatus(List<LeadId> ids, LeadStatus status) {
        Objects.requireNonNull(ids, "ids must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (ids.isEmpty()) return 0;
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        String sql = "UPDATE leads SET lead_status=?, updated_at=? WHERE deleted_at IS NULL AND id IN ("
                + placeholders + ")";
        long now = Instant.now().toEpochMilli();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, status.dbValue());
            stmt.setLong(2, now);
            for (int i = 0; i < ids.size(); i++) {
                stmt.setLong(i + 3, ids.get(i).value());
            }
            return stmt.executeUpdate();
        } catch (SQLException e) {
            return 0;
        }
    }

    @Override
    public int bulkSetDnc(List<LeadId> ids, boolean dnc) {
        Objects.requireNonNull(ids, "ids must not be null");
        if (ids.isEmpty()) return 0;
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        String sql = "UPDATE leads SET dnc=?, updated_at=? WHERE deleted_at IS NULL AND id IN ("
                + placeholders + ")";
        long now = Instant.now().toEpochMilli();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, dnc ? 1 : 0);
            stmt.setLong(2, now);
            for (int i = 0; i < ids.size(); i++) {
                stmt.setLong(i + 3, ids.get(i).value());
            }
            return stmt.executeUpdate();
        } catch (SQLException e) {
            return 0;
        }
    }

    private List<Lead> query(String sql) {
        List<Lead> result = new ArrayList<>();
        try (var stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) result.add(map(rs));
        } catch (SQLException ignored) {}
        return List.copyOf(result);
    }

    private static Lead map(ResultSet rs) throws SQLException {
        return new Lead(
                new LeadId(rs.getLong("id")),
                Optional.ofNullable(rs.getString("first_name")),
                Optional.ofNullable(rs.getString("last_name")),
                new PhoneNumber(rs.getString("phone")),
                Optional.ofNullable(rs.getString("company")),
                Optional.ofNullable(rs.getString("title")),
                Optional.ofNullable(rs.getString("email")),
                DomainMappers.jsonToTags(rs.getString("tags")),
                Optional.ofNullable(rs.getString("notes")),
                rs.getInt("dnc") == 1,
                DomainMappers.jsonToCustomFields(rs.getString("custom_fields")),
                LeadStatus.fromDb(rs.getString("lead_status")),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                Instant.ofEpochMilli(rs.getLong("updated_at"))
        );
    }

    // ── Filtered keyset pagination ────────────────────────────────────────

    @Override
    public Page<Lead> findPage(LeadFilter filter) {
        Objects.requireNonNull(filter, "filter must not be null");
        List<Object> params = new ArrayList<>();
        String where = buildWhere(filter, params);

        int total = count(where, params);

        List<Object> pageParams = new ArrayList<>(params);
        StringBuilder sql = new StringBuilder("SELECT * FROM leads c WHERE ").append(where);
        if (filter.cursor().isPresent()) {
            Cursor cursor = filter.cursor().get();
            sql.append(" AND (c.created_at < ? OR (c.created_at = ? AND c.id < ?))");
            pageParams.add(cursor.createdAtMillis());
            pageParams.add(cursor.createdAtMillis());
            pageParams.add(cursor.id());
        }
        sql.append(" ORDER BY c.created_at DESC, c.id DESC LIMIT ?");
        pageParams.add((long) filter.limit() + 1);   // fetch one extra to detect a next page

        List<Lead> rows = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql.toString())) {
            bind(stmt, pageParams);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) rows.add(map(rs));
            }
        } catch (SQLException e) {
            return Page.empty();
        }

        Optional<Cursor> next = Optional.empty();
        List<Lead> pageRows = rows;
        if (rows.size() > filter.limit()) {
            Lead last = rows.get(filter.limit() - 1);
            pageRows = rows.subList(0, filter.limit());
            next = Optional.of(new Cursor(last.createdAt().toEpochMilli(), last.id().value()));
        }
        return new Page<>(pageRows, next, total);
    }

    @Override
    public List<String> customFieldKeys(Optional<CallListId> listId) {
        Objects.requireNonNull(listId, "listId must not be null");
        StringBuilder sql = new StringBuilder("""
                SELECT DISTINCT je.key
                FROM leads c, json_each(c.custom_fields) je
                WHERE c.deleted_at IS NULL AND c.custom_fields IS NOT NULL""");
        List<Object> params = new ArrayList<>();
        if (listId.isPresent()) {
            sql.append(" AND EXISTS (SELECT 1 FROM call_list_leads m")
               .append(" WHERE m.lead_id = c.id AND m.list_id = ?)");
            params.add(listId.get().value());
        }
        sql.append(" ORDER BY je.key");
        return queryStrings(sql.toString(), params);
    }

    @Override
    public List<String> distinctTags() {
        String sql = """
                SELECT DISTINCT t.value
                FROM leads c, json_each(c.tags) t
                WHERE c.deleted_at IS NULL AND c.tags IS NOT NULL
                ORDER BY t.value""";
        return queryStrings(sql, List.of());
    }

    /** Builds the shared WHERE body (no leading keyword) and appends its bind values. */
    private static String buildWhere(LeadFilter f, List<Object> params) {
        StringBuilder where = new StringBuilder("c.deleted_at IS NULL");

        f.search().ifPresent(s -> {
            where.append(" AND (LOWER(c.first_name) LIKE '%'||LOWER(?)||'%'")
                 .append(" OR LOWER(c.last_name) LIKE '%'||LOWER(?)||'%'")
                 .append(" OR LOWER(c.company) LIKE '%'||LOWER(?)||'%'")
                 .append(" OR c.phone LIKE '%'||?||'%'")
                 .append(" OR LOWER(c.email) LIKE '%'||LOWER(?)||'%')");
            params.add(s); params.add(s); params.add(s); params.add(s); params.add(s);
        });

        f.columnFilters().forEach((col, sub) -> {
            where.append(" AND LOWER(").append(sqlColumn(col)).append(") LIKE '%'||LOWER(?)||'%'");
            params.add(sub);
        });

        if (!f.statuses().isEmpty()) {
            where.append(" AND c.lead_status IN (");
            boolean first = true;
            for (LeadStatus status : f.statuses()) {
                where.append(first ? "?" : ",?");
                params.add(status.dbValue());
                first = false;
            }
            where.append(")");
        }

        for (String tag : f.tags()) {
            where.append(" AND EXISTS (SELECT 1 FROM json_each(c.tags) t WHERE t.value = ?)");
            params.add(tag);
        }

        switch (f.dnc()) {
            case ANY -> { }
            case ONLY -> where.append(" AND c.dnc = 1");
            case EXCLUDE -> where.append(" AND c.dnc = 0");
        }

        f.listId().ifPresent(id -> {
            where.append(" AND EXISTS (SELECT 1 FROM call_list_leads m")
                 .append(" WHERE m.lead_id = c.id AND m.list_id = ?)");
            params.add(id.value());
        });

        f.customFields().forEach((key, sub) -> {
            where.append(" AND EXISTS (SELECT 1 FROM json_each(c.custom_fields) je")
                 .append(" WHERE je.key = ? AND LOWER(je.value) LIKE '%'||LOWER(?)||'%')");
            params.add(key);
            params.add(sub);
        });

        return where.toString();
    }

    /** Maps a filterable column to its hard-coded SQL identifier (never user input). */
    private static String sqlColumn(LeadColumn col) {
        return switch (col) {
            case FIRST_NAME -> "c.first_name";
            case LAST_NAME  -> "c.last_name";
            case COMPANY    -> "c.company";
            case TITLE      -> "c.title";
            case PHONE      -> "c.phone";
            case EMAIL      -> "c.email";
        };
    }

    private int count(String where, List<Object> params) {
        String sql = "SELECT COUNT(*) FROM leads c WHERE " + where;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            bind(stmt, params);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    private List<String> queryStrings(String sql, List<Object> params) {
        List<String> out = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            bind(stmt, params);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        } catch (SQLException ignored) {}
        return List.copyOf(out);
    }

    private static void bind(PreparedStatement stmt, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            stmt.setObject(i + 1, params.get(i));
        }
    }
}

package com.elitale.coldbirds.coldcalling.storage;

import org.flywaydb.core.Flyway;
import org.sqlite.SQLiteDataSource;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Owns the single SQLite connection for the application.
 * Runs Flyway migrations on startup.
 * Call {@link #close()} on application exit.
 */
public final class DatabaseManager implements Closeable {

    private final Connection connection;

    /** Production: opens/creates the database at the platform-specific default path. */
    public DatabaseManager() throws SQLException {
        this(defaultPath());
    }

    /** Production: opens/creates the database at a custom path. */
    public DatabaseManager(Path dbPath) throws SQLException {
        try {
            Files.createDirectories(dbPath.getParent());
        } catch (IOException e) {
            throw new SQLException("Cannot create database directory: " + dbPath.getParent(), e);
        }
        String url = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        this.connection = openAndConfigure(url);
        migrate(url);
    }

    /**
     * Test factory: each call returns an isolated in-memory database.
     * The UUID-named shared cache ensures Flyway and repositories see the same db.
     */
    public static DatabaseManager inMemory() throws SQLException {
        String name = "testdb-" + UUID.randomUUID().toString().replace("-", "");
        String url = "jdbc:sqlite:file:" + name + "?mode=memory&cache=shared";
        Connection c = openAndConfigure(url);
        migrateUrl(url);
        return new DatabaseManager(c);
    }

    private DatabaseManager(Connection connection) {
        this.connection = connection;
    }

    private static Connection openAndConfigure(String url) throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (var stmt = c.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA foreign_keys=ON");
        }
        return c;
    }

    private void migrate(String url) {
        migrateUrl(url);
    }

    private static void migrateUrl(String url) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl(url);
        Flyway.configure()
              .dataSource(ds)
              .locations("classpath:db/migration")
              .load()
              .migrate();
    }

    public Connection connection() {
        return connection;
    }

    /** Platform-specific default database file path. */
    public static Path defaultPath() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData == null) appData = System.getProperty("user.home");
            return Path.of(appData, "coldcalling", "data.db");
        }
        return Path.of(System.getProperty("user.home"), ".coldcalling", "data.db");
    }

    @Override
    public void close() throws IOException {
        try {
            if (!connection.isClosed()) connection.close();
        } catch (SQLException e) {
            throw new IOException("Failed to close database connection", e);
        }
    }
}

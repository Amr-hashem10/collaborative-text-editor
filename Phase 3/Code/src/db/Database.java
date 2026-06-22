package db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

// SQLite wa7ed le el server — documents + ops + versions
public class Database {

    private static final String DB_FILE = "editor.db";

    private static Connection connection = null;

    // get — singleton connection + createTables awl marra
    public static Connection get() throws SQLException {

        if (connection == null || connection.isClosed()) {

            connection = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);

            createTables(connection);
        }
        return connection;
    }

    // createTables — DDL + migration column law mesh mawgood
    private static void createTables(Connection conn) throws SQLException {

        Statement st = conn.createStatement();

        st.execute(
            "CREATE TABLE IF NOT EXISTS documents (" +
            "  id          TEXT PRIMARY KEY," +
            "  editor_code TEXT UNIQUE NOT NULL," +
            "  viewer_code TEXT UNIQUE NOT NULL," +
            "  title       TEXT" +
            ")"
        );

        st.execute(
            "CREATE TABLE IF NOT EXISTS operations (" +
            "  id      INTEGER PRIMARY KEY AUTOINCREMENT," +
            "  doc_id  TEXT NOT NULL," +
            "  op_json TEXT NOT NULL" +
            ")"
        );

        st.execute(
            "CREATE TABLE IF NOT EXISTS document_versions (" +
            "  id         INTEGER PRIMARY KEY AUTOINCREMENT," +
            "  doc_id     TEXT    NOT NULL," +
            "  ops_count  INTEGER NOT NULL," +
            "  label      TEXT," +
            "  created_at TEXT    DEFAULT (datetime('now'))" +
            ")"
        );

        try {
            st.execute(
                "ALTER TABLE documents ADD COLUMN current_op_count INTEGER DEFAULT NULL"
            );
        } catch (SQLException ignored) {

        }

        st.close();
    }
}

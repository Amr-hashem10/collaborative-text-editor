package db;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

// Local recent docs — kol user w doc le nfs el row
public class ClientStore {

    private static final String DB_FILE = "client.db";

    private static Connection connection = null;

    // get — singleton + initSchema
    private static Connection get() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
            initSchema(connection);
        }
        return connection;
    }

    // initSchema — recent_docs + migration PK law lezim
    private static void initSchema(Connection conn) throws SQLException {
        Statement st = conn.createStatement();

        String tableSQL = "";
        ResultSet rs = st.executeQuery(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='recent_docs'");
        if (rs.next()) tableSQL = rs.getString(1) != null ? rs.getString(1) : "";
        rs.close();

        boolean hasCompositePK = tableSQL.contains("PRIMARY KEY (doc_id, user_id)");
        if (!hasCompositePK) {
            st.execute("DROP TABLE IF EXISTS recent_docs");
        }

        st.execute(
            "CREATE TABLE IF NOT EXISTS recent_docs ("
            + "  doc_id       TEXT    NOT NULL,"
            + "  user_id      INTEGER NOT NULL DEFAULT 0,"
            + "  join_code    TEXT    NOT NULL DEFAULT '',"
            + "  editor_code  TEXT    NOT NULL DEFAULT '',"
            + "  viewer_code  TEXT    NOT NULL DEFAULT '',"
            + "  title        TEXT    NOT NULL DEFAULT 'Untitled',"
            + "  server_url   TEXT    NOT NULL DEFAULT 'ws://localhost:8080',"
            + "  last_visited INTEGER NOT NULL DEFAULT 0,"
            + "  PRIMARY KEY (doc_id, user_id)"
            + ")"
        );

        st.close();
    }

    // saveDocument — upsert bel user_id + merge el fields el fadya
    public static void saveDocument(String docId, int userId, String joinCode,
                                    String editorCode, String viewerCode,
                                    String title, String serverUrl) {
        try {
            Connection conn = get();
            PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO recent_docs"
                + " (doc_id, user_id, join_code, editor_code, viewer_code, title, server_url, last_visited)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                + " ON CONFLICT(doc_id, user_id) DO UPDATE SET"

                + "   join_code    = CASE WHEN excluded.join_code   != '' THEN excluded.join_code   ELSE recent_docs.join_code   END,"
                + "   editor_code  = CASE WHEN excluded.editor_code != '' THEN excluded.editor_code ELSE recent_docs.editor_code END,"
                + "   viewer_code  = CASE WHEN excluded.viewer_code != '' THEN excluded.viewer_code ELSE recent_docs.viewer_code END,"
                + "   title        = CASE WHEN excluded.title       != '' THEN excluded.title       ELSE recent_docs.title       END,"
                + "   server_url   = CASE WHEN excluded.server_url  != '' THEN excluded.server_url  ELSE recent_docs.server_url  END,"
                + "   last_visited = excluded.last_visited"
            );
            ps.setString(1, docId);
            ps.setInt   (2, userId);
            ps.setString(3, joinCode   != null ? joinCode   : "");
            ps.setString(4, editorCode != null ? editorCode : "");
            ps.setString(5, viewerCode != null ? viewerCode : "");
            ps.setString(6, (title != null && !title.isEmpty()) ? title : "Untitled");
            ps.setString(7, serverUrl  != null ? serverUrl  : "");
            ps.setLong  (8, System.currentTimeMillis());
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            System.err.println("[ClientStore] Failed to save document: " + e.getMessage());
        }
    }

    // saveDocument — overload le default user 0
    public static void saveDocument(String docId, String editorCode, String viewerCode,
                                    String title, String serverUrl) {
        saveDocument(docId, 0, "", editorCode, viewerCode, title, serverUrl);
    }

    // findRecord — row le docId + userId
    public static ClientDocumentRecord findRecord(String docId, int userId) {
        try {
            Connection conn = get();
            PreparedStatement ps = conn.prepareStatement(
                "SELECT doc_id, user_id, join_code, editor_code, viewer_code, title, server_url"
                + " FROM recent_docs WHERE doc_id = ? AND user_id = ?"
            );
            ps.setString(1, docId);
            ps.setInt   (2, userId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                ClientDocumentRecord rec = new ClientDocumentRecord(
                    rs.getString("doc_id"),
                    rs.getInt   ("user_id"),
                    rs.getString("join_code"),
                    rs.getString("editor_code"),
                    rs.getString("viewer_code"),
                    rs.getString("title"),
                    rs.getString("server_url")
                );
                rs.close();
                ps.close();
                return rec;
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            System.err.println("[ClientStore] findRecord: " + e.getMessage());
        }
        return null;
    }

    // removeDocument — delete men recent_docs bel docId
    public static void removeDocument(String docId) {
        try {
            Connection conn = get();
            PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM recent_docs WHERE doc_id = ?"
            );
            ps.setString(1, docId);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            System.err.println("[ClientStore] Failed to remove document: " + e.getMessage());
        }
    }

    // getRecentDocuments — group by doc_id + order bel last visited
    public static List<ClientDocumentRecord> getRecentDocuments() {
        List<ClientDocumentRecord> list = new ArrayList<ClientDocumentRecord>();
        try {
            Connection conn = get();
            PreparedStatement ps = conn.prepareStatement(
                "SELECT doc_id, user_id, join_code, editor_code, viewer_code, title, server_url"
                + " FROM recent_docs GROUP BY doc_id ORDER BY MAX(last_visited) DESC"
            );
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(new ClientDocumentRecord(
                    rs.getString("doc_id"),
                    rs.getInt   ("user_id"),
                    rs.getString("join_code"),
                    rs.getString("editor_code"),
                    rs.getString("viewer_code"),
                    rs.getString("title"),
                    rs.getString("server_url")
                ));
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            System.err.println("[ClientStore] Failed to load recent documents: " + e.getMessage());
        }
        return list;
    }

    public static class ClientDocumentRecord {
        public final String docId;
        public final int    userId;
        public final String joinCode;
        public final String editorCode;
        public final String viewerCode;
        public final String title;
        public final String serverUrl;

        // ClientDocumentRecord — constructor; defaults lel strings el fadya
        public ClientDocumentRecord(String docId, int userId, String joinCode,
                                    String editorCode, String viewerCode,
                                    String title, String serverUrl) {
            this.docId       = docId;
            this.userId      = userId;
            this.joinCode    = joinCode    != null ? joinCode    : "";
            this.editorCode  = editorCode  != null ? editorCode  : "";
            this.viewerCode  = viewerCode  != null ? viewerCode  : "";
            this.title       = (title != null && !title.isEmpty()) ? title : "Untitled";
            this.serverUrl   = serverUrl   != null ? serverUrl   : "";
        }

        // isEditor — joinCode fady aw bysawy editor code
        public boolean isEditor() {
            return joinCode.isEmpty() || joinCode.equals(editorCode);
        }

        // toString — el JList by3ard el title
        @Override
        public String toString() {
            return title;
        }
    }
}

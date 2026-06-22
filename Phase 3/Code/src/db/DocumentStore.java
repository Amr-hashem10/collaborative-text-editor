package db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

// Server-side DB: create doc, codes, append ops, versions
public class DocumentStore {

    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int    CODE_LEN   = 6;
    private static final Random RANDOM     = new Random();

    // createDocument — row gedeed + codes unique
    public static DocumentRecord createDocument(String docId, String title)
            throws SQLException {

        String editorCode = generateUniqueCode("editor_code");
        String viewerCode = generateUniqueCode("viewer_code");

        Connection conn = Database.get();

        PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO documents (id, editor_code, viewer_code, title)" +
            " VALUES (?, ?, ?, ?)"
        );
        ps.setString(1, docId);
        ps.setString(2, editorCode);
        ps.setString(3, viewerCode);
        ps.setString(4, title != null ? title : "Untitled");
        ps.executeUpdate();
        ps.close();

        return new DocumentRecord(docId, editorCode, viewerCode,
                title != null ? title : "Untitled");
    }

    // findByCode — editor_code OR viewer_code
    public static DocumentRecord findByCode(String code) throws SQLException {
        Connection conn = Database.get();
        PreparedStatement ps = conn.prepareStatement(
            "SELECT id, editor_code, viewer_code, title FROM documents" +
            " WHERE editor_code = ? OR viewer_code = ?"
        );
        ps.setString(1, code);
        ps.setString(2, code);
        ResultSet rs = ps.executeQuery();

        DocumentRecord record = null;
        if (rs.next()) {
            record = new DocumentRecord(
                rs.getString("id"),
                rs.getString("editor_code"),
                rs.getString("viewer_code"),
                rs.getString("title")
            );
        }
        rs.close();
        ps.close();
        return record;
    }

    // findById — primary key id
    public static DocumentRecord findById(String docId) throws SQLException {
        Connection conn = Database.get();
        PreparedStatement ps = conn.prepareStatement(
            "SELECT id, editor_code, viewer_code, title FROM documents WHERE id = ?"
        );
        ps.setString(1, docId);
        ResultSet rs = ps.executeQuery();

        DocumentRecord record = null;
        if (rs.next()) {
            record = new DocumentRecord(
                rs.getString("id"),
                rs.getString("editor_code"),
                rs.getString("viewer_code"),
                rs.getString("title")
            );
        }
        rs.close();
        ps.close();
        return record;
    }

    // saveOp — append op; law fe current_op_count ymsa7 el tail abl insert
    public static void saveOp(String docId, String opJson) throws SQLException {
        Connection conn = Database.get();

        PreparedStatement checkPs = conn.prepareStatement(
            "SELECT current_op_count FROM documents WHERE id = ?"
        );
        checkPs.setString(1, docId);
        ResultSet checkRs = checkPs.executeQuery();
        Integer pointer = null;
        if (checkRs.next()) {
            int val = checkRs.getInt("current_op_count");
            if (!checkRs.wasNull()) pointer = val;
        }
        checkRs.close();
        checkPs.close();

        if (pointer != null) {

            deleteOpsAfter(docId, pointer);
            clearCurrentOpCount(docId);
        }

        PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO operations (doc_id, op_json) VALUES (?, ?)"
        );
        ps.setString(1, docId);
        ps.setString(2, opJson);
        ps.executeUpdate();
        ps.close();
    }

    // getOps — replay list; COALESCE -1 = kol el ops
    public static List<String> getOps(String docId) throws SQLException {
        List<String> list = new ArrayList<String>();
        Connection conn = Database.get();

        PreparedStatement ps = conn.prepareStatement(
            "SELECT op_json FROM operations WHERE doc_id = ? ORDER BY id ASC" +
            " LIMIT COALESCE((SELECT current_op_count FROM documents WHERE id = ?), -1)"
        );
        ps.setString(1, docId);
        ps.setString(2, docId);
        ResultSet rs = ps.executeQuery();
        while (rs.next()) {
            list.add(rs.getString("op_json"));
        }
        rs.close();
        ps.close();
        return list;
    }

    // renameDocument — UPDATE title
    public static void renameDocument(String docId, String title) throws SQLException {
        Connection conn = Database.get();
        PreparedStatement ps = conn.prepareStatement(
            "UPDATE documents SET title = ? WHERE id = ?"
        );
        ps.setString(1, title != null ? title : "Untitled");
        ps.setString(2, docId);
        ps.executeUpdate();
        ps.close();
    }

    // deleteDocument — operations + row document
    public static void deleteDocument(String docId) throws SQLException {
        Connection conn = Database.get();

        PreparedStatement ps1 = conn.prepareStatement(
            "DELETE FROM operations WHERE doc_id = ?"
        );
        ps1.setString(1, docId);
        ps1.executeUpdate();
        ps1.close();

        PreparedStatement ps2 = conn.prepareStatement(
            "DELETE FROM documents WHERE id = ?"
        );
        ps2.setString(1, docId);
        ps2.executeUpdate();
        ps2.close();
    }

    // saveVersion — snapshot bel ops_count + trim le 5 versions max
    public static long saveVersion(String docId, String label) throws SQLException {
        Connection conn = Database.get();

        PreparedStatement countPs = conn.prepareStatement(
            "SELECT COUNT(*) FROM operations WHERE doc_id = ?"
        );
        countPs.setString(1, docId);
        ResultSet countRs = countPs.executeQuery();
        int opsCount = countRs.next() ? countRs.getInt(1) : 0;
        countRs.close();
        countPs.close();

        PreparedStatement ins = conn.prepareStatement(
            "INSERT INTO document_versions (doc_id, ops_count, label) VALUES (?, ?, ?)"
        );
        ins.setString(1, docId);
        ins.setInt(2, opsCount);
        ins.setString(3, label != null ? label : "Version");
        ins.executeUpdate();

        ResultSet genKeys = ins.getGeneratedKeys();
        long newId = genKeys.next() ? genKeys.getLong(1) : -1;
        genKeys.close();
        ins.close();

        PreparedStatement trim = conn.prepareStatement(
            "DELETE FROM document_versions WHERE doc_id = ? AND id NOT IN (" +
            "  SELECT id FROM document_versions WHERE doc_id = ? ORDER BY id DESC LIMIT 5" +
            ")"
        );
        trim.setString(1, docId);
        trim.setString(2, docId);
        trim.executeUpdate();
        trim.close();

        return newId;
    }

    // getVersions — a5er 5 rows
    public static List<VersionRecord> getVersions(String docId) throws SQLException {
        List<VersionRecord> list = new ArrayList<>();
        Connection conn = Database.get();
        PreparedStatement ps = conn.prepareStatement(
            "SELECT id, ops_count, label, created_at FROM document_versions" +
            " WHERE doc_id = ? ORDER BY id DESC LIMIT 5"
        );
        ps.setString(1, docId);
        ResultSet rs = ps.executeQuery();
        while (rs.next()) {
            list.add(new VersionRecord(
                rs.getLong("id"),
                rs.getInt("ops_count"),
                rs.getString("label"),
                rs.getString("created_at")
            ));
        }
        rs.close();
        ps.close();
        return list;
    }

    // getOpsForVersion — LIMIT bel ops_count lel rollback
    public static List<String> getOpsForVersion(String docId, int opsCount) throws SQLException {
        List<String> list = new ArrayList<>();
        Connection conn = Database.get();
        PreparedStatement ps = conn.prepareStatement(
            "SELECT op_json FROM operations WHERE doc_id = ? ORDER BY id ASC LIMIT ?"
        );
        ps.setString(1, docId);
        ps.setInt(2, opsCount);
        ResultSet rs = ps.executeQuery();
        while (rs.next()) {
            list.add(rs.getString("op_json"));
        }
        rs.close();
        ps.close();
        return list;
    }

    // setCurrentOpCount — pointer rollback fel documents row
    public static void setCurrentOpCount(String docId, int opsCount) throws SQLException {
        Connection conn = Database.get();
        PreparedStatement ps = conn.prepareStatement(
            "UPDATE documents SET current_op_count = ? WHERE id = ?"
        );
        ps.setInt(1, opsCount);
        ps.setString(2, docId);
        ps.executeUpdate();
        ps.close();
    }

    // clearCurrentOpCount — NULL = replay full tail
    private static void clearCurrentOpCount(String docId) throws SQLException {
        Connection conn = Database.get();
        PreparedStatement ps = conn.prepareStatement(
            "UPDATE documents SET current_op_count = NULL WHERE id = ?"
        );
        ps.setString(1, docId);
        ps.executeUpdate();
        ps.close();
    }

    // deleteOpsAfter — ymsa7 ops men cutoff (rollback)
    public static void deleteOpsAfter(String docId, int opsCount) throws SQLException {
        Connection conn = Database.get();

        PreparedStatement find = conn.prepareStatement(
            "SELECT id FROM operations WHERE doc_id = ? ORDER BY id ASC LIMIT 1 OFFSET ?"
        );
        find.setString(1, docId);
        find.setInt(2, opsCount);
        ResultSet rs = find.executeQuery();

        if (rs.next()) {

            long cutoffId = rs.getLong("id");
            rs.close();
            find.close();

            PreparedStatement del = conn.prepareStatement(
                "DELETE FROM operations WHERE doc_id = ? AND id >= ?"
            );
            del.setString(1, docId);
            del.setLong(2, cutoffId);
            del.executeUpdate();
            del.close();
        } else {

            rs.close();
            find.close();
        }
    }

    public static class VersionRecord {
        public final long   id;
        public final int    opsCount;
        public final String label;
        public final String createdAt;

        // VersionRecord — row men document_versions
        public VersionRecord(long id, int opsCount, String label, String createdAt) {
            this.id        = id;
            this.opsCount  = opsCount;
            this.label     = label;
            this.createdAt = createdAt;
        }
    }

    public static class DocumentRecord {
        public final String id;
        public final String editorCode;
        public final String viewerCode;
        public final String title;

        // DocumentRecord — metadata row men documents
        public DocumentRecord(String id, String editorCode, String viewerCode, String title) {
            this.id         = id;
            this.editorCode = editorCode;
            this.viewerCode = viewerCode;
            this.title      = (title != null && !title.isEmpty()) ? title : "Untitled";
        }
    }

    // generateUniqueCode — loop 7atta code unique fel column
    private static String generateUniqueCode(String column) throws SQLException {
        while (true) {
            String code = randomCode();
            if (!codeExists(column, code)) return code;

        }
    }

    // codeExists — SELECT 1 check
    private static boolean codeExists(String column, String code) throws SQLException {
        Connection conn = Database.get();
        PreparedStatement ps = conn.prepareStatement(
            "SELECT 1 FROM documents WHERE " + column + " = ?"
        );
        ps.setString(1, code);
        ResultSet rs = ps.executeQuery();
        boolean found = rs.next();
        rs.close();
        ps.close();
        return found;
    }

    // randomCode — CODE_LEN chars men CODE_CHARS
    private static String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LEN);
        for (int i = 0; i < CODE_LEN; i++) {
            sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }
}

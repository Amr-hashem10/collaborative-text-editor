package network;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import db.DocumentStore;
import db.DocumentStore.DocumentRecord;
import db.DocumentStore.VersionRecord;
import operations.OperationSerializer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

// Relay + SQLite — handshake, join, broadcast ops, reconnect grace
public class CrdtServer extends WebSocketServer {

    private final Map<WebSocket, String>  connDoc    = new ConcurrentHashMap<>();

    private final Map<WebSocket, String>  connRole   = new ConcurrentHashMap<>();

    private final Map<WebSocket, Integer> connUserId = new ConcurrentHashMap<>();

    private final Map<String, Set<WebSocket>> sessions = new ConcurrentHashMap<>();

    private final Map<Integer, ReconnectEntry> reconnecting = new ConcurrentHashMap<>();

    private final Set<WebSocket> voluntaryLeave = ConcurrentHashMap.newKeySet();

    private static final long RECONNECT_GRACE_MS = 5 * 60 * 1000L;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    // lama el net yw2f shwya — buffer ops 7atta yexpire
    private static class ReconnectEntry {
        final String docId;
        final String role;

        final List<String> bufferedOps = new ArrayList<>();

        ScheduledFuture<?> expireTask;

        // ReconnectEntry — buffer ops fel grace
        ReconnectEntry(String docId, String role) {
            this.docId = docId;
            this.role  = role;
        }
    }

    // CrdtServer — port + reuseAddr
    public CrdtServer(int port) {
        super(new InetSocketAddress(port));
        setReuseAddr(true);
    }

    // onOpen — log remote address
    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("[Server] Connection from " + conn.getRemoteSocketAddress());
    }

    // onMessage — handshake aw handleOp
    @Override
    public void onMessage(WebSocket conn, String message) {
        if (!connDoc.containsKey(conn)) {

            handleHandshake(conn, message);
        } else {

            handleOp(conn, message);
        }
    }

    // onClose — sessions + grace aw user_left
    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {

        String  docId  = connDoc.remove(conn);
        String  role   = connRole.remove(conn);
        Integer userId = connUserId.remove(conn);

        if (docId != null) {

            Set<WebSocket> peers = sessions.get(docId);
            if (peers != null) {
                peers.remove(conn);
                if (peers.isEmpty()) sessions.remove(docId);
            }

            if (userId != null) {

                if (voluntaryLeave.remove(conn)) {

                    String leftMsg = "{\"type\":\"user_left\",\"userId\":" + userId + "}";
                    Set<WebSocket> remaining = sessions.get(docId);
                    if (remaining != null) {
                        for (WebSocket peer : remaining) {
                            if (peer.isOpen()) peer.send(leftMsg);
                        }
                    }
                    System.out.printf("[Server] User %d left '%s' (voluntary)%n", userId, docId);
                } else {

                    ReconnectEntry entry = new ReconnectEntry(docId, role != null ? role : "viewer");

                    ScheduledFuture<?> task = scheduler.schedule(() -> {

                        reconnecting.remove(userId);
                        String leftMsg = "{\"type\":\"user_left\",\"userId\":" + userId + "}";
                        Set<WebSocket> remaining = sessions.get(docId);
                        if (remaining != null) {
                            for (WebSocket peer : remaining) {
                                if (peer.isOpen()) peer.send(leftMsg);
                            }
                        }
                        System.out.printf("[Server] User %d grace period expired for '%s'%n",
                                userId, docId);
                    }, RECONNECT_GRACE_MS, TimeUnit.MILLISECONDS);

                    entry.expireTask = task;
                    reconnecting.put(userId, entry);
                    System.out.printf("[Server] User %d disconnected from '%s'; grace period started%n",
                            userId, docId);
                }
            }
        }
        System.out.println("[Server] Disconnected: " + conn.getRemoteSocketAddress()
                + " (code=" + code + ")");
    }

    // onError — log connection + ex
    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("[Server] Error on "
                + (conn != null ? conn.getRemoteSocketAddress() : "?")
                + ": " + ex.getMessage());
    }

    // onStart — print port
    @Override
    public void onStart() {
        System.out.println("[Server] Listening on port " + getPort());
    }

    // handleHandshake — create/join/rename/delete/versions/leave
    private void handleHandshake(WebSocket conn, String message) {
        try {
            JsonObject msg = JsonParser.parseString(message).getAsJsonObject();
            String action  = msg.get("action").getAsString();

            if (action.equals("create")) {
                int    userId = msg.has("userId") ? msg.get("userId").getAsInt() : 0;
                String title  = msg.has("title")  ? msg.get("title").getAsString() : "Untitled";
                handleCreate(conn, userId, title);

            } else if (action.equals("join")) {

                String code = msg.get("code").getAsString().trim().toUpperCase();
                int userId  = msg.has("userId") ? msg.get("userId").getAsInt() : 0;
                handleJoin(conn, code, userId);

            } else if (action.equals("rename")) {
                String code  = msg.get("code").getAsString().trim().toUpperCase();
                String title = msg.has("title") ? msg.get("title").getAsString() : "Untitled";
                handleRename(conn, code, title);

            } else if (action.equals("delete")) {
                String code = msg.get("code").getAsString().trim().toUpperCase();
                handleDelete(conn, code);

            } else if (action.equals("save_version")) {
                String code  = msg.get("code").getAsString().trim().toUpperCase();
                String label = msg.has("label") ? msg.get("label").getAsString() : "Version";
                handleSaveVersion(conn, code, label);

            } else if (action.equals("get_versions")) {
                String code = msg.get("code").getAsString().trim().toUpperCase();
                handleGetVersions(conn, code);

            } else if (action.equals("rollback_version")) {
                String code      = msg.get("code").getAsString().trim().toUpperCase();
                long   versionId = msg.get("versionId").getAsLong();
                handleRollbackVersion(conn, code, versionId);

            } else if (action.equals("leave")) {
                voluntaryLeave.add(conn);
                conn.close();

            } else {
                System.err.println("[Server] Unknown action: " + action);
                conn.close();
            }
        } catch (Exception e) {
            System.err.println("[Server] Bad handshake: " + e.getMessage());
            conn.close();
        }
    }

    // handleCreate — UUID doc + joined + history_done
    private void handleCreate(WebSocket conn, int userId, String title) throws Exception {
        String docId = UUID.randomUUID().toString();
        String safeTitle = (title != null && !title.trim().isEmpty()) ? title.trim() : "Untitled";
        DocumentRecord rec = DocumentStore.createDocument(docId, safeTitle);

        registerConn(conn, rec.id, "editor", userId);

        String escapedTitle = rec.title.replace("\\", "\\\\").replace("\"", "\\\"");
        String reply = "{\"type\":\"joined\","
                + "\"docId\":\"" + rec.id + "\","
                + "\"role\":\"editor\","
                + "\"editorCode\":\"" + rec.editorCode + "\","
                + "\"viewerCode\":\"" + rec.viewerCode + "\","
                + "\"title\":\"" + escapedTitle + "\"}";
        conn.send(reply);
        conn.send("{\"type\":\"history_done\"}");
        conn.send("{\"type\":\"user_list\",\"userIds\":[" + userId + "]}");

        System.out.printf("[Server] Created doc '%s'  editor=%s  viewer=%s%n",
                rec.id, rec.editorCode, rec.viewerCode);
    }

    // handleJoin — reconnect buffer aw full history men DB
    private void handleJoin(WebSocket conn, String code, int userId) throws Exception {
        DocumentRecord rec = DocumentStore.findByCode(code);
        if (rec == null) {
            conn.send("{\"type\":\"error\",\"message\":\"Document not found\"}");
            conn.close();
            return;
        }

        String role = code.equals(rec.editorCode) ? "editor" : "viewer";

        Set<WebSocket> currentPeers = sessions.get(rec.id);
        if (currentPeers != null) {
            for (WebSocket peer : currentPeers) {
                if (peer.isOpen() && userId == connUserId.getOrDefault(peer, -1)) {
                    conn.send("{\"type\":\"error\",\"message\":\"User " + userId
                            + " is already connected to this document\"}");
                    conn.close();
                    return;
                }
            }
        }

        ReconnectEntry reconnectEntry = reconnecting.get(userId);
        boolean isReconnect = reconnectEntry != null && reconnectEntry.docId.equals(rec.id);

        if (isReconnect) {
            reconnectEntry.expireTask.cancel(false);
            reconnecting.remove(userId);

            registerConn(conn, rec.id, reconnectEntry.role, userId);

            String escapedTitle1 = rec.title.replace("\\", "\\\\").replace("\"", "\\\"");
            String reply = "{\"type\":\"joined\","
                    + "\"docId\":\"" + rec.id + "\","
                    + "\"role\":\"" + reconnectEntry.role + "\","
                    + "\"editorCode\":\"" + rec.editorCode + "\","
                    + "\"viewerCode\":\"" + rec.viewerCode + "\","
                    + "\"title\":\"" + escapedTitle1 + "\"}";
            conn.send(reply);

            List<String> buffered = reconnectEntry.bufferedOps;
            for (String opJson : buffered) {
                conn.send("{\"type\":\"history\",\"op\":" + opJson + "}");
            }
            conn.send("{\"type\":\"history_done\"}");
            conn.send(buildUserListJson(rec.id));

            System.out.printf("[Server] User %d reconnected to '%s' (%d missed ops)%n",
                    userId, rec.id, buffered.size());
            return;
        }

        Set<WebSocket> existingPeers = sessions.get(rec.id);
        String joinedMsg = "{\"type\":\"user_joined\",\"userId\":" + userId + "}";
        if (existingPeers != null) {
            for (WebSocket peer : existingPeers) {
                if (peer.isOpen()) peer.send(joinedMsg);
            }
        }

        registerConn(conn, rec.id, role, userId);

        String escapedTitle2 = rec.title.replace("\\", "\\\\").replace("\"", "\\\"");
        String reply = "{\"type\":\"joined\","
                + "\"docId\":\"" + rec.id + "\","
                + "\"role\":\"" + role + "\","
                + "\"editorCode\":\"" + rec.editorCode + "\","
                + "\"viewerCode\":\"" + rec.viewerCode + "\","
                + "\"title\":\"" + escapedTitle2 + "\"}";
        conn.send(reply);

        List<String> history = DocumentStore.getOps(rec.id);
        for (String opJson : history) {
            conn.send("{\"type\":\"history\",\"op\":" + opJson + "}");
        }
        conn.send("{\"type\":\"history_done\"}");

        conn.send(buildUserListJson(rec.id));

        System.out.printf("[Server] User %d joined doc '%s' as %s (history: %d ops)%n",
                userId, rec.id, role, history.size());
    }

    // buildUserListJson — userIds men el session set
    private String buildUserListJson(String docId) {
        Set<WebSocket> allPeers = sessions.get(docId);
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"user_list\",\"userIds\":[");
        boolean first = true;
        if (allPeers != null) {
            for (WebSocket peer : allPeers) {
                Integer uid = connUserId.get(peer);
                if (uid != null) {
                    if (!first) sb.append(",");
                    sb.append(uid);
                    first = false;
                }
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    // handleRename — editor only + broadcast renamed
    private void handleRename(WebSocket conn, String code, String title) throws Exception {
        DocumentRecord rec = DocumentStore.findByCode(code);
        if (rec == null) {

            if (conn.isOpen()) conn.send("{\"type\":\"error\",\"message\":\"Document not found\"}");
            return;
        }
        if (!code.equals(rec.editorCode)) {

            if (conn.isOpen()) conn.send("{\"type\":\"error\",\"message\":\"Not authorized\"}");
            return;
        }

        DocumentStore.renameDocument(rec.id, title);

        String escapedTitle = title.replace("\\", "\\\\").replace("\"", "\\\"");
        String renamedMsg = "{\"type\":\"renamed\",\"title\":\"" + escapedTitle + "\"}";

        Set<WebSocket> peers = sessions.get(rec.id);
        if (peers != null) {
            for (WebSocket peer : peers) {
                if (peer.isOpen()) peer.send(renamedMsg);
            }
        }

        if (peers == null || !peers.contains(conn)) {
            if (conn.isOpen()) conn.send(renamedMsg);
        }

        System.out.printf("[Server] Renamed doc '%s' to '%s'%n", rec.id, title);
    }

    // handleDelete — shel men DB + 2fl el peers
    private void handleDelete(WebSocket conn, String code) throws Exception {
        DocumentRecord rec = DocumentStore.findByCode(code);
        if (rec == null) {
            if (conn.isOpen()) conn.send("{\"type\":\"error\",\"message\":\"Document not found\"}");
            return;
        }
        if (!code.equals(rec.editorCode)) {
            if (conn.isOpen()) conn.send("{\"type\":\"error\",\"message\":\"Not authorized\"}");
            return;
        }

        DocumentStore.deleteDocument(rec.id);

        String deletedMsg = "{\"type\":\"deleted\"}";
        Set<WebSocket> peers = sessions.get(rec.id);
        List<WebSocket> toClose = new ArrayList<WebSocket>();
        if (peers != null) {
            for (WebSocket peer : peers) {
                if (peer.isOpen()) {
                    peer.send(deletedMsg);
                    toClose.add(peer);
                }
            }
        }
        for (WebSocket peer : toClose) {
            peer.close();
        }
        sessions.remove(rec.id);

        if (conn.isOpen()) {
            conn.send(deletedMsg);
            conn.close();
        }

        System.out.printf("[Server] Deleted doc '%s'%n", rec.id);
    }

    // handleSaveVersion — saveVersion + broadcast version_saved
    private void handleSaveVersion(WebSocket conn, String code, String label) throws Exception {
        DocumentRecord rec = DocumentStore.findByCode(code);
        if (rec == null) {
            conn.send("{\"type\":\"error\",\"message\":\"Document not found\"}");
            return;
        }
        if (!code.equals(rec.editorCode)) {
            conn.send("{\"type\":\"error\",\"message\":\"Not authorized\"}");
            return;
        }

        long verId = DocumentStore.saveVersion(rec.id, label);
        String msg = "{\"type\":\"version_saved\",\"versionId\":" + verId + "}";

        Set<WebSocket> peers = sessions.get(rec.id);
        if (peers != null) {
            for (WebSocket peer : peers) {
                if (peer.isOpen()) peer.send(msg);
            }
        }
        if (conn.isOpen() && (peers == null || !peers.contains(conn))) conn.send(msg);

        System.out.printf("[Server] Saved version %d for doc '%s'%n", verId, rec.id);
    }

    // handleGetVersions — JSON versions_list lel wa7ed
    private void handleGetVersions(WebSocket conn, String code) throws Exception {
        DocumentRecord rec = DocumentStore.findByCode(code);
        if (rec == null) {
            conn.send("{\"type\":\"error\",\"message\":\"Document not found\"}");
            return;
        }

        List<VersionRecord> versions = DocumentStore.getVersions(rec.id);

        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"versions_list\",\"versions\":[");
        boolean first = true;
        for (VersionRecord v : versions) {
            if (!first) sb.append(",");

            sb.append("{\"id\":").append(v.id)
              .append(",\"label\":\"").append(v.label.replace("\"", "\\\"")).append("\"")
              .append(",\"createdAt\":\"").append(v.createdAt).append("\"")
              .append("}");
            first = false;
        }
        sb.append("]}");
        conn.send(sb.toString());
    }

    // handleRollbackVersion — ops + setCurrentOpCount + broadcast
    private void handleRollbackVersion(WebSocket conn, String code, long versionId) throws Exception {
        DocumentRecord rec = DocumentStore.findByCode(code);
        if (rec == null) {
            conn.send("{\"type\":\"error\",\"message\":\"Document not found\"}");
            return;
        }
        if (!code.equals(rec.editorCode)) {
            conn.send("{\"type\":\"error\",\"message\":\"Not authorized\"}");
            return;
        }

        List<VersionRecord> versions = DocumentStore.getVersions(rec.id);
        VersionRecord target = null;
        for (VersionRecord v : versions) {
            if (v.id == versionId) { target = v; break; }
        }
        if (target == null) {
            conn.send("{\"type\":\"error\",\"message\":\"Version not found\"}");
            return;
        }

        List<String> ops = DocumentStore.getOpsForVersion(rec.id, target.opsCount);

        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"version_rollback\",\"ops\":[");
        boolean first = true;
        for (String opJson : ops) {
            if (!first) sb.append(",");
            sb.append(opJson);
            first = false;
        }
        sb.append("]}");
        String rollbackMsg = sb.toString();

        Set<WebSocket> peers = sessions.get(rec.id);
        if (peers != null) {
            for (WebSocket peer : peers) {
                if (peer.isOpen()) peer.send(rollbackMsg);
            }
        }
        if (conn.isOpen() && (peers == null || !peers.contains(conn))) conn.send(rollbackMsg);

        DocumentStore.setCurrentOpCount(rec.id, target.opsCount);

        System.out.printf("[Server] Rolled back doc '%s' to version %d (%d ops kept)%n",
                rec.id, versionId, ops.size());
    }

    // registerConn — maps + session set
    private void registerConn(WebSocket conn, String docId, String role, int userId) {
        connDoc.put(conn, docId);
        connRole.put(conn, role);
        connUserId.put(conn, userId);

        sessions.computeIfAbsent(docId, k -> ConcurrentHashMap.newKeySet()).add(conn);
    }

    // handleOp — cursor relay; editor saves + relay + buffer grace
    private void handleOp(WebSocket conn, String message) {
        String role  = connRole.get(conn);
        String docId = connDoc.get(conn);

        boolean isCursor = message.contains("\"type\":\"cursor\"");

        if (isCursor) {
            relay(conn, message, docId);
            return;
        }

        if (message.contains("\"action\"")) {
            handleHandshake(conn, message);
            return;
        }

        if ("viewer".equals(role)) return;

        try {
            DocumentStore.saveOp(docId, message);
        } catch (Exception e) {
            System.err.println("[Server] Failed to save op: " + e.getMessage());
        }

        for (Map.Entry<Integer, ReconnectEntry> entry : reconnecting.entrySet()) {
            if (entry.getValue().docId.equals(docId)) {
                entry.getValue().bufferedOps.add(message);
            }
        }

        relay(conn, message, docId);
    }

    // relay — broadcast 3ada el sender
    private void relay(WebSocket sender, String message, String docId) {
        Set<WebSocket> peers = sessions.get(docId);
        if (peers == null) return;
        for (WebSocket peer : peers) {

            if (peer != sender && peer.isOpen()) {
                peer.send(message);
            }
        }
    }

    // main — start server + block
    public static void main(String[] args) throws InterruptedException {
        int port = (args.length > 0) ? Integer.parseInt(args[0]) : 8080;
        CrdtServer server = new CrdtServer(port);
        server.start();
        System.out.println("Press Ctrl+C to stop the server.");
        Thread.currentThread().join();
    }
}

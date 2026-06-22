package network;

import com.google.gson.JsonArray;   // represent JSON array []
import com.google.gson.JsonObject;  // represent JSON object with key {}
import com.google.gson.JsonParser;   // for convert from JSON string to JSON object or array
import operations.Operation;
import operations.OperationSerializer;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

// WebSocket client — create/join w parsing le el server JSON
public class CrdtClient extends WebSocketClient {

    public interface SessionListener {

        // onJoined — docId + codes + role + title
        void onJoined(String docId, String editorCode, String viewerCode, String role, String title);

        // onHistoryOp — replay op wa7ed
        void onHistoryOp(Operation op);

        // onReady — ba3d history_done
        void onReady();

        // onRemoteOp — live op men user tany
        void onRemoteOp(Operation op);

        // onRemoteCursor — cursor mesh Operation JSON
        default void onRemoteCursor(int userId, int blockIndex, int charPos) {}

        // onUserJoined — user da5al el session
        default void onUserJoined(int userId) {}

        // onUserLeft — user khrg aw grace expire
        default void onUserLeft(int userId) {}

        // onUserList — refresh online IDs
        default void onUserList(List<Integer> userIds) {}

        // onDocumentDeleted — editor ms7 el doc
        default void onDocumentDeleted() {}

        // onDocumentRenamed — title etghayar
        default void onDocumentRenamed(String newTitle) {}

        // onDisconnected — reconnect fel UI
        default void onDisconnected() {}

        // onVersionsList — list snapshots
        default void onVersionsList(List<VersionInfo> versions) {}

        // onVersionRolledBack — ops lel rebuild
        default void onVersionRolledBack(List<Operation> ops) {}

        // onError — server error string
        default void onError(String message) {}
    }

    public static class VersionInfo {
        public final long   id;
        public final String label;
        public final String createdAt;

        // VersionInfo — row snapshot fel UI
        public VersionInfo(long id, String label, String createdAt) {
            this.id        = id;
            this.label     = label;
            this.createdAt = createdAt;
        }

        // toString — label + createdAt
        @Override
        public String toString() {
            return label + "  (" + createdAt + ")";
        }
    }

    private final String action;

    private final String code;

    private final int userId;

    private final SessionListener listener;

    private volatile boolean joined = false;

    private volatile boolean ready = false;

    private volatile String role = "viewer";

    private String pendingTitle = "Untitled";

    // factory — action "create" mafhash code
    public static CrdtClient forCreate(URI serverUri, int userId, String title, SessionListener listener) {
        CrdtClient c = new CrdtClient(serverUri, "create", null, userId, listener);
        c.pendingTitle = (title != null && !title.trim().isEmpty()) ? title.trim() : "Untitled";
        return c;
    }

    // forCreate — default title Untitled
    public static CrdtClient forCreate(URI serverUri, int userId, SessionListener listener) {
        return forCreate(serverUri, userId, "Untitled", listener);
    }

    // forJoin — bel share code
    public static CrdtClient forJoin(URI serverUri, String code, int userId, SessionListener listener) {
        return new CrdtClient(serverUri, "join", code, userId, listener);
    }

    // CrdtClient — WebSocket + action/code/userId
    private CrdtClient(URI serverUri, String action, String code, int userId,
                       SessionListener listener) {
        super(serverUri);
        this.action   = action;
        this.code     = code;
        this.userId   = userId;
        this.listener = listener;
    }

    // onOpen — awl packet create aw join JSON
    @Override
    public void onOpen(ServerHandshake handshake) {
        if (action.equals("create")) {

            String escaped = pendingTitle.replace("\\", "\\\\").replace("\"", "\\\"");
            send("{\"action\":\"create\",\"userId\":" + userId + ",\"title\":\"" + escaped + "\"}");
        } else {

            send("{\"action\":\"join\",\"code\":\"" + code + "\",\"userId\":" + userId + "}");
        }
    }

    // onMessage — server types + live cursor/op
    @Override
    public void onMessage(String message) {
        try {
            JsonObject obj = JsonParser.parseString(message).getAsJsonObject();

            if (obj.has("type")) {
                String type = obj.get("type").getAsString();

                if (type.equals("user_joined")) {
                    listener.onUserJoined(obj.get("userId").getAsInt());
                    return;
                }
                if (type.equals("user_left")) {
                    listener.onUserLeft(obj.get("userId").getAsInt());
                    return;
                }
                if (type.equals("user_list")) {

                    JsonArray arr = obj.getAsJsonArray("userIds");
                    List<Integer> ids = new ArrayList<Integer>();
                    for (int i = 0; i < arr.size(); i++) {
                        ids.add(arr.get(i).getAsInt());
                    }
                    listener.onUserList(ids);
                    return;
                }

                if (type.equals("deleted")) {
                    listener.onDocumentDeleted();
                    return;
                }
                if (type.equals("renamed")) {
                    listener.onDocumentRenamed(obj.get("title").getAsString());
                    return;
                }
                if (type.equals("version_saved")) {

                    return;
                }

                if (type.equals("joined")) {
                    handleJoined(obj);
                    return;
                }
                if (type.equals("history")) {
                    handleHistoryOp(obj);
                    return;
                }
                if (type.equals("history_done")) {
                    ready = true;
                    listener.onReady();
                    return;
                }

                if (type.equals("error")) {
                    String msg = obj.has("message") ? obj.get("message").getAsString() : "Unknown error";
                    System.err.println("[Client] Server error: " + msg);
                    listener.onError(msg);
                    return;
                }

                if (type.equals("versions_list")) {
                    handleVersionsList(obj);
                    return;
                }
                if (type.equals("version_rollback")) {
                    handleVersionRollback(obj);
                    return;
                }

            }

            if (ready) {
                if (obj.has("type") && obj.get("type").getAsString().equals("cursor")) {

                    int uid = obj.get("userId").getAsInt();
                    int bi  = obj.get("blockIndex").getAsInt();
                    int cp  = obj.get("charPos").getAsInt();
                    listener.onRemoteCursor(uid, bi, cp);
                } else {

                    Operation op = OperationSerializer.fromJson(message);
                    listener.onRemoteOp(op);
                }
            }

        } catch (Exception e) {
            System.err.println("[Client] Failed to parse message: " + e.getMessage());
        }
    }

    // onClose — reset joined/ready + onDisconnected
    @Override
    public void onClose(int code, String reason, boolean remote) {
        boolean wasReady = ready;
        joined = false;
        ready  = false;
        System.out.println("[Client] Connection closed: " + reason + " (code=" + code + ")");

        if (wasReady) {
            listener.onDisconnected();
        }
    }

    // onError — log stderr
    @Override
    public void onError(Exception ex) {
        System.err.println("[Client] Error: " + ex.getMessage());
    }

    // handleJoined — joined=true; history ba3daha
    private void handleJoined(JsonObject obj) {
        joined = true;
        String docId      = obj.get("docId").getAsString();
        String editorCode = obj.has("editorCode") ? obj.get("editorCode").getAsString() : "";
        String viewerCode = obj.has("viewerCode") ? obj.get("viewerCode").getAsString() : "";
        role              = obj.has("role")        ? obj.get("role").getAsString()        : "viewer";

        String title      = obj.has("title")       ? obj.get("title").getAsString()       : "Untitled";
        listener.onJoined(docId, editorCode, viewerCode, role, title);
    }

    // handleHistoryOp — parse "op" -> onHistoryOp
    private void handleHistoryOp(JsonObject obj) {
        String opJson = null;
        try {

            com.google.gson.JsonElement opEl = obj.get("op");
            if (opEl == null || opEl.isJsonNull()) {
                System.err.println("[Client] History message has no 'op' field");
                return;
            }

            opJson = opEl.isJsonPrimitive() ? opEl.getAsString() : opEl.toString();
            Operation op = OperationSerializer.fromJson(opJson);
            listener.onHistoryOp(op);
        } catch (Exception e) {
            System.err.println("[Client] Failed to parse history op: " + e.getMessage());
            System.err.println("[Client] Failing op JSON was: " + opJson);
        }
    }

    // handleVersionsList — array -> VersionInfo list
    private void handleVersionsList(JsonObject obj) {
        try {
            JsonArray arr = obj.getAsJsonArray("versions");
            List<VersionInfo> infos = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                JsonObject v = arr.get(i).getAsJsonObject();
                infos.add(new VersionInfo(
                        v.get("id").getAsLong(),
                        v.has("label")     ? v.get("label").getAsString()     : "Version",
                        v.has("createdAt") ? v.get("createdAt").getAsString() : ""
                ));
            }
            listener.onVersionsList(infos);
        } catch (Exception e) {
            System.err.println("[Client] Failed to parse versions_list: " + e.getMessage());
        }
    }

    // handleVersionRollback — ops array -> listener
    private void handleVersionRollback(JsonObject obj) {
        try {
            JsonArray arr = obj.getAsJsonArray("ops");
            List<Operation> ops = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                ops.add(OperationSerializer.fromJson(arr.get(i).toString()));
            }
            listener.onVersionRolledBack(ops);
        } catch (Exception e) {
            System.err.println("[Client] Failed to parse version_rollback: " + e.getMessage());
        }
    }

    // sendCursor — JSON s8yr mesh Operation
    public void sendCursor(int blockIndex, int charPos) {
        if (isOpen() && ready) {
            send("{\"type\":\"cursor\",\"userId\":" + userId
                    + ",\"blockIndex\":" + blockIndex
                    + ",\"charPos\":" + charPos + "}");
        }
    }

    // sendOp — toJson string
    public void sendOp(Operation op) {
        if (isOpen() && ready) {
            send(OperationSerializer.toJson(op));
        }
    }

    // sendOps — loop sendOp
    public void sendOps(List<Operation> ops) {
        for (Operation op : ops) {
            sendOp(op);
        }
    }

    // sendRename — action rename
    public void sendRename(String editorCode, String title) {
        if (isOpen()) {
            send("{\"action\":\"rename\",\"code\":\"" + editorCode + "\",\"title\":\"" + title + "\"}");
        }
    }

    // sendDelete — action delete
    public void sendDelete(String editorCode) {
        if (isOpen()) {
            send("{\"action\":\"delete\",\"code\":\"" + editorCode + "\"}");
        }
    }

    // sendLeave — action leave
    public void sendLeave() {
        if (isOpen()) {
            send("{\"action\":\"leave\"}");
        }
    }

    // sendSaveVersion — escape label + send
    public void sendSaveVersion(String editorCode, String label) {
        if (isOpen()) {

            String escaped = label.replace("\\", "\\\\").replace("\"", "\\\"");
            send("{\"action\":\"save_version\",\"code\":\"" + editorCode
                    + "\",\"label\":\"" + escaped + "\"}");
        }
    }

    // sendGetVersions — action get_versions
    public void sendGetVersions(String editorCode) {
        if (isOpen()) {
            send("{\"action\":\"get_versions\",\"code\":\"" + editorCode + "\"}");
        }
    }

    // sendRollback — action rollback_version
    public void sendRollback(String editorCode, long versionId) {
        if (isOpen()) {
            send("{\"action\":\"rollback_version\",\"code\":\"" + editorCode
                    + "\",\"versionId\":" + versionId + "}");
        }
    }

    // isJoined — lamma yakhod joined packet
    public boolean isJoined() { return joined; }

    // isReady — ba3d history_done
    public boolean isReady()  { return ready; }

    // getRole — editor aw viewer
    public String getRole()   { return role; }
}

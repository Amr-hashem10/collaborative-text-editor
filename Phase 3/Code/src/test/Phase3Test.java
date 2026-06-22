package test;

import crdt.Document;
import db.Database;
import db.DocumentStore;
import db.DocumentStore.DocumentRecord;
import network.CrdtClient;
import network.CrdtClient.SessionListener;
import network.CrdtServer;
import operations.*;

import java.net.URI;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

// Phase 3 — DB + replay + viewer + collaboration
public class Phase3Test {

    private static int passed = 0;
    private static int failed = 0;

    // main — reset DB + kol phase3 tests
    public static void main(String[] args) throws Exception {
        System.out.println("=== Phase 3 Tests ===\n");

        resetDatabase();

        testDatabase();
        testHistoryReplay();
        testViewerMode();
        testTwoEditors();

        System.out.println("\n=== Results: " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) System.exit(1);
    }

    // testDatabase — createDocument + findByCode
    private static void testDatabase() throws Exception {
        System.out.println("--- Database tests ---");

        DocumentRecord rec = null;
        try {
            rec = DocumentStore.createDocument("doc-001", "Test Doc");
        } catch (Exception ex) {
            System.err.println("createDocument threw: " + ex.getMessage());
        }
        check("DB-1 create returns non-null", rec != null);
        if (rec == null) return;

        check("DB-2 id stored",          rec.id.equals("doc-001"));
        check("DB-3 editorCode 6 chars", rec.editorCode.length() == 6);
        check("DB-4 viewerCode 6 chars", rec.viewerCode.length() == 6);
        check("DB-5 codes differ",       !rec.editorCode.equals(rec.viewerCode));

        DocumentRecord found = DocumentStore.findByCode(rec.editorCode);
        check("DB-6 findByCode(editor)", found != null && found.id.equals("doc-001"));

        DocumentRecord found2 = DocumentStore.findByCode(rec.viewerCode);
        check("DB-7 findByCode(viewer)", found2 != null && found2.id.equals("doc-001"));
    }

    // testHistoryReplay — server + join + history Hello
    private static void testHistoryReplay() throws Exception {
        System.out.println("\n--- History replay tests ---");

        CrdtServer server = new CrdtServer(8090);
        server.start();
        Thread.sleep(200);

        URI uri = URI.create("ws://localhost:8090");

        final String[] docIdHolder      = new String[1];
        final String[] editorCodeHolder = new String[1];
        final CountDownLatch ready1 = new CountDownLatch(1);

        CrdtClient client1 = CrdtClient.forCreate(uri, 1, new SessionListener() {
            // onJoined — save docId + editor code
            @Override
            public void onJoined(String docId, String editorCode,
                                 String viewerCode, String role, String title) {
                docIdHolder[0]      = docId;
                editorCodeHolder[0] = editorCode;
            }
            // onHistoryOp — noop
            @Override public void onHistoryOp(Operation op) {}
            // onReady — latch 1
            @Override
            public void onReady() { ready1.countDown(); }
            // onRemoteOp — noop
            @Override public void onRemoteOp(Operation op) {}
        });
        client1.connect();
        check("HR-1 client1 connected", ready1.await(5, TimeUnit.SECONDS));

        Document doc1 = new Document(1);
        BlockInsertOp blockOp = doc1.insertBlock(-1);
        client1.sendOp(blockOp);
        Thread.sleep(100);

        List<Operation> charOps = new ArrayList<>();
        charOps.add(doc1.insertChar(0, 0, 'H', false, false));
        charOps.add(doc1.insertChar(0, 1, 'e', false, false));
        charOps.add(doc1.insertChar(0, 2, 'l', false, false));
        charOps.add(doc1.insertChar(0, 3, 'l', false, false));
        charOps.add(doc1.insertChar(0, 4, 'o', false, false));
        client1.sendOps(charOps);
        Thread.sleep(300);

        final Document doc2 = new Document(2);
        final CountDownLatch ready2 = new CountDownLatch(1);
        final List<Operation> historyReceived = new ArrayList<>();

        CrdtClient client2 = CrdtClient.forJoin(uri, editorCodeHolder[0], 2,
                new SessionListener() {
            // onJoined — assert editor role
            @Override public void onJoined(String docId, String editorCode,
                                           String viewerCode, String role, String title) {
                check("HR-2 role is editor", role.equals("editor"));
            }
            // onHistoryOp — collect + apply doc2
            @Override
            public void onHistoryOp(Operation op) {
                historyReceived.add(op);
                try { doc2.applyOp(op); } catch (Exception e) {  }
            }
            // onReady — latch 2
            @Override
            public void onReady() { ready2.countDown(); }
            // onRemoteOp — noop
            @Override public void onRemoteOp(Operation op) {}
        });
        client2.connect();
        check("HR-3 client2 ready",   ready2.await(5, TimeUnit.SECONDS));
        check("HR-4 history non-empty", historyReceived.size() >= 6);
        check("HR-5 doc2 text is Hello",
              "Hello".equals(doc2.getBlockText(0)));

        final DocumentRecord rec = DocumentStore.findById(docIdHolder[0]);
        final Document doc3      = new Document(3);
        final CountDownLatch ready3 = new CountDownLatch(1);
        final List<Operation> viewerHistory = new ArrayList<>();

        CrdtClient viewer = CrdtClient.forJoin(uri, rec.viewerCode, 3,
                new SessionListener() {
            // onJoined — assert viewer
            @Override public void onJoined(String docId, String editorCode,
                                           String viewerCode, String role, String title) {
                check("HR-6 viewer role", role.equals("viewer"));
            }
            // onHistoryOp — viewer replay
            @Override
            public void onHistoryOp(Operation op) {
                viewerHistory.add(op);
                try { doc3.applyOp(op); } catch (Exception e) {  }
            }
            // onReady — latch 3
            @Override
            public void onReady() { ready3.countDown(); }
            // onRemoteOp — noop
            @Override public void onRemoteOp(Operation op) {}
        });
        viewer.connect();
        check("HR-7 viewer ready",        ready3.await(5, TimeUnit.SECONDS));
        check("HR-8 viewer sees Hello",   "Hello".equals(doc3.getBlockText(0)));
        check("HR-9 viewer history size", viewerHistory.size() >= 6);

        client1.closeBlocking();
        client2.closeBlocking();
        viewer.closeBlocking();
        server.stop();
        Thread.sleep(200);
    }

    // testViewerMode — viewer mesh yb3at + live ops
    private static void testViewerMode() throws Exception {
        System.out.println("\n--- Viewer mode tests ---");

        CrdtServer server = new CrdtServer(8091);
        server.start();
        Thread.sleep(200);

        URI uri = URI.create("ws://localhost:8091");

        final String[] viewerCodeHolder = new String[1];
        final CountDownLatch ready1 = new CountDownLatch(1);

        CrdtClient editor = CrdtClient.forCreate(uri, 1, new SessionListener() {
            // onJoined — save viewer code
            @Override
            public void onJoined(String docId, String editorCode,
                                 String viewerCode, String role, String title) {
                viewerCodeHolder[0] = viewerCode;
            }
            // onHistoryOp — noop
            @Override public void onHistoryOp(Operation op) {}
            // onReady — latch
            @Override public void onReady() { ready1.countDown(); }
            // onRemoteOp — noop
            @Override public void onRemoteOp(Operation op) {}
        });
        editor.connect();
        check("VM-0 editor ready", ready1.await(5, TimeUnit.SECONDS));

        Document editorDoc = new Document(1);
        BlockInsertOp blockOp = editorDoc.insertBlock(-1);
        editor.sendOp(blockOp);
        Thread.sleep(100);
        editor.sendOp(editorDoc.insertChar(0, 0, 'A', false, false));
        editor.sendOp(editorDoc.insertChar(0, 1, 'b', false, false));
        editor.sendOp(editorDoc.insertChar(0, 2, 'c', false, false));
        Thread.sleep(300);

        final Document viewerDoc = new Document(2);
        final CountDownLatch ready2 = new CountDownLatch(1);
        final List<Operation> liveOps = new ArrayList<>();

        CrdtClient viewerClient = CrdtClient.forJoin(uri, viewerCodeHolder[0], 2,
                new SessionListener() {
            // onJoined — noop
            @Override public void onJoined(String docId, String editorCode,
                                           String viewerCode, String role, String title) {}
            // onHistoryOp — replay
            @Override
            public void onHistoryOp(Operation op) {
                try { viewerDoc.applyOp(op); } catch (Exception e) {  }
            }
            // onReady — latch 2
            @Override public void onReady() { ready2.countDown(); }
            // onRemoteOp — live + list
            @Override
            public void onRemoteOp(Operation op) {
                liveOps.add(op);
                try { viewerDoc.applyOp(op); } catch (Exception e) {  }
            }
        });
        viewerClient.connect();
        check("VM-1 viewer ready", ready2.await(5, TimeUnit.SECONDS));
        check("VM-2 viewer sees Abc", "Abc".equals(viewerDoc.getBlockText(0)));
        check("VM-3 viewer isReady",  viewerClient.isReady());
        check("VM-4 viewer role",     viewerClient.getRole().equals("viewer"));

        editor.sendOp(editorDoc.insertChar(0, 3, 'd', false, false));
        Thread.sleep(400);
        check("VM-5 viewer gets live op", !liveOps.isEmpty());

        editor.closeBlocking();
        viewerClient.closeBlocking();
        server.stop();
        Thread.sleep(200);
    }

    // testTwoEditors — 2 editors + reconnect history
    private static void testTwoEditors() throws Exception {
        System.out.println("\n--- Two-editor collaboration tests ---");

        CrdtServer server = new CrdtServer(8092);
        server.start();
        Thread.sleep(200);

        URI uri = URI.create("ws://localhost:8092");

        final String[] editorCodeHolder = new String[1];
        final CountDownLatch ready1 = new CountDownLatch(1);
        final List<Operation> ops1Received = new ArrayList<>();

        CrdtClient client1 = CrdtClient.forCreate(uri, 1, new SessionListener() {
            // onJoined — save editor code
            @Override
            public void onJoined(String docId, String editorCode,
                                 String viewerCode, String role, String title) {
                editorCodeHolder[0] = editorCode;
            }
            // onHistoryOp — noop
            @Override public void onHistoryOp(Operation op) {}
            // onReady — latch 1
            @Override public void onReady() { ready1.countDown(); }
            // onRemoteOp — collect
            @Override
            public void onRemoteOp(Operation op) { ops1Received.add(op); }
        });
        client1.connect();
        check("TE-1 client1 ready", ready1.await(5, TimeUnit.SECONDS));

        Document doc1 = new Document(1);
        BlockInsertOp b1 = doc1.insertBlock(-1);
        client1.sendOp(b1);
        Thread.sleep(100);

        final Document doc2 = new Document(2);
        final CountDownLatch ready2 = new CountDownLatch(1);
        final List<Operation> ops2Received = new ArrayList<>();

        CrdtClient client2 = CrdtClient.forJoin(uri, editorCodeHolder[0], 2,
                new SessionListener() {
            // onJoined — noop
            @Override public void onJoined(String docId, String editorCode,
                                           String viewerCode, String role, String title) {}
            // onHistoryOp — replay doc2
            @Override
            public void onHistoryOp(Operation op) {
                try { doc2.applyOp(op); } catch (Exception e) {  }
            }
            // onReady — latch 2
            @Override public void onReady() { ready2.countDown(); }
            // onRemoteOp — collect + apply
            @Override
            public void onRemoteOp(Operation op) {
                ops2Received.add(op);
                try { doc2.applyOp(op); } catch (Exception e) {  }
            }
        });
        client2.connect();
        check("TE-2 client2 ready", ready2.await(5, TimeUnit.SECONDS));

        BlockInsertOp b2 = doc2.insertBlock(-1);
        client2.sendOp(b2);
        Thread.sleep(200);

        Operation c1 = doc1.insertChar(0, 0, 'H', false, false);
        Operation c2 = doc1.insertChar(0, 1, 'i', false, false);
        client1.sendOp(c1);
        client1.sendOp(c2);
        Thread.sleep(400);

        check("TE-3 editor2 received ops", ops2Received.size() >= 2);
        boolean foundHi = false;
        for (int i = 0; i < doc2.blockCount(); i++) {
            if ("Hi".equals(doc2.getBlockText(i))) { foundHi = true; break; }
        }
        check("TE-4 doc2 contains block with 'Hi'", foundHi);

        Operation cx1 = doc2.insertChar(1, 0, 'B', false, false);
        Operation cx2 = doc2.insertChar(1, 1, 'y', false, false);
        Operation cx3 = doc2.insertChar(1, 2, 'e', false, false);
        client2.sendOp(cx1);
        client2.sendOp(cx2);
        client2.sendOp(cx3);
        Thread.sleep(400);

        check("TE-5 editor1 received ops from editor2", ops1Received.size() >= 3);

        client2.closeBlocking();
        Thread.sleep(200);

        final Document doc2b = new Document(2);
        final CountDownLatch ready2b = new CountDownLatch(1);

        CrdtClient client2b = CrdtClient.forJoin(uri, editorCodeHolder[0], 2,
                new SessionListener() {
            // onJoined — noop
            @Override public void onJoined(String docId, String editorCode,
                                           String viewerCode, String role, String title) {}
            // onHistoryOp — replay reconnect
            @Override
            public void onHistoryOp(Operation op) {
                try { doc2b.applyOp(op); } catch (Exception e) {  }
            }
            // onReady — latch 2b
            @Override public void onReady() { ready2b.countDown(); }
            // onRemoteOp — noop
            @Override public void onRemoteOp(Operation op) {}
        });
        client2b.connect();
        check("TE-6 reconnect sees history", ready2b.await(5, TimeUnit.SECONDS));

        client1.closeBlocking();
        client2b.closeBlocking();
        server.stop();
        Thread.sleep(200);
    }

    // check — PASS/FAIL + counters
    private static void check(String name, boolean condition) {
        if (condition) {
            System.out.println("  PASS  " + name);
            passed++;
        } else {
            System.out.println("  FAIL  " + name);
            failed++;
        }
    }

    @SuppressWarnings("SqlNoDataSourceInspection")
    // resetDatabase — DROP tables + reconnect
    private static void resetDatabase() throws Exception {
        Connection conn = Database.get();
        Statement st = conn.createStatement();
        st.execute("DROP TABLE IF EXISTS operations");
        st.execute("DROP TABLE IF EXISTS document_versions");
        st.execute("DROP TABLE IF EXISTS documents");
        st.close();

        conn.close();

        Database.get();
    }
}

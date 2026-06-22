package test;

import crdt.Document;
import network.CrdtClient;
import network.CrdtClient.SessionListener;
import network.CrdtServer;
import operations.*;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

// Phase 2 — server + 2 clients + serializer
public class NetworkTest {

    static int pass = 0, fail = 0;

    // main — yshaghal network tests
    public static void main(String[] args) throws Exception {
        System.out.println("===============================================");
        System.out.println("  Phase 2 -- Network + Serialization Tests");
        System.out.println("===============================================");

        testSerializerRoundtrip();
        testTwoClientCollaboration();
        testUndoRedoBroadcast();

        System.out.println("===============================================");
        System.out.printf("  Results: %d passed, %d failed%n", pass, fail);
        System.out.println("===============================================");
    }

    // testSerializerRoundtrip — Gson kol el ops
    static void testSerializerRoundtrip() {
        System.out.println("\n-- Serializer roundtrip --");

        Document doc = new Document(1);
        BlockInsertOp blockOp = doc.insertBlock(-1);

        CharInsertOp ins = doc.insertChar(0, 0, 'X', true, false);
        Operation rt = OperationSerializer.fromJson(OperationSerializer.toJson(ins));
        check("CharInsertOp roundtrip type",    rt instanceof CharInsertOp);
        CharInsertOp rtIns = (CharInsertOp) rt;
        check("CharInsertOp value",             rtIns.value() == 'X');
        check("CharInsertOp bold",              rtIns.bold());
        check("CharInsertOp blockId matches",   rtIns.blockId().equals(ins.blockId()));
        check("CharInsertOp charId matches",    rtIns.charId().equals(ins.charId()));
        check("CharInsertOp parentId null",     rtIns.parentId() == null);

        CharInsertOp ins2 = doc.insertChar(0, 1, 'Y', false, true);
        CharInsertOp rt2  = (CharInsertOp) OperationSerializer.fromJson(OperationSerializer.toJson(ins2));
        check("CharInsertOp parentId non-null", rt2.parentId() != null);
        check("CharInsertOp parentId matches",  rt2.parentId().equals(ins2.parentId()));

        CharDeleteOp del   = doc.deleteChar(0, 0);
        Operation    rtDel = OperationSerializer.fromJson(OperationSerializer.toJson(del));
        check("CharDeleteOp roundtrip type",    rtDel instanceof CharDeleteOp);
        check("CharDeleteOp charId matches",    ((CharDeleteOp) rtDel).charId().equals(del.charId()));

        Operation rtBi = OperationSerializer.fromJson(OperationSerializer.toJson(blockOp));
        check("BlockInsertOp roundtrip type",   rtBi instanceof BlockInsertOp);
        check("BlockInsertOp parentId null",    ((BlockInsertOp) rtBi).parentId() == null);

        BlockInsertOp bi2   = doc.insertBlock(0);
        Operation     rtBi2 = OperationSerializer.fromJson(OperationSerializer.toJson(bi2));
        check("BlockInsertOp parentId non-null", ((BlockInsertOp) rtBi2).parentId() != null);

        doc.insertChar(0, 0, 'A', false, false);
        doc.insertChar(0, 1, 'B', false, false);
        List<Operation> splitOps = doc.splitBlock(0, 0);
        BlockSplitOp sp   = (BlockSplitOp) splitOps.get(0);
        Operation    rtSp = OperationSerializer.fromJson(OperationSerializer.toJson(sp));
        check("BlockSplitOp roundtrip type",    rtSp instanceof BlockSplitOp);
        check("BlockSplitOp originalBlockId",   ((BlockSplitOp) rtSp).originalBlockId().equals(sp.originalBlockId()));

        Document doc2 = new Document(2);
        doc2.insertBlock(-1);
        doc2.insertChar(0, 0, 'Z', false, false);
        List<FormatOp> fmts  = doc2.formatRange(0, 0, 0, true, true);
        FormatOp       fmt   = fmts.get(0);
        Operation      rtFmt = OperationSerializer.fromJson(OperationSerializer.toJson(fmt));
        check("FormatOp roundtrip type",        rtFmt instanceof FormatOp);
        check("FormatOp bold",                  ((FormatOp) rtFmt).bold());
        check("FormatOp italic",                ((FormatOp) rtFmt).italic());

        List<Operation> list   = new ArrayList<Operation>(splitOps);
        List<Operation> rtList = OperationSerializer.listFromJson(OperationSerializer.listToJson(list));
        check("List roundtrip size matches",    rtList.size() == list.size());
        check("List[0] is BlockSplitOp",        rtList.get(0) instanceof BlockSplitOp);
    }

    // testTwoClientCollaboration — 2 clients + Hello World
    static void testTwoClientCollaboration() throws Exception {
        System.out.println("\n-- Two-client collaboration --");

        CrdtServer server = new CrdtServer(18887);
        server.start();
        Thread.sleep(200);

        URI uri = URI.create("ws://localhost:18887");

        Document docA = new Document(1);
        Document docB = new Document(2);

        final String[]      editorCodeHolder = new String[1];
        final CountDownLatch readyA          = new CountDownLatch(1);

        CrdtClient clientA = CrdtClient.forCreate(uri, 1, new SessionListener() {
            // onJoined — save editor code
            @Override
            public void onJoined(String docId, String editorCode,
                                 String viewerCode, String role, String title) {
                editorCodeHolder[0] = editorCode;
            }
            // onHistoryOp — noop
            @Override public void onHistoryOp(Operation op) {}
            // onReady — latch A
            @Override public void onReady()                  { readyA.countDown(); }
            // onRemoteOp — noop
            @Override public void onRemoteOp(Operation op)   {}
        });
        clientA.connect();
        check("Client A ready", readyA.await(5, TimeUnit.SECONDS));

        BlockInsertOp blockOp = docA.insertBlock(-1);
        clientA.sendOp(blockOp);
        Thread.sleep(100);

        String word = "Hello";
        for (int i = 0; i < word.length(); i++) {
            clientA.sendOp(docA.insertChar(0, i, word.charAt(i), false, false));
        }
        Thread.sleep(300);

        final CountDownLatch latchB = new CountDownLatch(1);

        CrdtClient clientB = CrdtClient.forJoin(uri, editorCodeHolder[0], 2,
                new SessionListener() {
            // onJoined — noop
            @Override public void onJoined(String docId, String editorCode,
                                           String viewerCode, String role, String title) {}
            // onHistoryOp — replay fel docB
            @Override
            public void onHistoryOp(Operation op) {
                docB.applyOp(op);
            }
            // onReady — latch B
            @Override public void onReady()                  { latchB.countDown(); }
            // onRemoteOp — live apply
            @Override public void onRemoteOp(Operation op)   { docB.applyOp(op); }
        });
        clientB.connect();
        check("Client B received history + ready", latchB.await(5, TimeUnit.SECONDS));
        check("Client A text = 'Hello'", "Hello".equals(docA.getBlockText(0)));
        check("Client B text = 'Hello'", "Hello".equals(docB.getBlockText(0)));

        final List<Operation> receivedByA = new ArrayList<Operation>();
        final CountDownLatch  latchA2     = new CountDownLatch(6);

        CrdtClient clientA2 = CrdtClient.forJoin(uri, editorCodeHolder[0], 1,
                new SessionListener() {
            // onJoined — noop
            @Override public void onJoined(String docId, String editorCode,
                                           String viewerCode, String role, String title) {}
            // onHistoryOp — noop
            @Override public void onHistoryOp(Operation op) {}
            // onReady — noop
            @Override public void onReady()                  {}
            // onRemoteOp — collect + apply + latch
            @Override
            public void onRemoteOp(Operation op) {
                synchronized (receivedByA) { receivedByA.add(op); }
                docA.applyOp(op);
                latchA2.countDown();
            }
        });
        clientA2.connect();
        Thread.sleep(300);

        String word2 = " World";
        for (int i = 0; i < word2.length(); i++) {
            clientB.sendOp(docB.insertChar(0, 5 + i, word2.charAt(i), false, false));
        }

        boolean received2 = latchA2.await(5, TimeUnit.SECONDS);
        check("Client A received ' World' ops (" + receivedByA.size() + "/6)",
              received2);
        check("Client A text = 'Hello World'", "Hello World".equals(docA.getBlockText(0)));
        check("Client B text = 'Hello World'", "Hello World".equals(docB.getBlockText(0)));

        clientA.close(); clientA2.close(); clientB.close();
        server.stop(500);
        Thread.sleep(300);
    }

    // testUndoRedoBroadcast — undo inverse ywsal lel B
    static void testUndoRedoBroadcast() throws Exception {
        System.out.println("\n-- Undo / Redo broadcast --");

        CrdtServer server = new CrdtServer(18888);
        server.start();
        Thread.sleep(200);

        URI uri = URI.create("ws://localhost:18888");

        Document docA = new Document(1);
        Document docB = new Document(2);

        final String[]      editorCodeHolder = new String[1];
        final CountDownLatch readyA          = new CountDownLatch(1);

        CrdtClient clientA = CrdtClient.forCreate(uri, 1, new SessionListener() {
            // onJoined — save editor code
            @Override
            public void onJoined(String docId, String editorCode,
                                 String viewerCode, String role, String title) {
                editorCodeHolder[0] = editorCode;
            }
            // onHistoryOp — noop
            @Override public void onHistoryOp(Operation op) {}
            // onReady — latch
            @Override public void onReady()                  { readyA.countDown(); }
            // onRemoteOp — noop
            @Override public void onRemoteOp(Operation op)   {}
        });
        clientA.connect();
        readyA.await(5, TimeUnit.SECONDS);

        BlockInsertOp blockOp = docA.insertBlock(-1);
        clientA.sendOp(blockOp);
        Thread.sleep(100);

        String text = "Hi!";
        for (int i = 0; i < text.length(); i++) {
            clientA.sendOp(docA.insertChar(0, i, text.charAt(i), false, false));
        }
        Thread.sleep(300);

        final CountDownLatch undoLatch = new CountDownLatch(1);

        CrdtClient clientB = CrdtClient.forJoin(uri, editorCodeHolder[0], 2,
                new SessionListener() {
            // onJoined — noop
            @Override public void onJoined(String docId, String editorCode,
                                           String viewerCode, String role, String title) {}
            // onHistoryOp — replay
            @Override
            public void onHistoryOp(Operation op) {
                docB.applyOp(op);
            }
            // onReady — noop
            @Override public void onReady() {}
            // onRemoteOp — apply + latch 3la delete
            @Override
            public void onRemoteOp(Operation op) {
                docB.applyOp(op);
                if (op instanceof CharDeleteOp) undoLatch.countDown();
            }
        });
        clientB.connect();
        Thread.sleep(500);

        check("Before undo -- A text = 'Hi!'", "Hi!".equals(docA.getBlockText(0)));
        check("Before undo -- B text = 'Hi!'", "Hi!".equals(docB.getBlockText(0)));

        List<Operation> inverses = docA.undo();
        for (Operation inv : inverses) {
            clientA.sendOp(inv);
        }

        undoLatch.await(4, TimeUnit.SECONDS);
        check("After undo -- A text = 'Hi'", "Hi".equals(docA.getBlockText(0)));
        check("After undo -- B text = 'Hi'", "Hi".equals(docB.getBlockText(0)));

        clientA.close(); clientB.close();
        server.stop(500);
        Thread.sleep(300);
    }

    // check — print PASS/FAIL + counter
    static void check(String label, boolean condition) {
        String mark = condition ? "[PASS]" : "[FAIL]";
        System.out.printf("  %s %s%n", mark, label);
        if (condition) pass++; else fail++;
    }
}

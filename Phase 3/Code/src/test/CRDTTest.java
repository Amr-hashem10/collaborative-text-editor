package test;

import crdt.*;
import operations.*;

import java.util.List;

// Phase 1 tests — Document + CRDT bla network
public class CRDTTest {

    private static int passed = 0;
    private static int failed = 0;

    // assertEquals — string compare + pass/fail count
    private static void assertEquals(String expected, String actual, String name) {
        if (expected.equals(actual)) {
            System.out.println("[PASS] " + name);
            passed++;
        } else {
            System.out.printf("[FAIL] %-55s  expected='%s'  got='%s'%n",
                    name, expected, actual);
            failed++;
        }
    }

    // assertTrue — bool + pass/fail count
    private static void assertTrue(boolean cond, String name) {
        if (cond) { System.out.println("[PASS] " + name); passed++; }
        else      { System.out.println("[FAIL] " + name); failed++; }
    }

    // testBasicInsert — insert chars -> Hi!
    private static void testBasicInsert() {
        Document doc = new Document(1);
        doc.insertBlock(-1);

        doc.insertChar(0, 0, 'H', false, false);
        doc.insertChar(0, 1, 'i', false, false);
        doc.insertChar(0, 2, '!', false, false);

        assertEquals("Hi!", doc.getBlockText(0), "basic insert — 'Hi!'");
    }

    // testBasicDelete — delete middle/first/last
    private static void testBasicDelete() {
        Document doc = new Document(1);
        doc.insertBlock(-1);

        doc.insertChar(0, 0, 'A', false, false);
        doc.insertChar(0, 1, 'B', false, false);
        doc.insertChar(0, 2, 'C', false, false);
        assertEquals("ABC", doc.getBlockText(0), "delete setup — 'ABC'");

        doc.deleteChar(0, 1);
        assertEquals("AC", doc.getBlockText(0), "delete middle char — 'AC'");

        doc.deleteChar(0, 0);
        assertEquals("C", doc.getBlockText(0), "delete first char — 'C'");

        doc.deleteChar(0, 0);
        assertEquals("", doc.getBlockText(0), "delete last char — ''");
    }

    // testInsertAfterTombstone — parent tombstone + insert
    private static void testInsertAfterTombstone() {

        CharacterCRDT crdt = new CharacterCRDT();

        CrdtId idA = new CrdtId(1, 1);
        CrdtId idB = new CrdtId(1, 2);
        CrdtId idC = new CrdtId(1, 3);
        CrdtId idD = new CrdtId(1, 4);

        crdt.insert(idA, null,  'A', false, false);
        crdt.insert(idB, idA,   'B', false, false);
        crdt.insert(idC, idB,   'C', false, false);

        assertEquals("ABC", crdt.getText(), "tombstone-parent setup — 'ABC'");

        crdt.delete(idB);
        assertEquals("AC", crdt.getText(), "after delete B — 'AC'");

        crdt.insert(idD, idB, 'D', false, false);
        assertEquals("ADC", crdt.getText(), "insert after tombstone — 'ADC'");
    }

    // testConcurrentInsertHigherClockWins — tie clock user order
    private static void testConcurrentInsertHigherClockWins() {

        CharacterCRDT crdt = new CharacterCRDT();
        crdt.insert(new CrdtId(1, 1), null, 'A', false, false);
        crdt.insert(new CrdtId(2, 2), null, 'B', false, false);

        assertEquals("BA", crdt.getText(), "concurrent insert — higher clock first ('BA')");

        CharacterCRDT crdt2 = new CharacterCRDT();
        crdt2.insert(new CrdtId(2, 2), null, 'B', false, false);
        crdt2.insert(new CrdtId(1, 1), null, 'A', false, false);

        assertEquals("BA", crdt2.getText(), "concurrent insert — convergence regardless of arrival order");
    }

    // testConcurrentInsertSameClockLowerUserWins — same clock tie-break
    private static void testConcurrentInsertSameClockLowerUserWins() {

        CharacterCRDT crdt1 = new CharacterCRDT();
        crdt1.insert(new CrdtId(1, 5), null, 'A', false, false);
        crdt1.insert(new CrdtId(2, 5), null, 'B', false, false);
        assertEquals("AB", crdt1.getText(), "same-clock tie-break — lower userId first ('AB') order1");

        CharacterCRDT crdt2 = new CharacterCRDT();
        crdt2.insert(new CrdtId(2, 5), null, 'B', false, false);
        crdt2.insert(new CrdtId(1, 5), null, 'A', false, false);
        assertEquals("AB", crdt2.getText(), "same-clock tie-break — lower userId first ('AB') order2");
    }

    // testMultiUserConvergence — merge 2 CRDTs
    private static void testMultiUserConvergence() {

        CrdtId a = new CrdtId(1, 1), b = new CrdtId(1, 2), c = new CrdtId(1, 3);
        CrdtId x = new CrdtId(2, 3), y = new CrdtId(2, 4);

        CharacterCRDT u1 = new CharacterCRDT();
        u1.insert(a, null, 'A', false, false);
        u1.insert(b, a,    'B', false, false);
        u1.insert(c, b,    'C', false, false);
        u1.insert(x, null, 'X', false, false);
        u1.insert(y, x,    'Y', false, false);

        CharacterCRDT u2 = new CharacterCRDT();
        u2.insert(x, null, 'X', false, false);
        u2.insert(y, x,    'Y', false, false);
        u2.insert(a, null, 'A', false, false);
        u2.insert(b, a,    'B', false, false);
        u2.insert(c, b,    'C', false, false);

        assertTrue(u1.getText().equals(u2.getText()),
                "multi-user convergence — same text: '" + u1.getText() + "'");
        assertEquals("XYABC", u1.getText(), "multi-user result — 'XYABC'");
    }

    // testBlockInsert — blocks fel Document
    private static void testBlockInsert() {
        Document doc = new Document(1);

        doc.insertBlock(-1);
        doc.insertBlock(0);
        doc.insertBlock(1);

        assertTrue(doc.blockCount() == 3, "block insert — 3 blocks");

        doc.insertChar(0, 0, 'A', false, false);
        doc.insertChar(1, 0, 'B', false, false);
        doc.insertChar(2, 0, 'C', false, false);

        assertEquals("A", doc.getBlockText(0), "block 0 text — 'A'");
        assertEquals("B", doc.getBlockText(1), "block 1 text — 'B'");
        assertEquals("C", doc.getBlockText(2), "block 2 text — 'C'");
    }

    // testBlockDelete — delete block
    private static void testBlockDelete() {
        Document doc = new Document(1);
        doc.insertBlock(-1);
        doc.insertBlock(0);
        doc.insertBlock(1);

        doc.insertChar(0, 0, 'A', false, false);
        doc.insertChar(1, 0, 'B', false, false);
        doc.insertChar(2, 0, 'C', false, false);

        doc.deleteBlock(1);

        assertTrue(doc.blockCount() == 2, "block delete — 2 blocks remain");
        assertEquals("A", doc.getBlockText(0), "after block delete — block 0 still 'A'");
        assertEquals("C", doc.getBlockText(1), "after block delete — block 1 (was 2) is 'C'");
    }

    // testBlockSplit — split paragraph
    private static void testBlockSplit() {

        Document doc = new Document(1);
        doc.insertBlock(-1);
        for (char ch : "HELLO".toCharArray()) {
            doc.insertChar(0, doc.getBlockCRDT().getVisibleBlock(0).content.size(), ch, false, false);
        }
        assertEquals("HELLO", doc.getBlockText(0), "split setup — 'HELLO'");

        doc.splitBlock(0, 1);

        assertTrue(doc.blockCount() == 2, "after split — 2 blocks");
        assertEquals("HE",  doc.getBlockText(0), "split — block 0 is 'HE'");
        assertEquals("LLO", doc.getBlockText(1), "split — block 1 is 'LLO'");
    }

    // testBlockMerge — merge blocks
    private static void testBlockMerge() {

        Document doc = new Document(1);
        doc.insertBlock(-1);
        doc.insertBlock(0);

        for (char ch : "HE".toCharArray())
            doc.insertChar(0, doc.getBlockCRDT().getVisibleBlock(0).content.size(), ch, false, false);
        for (char ch : "LLO".toCharArray())
            doc.insertChar(1, doc.getBlockCRDT().getVisibleBlock(1).content.size(), ch, false, false);

        assertEquals("HE",  doc.getBlockText(0), "merge setup — block 0 'HE'");
        assertEquals("LLO", doc.getBlockText(1), "merge setup — block 1 'LLO'");

        doc.mergeBlocks(0, 1);

        assertTrue(doc.blockCount() == 1, "after merge — 1 block");
        assertEquals("HELLO", doc.getBlockText(0), "after merge — block 0 is 'HELLO'");
    }

    // testApplyRemoteOps — applyOp men user tany
    private static void testApplyRemoteOps() {

        Document doc1 = new Document(1);
        Document doc2 = new Document(2);

        BlockInsertOp bOp = doc1.insertBlock(-1);
        doc2.applyOp(bOp);

        CharInsertOp aOp = doc1.insertChar(0, 0, 'A', false, false);
        CharInsertOp bCharOp = doc1.insertChar(0, 1, 'B', false, false);

        CharInsertOp xOp = doc2.insertChar(0, 0, 'X', false, false);

        doc2.applyOp(aOp);
        doc2.applyOp(bCharOp);
        doc1.applyOp(xOp);

        assertTrue(doc1.getBlockText(0).equals(doc2.getBlockText(0)),
                "remote ops — both docs converge: '" + doc1.getBlockText(0) + "'");
    }

    // testFormatting — FormatOp range
    private static void testFormatting() {
        Document doc = new Document(1);
        doc.insertBlock(-1);

        doc.insertChar(0, 0, 'H', false, false);
        doc.insertChar(0, 1, 'i', false, false);
        doc.insertChar(0, 2, '!', false, false);

        doc.formatRange(0, 0, 1, true, false);

        BlockNode block = doc.getBlockCRDT().getVisibleBlock(0);
        List<CharacterNode> visible = block.content.getVisibleNodes();

        assertTrue(visible.get(0).bold   && !visible.get(0).italic, "format — char 0 is bold");
        assertTrue(visible.get(1).bold   && !visible.get(1).italic, "format — char 1 is bold");
        assertTrue(!visible.get(2).bold  && !visible.get(2).italic, "format — char 2 is not bold");
    }

    // testUndoRedo — stack inverse
    private static void testUndoRedo() {
        Document doc = new Document(1);
        doc.insertBlock(-1);

        doc.insertChar(0, 0, 'A', false, false);
        doc.insertChar(0, 1, 'B', false, false);
        doc.insertChar(0, 2, 'C', false, false);
        assertEquals("ABC", doc.getBlockText(0), "undo/redo setup — 'ABC'");

        doc.undo();
        assertEquals("AB", doc.getBlockText(0), "after undo — 'AB'");

        doc.undo();
        assertEquals("A", doc.getBlockText(0), "after 2nd undo — 'A'");

        doc.redo();
        assertEquals("AB", doc.getBlockText(0), "after redo — 'AB'");

        doc.redo();
        assertEquals("ABC", doc.getBlockText(0), "after 2nd redo — 'ABC'");
    }

    // testMoveBlock — reorder blocks
    private static void testMoveBlock() {

        Document doc = new Document(1);
        doc.insertBlock(-1);
        doc.insertBlock(0);
        doc.insertBlock(1);

        for (char c : "AAA".toCharArray()) doc.insertChar(0, doc.getBlockText(0).length(), c, false, false);
        for (char c : "BBB".toCharArray()) doc.insertChar(1, doc.getBlockText(1).length(), c, false, false);
        for (char c : "CCC".toCharArray()) doc.insertChar(2, doc.getBlockText(2).length(), c, false, false);

        doc.moveBlock(0, 2);

        assertTrue(doc.blockCount() == 3,  "move block — still 3 blocks");
        assertEquals("BBB", doc.getBlockText(0), "move block — block 0 is now 'BBB'");
        assertEquals("CCC", doc.getBlockText(1), "move block — block 1 is now 'CCC'");
        assertEquals("AAA", doc.getBlockText(2), "move block — block 2 is now 'AAA'");

        doc.undo();
        assertTrue(doc.blockCount() == 3,  "move block undo — still 3 blocks");
        assertEquals("AAA", doc.getBlockText(0), "move block undo — block 0 restored 'AAA'");
        assertEquals("BBB", doc.getBlockText(1), "move block undo — block 1 restored 'BBB'");
        assertEquals("CCC", doc.getBlockText(2), "move block undo — block 2 restored 'CCC'");
    }

    // testCopyBlock — duplicate block
    private static void testCopyBlock() {

        Document doc = new Document(1);
        doc.insertBlock(-1);
        doc.insertBlock(0);

        for (char c : "Hi".toCharArray())  doc.insertChar(0, doc.getBlockText(0).length(), c, false, false);
        for (char c : "Bye".toCharArray()) doc.insertChar(1, doc.getBlockText(1).length(), c, false, false);

        doc.copyBlock(0, 1);

        assertTrue(doc.blockCount() == 3,  "copy block — 3 blocks after copy");
        assertEquals("Hi",  doc.getBlockText(0), "copy block — original block 0 unchanged 'Hi'");
        assertEquals("Bye", doc.getBlockText(1), "copy block — block 1 unchanged 'Bye'");
        assertEquals("Hi",  doc.getBlockText(2), "copy block — new block 2 is copy 'Hi'");

        doc.undo();
        assertTrue(doc.blockCount() == 2,  "copy block undo — back to 2 blocks");
        assertEquals("Hi",  doc.getBlockText(0), "copy block undo — block 0 still 'Hi'");
        assertEquals("Bye", doc.getBlockText(1), "copy block undo — block 1 still 'Bye'");
    }

    // testIdempotency — apply tany marra mesh bye2fl
    private static void testIdempotency() {
        Document doc = new Document(1);
        BlockInsertOp blockOp = doc.insertBlock(-1);
        CharInsertOp charOp = doc.insertChar(0, 0, 'Z', false, false);

        doc.applyOp(blockOp);
        doc.applyOp(charOp);
        doc.applyOp(charOp);

        assertTrue(doc.blockCount() == 1, "idempotency — still 1 block");
        assertEquals("Z", doc.getBlockText(0), "idempotency — still 'Z'");
    }

    // main — yshaghal kol el tests
    public static void main(String[] args) {
        System.out.println("═══════════════════════════════════════════");
        System.out.println("  Phase 1 CRDT Test Suite");
        System.out.println("═══════════════════════════════════════════");

        testBasicInsert();
        testBasicDelete();
        testInsertAfterTombstone();
        testConcurrentInsertHigherClockWins();
        testConcurrentInsertSameClockLowerUserWins();
        testMultiUserConvergence();
        testBlockInsert();
        testBlockDelete();
        testBlockSplit();
        testBlockMerge();
        testApplyRemoteOps();
        testFormatting();
        testUndoRedo();
        testMoveBlock();
        testCopyBlock();
        testIdempotency();

        System.out.println("───────────────────────────────────────────");
        System.out.printf("  Results: %d passed, %d failed%n", passed, failed);
        System.out.println("═══════════════════════════════════════════");

        if (failed > 0) System.exit(1);
    }
}

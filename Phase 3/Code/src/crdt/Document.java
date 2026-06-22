package crdt;

import operations.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// El model el ra2esy: blocks + chars + undo/redo + comments
public class Document {

    public static final int UNDO_LIMIT = 10;

    private final int userId;

    private int clock = 0;

    private final BlockCRDT blockCRDT = new BlockCRDT();

    private final java.util.Deque<List<Operation>> undoStack = new java.util.ArrayDeque<>();

    private final java.util.Deque<List<Operation>> redoStack = new java.util.ArrayDeque<>();

    private final Map<CrdtId, Comment> comments = new HashMap<>();

    private final Map<CrdtId, Set<CrdtId>> charToCommentIds = new HashMap<>();
    // charToCommentIds: 7arf -> set of comment ids 3aleh

    // Document — btbda2 el model bel userId (el clock bta3 el CRDT)
    public Document(int userId) {
        this.userId = userId;
    }

    // nextId — kol id gedeed: userId sabt + clock byezed
    private CrdtId nextId() {
        return new CrdtId(userId, ++clock);
    }

    // updateClock — lama op tigi men barra; el clock lazem yfdl a3la 3shan el ids mat5tlsh
    private void updateClock(int remoteClock) {
        clock = Math.max(clock, remoteClock) + 1;
    }

    // insertBlock — block gedeed; parent 7asb el index (negative = ta7t el root)
    public BlockInsertOp insertBlock(int afterBlockIndex) {
        CrdtId parentId = afterBlockIndex < 0
                ? null
                : blockCRDT.getParentForInsertAfter(afterBlockIndex);
        CrdtId id = nextId();
        blockCRDT.insertBlock(id, parentId);
        BlockInsertOp op = new BlockInsertOp(id, parentId);
        pushUndo(List.of(op));
        return op;
    }

    // deleteBlock — soft delete lel block fel tree
    public BlockDeleteOp deleteBlock(int blockIndex) {
        BlockNode block = requireBlock(blockIndex);
        blockCRDT.deleteBlock(block.id);
        BlockDeleteOp op = new BlockDeleteOp(block.id, clock);
        pushUndo(List.of(op));
        return op;
    }

    // splitBlock — Enter fel nos: chars tetn2al block tany + ids gededa
    public List<Operation> splitBlock(int blockIndex, int splitAfterPos) {
        List<Operation> ops = new ArrayList<>();
        BlockNode original = requireBlock(blockIndex);

        List<CharacterNode> allVisible = original.content.getVisibleNodes();
        int moveFrom = splitAfterPos + 1;

        List<CharacterNode> toMove = new ArrayList<>(
                allVisible.subList(Math.min(moveFrom, allVisible.size()), allVisible.size()));

        CrdtId newBlockId = nextId();
        blockCRDT.insertBlock(newBlockId, original.id);
        ops.add(new BlockSplitOp(original.id, newBlockId, splitAfterPos, clock));

        for (CharacterNode cn : toMove) {
            original.content.delete(cn.id);
            ops.add(new CharDeleteOp(original.id, cn.id, clock));
        }

        BlockNode newBlock = blockCRDT.getBlock(newBlockId);
        CrdtId prevId = null;
        for (CharacterNode cn : toMove) {
            CrdtId newCharId = nextId();
            newBlock.content.insert(newCharId, prevId, cn.value, cn.bold, cn.italic);
            ops.add(new CharInsertOp(newBlockId, newCharId, prevId, cn.value, cn.bold, cn.italic));
            prevId = newCharId;
        }

        pushUndo(ops);
        return ops;
    }

    // mergeBlocks — y7ot block2 fel a5er block1 w yms7 block2
    public List<Operation> mergeBlocks(int block1Index, int block2Index) {
        List<Operation> ops = new ArrayList<>();
        BlockNode block1 = requireBlock(block1Index);
        BlockNode block2 = requireBlock(block2Index);

        List<CharacterNode> block2Chars = new ArrayList<>(block2.content.getVisibleNodes());

        List<CharacterNode> block1Chars = block1.content.getVisibleNodes();
        CrdtId prevId = block1Chars.isEmpty() ? null : block1Chars.get(block1Chars.size() - 1).id;

        for (CharacterNode cn : block2Chars) {
            block2.content.delete(cn.id);
            ops.add(new CharDeleteOp(block2.id, cn.id, clock));
        }

        for (CharacterNode cn : block2Chars) {
            CrdtId newCharId = nextId();
            block1.content.insert(newCharId, prevId, cn.value, cn.bold, cn.italic);
            ops.add(new CharInsertOp(block1.id, newCharId, prevId, cn.value, cn.bold, cn.italic));
            prevId = newCharId;
        }

        blockCRDT.deleteBlock(block2.id);
        ops.add(new BlockDeleteOp(block2.id, clock));

        pushUndo(ops);
        return ops;
    }

    // partialMergeBlocks — move first 'count' chars of block2 to end of block1; keep block2 unless empty
    public List<Operation> partialMergeBlocks(int block1Index, int block2Index, int count) {
        if (count <= 0) return new ArrayList<>();
        List<Operation> ops = new ArrayList<>();
        BlockNode block1 = requireBlock(block1Index);
        BlockNode block2 = requireBlock(block2Index);

        List<CharacterNode> b2Visible = block2.content.getVisibleNodes();
        int moveCount = Math.min(count, b2Visible.size());
        if (moveCount == 0) return ops;
        List<CharacterNode> toMove = new ArrayList<>(b2Visible.subList(0, moveCount));

        for (CharacterNode cn : toMove) {
            block2.content.delete(cn.id);
            ops.add(new CharDeleteOp(block2.id, cn.id, clock));
        }

        List<CharacterNode> b1Chars = block1.content.getVisibleNodes();
        CrdtId prevId = b1Chars.isEmpty() ? null : b1Chars.get(b1Chars.size() - 1).id;
        for (CharacterNode cn : toMove) {
            CrdtId newId = nextId();
            block1.content.insert(newId, prevId, cn.value, cn.bold, cn.italic);
            ops.add(new CharInsertOp(block1.id, newId, prevId, cn.value, cn.bold, cn.italic));
            prevId = newId;
        }

        if (block2.content.getVisibleNodes().isEmpty()) {
            blockCRDT.deleteBlock(block2.id);
            ops.add(new BlockDeleteOp(block2.id, clock));
        }

        pushUndo(ops);
        return ops;
    }

    // cascadeOverflow — overflow men block bi yetna2al le el block el gaye (aw block gedeed law mafesh)
    public List<Operation> cascadeOverflow(int bi, int max) {
        List<Operation> ops = new ArrayList<>();
        int current = bi;
        while (current < blockCount()) {
            BlockNode block = requireBlock(current);
            List<CharacterNode> visible = block.content.getVisibleNodes();
            if (visible.size() <= max) break;
            List<CharacterNode> overflow = new ArrayList<>(visible.subList(max, visible.size()));
            for (CharacterNode cn : overflow) {
                block.content.delete(cn.id);
                ops.add(new CharDeleteOp(block.id, cn.id, clock));
            }
            int next = current + 1;
            if (next < blockCount()) {
                BlockNode nextBlock = requireBlock(next);
                // prepend in reverse order: higher clock sorts first (= leftmost) per CRDT ordering
                for (int i = overflow.size() - 1; i >= 0; i--) {
                    CharacterNode cn = overflow.get(i);
                    CrdtId newId = nextId();
                    nextBlock.content.insert(newId, null, cn.value, cn.bold, cn.italic);
                    ops.add(new CharInsertOp(nextBlock.id, newId, null, cn.value, cn.bold, cn.italic));
                }
            } else {
                CrdtId newBlockId = nextId();
                blockCRDT.insertBlock(newBlockId, block.id);
                ops.add(new BlockInsertOp(newBlockId, block.id));
                BlockNode newBlock = blockCRDT.getBlock(newBlockId);
                CrdtId prevId = null;
                for (CharacterNode cn : overflow) {
                    CrdtId newId = nextId();
                    newBlock.content.insert(newId, prevId, cn.value, cn.bold, cn.italic);
                    ops.add(new CharInsertOp(newBlockId, newId, prevId, cn.value, cn.bold, cn.italic));
                    prevId = newId;
                }
                break;
            }
            current = next;
        }
        if (!ops.isEmpty()) pushUndo(ops);
        return ops;
    }

    // moveBlock — zy copy + delete lel source
    public List<Operation> moveBlock(int fromIndex, int toAfterIndex) {
        List<Operation> ops = new ArrayList<>();

        BlockNode source = requireBlock(fromIndex);
        List<CharacterNode> sourceChars = source.content.getVisibleNodes();

        CrdtId targetParentId = (toAfterIndex < 0) ? null : requireBlock(toAfterIndex).id;

        CrdtId newBlockId = nextId();
        blockCRDT.insertBlock(newBlockId, targetParentId);
        ops.add(new BlockInsertOp(newBlockId, targetParentId));

        BlockNode newBlock = blockCRDT.getBlock(newBlockId);
        CrdtId prevId = null;
        for (CharacterNode cn : sourceChars) {
            CrdtId newCharId = nextId();
            newBlock.content.insert(newCharId, prevId, cn.value, cn.bold, cn.italic);
            ops.add(new CharInsertOp(newBlockId, newCharId, prevId, cn.value, cn.bold, cn.italic));
            prevId = newCharId;
        }

        blockCRDT.deleteBlock(source.id);
        ops.add(new BlockDeleteOp(source.id, clock));

        pushUndo(ops);
        return ops;
    }

    // copyBlock — duplicate chars bel ids gededa; el source yfdl zy ma howa
    public List<Operation> copyBlock(int fromIndex, int toAfterIndex) {
        List<Operation> ops = new ArrayList<>();

        BlockNode source = requireBlock(fromIndex);
        List<CharacterNode> sourceChars = source.content.getVisibleNodes();

        CrdtId targetParentId = (toAfterIndex < 0) ? null : requireBlock(toAfterIndex).id;

        CrdtId newBlockId = nextId();
        blockCRDT.insertBlock(newBlockId, targetParentId);
        ops.add(new BlockInsertOp(newBlockId, targetParentId));

        BlockNode newBlock = blockCRDT.getBlock(newBlockId);
        CrdtId prevId = null;
        for (CharacterNode cn : sourceChars) {
            CrdtId newCharId = nextId();
            newBlock.content.insert(newCharId, prevId, cn.value, cn.bold, cn.italic);
            ops.add(new CharInsertOp(newBlockId, newCharId, prevId, cn.value, cn.bold, cn.italic));
            prevId = newCharId;
        }

        pushUndo(ops);
        return ops;
    }

    // insertChar — parentId = el char el abl el caret fel visible list
    public CharInsertOp insertChar(int blockIndex, int cursorPos,
                                   char value, boolean bold, boolean italic) {
        BlockNode block = requireBlock(blockIndex);

        CrdtId parentId = block.content.getParentForCursor(cursorPos);
        CrdtId id = nextId();
        block.content.insert(id, parentId, value, bold, italic);
        CharInsertOp op = new CharInsertOp(block.id, id, parentId, value, bold, italic);
        pushUndo(List.of(op));
        return op;
    }

    // insertCharAfter — O(1) bulk insert; caller tracks the tail ID directly (no visible-list scan)
    public CharInsertOp insertCharAfter(int blockIndex, CrdtId parentId,
                                        char value, boolean bold, boolean italic) {
        BlockNode block = requireBlock(blockIndex);
        CrdtId id = nextId();
        block.content.insert(id, parentId, value, bold, italic);
        CharInsertOp op = new CharInsertOp(block.id, id, parentId, value, bold, italic);
        pushUndo(List.of(op));
        return op;
    }

    // getBlockLength — O(1) with warm cache; avoids getText() in the hot keystroke path
    public int getBlockLength(int blockIndex) {
        BlockNode block = blockCRDT.getVisibleBlock(blockIndex);
        if (block == null) return 0;
        return block.content.getVisibleNodes().size();
    }

    // deleteChar — tombstone 3la visible index
    public CharDeleteOp deleteChar(int blockIndex, int charPos) {
        BlockNode block = requireBlock(blockIndex);
        CrdtId charId = block.content.getIdAtVisibleIndex(charPos);
        if (charId == null) {
            throw new IndexOutOfBoundsException(
                    "No character at position " + charPos + " in block " + blockIndex);
        }
        block.content.delete(charId);
        CharDeleteOp op = new CharDeleteOp(block.id, charId, clock);
        pushUndo(List.of(op));
        return op;
    }

    // formatRange — kol 7arf fel range; undo group wa7ed
    public List<FormatOp> formatRange(int blockIndex, int fromPos, int toPos,
                                      boolean bold, boolean italic) {
        BlockNode block = requireBlock(blockIndex);
        List<FormatOp>        ops         = new ArrayList<>();
        List<FormatRestoreOp> undoEntries = new ArrayList<>();

        for (int i = fromPos; i <= toPos; i++) {
            CrdtId charId = block.content.getIdAtVisibleIndex(i);
            if (charId == null) break;

            CharacterNode cn = block.content.getNode(charId);
            boolean prevBold   = (cn != null) && cn.bold;
            boolean prevItalic = (cn != null) && cn.italic;

            block.content.format(charId, bold, italic);
            int c = ++clock;
            ops.add(new FormatOp(block.id, charId, bold, italic, c));

            undoEntries.add(new FormatRestoreOp(block.id, charId, bold, italic, prevBold, prevItalic, c));
        }
        pushUndo(new ArrayList<>(undoEntries));
        return ops;
    }

    // addComment — mesh fel undo stack el 3ady; el UI byhandlha
    public AddCommentOp addComment(int blockIndex, int charPos, String text) {
        BlockNode block  = requireBlock(blockIndex);
        CrdtId    charId = block.content.getIdAtVisibleIndex(charPos);
        if (charId == null)
            throw new IndexOutOfBoundsException("No character at " + charPos);

        CrdtId commentId = nextId();
        Comment comment  = new Comment(commentId, block.id, charId, text, userId);

        comments.put(commentId, comment);

        charToCommentIds.computeIfAbsent(charId, k -> new HashSet<>()).add(commentId);

        return new AddCommentOp(commentId, block.id, charId, text, userId, clock);
    }

    // removeComment — shel el comment men el maps
    public RemoveCommentOp removeComment(CrdtId commentId) {
        Comment c = comments.remove(commentId);
        if (c == null) return null;

        Set<CrdtId> anchored = charToCommentIds.get(c.charId());
        if (anchored != null) anchored.remove(commentId);

        return new RemoveCommentOp(commentId, ++clock);
    }

    // getCommentsForBlock — map visible char index → comment (awl wa7ed bas)
    public Map<Integer, Comment> getCommentsForBlock(int blockIndex) {
        BlockNode block = blockCRDT.getVisibleBlock(blockIndex);
        if (block == null) return new HashMap<>();

        List<CharacterNode> nodes = block.content.getVisibleNodes();
        Map<Integer, Comment> result = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            Set<CrdtId> cids = charToCommentIds.get(nodes.get(i).id);
            if (cids != null && !cids.isEmpty()) {
                CrdtId first = cids.iterator().next();
                Comment cm = comments.get(first);
                if (cm != null) result.put(i, cm);
            }
        }
        return result;
    }

    // getAllComments — kol el comments el ma7fouza
    public Collection<Comment> getAllComments() {
        return comments.values();
    }

    // hasComment — fe comment 3la el char da?
    public boolean hasComment(int blockIndex, int charPos) {
        BlockNode block = blockCRDT.getVisibleBlock(blockIndex);
        if (block == null) return false;
        CrdtId charId = block.content.getIdAtVisibleIndex(charPos);
        if (charId == null) return false;
        Set<CrdtId> cids = charToCommentIds.get(charId);
        return cids != null && !cids.isEmpty();
    }

    // getCommentAt — law fe aktr men comment byakhod awl wa7ed
    public Comment getCommentAt(int blockIndex, int charPos) {
        BlockNode block = blockCRDT.getVisibleBlock(blockIndex);
        if (block == null) return null;
        CrdtId charId = block.content.getIdAtVisibleIndex(charPos);
        if (charId == null) return null;
        Set<CrdtId> cids = charToCommentIds.get(charId);
        if (cids == null || cids.isEmpty()) return null;
        return comments.get(cids.iterator().next());
    }

    // applyOp — remote/history; updateClock + dispatch 3la no3 el op
    public void applyOp(Operation op) {
        updateClock(op.opClock());

        if (op instanceof CharInsertOp) {
            CharInsertOp o = (CharInsertOp) op;
            BlockNode block = requireBlockById(o.blockId());
            block.content.insert(o.charId(), o.parentId(), o.value(), o.bold(), o.italic());

        } else if (op instanceof CharDeleteOp) {
            CharDeleteOp o = (CharDeleteOp) op;
            BlockNode block = requireBlockById(o.blockId());
            block.content.delete(o.charId());
            autoRemoveCommentsForChar(o.charId());

        } else if (op instanceof CharUndeleteOp) {
            CharUndeleteOp o = (CharUndeleteOp) op;
            BlockNode block = requireBlockById(o.blockId());
            block.content.undelete(o.charId());

        } else if (op instanceof FormatOp) {
            FormatOp o = (FormatOp) op;
            BlockNode block = requireBlockById(o.blockId());
            block.content.format(o.charId(), o.bold(), o.italic());

        } else if (op instanceof FormatRestoreOp) {
            FormatRestoreOp o = (FormatRestoreOp) op;
            BlockNode block = requireBlockById(o.blockId());
            block.content.format(o.charId(), o.targetBold(), o.targetItalic());

        } else if (op instanceof BlockInsertOp) {
            BlockInsertOp o = (BlockInsertOp) op;
            blockCRDT.insertBlock(o.blockId(), o.parentId());

        } else if (op instanceof BlockDeleteOp) {
            BlockDeleteOp o = (BlockDeleteOp) op;
            blockCRDT.deleteBlock(o.blockId());

        } else if (op instanceof BlockSplitOp) {
            BlockSplitOp o = (BlockSplitOp) op;
            blockCRDT.insertBlock(o.newBlockId(), o.originalBlockId());

        } else if (op instanceof AddCommentOp) {
            AddCommentOp o = (AddCommentOp) op;
            BlockNode block = blockCRDT.getBlock(o.blockId());
            if (block != null) {
                Comment c = new Comment(o.commentId(), o.blockId(), o.charId(), o.text(), o.authorId());
                comments.put(o.commentId(), c);
                charToCommentIds.computeIfAbsent(o.charId(), k -> new HashSet<>()).add(o.commentId());
            }

        } else if (op instanceof RemoveCommentOp) {
            RemoveCommentOp o = (RemoveCommentOp) op;
            Comment c = comments.remove(o.commentId());
            if (c != null) {
                Set<CrdtId> anchored = charToCommentIds.get(c.charId());
                if (anchored != null) anchored.remove(o.commentId());
            }

        } else {
            throw new IllegalArgumentException("Unknown operation type: " + op.getClass());
        }
    }

    // applyAndTrackUndo — remote; FormatOp leh undo mo5talef, ba2y el ops 3ady
    public void applyAndTrackUndo(Operation op) {
        if (op instanceof FormatOp) {

            FormatOp fo = (FormatOp) op;
            BlockNode block = blockCRDT.getBlock(fo.blockId());
            boolean prevBold = false, prevItalic = false;
            if (block != null) {
                CharacterNode cn = block.content.getNode(fo.charId());
                if (cn != null) {
                    prevBold   = cn.bold;
                    prevItalic = cn.italic;
                }
            }
            applyOp(op);
            int c = ++clock;

            pushUndo(java.util.List.of(new FormatRestoreOp(
                    fo.blockId(), fo.charId(),
                    fo.bold(), fo.italic(),
                    prevBold, prevItalic, c)));
        } else {
            applyOp(op);
            pushUndo(java.util.List.of(op));
        }
    }

    // undo — invert last group w push fel redo
    public List<Operation> undo() {
        if (undoStack.isEmpty()) return List.of();

        List<Operation> group    = undoStack.pop();
        List<Operation> inverses = invert(group);
        for (Operation inv : inverses) applyOp(inv);
        redoStack.push(inverses);
        return inverses;
    }

    // redo — 3aks el undo
    public List<Operation> redo() {
        if (redoStack.isEmpty()) return List.of();

        List<Operation> group    = redoStack.pop();
        List<Operation> inverses = invert(group);
        for (Operation inv : inverses) applyOp(inv);
        undoStack.push(inverses);
        return inverses;
    }

    // invert — reverse tarteeb el group
    private List<Operation> invert(List<Operation> ops) {
        List<Operation> result = new ArrayList<>();

        for (int i = ops.size() - 1; i >= 0; i--) {
            Operation inv = invertOne(ops.get(i));
            if (inv != null) result.add(inv);
        }
        return result;
    }

    // invertOne — inverse le op wa7da + clock gedeed
    private Operation invertOne(Operation op) {
        int c = ++clock;

        if (op instanceof CharInsertOp) {
            CharInsertOp o = (CharInsertOp) op;
            return new CharDeleteOp(o.blockId(), o.charId(), c);

        } else if (op instanceof CharDeleteOp) {
            CharDeleteOp o = (CharDeleteOp) op;
            return new CharUndeleteOp(o.blockId(), o.charId(), c);

        } else if (op instanceof CharUndeleteOp) {
            CharUndeleteOp o = (CharUndeleteOp) op;
            return new CharDeleteOp(o.blockId(), o.charId(), c);

        } else if (op instanceof BlockInsertOp) {
            BlockInsertOp o = (BlockInsertOp) op;
            return new BlockDeleteOp(o.blockId(), c);

        } else if (op instanceof BlockDeleteOp) {
            BlockDeleteOp o = (BlockDeleteOp) op;
            BlockNode node = blockCRDT.getBlock(o.blockId());
            if (node != null) {
                return new BlockInsertOp(o.blockId(), node.parentId);
            }
            return null;

        } else if (op instanceof BlockSplitOp) {
            BlockSplitOp o = (BlockSplitOp) op;
            return new BlockDeleteOp(o.newBlockId(), c);

        } else if (op instanceof FormatRestoreOp) {
            FormatRestoreOp o = (FormatRestoreOp) op;
            return new FormatRestoreOp(o.blockId(), o.charId(),
                    o.otherBold(), o.otherItalic(),
                    o.targetBold(), o.targetItalic(),
                    c);
        }

        return null;
    }

    // getFullText — blocks be \n\n benhom
    public String getFullText() {
        StringBuilder sb = new StringBuilder();
        List<BlockNode> blocks = blockCRDT.getVisibleBlocks();
        for (int i = 0; i < blocks.size(); i++) {
            if (i > 0) sb.append("\n\n");
            sb.append(blocks.get(i).content.getText());
        }
        return sb.toString();
    }

    // getBlockText — nass block wa7ed visible
    public String getBlockText(int blockIndex) {
        BlockNode block = blockCRDT.getVisibleBlock(blockIndex);
        return (block == null) ? null : block.content.getText();
    }

    // StyledChar — char + style lel UI
    public record StyledChar(char value, boolean bold, boolean italic) {}

    // getBlockStyledChars — lel JTextPane; visible chars bas
    public List<StyledChar> getBlockStyledChars(int blockIndex) {
        BlockNode block = blockCRDT.getVisibleBlock(blockIndex);
        if (block == null) return new ArrayList<>();

        List<CharacterNode> nodes  = block.content.getVisibleNodes();
        List<StyledChar>    result = new ArrayList<>();
        for (CharacterNode cn : nodes) {
            result.add(new StyledChar(cn.value, cn.bold, cn.italic));
        }
        return result;
    }

    // blockCount — 3ad el blocks el visible
    public int blockCount() {
        return blockCRDT.size();
    }

    // getUserId — el user bta3 el document
    public int getUserId()   { return userId; }

    // getClock — a5er clock local
    public int getClock()    { return clock;  }

    // getBlockCRDT — access lel tree el blocks
    public BlockCRDT getBlockCRDT() { return blockCRDT; }

    // requireBlock — index visible; law mesh mawgood yormy exception
    private BlockNode requireBlock(int visibleIndex) {
        BlockNode block = blockCRDT.getVisibleBlock(visibleIndex);
        if (block == null)
            throw new IndexOutOfBoundsException("No block at visible index " + visibleIndex);
        return block;
    }

    // requireBlockById — block bel CrdtId; law mesh mawgood yormy
    private BlockNode requireBlockById(CrdtId id) {
        BlockNode block = blockCRDT.getBlock(id);
        if (block == null)
            throw new IllegalStateException("Block not found: " + id);
        return block;
    }

    // autoRemoveCommentsForChar — lama el 7arf yetmsa7 shel el comments el laze2a
    private void autoRemoveCommentsForChar(CrdtId charId) {
        Set<CrdtId> cids = charToCommentIds.remove(charId);
        if (cids != null) {
            for (CrdtId cid : cids) {
                comments.remove(cid);
            }
        }
    }

    // pushUndo — redo byettafa; limit 3ala undo depth
    private void pushUndo(List<Operation> ops) {
        if (ops.isEmpty()) return;
        redoStack.clear();
        undoStack.push(new ArrayList<>(ops));
        if (undoStack.size() > UNDO_LIMIT) {
            ((java.util.ArrayDeque<List<Operation>>) undoStack).removeLast();
        }
    }
}

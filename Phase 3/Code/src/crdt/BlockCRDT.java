package crdt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Tree le el blocks — sorting bel clock/user 3shan el concurrent inserts
public class BlockCRDT {

    private final BlockNode root = new BlockNode();

    private final Map<CrdtId, BlockNode> nodeMap = new HashMap<>();

    private List<BlockNode> cachedVisible = null;

    // insertBlock — lw el id mawgood = undelete; y3ni insertSorted ta7t parent
    public BlockNode insertBlock(CrdtId id, CrdtId parentId) {
        if (nodeMap.containsKey(id)) {

            nodeMap.get(id).deleted = false;
            cachedVisible = null;
            return nodeMap.get(id);
        }

        BlockNode parent = resolveParent(parentId);
        if (parent == null) {
            throw new IllegalStateException("Parent block not found: " + parentId);
        }

        BlockNode node = new BlockNode(id, parentId);
        nodeMap.put(id, node);

        insertSorted(parent.children, node);
        cachedVisible = null;
        return node;
    }

    // deleteBlock — soft delete bel flag
    public void deleteBlock(CrdtId id) {
        requireBlock(id).deleted = true;
        cachedVisible = null;
    }

    // getVisibleBlocks — DFS men root; mesh deleted; cached
    public List<BlockNode> getVisibleBlocks() {
        if (cachedVisible == null) {
            cachedVisible = new ArrayList<>();
            collectVisible(root, cachedVisible);
        }
        return cachedVisible;
    }

    // getVisibleBlock — by index fel visible list
    public BlockNode getVisibleBlock(int index) {
        List<BlockNode> visible = getVisibleBlocks();
        if (index < 0 || index >= visible.size()) return null;
        return visible.get(index);
    }

    // getBlock — lookup bel id (visible aw la2)
    public BlockNode getBlock(CrdtId id) {
        return nodeMap.get(id);
    }

    // getParentForInsertAfter — id el block el insert yeb2a ta7to
    public CrdtId getParentForInsertAfter(int afterIndex) {
        if (afterIndex < 0) return null;
        List<BlockNode> visible = getVisibleBlocks();
        if (afterIndex >= visible.size()) return null;
        return visible.get(afterIndex).id;
    }

    // size — 3ad el visible blocks
    public int size() {
        return getVisibleBlocks().size();
    }

    // resolveParent — null/ROOT → root node
    private BlockNode resolveParent(CrdtId parentId) {
        if (parentId == null || parentId.equals(CrdtId.ROOT)) return root;
        return nodeMap.get(parentId);
    }

    // requireBlock — law mesh mawgood yormy
    private BlockNode requireBlock(CrdtId id) {
        BlockNode node = nodeMap.get(id);
        if (node == null) throw new IllegalStateException("Block not found: " + id);
        return node;
    }

    // insertSorted — CRDT ordering fel siblings list
    private void insertSorted(List<BlockNode> siblings, BlockNode node) {
        int i = 0;

        while (i < siblings.size() && siblingOrder(siblings.get(i), node) < 0) {
            i++;
        }
        siblings.add(i, node);
    }

    // siblingOrder — clock then userId (nafs el chars)
    private int siblingOrder(BlockNode a, BlockNode b) {
        if (a.id.clock != b.id.clock) return b.id.clock - a.id.clock;
        return a.id.userId - b.id.userId;
    }

    // collectVisible — iterative DFS; avoids StackOverflow on large trees
    private void collectVisible(BlockNode start, List<BlockNode> out) {
        Deque<BlockNode> stack = new ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            BlockNode node = stack.pop();
            if (node != root && !node.deleted) out.add(node);
            List<BlockNode> ch = node.children;
            for (int i = ch.size() - 1; i >= 0; i--) stack.push(ch.get(i));
        }
    }
}

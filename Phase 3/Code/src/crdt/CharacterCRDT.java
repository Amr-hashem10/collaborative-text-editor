package crdt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// CRDT le 7orouf block wa7ed — nafs fekret el ordering bel ids
public class CharacterCRDT {

    private final CharacterNode root = new CharacterNode();

    private final Map<CrdtId, CharacterNode> nodeMap = new HashMap<>();

    private List<CharacterNode> cachedVisible = null;

    // insert — duplicate id = ignore; y7ot fel tree ta7t parent
    public void insert(CrdtId id, CrdtId parentId, char value, boolean bold, boolean italic) {
        if (nodeMap.containsKey(id)) return;

        CharacterNode parent = resolveParent(parentId);
        if (parent == null) {
            throw new IllegalStateException(
                    "Parent character not found: " + parentId
                    + ".  Out-of-order delivery is not supported in Phase 1.");
        }

        CharacterNode node = new CharacterNode(id, value, bold, italic, parentId);
        nodeMap.put(id, node);

        insertSorted(parent.children, node);
        cachedVisible = null;
    }

    // delete — tombstone
    public void delete(CrdtId id) {
        requireNode(id).deleted = true;
        cachedVisible = null;
    }

    // undelete — yeraga3 el 7arf
    public void undelete(CrdtId id) {
        requireNode(id).deleted = false;
        cachedVisible = null;
    }

    // format — bold/italic 3la node
    public void format(CrdtId id, boolean bold, boolean italic) {
        CharacterNode node = requireNode(id);
        node.bold   = bold;
        node.italic = italic;
    }

    // getText — nass visible concatenated
    public String getText() {
        StringBuilder sb = new StringBuilder();
        collectText(root, sb);
        return sb.toString();
    }

    // getVisibleNodes — DFS visible bas; cached after first build
    public List<CharacterNode> getVisibleNodes() {
        if (cachedVisible == null) {
            cachedVisible = new ArrayList<>();
            collectVisible(root, cachedVisible);
        }
        return cachedVisible;
    }

    // getAllNodes — kol el nodes (debug/history)
    public List<CharacterNode> getAllNodes() {
        List<CharacterNode> result = new ArrayList<>();
        collectAll(root, result);
        return result;
    }

    // getIdAtVisibleIndex — char id bel visible index
    public CrdtId getIdAtVisibleIndex(int index) {
        List<CharacterNode> visible = getVisibleNodes();
        if (index < 0 || index >= visible.size()) return null;
        return visible.get(index).id;
    }

    // getParentForCursor — el char el abl el caret
    public CrdtId getParentForCursor(int cursorPos) {
        if (cursorPos <= 0) return null;
        return getIdAtVisibleIndex(cursorPos - 1);
    }

    // getNode — lookup men el map
    public CharacterNode getNode(CrdtId id) {
        return nodeMap.get(id);
    }

    // size — 3ad el visible chars
    public int size() {
        return getVisibleNodes().size();
    }

    // resolveParent — null/ROOT → root
    private CharacterNode resolveParent(CrdtId parentId) {
        if (parentId == null || parentId.equals(CrdtId.ROOT)) return root;
        return nodeMap.get(parentId);
    }

    // requireNode — law mesh mawgood yormy
    private CharacterNode requireNode(CrdtId id) {
        CharacterNode node = nodeMap.get(id);
        if (node == null) throw new IllegalStateException("Character node not found: " + id);
        return node;
    }

    // insertSorted — CRDT ordering fel siblings
    private void insertSorted(List<CharacterNode> siblings, CharacterNode node) {
        int i = 0;

        while (i < siblings.size() && siblingOrder(siblings.get(i), node) < 0) {
            i++;
        }

        siblings.add(i, node);
    }

    // siblingOrder — clock then userId
    private int siblingOrder(CharacterNode a, CharacterNode b) {
        if (a.id.clock != b.id.clock) {

            return b.id.clock - a.id.clock;
        }

        return a.id.userId - b.id.userId;
    }

    // collectText — iterative DFS; avoids StackOverflow on long documents
    private void collectText(CharacterNode start, StringBuilder sb) {
        Deque<CharacterNode> stack = new ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            CharacterNode node = stack.pop();
            if (node != root && !node.deleted) sb.append(node.value);
            List<CharacterNode> ch = node.children;
            for (int i = ch.size() - 1; i >= 0; i--) stack.push(ch.get(i));
        }
    }

    // collectVisible — iterative DFS; avoids StackOverflow on long documents
    private void collectVisible(CharacterNode start, List<CharacterNode> out) {
        Deque<CharacterNode> stack = new ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            CharacterNode node = stack.pop();
            if (node != root && !node.deleted) out.add(node);
            List<CharacterNode> ch = node.children;
            for (int i = ch.size() - 1; i >= 0; i--) stack.push(ch.get(i));
        }
    }

    // collectAll — iterative DFS; avoids StackOverflow on long documents
    private void collectAll(CharacterNode start, List<CharacterNode> out) {
        Deque<CharacterNode> stack = new ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            CharacterNode node = stack.pop();
            if (node != root) out.add(node);
            List<CharacterNode> ch = node.children;
            for (int i = ch.size() - 1; i >= 0; i--) stack.push(ch.get(i));
        }
    }
}

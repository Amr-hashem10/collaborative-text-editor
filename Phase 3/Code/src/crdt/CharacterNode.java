package crdt;

import java.util.ArrayList;
import java.util.List;

// 7arf wa7ed — tombstone bel deleted mesh bytshal men el tree
public final class CharacterNode {

    public final CrdtId id;

    public char value;

    public boolean bold;

    public boolean italic;

    public boolean deleted;

    public final CrdtId parentId;

    public final List<CharacterNode> children = new ArrayList<>();

    // CharacterNode — root placeholder
    CharacterNode() {
        this.id       = null;
        this.value    = '\0';
        this.bold     = false;
        this.italic   = false;
        this.deleted  = false;
        this.parentId = null;
    }

    public CharacterNode(CrdtId id, char value, boolean bold, boolean italic, CrdtId parentId) {
        this.id       = id;
        this.value    = value;
        this.bold     = bold;
        this.italic   = italic;
        this.deleted  = false;
        this.parentId = parentId;
    }

    // toString — debug shape
    @Override
    public String toString() {
        return String.format("Char{id=%s, val='%c', bold=%b, italic=%b, del=%b}",
                id, value, bold, italic, deleted);
    }
}

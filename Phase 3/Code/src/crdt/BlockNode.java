package crdt;

import java.util.ArrayList;
import java.util.List;

// Block wa7ed fel doc — feh CRDT le el 7orouf w children lw nested
public final class BlockNode {

    public final CrdtId id;

    public boolean deleted;

    public final CharacterCRDT content;

    public final CrdtId parentId;

    public final List<BlockNode> children = new ArrayList<>();

    // BlockNode — placeholder root node (package-private)
    BlockNode() {
        this.id       = null;
        this.deleted  = false;
        this.content  = null;
        this.parentId = null;
    }

    // BlockNode — block 7a2i2 + CRDT chars
    public BlockNode(CrdtId id, CrdtId parentId) {
        this.id       = id;
        this.deleted  = false;
        this.content  = new CharacterCRDT();
        this.parentId = parentId;
    }

    // toString — debug shape
    @Override
    public String toString() {
        String text = (content != null) ? content.getText() : "";
        return String.format("Block{id=%s, del=%b, text='%s'}", id, deleted, text);
    }
}

package operations;

import crdt.CrdtId;

// insert 7arf — parentId = el 7arf el abl meno fel tree
public record CharInsertOp(
        CrdtId blockId,
        CrdtId charId,
        CrdtId parentId,
        char   value,
        boolean bold,
        boolean italic
) implements Operation {

    // opClock — men charId.clock
    @Override
    public int opClock() { return charId.clock; }
}

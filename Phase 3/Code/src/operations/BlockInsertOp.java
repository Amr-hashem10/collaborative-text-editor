package operations;

import crdt.CrdtId;

// block gedeed ta7t parent (null = root)
public record BlockInsertOp(
        CrdtId blockId,
        CrdtId parentId
) implements Operation {

    // opClock — men blockId.clock
    @Override
    public int opClock() { return blockId.clock; }
}

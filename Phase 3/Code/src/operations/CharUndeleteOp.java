package operations;

import crdt.CrdtId;

// undo delete — yeraga3 el 7arf
public record CharUndeleteOp(
        CrdtId blockId,
        CrdtId charId,
        int    opClock
) implements Operation {}

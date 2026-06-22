package operations;

import crdt.CrdtId;

// tombstone delete
public record CharDeleteOp(
        CrdtId blockId,
        CrdtId charId,
        int    opClock
) implements Operation {}

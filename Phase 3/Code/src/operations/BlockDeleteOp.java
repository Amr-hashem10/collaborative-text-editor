package operations;

import crdt.CrdtId;

// soft delete le block
public record BlockDeleteOp(
        CrdtId blockId,
        int    opClock
) implements Operation {}

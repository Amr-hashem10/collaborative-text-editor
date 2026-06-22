package operations;

import crdt.CrdtId;

// Enter fel nos — block tany men splitAfterIndex
public record BlockSplitOp(
        CrdtId originalBlockId,
        CrdtId newBlockId,
        int    splitAfterIndex,
        int    opClock
) implements Operation {}

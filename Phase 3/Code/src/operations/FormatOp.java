package operations;

import crdt.CrdtId;

// bold/italic 3la 7arf
public record FormatOp(
        CrdtId  blockId,
        CrdtId  charId,
        boolean bold,
        boolean italic,
        int     opClock
) implements Operation {}

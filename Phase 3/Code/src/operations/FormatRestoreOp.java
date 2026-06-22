package operations;

import crdt.CrdtId;

// undo le el format — el style el adem w el gedeed
public record FormatRestoreOp(
        CrdtId  blockId,
        CrdtId  charId,
        boolean targetBold,
        boolean targetItalic,
        boolean otherBold,
        boolean otherItalic,
        int     opClock
) implements Operation {}

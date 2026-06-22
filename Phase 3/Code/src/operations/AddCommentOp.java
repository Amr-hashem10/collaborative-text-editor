package operations;

import crdt.CrdtId;

// zyd comment 3la 7arf
public record AddCommentOp(
        CrdtId commentId,
        CrdtId blockId,
        CrdtId charId,
        String text,
        int    authorId,
        int    opClock
) implements Operation {}

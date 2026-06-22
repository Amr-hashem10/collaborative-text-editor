package operations;

import crdt.CrdtId;

// shel comment bel id
public record RemoveCommentOp(
        CrdtId commentId,
        int    opClock
) implements Operation {}

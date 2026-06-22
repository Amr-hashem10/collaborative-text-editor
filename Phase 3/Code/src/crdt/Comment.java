package crdt;

// Comment 3la 7arf fel document — mesh Java comment :)
public record Comment(
        CrdtId commentId,
        CrdtId blockId,
        CrdtId charId,
        String text,
        int    authorId
) {}


package operations;

// ay 7aga el server y-serialize w yb3atha — lazem opClock le el ordering
public interface Operation {

    // opClock — lel ordering wel merge fel CRDT
    int opClock();
}

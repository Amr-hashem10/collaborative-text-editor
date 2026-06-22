package crdt;

import java.util.Objects;

// Lam7a unique le kol node fel CRDT — userId + clock
public final class CrdtId {

    public final int userId;
    public final int clock;

    public static final CrdtId ROOT = new CrdtId(-1, -1); // virtual parent le el tree

    // CrdtId — constructor: userId + clock
    public CrdtId(int userId, int clock) {
        this.userId = userId;
        this.clock  = clock;
    }

    // equals — userId w clock bas
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CrdtId that)) return false;
        return userId == that.userId && clock == that.clock;
    }

    // hashCode — hash le userId w clock
    @Override
    public int hashCode() {
        return Objects.hash(userId, clock);
    }

    // toString — ROOT aw "user@clock"
    @Override
    public String toString() {
        if (equals(ROOT)) return "ROOT";
        return userId + "@" + clock;
    }
}

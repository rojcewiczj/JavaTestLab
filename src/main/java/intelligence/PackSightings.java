package intelligence;

import characters.Unit;

/**
 * Tracks "pack knowledge" of targets per wolf den.
 * Coordinates are in TILE space: x = column, y = row.
 * TTL is enforced per entry so wolves forget targets after some time unseen.
 */
public class PackSightings {

    /** A single remembered target for a pack (wolf den). */
    public static final class PackSighting {
        public final int targetId;
        public double x, y;       // tile coords: x = col, y = row
        public long seenNanos;    // last time seen by any wolf of this den

        public PackSighting(int id, double x, double y, long t) {
            this.targetId = id;
            this.x = x;
            this.y = y;
            this.seenNanos = t;
        }
    }

    // denId -> (targetId -> sighting)
    private final java.util.HashMap<Integer, java.util.HashMap<Integer, PackSighting>> books =
            new java.util.HashMap<>();

    // default TTL = 5s (in nanoseconds)
    private final long ttlNanos;

    public PackSightings() {
        this(5_000_000_000L);
    }

    public PackSightings(long ttlNanos) {
        this.ttlNanos = ttlNanos;
    }

    /** Ensure a book exists for a den. */
    private java.util.HashMap<Integer, PackSighting> book(int denId) {
        return books.computeIfAbsent(denId, k -> new java.util.HashMap<>());
    }

    /** Report/refresh a sighting for this den. (Ignores null or dead targets.) */
    public void packReportSighting(int denId, Unit target, long nowNanos) {
        if (target == null || target.isDead()) return;
        var b = book(denId);
        b.put(target.getId(), new PackSighting(target.getId(), target.getX(), target.getY(), nowNanos));
    }

    /**
     * Pick the closest *non-expired* sighting to (fromRow, fromCol).
     * Returns null if none exist after pruning.
     */
    public PackSighting packPickClosestSighting(int denId, double fromRow, double fromCol) {
        var b = books.get(denId);
        if (b == null || b.isEmpty()) return null;

        long now = System.nanoTime();
        double best = Double.POSITIVE_INFINITY;
        PackSighting bestS = null;

        var it = b.values().iterator();
        while (it.hasNext()) {
            PackSighting s = it.next();
            // prune expired
            if (now - s.seenNanos > ttlNanos) { it.remove(); continue; }

            double dr = s.y - fromRow;  // y = row
            double dc = s.x - fromCol;  // x = col
            double d2 = dr*dr + dc*dc;
            if (d2 < best) { best = d2; bestS = s; }
        }
        return bestS;
    }

    /** Manually forget one target for a den (e.g., target died/despawned). */
    public void forgetTarget(int denId, int targetId) {
        var b = books.get(denId);
        if (b != null) b.remove(targetId);
    }

    /** Clear all knowledge for a den (e.g., den destroyed/disabled). */
    public void clearDen(int denId) {
        books.remove(denId);
    }

    /** Optional periodic prune for a single den. */
    public void pruneExpired(int denId, long nowNanos) {
        var b = books.get(denId);
        if (b == null) return;
        b.values().removeIf(s -> nowNanos - s.seenNanos > ttlNanos);
    }

    /** Optional periodic prune for all dens. */
    public void pruneAll(long nowNanos) {
        for (var b : books.values()) {
            b.values().removeIf(s -> nowNanos - s.seenNanos > ttlNanos);
        }
    }
    // in intelligence/PackSightings.java
    public boolean hasActiveSighting(int denId, int targetId) {
        var b = books.get(denId);
        if (b == null) return false;
        var s = b.get(targetId);
        if (s == null) return false;
        return System.nanoTime() - s.seenNanos <= ttlNanos;
    }

    public PackSighting getSighting(int denId, int targetId) {
        var b = books.get(denId);
        if (b == null) return null;
        var s = b.get(targetId);
        if (s == null) return null;
        if (System.nanoTime() - s.seenNanos > ttlNanos) return null; // expired
        return s;
    }

    /** Get current TTL in nanoseconds. */
    public long getTtlNanos() { return ttlNanos; }
}

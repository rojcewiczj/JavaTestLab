package intelligence;

import characters.Team;
import characters.Unit;
import world.World;

public class TeamSightings {

    public static final class Sighting {
        public final int unitId;
        public final double x, y;     // world coords (cells)
        public final long seenNanos;  // System.nanoTime()
        public Sighting(int unitId, double x, double y, long t) {
            this.unitId = unitId; this.x = x; this.y = y; this.seenNanos = t;
        }
    }

    private final java.util.EnumMap<Team, java.util.HashMap<Integer, Sighting>> byTeam =
            new java.util.EnumMap<>(Team.class);

    private long ttlNanos = 10_000_000_000L; // default 10s

    public TeamSightings() {
        for (Team t : Team.values()) byTeam.put(t, new java.util.HashMap<>());
    }

    /** Optional: adjust how long sightings persist. */
    public void setTtlSeconds(double seconds) {
        ttlNanos = (long)Math.max(0, seconds * 1e9);
    }

    /** Read-only view for AI to use. */
    public java.util.Map<Integer, Sighting> view(Team team) {
        return java.util.Collections.unmodifiableMap(byTeam.get(team));
    }

    /** Remove all teams’ sightings. */
    public void clearAll() {
        for (var m : byTeam.values()) m.clear();
    }

    /** Remove one team’s sightings. */
    public void clearTeam(Team team) {
        byTeam.get(team).clear();
    }

    /** Record deer that are currently visible to 'team' this frame. */
    public void updateDeerFromVisibility(World world, Team team, long nowNanos) {
        var book = byTeam.get(team);
        if (book == null) return;

        for (Unit u : world.getUnits()) {
            if (!(u.getActor() instanceof characters.Deer)) continue;
            int rr = (int)Math.floor(u.getY());
            int cc = (int)Math.floor(u.getX());
            if (world.isVisible(rr, cc)) {
                book.put(u.getId(), new Sighting(u.getId(), u.getX(), u.getY(), nowNanos));
            }
        }
    }

    /** Expire old sightings for a team (call each frame after update). */
    public void expireOld(Team team, long nowNanos) {
        var book = byTeam.get(team);
        if (book == null || ttlNanos <= 0) return;
        book.values().removeIf(s -> nowNanos - s.seenNanos > ttlNanos);
    }

    // Convenience: find closest sighting to (x,y) for a team (used by Hunter AI).
    public Sighting closest(Team team, double x, double y) {
        var book = byTeam.get(team);
        if (book == null || book.isEmpty()) return null;
        double best = Double.POSITIVE_INFINITY; Sighting pick = null;
        for (Sighting s : book.values()) {
            double dx = s.x - x, dy = s.y - y, d2 = dx*dx + dy*dy;
            if (d2 < best) { best = d2; pick = s; }
        }
        return pick;
    }
}

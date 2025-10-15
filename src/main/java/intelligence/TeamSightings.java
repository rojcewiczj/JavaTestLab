package intelligence;

import characters.ActorType;
import characters.Team;
import characters.Unit;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

public final class TeamSightings {

    /** One sighting entry on a viewer team's board. */
    public static final class Sighting {
        public int getUnitId() {
            return unitId;
        }

        public final int unitId;
        public double x, y;
        public long seenNanos;

        public Team getTeam() {
            return team;
        }

        public final characters.Team team;            // the target's team

        public ActorType getActorType() {
            return actorType;
        }

        public final characters.ActorType actorType;  // HUMAN, DEER, WOLF, ...

        public Sighting(int unitId, double x, double y, long seenNanos,
                        characters.Team team, characters.ActorType actorType) {
            this.unitId = unitId;
            this.x = x; this.y = y;
            this.seenNanos = seenNanos;
            this.team = team;
            this.actorType = actorType;
        }
    }

    // Board: viewerTeam -> (unitId -> sighting)
    private final EnumMap<characters.Team, HashMap<Integer, Sighting>> byTeam =
            new EnumMap<>(characters.Team.class);

    /** Default TTL: 10s (nanoseconds). */
    private long ttlNanos = 10_000_000_000L;

    public TeamSightings() {
        for (characters.Team t : characters.Team.values()) {
            byTeam.put(t, new HashMap<>());
        }
    }

    /** Change sighting TTL (seconds). */
    public void setTtlSeconds(double seconds) {
        ttlNanos = (long) Math.max(0, seconds * 1e9);
    }

    /** Read-only view for a viewer team. */
    public Map<Integer, Sighting> view(characters.Team viewer) {
        HashMap<Integer, Sighting> m = byTeam.get(viewer);
        if (m == null) return Collections.emptyMap();
        return Collections.unmodifiableMap(m);
    }

    /** True if the viewer’s board currently has an entry for unitId. */
    public boolean hasActive(characters.Team viewer, int unitId) {
        var m = byTeam.get(viewer);
        if (m == null) return false;
        return m.containsKey(unitId); // entries are pruned by expireOld(...)
    }

    /** Manually forget a single target from viewer’s board. */
    public void forget(characters.Team viewer, int unitId) {
        var m = byTeam.get(viewer);
        if (m != null) m.remove(unitId);
    }

    public void clearTeam(characters.Team viewer) {
        var m = byTeam.get(viewer);
        if (m != null) m.clear();
    }

    public void clearAll() {
        for (var m : byTeam.values()) m.clear();
    }

    /** Internal upsert. */
    private void put(characters.Team viewer, int unitId, double x, double y, long nowNanos,
                     characters.Team targetTeam, characters.ActorType actorType) {
        var m = byTeam.computeIfAbsent(viewer, k -> new HashMap<>());
        var s = m.get(unitId);
        if (s == null) {
            s = new Sighting(unitId, x, y, nowNanos, targetTeam, actorType);
            m.put(unitId, s);
        } else {
            s.x = x; s.y = y; s.seenNanos = nowNanos;
            // If targets can morph team/type at runtime, rebuild Sighting here.
        }
    }

    /** Expire stale entries for one viewer team. Call this once per frame after publishing. */
    public void expireOld(characters.Team viewer, long nowNanos) {
        var m = byTeam.get(viewer);
        if (m == null || ttlNanos <= 0) return;
        m.values().removeIf(s -> nowNanos - s.seenNanos > ttlNanos);
    }

    /* ========================= PUBLISH (from team-aware FOV) ========================= */

    /** Preferred: single pass that writes all visible units using ActorType. */
// TeamSightings.java
    // TeamSightings.java
    public void updateFromVisibility(world.World w, characters.Team viewer, boolean[][] vis, long nowNanos) {
        for (characters.Unit u : w.getUnits()) {
            if (u.isDead()) continue;

            int r = u.getRowRounded(), c = u.getColRounded();
            if (!w.inBoundsRC(r, c) || !vis[r][c]) continue;

            var actor = u.getActor();
            if (actor == null){
                System.out.println(" null actor on team:" + u.getTeam());
                continue;
            }
            var type  = actor.getType();

            // If you truly mean *everyone*, drop this type filter too.
            // Otherwise keep it to only combat-relevant actors:
            // if (type != ActorType.HUMAN && type != ActorType.DEER && type != ActorType.WOLF) continue;

            // ✅ DO NOT drop same-team entries here. Keep the board raw.
            put(viewer, u.getId(), u.getX(), u.getY(), nowNanos, u.getTeam(), type);
        }
    }
    // Legacy publishers kept for compatibility; now use ActorType under the hood.


    /* ========================= QUERIES (closest) ========================= */

    public Sighting closestDeer(characters.Team viewer, double x, double y) {
        return closestOfActorType(viewer, x, y, characters.ActorType.DEER);
    }

    public Sighting closestWolf(characters.Team viewer, double x, double y) {
        return closestOfActorType(viewer, x, y, characters.ActorType.WOLF);
    }

    public Sighting closestHuman(characters.Team viewer, double x, double y) {
        return closestOfActorType(viewer, x, y, characters.ActorType.HUMAN);
    }

    /** Generic closest by ActorType. */
    public Sighting closestOfActorType(characters.Team viewer, double x, double y,
                                       characters.ActorType want) {
        var m = byTeam.get(viewer);
        if (m == null || m.isEmpty()) return null;
        double best = Double.POSITIVE_INFINITY; Sighting pick = null;
        for (Sighting s : m.values()) {
            if (s.actorType != want) continue;
            double dx = s.x - x, dy = s.y - y, d2 = dx * dx + dy * dy;
            if (d2 < best) { best = d2; pick = s; }
        }
        return pick;
    }

}

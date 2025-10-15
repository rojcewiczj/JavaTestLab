package intelligence;

import characters.Unit;

public class TargetSelector {
    private final world.World world;
    private final long ttlNanos;
    private final double switchMargin;
    public static final java.util.EnumSet<characters.ActorType> WOLF_PREY_TYPES =
            java.util.EnumSet.of(characters.ActorType.HUMAN,
                    characters.ActorType.DEER,
                    characters.ActorType.HORSE);
    public TargetSelector(world.World world, double traceTtlSec, double switchMargin) {
        this.world = world;
        this.ttlNanos = (long)(traceTtlSec * 1e9);
        this.switchMargin = switchMargin;
    }

    // presets per AI
    public static final java.util.EnumSet<characters.ActorType> WOLF_ALLOWED =
            java.util.EnumSet.of(characters.ActorType.DEER, characters.ActorType.HUMAN);
    public static final java.util.EnumSet<characters.ActorType> HUNTER_ALLOWED =
            java.util.EnumSet.of(characters.ActorType.DEER);

    public TargetPick pickClosestFromBoard(Unit me,
                                           java.util.EnumSet<characters.ActorType> allowedTypes,
                                           boolean requireAlive) {
        var book = world.getSightingsForTeam(me.getTeam());
        if (book == null || book.isEmpty()) return null;

        long now = System.nanoTime();
        double best = Double.POSITIVE_INFINITY; TargetPick bestPick = null;

        for (var s : book.values()) {
            System.out.println(s.getTeam() + " " + s.getUnitId());
            if (now - s.seenNanos > ttlNanos) continue;
            if (!allowedTypes.contains(s.actorType)) continue;
            if (s.team == characters.Team.WOLF) continue; // “non-wolf”

            Unit live = lookupLive(s.unitId);
            boolean liveOk = (live != null && !live.isDead());
            if (requireAlive && !liveOk) continue;

            double tx = liveOk ? live.getX() : s.x;
            double ty = liveOk ? live.getY() : s.y;

            double dx = tx - me.getX(), dy = ty - me.getY();
            double d2 = dx*dx + dy*dy;
            if (d2 < best) {
                best = d2;
                bestPick = new TargetPick(s.unitId, tx, ty, liveOk, s.actorType);
            }
        }
        return bestPick;
    }

    public boolean isTraceActive(Unit me, Integer id) {
        return id != null && world.getTeamSightings().hasActive(me.getTeam(), id);
    }


    public boolean isBetterThanCurrent(Unit me, Integer currentId, double lsx, double lsy, TargetPick cand) {
        if (cand == null) return false;
        if (currentId == null) return true;
        if (!isTraceActive(me, currentId)) return true;

        double cx, cy;
        Unit curLive = lookupLive(currentId);
        if (curLive != null && !curLive.isDead()) { cx = curLive.getX(); cy = curLive.getY(); }
        else {
            var book = world.getSightingsForTeam(me.getTeam());
            var s = (book != null) ? book.get(currentId) : null;
            if (s != null && System.nanoTime() - s.seenNanos <= ttlNanos) { cx = s.x; cy = s.y; }
            else { cx = lsx; cy = lsy; }
        }
        double curD2 = dist2(me.getX(), me.getY(), cx, cy);
        double newD2 = dist2(me.getX(), me.getY(), cand.x, cand.y);
        return newD2 + switchMargin*switchMargin < curD2;
    }

    public void adoptPick(Adopter a, TargetPick p) { if (p != null) { a.setTargetId(p.unitId); a.setLastSeen(p.x, p.y); } }

    private Unit lookupLive(Integer id){ if (id==null) return null; for (Unit u: world.getUnits()) if (u.getId()==id) return u; return null; }
    private static double dist2(double x0,double y0,double x1,double y1){ double dx=x1-x0,dy=y1-y0; return dx*dx+dy*dy; }

    public interface Adopter { void setTargetId(Integer id); void setLastSeen(double x,double y); }
}

package intelligence;

import characters.Unit;

import java.awt.*;
import java.util.Random;

public class WolfAI implements Unit.UnitAI {
    public enum State { INIT, ROAM, CHASE, BITE, LOOT, RETURN_HOME, UNLOAD }

    private final int denId;
    private final Random rng = new Random();

    // tuning
    private static final double VIEW = 8.0;      // tiles
    private static final double CHASE_REPATH = 0.5; // s
    private static final double BITE_RANGE = 1.1;   // tiles (adjacent)
    private static final double WANDER_RETARGET = 1.0; // s between wander nudges

    // internal
    private State state = State.INIT;
    private Integer targetId = null;
    private double repathT = 0.0;
    private double wanderT = 0.0;
    private int navR = Integer.MIN_VALUE, navC = Integer.MIN_VALUE;

    public WolfAI(int denId) {
        this.denId = denId;
    }

    private void setNav(int r, int c){ navR = r; navC = c; }
    private void clearNav(){ navR = navC = Integer.MIN_VALUE; }
    private boolean hasNav(){ return navR != Integer.MIN_VALUE; }

    @Override
    public void update(world.World world, Unit w, double dt) {
        // dead wolves do nothing
        if (w.isDead()) return;

        long nowN = System.nanoTime();
        repathT -= dt; wanderT -= dt;

        // Report any target we can see (deer OR human) to pack
        Unit seen = scanForTargets(world, w);
        if (seen != null) {
            world.getPackSightings().packReportSighting(denId, seen, nowN);
            // If we had no target, lock immediately
            if (targetId == null || !isValidTarget(world, getById(world, targetId))) {
                targetId = seen.getId();
            }
        }

        // If we lost our target (dead or gone), clear
        Unit tgt = getById(world, targetId);
        if (!isValidTarget(world, tgt)) tgt = null;

        switch (state) {
            case INIT -> {
                targetId = null; clearNav();
                state = State.ROAM;
                System.out.println("[WOLF id="+w.getId()+"] → ROAM");
            }

            case ROAM -> {
                // 1) If we have a valid target (current or from pack), chase it
                if (tgt == null) {
                    var s = world.getPackSightings()
                            .packPickClosestSighting(denId, w.getY(), w.getX());
                    if (s != null) {
                        targetId = s.targetId;

                        // immediately move toward the sighting location so we don't stall
                        setNav((int)Math.round(s.y), (int)Math.round(s.x));
                        commandMove(world, w, navR, navC);
                    }
                    tgt = getById(world, targetId);
                }
                if (isValidTarget(world, tgt)) {
                    state = State.CHASE;
                    System.out.println("[WOLF id="+w.getId()+"] ROAM→CHASE target="+tgt.getId());
                    break;
                }

                // 2) else wander (unchanged)
                if (!w.isMoving() || wanderT <= 0) {
                    Point p = pickNearbyReachable(world, w, 4, 9);
                    if (p != null) {
                        setNav(p.y, p.x);
                        commandMove(world, w, p.y, p.x);
                        wanderT = WANDER_RETARGET + rng.nextDouble()*0.8;
                        w.clearAimTarget();
                    }
                }
            }

            case CHASE -> {
                // Drop target if it’s not valid OR the pack trace expired
                boolean traceActive = (targetId != null) &&
                        world.getPackSightings().hasActiveSighting(denId, targetId);

                if (!isValidTarget(world, tgt) || !traceActive) {
                    System.out.println("[WOLF id="+w.getId()+"] CHASE: trace expired or invalid target → ROAM");
                    targetId = null; clearNav(); w.clearAimTarget();
                    state = State.ROAM;
                    break;
                }

                // face and pursue (as you have)
                w.setAimTarget(tgt.getX(), tgt.getY());
                double d = dist(w.getY(), w.getX(), tgt.getY(), tgt.getX());
                if (d <= BITE_RANGE) { clearNav(); state = State.BITE; break; }

                if (repathT <= 0 || !w.isMoving() || !hasNav()) {
                    int tr = (int)Math.round(tgt.getY()), tc = (int)Math.round(tgt.getX());
                    setNav(tr, tc);
                    commandMove(world, w, tr, tc);
                    repathT = CHASE_REPATH;
                }
            }

            case BITE -> {
                boolean traceActive = (targetId != null) &&
                        world.getPackSightings().hasActiveSighting(denId, targetId);

                if (!isValidTarget(world, tgt) || !traceActive) {
                    System.out.println("[WOLF id="+w.getId()+"] BITE: trace expired or invalid → ROAM");
                    targetId = null; clearNav(); w.clearAimTarget();
                    state = State.ROAM;
                    break;
                }

                // keep facing, check range, bite using your melee system (unchanged)
                w.setAimTarget(tgt.getX(), tgt.getY());
                double d = dist(w.getY(), w.getX(), tgt.getY(), tgt.getX());
                if (d > BITE_RANGE + 0.2) { state = State.CHASE; break; }

                double now = world.nowSeconds();
                if (now >= w.getNextMeleeAt()) {
                    world.resolveMeleeHit(w, tgt);
                    w.setNextMeleeAt(now + w.getMeleeCooldownSec());
                    if (tgt.isDead()) {
                        tgt.lootCorpse(w);
                        state = State.RETURN_HOME;
                        System.out.println("[WOLF id="+w.getId()+"] BITE→RETURN_HOME");
                    }
                }
            }

            case RETURN_HOME -> {
                var den = w.getAssignedCamp(); // we set this to the den building
                if (den == null) { state = State.ROAM; break; }
                int[] ap = world.findApproachTileForBuilding(den, w);
                if (ap == null) { state = State.ROAM; break; }
                int tr = ap[0], tc = ap[1];
                if (!w.isMoving() || !hasNav()) {
                    setNav(tr, tc);
                    commandMove(world, w, tr, tc);
                    w.clearAimTarget();
                }
                double d = dist(w.getY(), w.getX(), tr, tc);
                if (d <= 1.2) {
                    state = State.UNLOAD;
                    System.out.println("[WOLF id="+w.getId()+"] RETURN_HOME→UNLOAD");
                }
            }

            case UNLOAD -> {
                // clear carry flags (simple)
                if (w.isCarryingMeat() || w.isCarryingHide()) {
                    // For now: just drop (clear) instantly.
                    // You could notify the den/inventory here.
                    // Assuming Unit has clear methods; if not, add setters.
                }
                targetId = null;
                state = State.ROAM;
                System.out.println("[WOLF id="+w.getId()+"] UNLOAD→ROAM");
            }
        }
    }

    // --- helpers ---

    private Unit scanForTargets(world.World world, Unit w) {
        // Wolves hunt deer or humans (RED/BLUE). Ignore wolves.
        int wr = (int)Math.floor(w.getY()), wc = (int)Math.floor(w.getX());
        double range2 = VIEW*VIEW;

        Unit best = null; double bestD2 = Double.POSITIVE_INFINITY;
        for (Unit u : world.getUnits()) {
            if (u == w || u.isDead()) continue;
            // ignore wolves
            if (u.getTeam() == characters.Team.WOLF) continue;

            // target types: humans or deer
            boolean deer = (u.getActor() instanceof characters.Deer);
            boolean human = (u.getTeam() == characters.Team.RED || u.getTeam() == characters.Team.BLUE);
            if (!deer && !human) continue;

            double d2 = sq(u.getY() - w.getY()) + sq(u.getX() - w.getX());
            if (d2 > range2) continue;

            // line of sight (coarse): use your existing LOS
            int ur = (int)Math.floor(u.getY()), uc = (int)Math.floor(u.getX());
            if (!world.hasLineOfSight(wr, wc, ur, uc)) continue;

            if (d2 < bestD2) { bestD2 = d2; best = u; }
        }
        return best;
    }

    private boolean isValidTarget(world.World world, Unit u) {
        if (u == null || u.isDead()) return false;
        if (u.getTeam() == characters.Team.WOLF) return false;
        boolean deer = (u.getActor() instanceof characters.Deer);
        boolean human = (u.getTeam() == characters.Team.RED || u.getTeam() == characters.Team.BLUE);
        return deer || human;
    }

    private Unit getById(world.World world, Integer id) {
        if (id == null) return null;
        for (Unit u : world.getUnits()) if (u.getId() == id) return u;
        return null;
    }

    private void commandMove(world.World world, Unit u, int r, int c) {
        if (!world.commandMove(u, r, c)) {
            var p = world.findPath(u, r, c);
            if (p != null) u.setPath(p);
        }
    }

    private Point pickNearbyReachable(world.World world, Unit u, int minR, int maxR) {
        int tries = 16;
        int br = (int)Math.floor(u.getY()), bc = (int)Math.floor(u.getX());
        while (tries-- > 0) {
            double ang = rng.nextDouble() * Math.PI * 2;
            double r = minR + rng.nextDouble()*(maxR - minR);
            int cc = bc + (int)Math.round(Math.cos(ang) * r);
            int rr = br + (int)Math.round(Math.sin(ang) * r);
            if (!world.inBoundsRC(rr, cc) || world.isBlocked(rr, cc, u)) continue;
            var path = world.findPath(u, rr, cc);
            if (path != null && !path.isEmpty()) return new Point(cc, rr);
        }
        return null;
    }

    private static double dist(double r1,double c1,double r2,double c2){ return Math.hypot(r2-r1, c2-c1); }
    private static double sq(double v){ return v*v; }
}
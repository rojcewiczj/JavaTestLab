package intelligence;

import characters.Team;
import characters.Unit;

import java.util.Map;
import java.util.Random;

/**
 * BowManAI
 * - Ranged hunter-style logic (approach ring + LOS + aim gating)
 * - Perception via TeamSightings board (no dependence on render FOV)
 * - Targets: enemy humans (the other human team) AND wolves
 *   (e.g., RED bowmen shoot BLUE + WOLF; BLUE bowmen shoot RED + WOLF)
 *
 * Assumes world.updateAllSightings() runs before AI each frame.
 */
public class BowManAI implements Unit.UnitAI {

    private enum State { INIT, SEARCH, MOVE_TO_SHOT, SHOOT, IDLE }

    private final Random rng = new Random();

    // Ranged tunables (same feel as Hunter)
    private static final double BOW_RANGE       = 8.0;
    private static final double TRACE_TTL_SEC   = 5.0;   // governed by board expiry; local expectation
    private static final double SWITCH_MARGIN   = 0.5;   // switch only if strictly closer than this
    private static final double ARRIVE_EPS      = 0.6;

    // Aim/LOS gating (Hunter parity)
    private static final double AIM_DOT_MIN     = 0.95;

    // Fire cadence
    private double nextShotAt = 0.0;

    // Target memory ("trace")
    private Integer targetId = null;
    private double lastSeenX = 0, lastSeenY = 0;

    // Nav watchdog (Hunter parity)
    private int    navTargetR = Integer.MIN_VALUE, navTargetC = Integer.MIN_VALUE;
    private double lastDistToTarget = Double.POSITIVE_INFINITY;
    private double repathCooldown   = 0.8;
    private double repathTimer      = 0.0;

    // Sticky approach ring to reduce jitter
    private boolean haveRing = false;
    private int ringR, ringC;
    private double ringStickTimer = 0.0;
    private static final double RING_STICK_SEC = 1.2;

    // Wander (same controller as Hunter)
    private boolean wandering = false;
    private int    wTarR = Integer.MIN_VALUE, wTarC = Integer.MIN_VALUE;
    private double wPause = 0.0;
    private double wRepathCD = 1.5, wRepathT = 0.0;
    private double wRetargetT = 0.0;
    private double lastWanderDist = Double.POSITIVE_INFINITY;

    private double wLastNudgeAtX = 0, wLastNudgeAtY = 0;
    private int    wNudgesThisLeg = 0;

    private static final int    WANDER_MIN_R = 4;
    private static final int    WANDER_MAX_R = 10;
    private static final double WANDER_RETARGET_MIN = 2.5;
    private static final double WANDER_RETARGET_MAX = 4.0;
    private static final double WANDER_PAUSE_MIN    = 1.0;
    private static final double WANDER_PAUSE_MAX    = 2.2;

    private static final double WANDER_MIN_TRAVEL_BETWEEN_NUDGES = 1.75;
    private static final int    WANDER_MAX_NUDGES_PER_LEG        = 2;
    private static final double WANDER_MIN_HEADING_CHANGE_DEG    = 25.0;

    // State
    private State state = State.INIT;
    private State lastLoggedState = null;
    private static final boolean DEBUG = true;

    private static String tag(Unit u) {
        String name = (u.getActor()!=null && u.getActor().getName()!=null) ? u.getActor().getName() : ("Unit#"+u.getId());
        return "[BOWMAN " + name + " id=" + u.getId() + "]";
    }
    private static String fmt(double v){ return String.format("%.2f", v); }
    private static void log(Unit u, world.World w, String msg){
        if (!DEBUG) return;
        System.out.println(tag(u)+" t="+fmt(w.nowSeconds())+" "+msg);
    }
    private void logStateIfChanged(world.World w, Unit u){
        if (state != lastLoggedState) {
            lastLoggedState = state;
            log(u, w, "STATE → " + state + (hasTarget()?(" tgt="+targetId):""));
        }
    }

    // ---------- Small math ----------
    private static double sq(double v){ return v*v; }
    private double rand(double a, double b){ return a + rng.nextDouble()*(b-a); }
    private static double deg(double rad){ return rad * 180.0 / Math.PI; }

    // ---------- Nav helpers ----------
    private void setNavTarget(int r, int c) {
        navTargetR = r; navTargetC = c;
        lastDistToTarget = Double.POSITIVE_INFINITY;
        repathTimer = 0.0;
    }
    private boolean hasNavTarget() { return navTargetR != Integer.MIN_VALUE; }
    private double distToNav(Unit u) { return Math.hypot(navTargetR - u.getY(), navTargetC - u.getX()); }
    private void clearNavTarget() { navTargetR = navTargetC = Integer.MIN_VALUE; }

    private void repathToNavIfStuck(world.World world, Unit u, double dt, double dToNav) {
        repathTimer -= dt;
        boolean notProgressing = (dToNav > lastDistToTarget - 0.05);
        if (repathTimer <= 0.0 && (!u.isMoving() || notProgressing)) {
            if (!world.commandMove(u, navTargetR, navTargetC)) {
                var p = world.findPath(u, navTargetR, navTargetC);
                if (p != null) u.setPath(p);
            }
            repathTimer = repathCooldown;
            lastDistToTarget = dToNav;
        }
    }

    // ---------- Core ----------
    @Override
    public void update(world.World world, Unit u, double dt) {
        // If you have a BOWMAN role, you can guard here:
        // if (u.getRole() != Unit.UnitRole.BOWMAN) return;

        if (u.isDead()) return;

        // While we have a target and the team's trace is active, aim toward LIVE position.
        if (hasTarget()) {
            if (isTraceActive(world, u)) {
                Unit tgt = getHostileById(world, u, targetId);
                if (tgt != null) {
                    lastSeenX = tgt.getX(); lastSeenY = tgt.getY();
                    u.setAimTarget(lastSeenX, lastSeenY);
                } else {
                    refreshLastSeenFromBoard(world, u);
                    u.setAimTarget(lastSeenX, lastSeenY);
                }
            } else {
                u.clearAimTarget();
            }
        } else {
            u.clearAimTarget();
        }

        logStateIfChanged(world, u);

        switch (state) {
            case INIT -> {
                clearTarget();
                clearNavTarget();
                haveRing = false;
                state = State.SEARCH;
                logStateIfChanged(world, u);
            }

            case SEARCH -> {
                // Prefer sightings from the TEAM board right now (enemy team + wolves)
                TeamSightings.Sighting spot = pickClosestHostileSighting(world, u);
                if (spot != null) {
                    setTargetFromSighting(spot);

                    Unit live = getHostileById(world, u, targetId);
                    if (live != null) {
                        u.setAimTarget(live.getX(), live.getY());
                        if (!tryApproachForShot(world, u, live)) goToLastSeen(world, u);
                    } else {
                        u.setAimTarget(lastSeenX, lastSeenY);
                        goToLastSeen(world, u);
                    }
                    state = State.MOVE_TO_SHOT;
                    logStateIfChanged(world, u);
                    break;
                }

                // If we already have a target and trace is valid (<5s unseen), pursue it
                if (hasTarget() && isTraceActive(world, u)) {
                    goToLastSeen(world, u);
                    state = State.MOVE_TO_SHOT;
                    logStateIfChanged(world, u);
                    break;
                }

                // Nothing to shoot: meander
                tickSearchWander(world, u, dt);
            }

            case MOVE_TO_SHOT -> {
                if (!hasTarget()) { clearNavTarget(); state = State.SEARCH; break; }
                if (!isTraceActive(world, u)) { clearTarget(); clearNavTarget(); state = State.SEARCH; break; }

                // Coming from SHOOT with no nav? Drop stale ring to force recompute.
                if (!hasNavTarget()) { haveRing = false; ringStickTimer = 0; }

                Unit tgt = getHostileById(world, u, targetId);
                if (tgt == null) {
                    refreshLastSeenFromBoard(world, u);
                    goToLastSeen(world, u);
                    break;
                }
                if (tgt.isDead()) {
                    clearNavTarget();
                    u.clearAimTarget();
                    clearTarget();
                    state = State.SEARCH;
                    break;
                }

                double d = Math.hypot(tgt.getY() - u.getY(), tgt.getX() - u.getX());
                if (d <= BOW_RANGE - 0.5) {
                    clearNavTarget();
                    log(u, world, "MOVE_TO_SHOT: in range (" + fmt(d) + "), -> SHOOT");
                    state = State.SHOOT;
                    break;
                }

                // Sticky ring ride / arrival
                if (haveRing && ringStickTimer > 0) {
                    double rcx = ringC + 0.5, rcy = ringR + 0.5;
                    double dnRing = Math.hypot(rcy - u.getY(), rcx - u.getX());

                    if (dnRing <= ARRIVE_EPS) {
                        clearNavTarget();
                        double dNow = Math.hypot(tgt.getY() - u.getY(), tgt.getX() - u.getX());
                        if (dNow <= BOW_RANGE - 0.5) {
                            state = State.SHOOT;
                            return;
                        } else {
                            haveRing = false; ringStickTimer = 0;
                            if (!tryApproachForShot(world, u, tgt)) {
                                int rr = (int)Math.round(tgt.getY()), cc = (int)Math.round(tgt.getX());
                                setNavTarget(rr, cc);
                                if (!world.commandMove(u, rr, cc)) {
                                    var p = world.findPath(u, rr, cc);
                                    if (p != null) u.setPath(p);
                                }
                            }
                            return;
                        }
                    } else {
                        if (!hasNavTarget() || navTargetR != ringR || navTargetC != ringC) {
                            setNavTarget(ringR, ringC);
                        }
                    }
                }

                // No ring or stick expired → (re)plan approach
                if (!haveRing || ringStickTimer <= 0) {
                    if (!tryApproachForShot(world, u, tgt)) {
                        int rr = (int)Math.round(tgt.getY()), cc = (int)Math.round(tgt.getX());
                        setNavTarget(rr, cc);
                        if (!world.commandMove(u, rr, cc)) {
                            var p = world.findPath(u, rr, cc);
                            if (p != null) u.setPath(p);
                        }
                    }
                }

                // Watchdog
                if (hasNavTarget()) {
                    double dn = distToNav(u);
                    repathToNavIfStuck(world, u, dt, dn);
                    if (dn <= ARRIVE_EPS) clearNavTarget();
                }
            }

            case SHOOT -> {
                if (!hasTarget()) { state = State.SEARCH; break; }
                if (!isTraceActive(world, u)) { clearTarget(); state = State.SEARCH; break; }

                Unit tgt = getHostileById(world, u, targetId);
                if (tgt == null) { clearTarget(); state = State.SEARCH; break; }
                if (tgt.isDead()) {
                    clearNavTarget();
                    u.clearAimTarget();
                    clearTarget();
                    state = State.SEARCH;
                    break;
                }
                double d = Math.hypot(tgt.getY()-u.getY(), tgt.getX()-u.getX());

                // If target ran out of range or LOS blocked, immediately pursue again
                boolean losOk = hasLineOfSight(world, u.getY(), u.getX(), tgt.getY(), tgt.getX());
                if (d > BOW_RANGE - 0.25 || !losOk) {
                    haveRing = false; ringStickTimer = 0;
                    state = State.MOVE_TO_SHOT;
                    break;
                }

                // Aim toward target before firing
                boolean aligned = aimToward(u, tgt.getX(), tgt.getY(), dt);
                if (!aligned) break;

                double now = world.nowSeconds();
                if (now >= nextShotAt) {
                    world.fireArrowShot(u, tgt);
                    nextShotAt = now + u.getRangedCooldownSec();
                }
            }

            case IDLE -> { /* not used */ }
        }
    }

    /* ---------- Board/trace helpers ---------- */

    private boolean hasTarget() { return targetId != null; }

    // Use nanoTime against the board entry (safe even if expireOld runs separately)
    private boolean isTraceActive(world.World world, Unit me) {
        if (!hasTarget()) return false;
        Map<Integer, TeamSightings.Sighting> book = world.getSightingsForTeam(me.getTeam());
        if (book == null) return false;
        TeamSightings.Sighting s = book.get(targetId);
        if (s == null) return false;
        long ageNanos = System.nanoTime() - s.seenNanos;
        return ageNanos <= (long)(TRACE_TTL_SEC * 1e9);
    }

    private void refreshLastSeenFromBoard(world.World world, Unit me){
        var book = world.getTeamSightings().view(me.getTeam());
        var s = (targetId != null) ? book.get(targetId) : null;
        if (s != null) { lastSeenX = s.x; lastSeenY = s.y; }
    }

    private void setTarget(Unit tgt) {
        targetId = tgt.getId();
        lastSeenX = tgt.getX(); lastSeenY = tgt.getY();
        haveRing = false;
    }

    private void setTargetFromSighting(TeamSightings.Sighting s) {
        targetId = s.unitId;
        lastSeenX = s.x; lastSeenY = s.y;
        haveRing = false;
    }

    private void clearTarget() {
        targetId = null;
        haveRing = false;
    }

    /** Hostile = opposite human team (RED<->BLUE) OR WOLF. */
    private boolean isHostile(Unit me, Unit other) {
        if (other.getTeam() == Team.WOLF) return true;
        // treat only RED/BLUE as human teams; ignore NEUTRAL buildings etc.
        if ((me.getTeam() == Team.RED && other.getTeam() == Team.BLUE) ||
                (me.getTeam() == Team.BLUE && other.getTeam() == Team.RED)) return true;
        return false;
    }

    private Unit getHostileById(world.World world, Unit me, Integer id) {
        if (id == null) return null;
        for (Unit d : world.getUnits()) {
            if (d.getId() == id && !d.isDead() && isHostile(me, d)) return d;
        }
        return null;
    }

    /** Board-driven: closest hostile sighting for my team. */
    private TeamSightings.Sighting pickClosestHostileSighting(world.World world, Unit me) {
        var book = world.getTeamSightings().view(me.getTeam());
        if (book == null || book.isEmpty()) return null;
        double best = Double.POSITIVE_INFINITY; TeamSightings.Sighting bestS = null;
        for (TeamSightings.Sighting s : book.values()) {
            // Filter: wolves or opposite human team
            if (s.team != Team.WOLF &&
                    !((me.getTeam() == Team.RED && s.team == Team.BLUE) ||
                            (me.getTeam() == Team.BLUE && s.team == Team.RED))) {
                continue;
            }
            double d2 = sq(s.x - me.getX()) + sq(s.y - me.getY());
            if (d2 < best) { best = d2; bestS = s; }
        }
        return bestS;
    }

    /* ---------- Approach ring (same idea as Hunter) ---------- */
    private boolean tryApproachForShot(world.World world, Unit u, Unit tgt) {
        final double desiredR   = Math.max(2.0, BOW_RANGE - 0.75);
        final int    samples    = 18;
        final int    maxSnap    = 3;
        final double maxAllowed = BOW_RANGE - 0.25;

        for (int i = 0; i < samples; i++) {
            double ang = (2*Math.PI) * (i + rng.nextDouble()*0.4) / samples;

            double fx = tgt.getX() + Math.cos(ang) * desiredR;
            double fy = tgt.getY() + Math.sin(ang) * desiredR;

            int tr = (int)Math.round(fy);
            int tc = (int)Math.round(fx);

            int[] snap = findReachableNear(world, u, tr, tc, maxSnap);
            if (snap == null) continue;

            double cx = snap[1] + 0.5;
            double cy = snap[0] + 0.5;
            double dist = Math.hypot(cy - tgt.getY(), cx - tgt.getX());
            if (dist > maxAllowed) continue;

            var path = world.findPath(u, snap[0], snap[1]);
            if (path != null && !path.isEmpty()) {
                haveRing = true;
                ringR = snap[0]; ringC = snap[1];
                ringStickTimer = RING_STICK_SEC;

                setNavTarget(ringR, ringC);
                u.setPath(path);
                return true;
            }
        }
        return false;
    }

    /** Move toward last known position (snap to reachable nearby). */
    private void goToLastSeen(world.World world, Unit u) {
        int tr = (int)Math.round(lastSeenY);
        int tc = (int)Math.round(lastSeenX);

        int[] dest = findReachableNear(world, u, tr, tc, 5);
        if (dest == null) return;

        setNavTarget(dest[0], dest[1]);
        if (!world.commandMove(u, dest[0], dest[1])) {
            var path = world.findPath(u, dest[0], dest[1]);
            if (path != null && !path.isEmpty()) u.setPath(path);
        }
    }

    /** Spiral search for a reachable near (r,c). Returns {r,c} or null. */
    private int[] findReachableNear(world.World world, Unit u, int r, int c, int maxRadius) {
        if (world.inBoundsRC(r, c) && !world.isBlocked(r, c, u)) return new int[]{r, c};
        for (int rad = 1; rad <= maxRadius; rad++) {
            int r0 = r - rad, r1 = r + rad, c0 = c - rad, c1 = c + rad;
            for (int cc = c0; cc <= c1; cc++) {
                if (world.inBoundsRC(r0, cc) && !world.isBlocked(r0, cc, u)) return new int[]{r0, cc};
                if (world.inBoundsRC(r1, cc) && !world.isBlocked(r1, cc, u)) return new int[]{r1, cc};
            }
            for (int rr = r0 + 1; rr <= r1 - 1; rr++) {
                if (world.inBoundsRC(rr, c0) && !world.isBlocked(rr, c0, u)) return new int[]{rr, c0};
                if (world.inBoundsRC(rr, c1) && !world.isBlocked(rr, c1, u)) return new int[]{rr, c1};
            }
        }
        return null;
    }

    /* ---------- Wander (Hunter parity) ---------- */

    private void startWander(world.World world, Unit u){
        int br = (int)Math.floor(u.getY());
        int bc = (int)Math.floor(u.getX());

        java.awt.Point p = pickNearbyReachableFrom(world, u, br, bc, WANDER_MIN_R, WANDER_MAX_R);
        if (p != null) {
            wTarR = p.y; wTarC = p.x;
            wandering = true;
            wRepathT = 0.0;
            lastWanderDist = Double.POSITIVE_INFINITY;

            wNudgesThisLeg = 0;
            wLastNudgeAtX = u.getX();
            wLastNudgeAtY = u.getY();
            wRetargetT = rand(WANDER_RETARGET_MIN, WANDER_RETARGET_MAX);

            if (!world.commandMove(u, wTarR, wTarC)) {
                var path = world.findPath(u, wTarR, wTarC);
                if (path != null) u.setPath(path);
            }
            if (DEBUG) System.out.println(tag(u)+" WANDER start -> ("+wTarR+","+wTarC+")");
        } else {
            wPause = rand(0.6, 1.0);
            wandering = false;
            if (DEBUG) System.out.println(tag(u)+" WANDER no target; pausing "+String.format("%.2f",wPause));
        }
    }

    private java.awt.Point pickNearbyReachableFrom(world.World world, Unit u, int br, int bc, int minR, int maxR){
        int tries = 16;
        while (tries-- > 0) {
            double ang = rand(0, Math.PI*2), r = rand(minR, maxR);
            int cc = bc + (int)Math.round(Math.cos(ang) * r);
            int rr = br + (int)Math.round(Math.sin(ang) * r);
            if (!world.inBoundsRC(rr, cc) || world.isBlocked(rr, cc, u)) continue;
            var path = world.findPath(u, rr, cc);
            if (path != null && !path.isEmpty()) return new java.awt.Point(cc, rr);
        }
        return null;
    }

    private java.awt.Point nudgeAround(world.World world, Unit u, int baseR, int baseC, int minR, int maxR){
        int tries = 10;
        while (tries-- > 0) {
            double ang = rand(-Math.PI/3, Math.PI/3);
            double r = rand(minR, maxR);
            int rr = baseR + (int)Math.round(Math.sin(ang) * r);
            int cc = baseC + (int)Math.round(Math.cos(ang) * r);
            if (!world.inBoundsRC(rr, cc) || world.isBlocked(rr, cc, u)) continue;
            var path = world.findPath(u, rr, cc);
            if (path != null && !path.isEmpty()) return new java.awt.Point(cc, rr);
        }
        return null;
    }

    private void tickSearchWander(world.World world, Unit u, double dt){
        u.clearAimTarget();

        if (wPause > 0.0) { wPause -= dt; return; }
        if (!wandering || wTarR == Integer.MIN_VALUE) { startWander(world, u); return; }

        double d = Math.hypot(wTarR - u.getY(), wTarC - u.getX());
        wRepathT -= dt;
        wRetargetT -= dt;

        boolean noProgress = (d > lastWanderDist - 0.05);
        if (wRepathT <= 0.0 && (!u.isMoving() || noProgress)) {
            if (!world.commandMove(u, wTarR, wTarC)) {
                var p = world.findPath(u, wTarR, wTarC);
                if (p != null) u.setPath(p);
            }
            wRepathT = wRepathCD;
            lastWanderDist = d;
        }

        double traveledSinceNudge = Math.hypot(u.getY() - wLastNudgeAtY, u.getX() - wLastNudgeAtX);
        if (wRetargetT <= 0.0
                && d > 3.0
                && u.isMoving()
                && traveledSinceNudge >= WANDER_MIN_TRAVEL_BETWEEN_NUDGES
                && wNudgesThisLeg < WANDER_MAX_NUDGES_PER_LEG) {

            var n = nudgeAround(world, u, wTarR, wTarC, 2, 4);
            if (n != null) {
                double curAng = Math.atan2(wTarR - u.getY(), wTarC - u.getX());
                double newAng = Math.atan2(n.y - u.getY(), n.x - u.getX());
                double headingDelta = Math.abs(Math.atan2(Math.sin(newAng - curAng), Math.cos(newAng - curAng)));

                if (deg(headingDelta) >= WANDER_MIN_HEADING_CHANGE_DEG) {
                    wTarR = n.y; wTarC = n.x;
                    if (!world.commandMove(u, wTarR, wTarC)) {
                        var p = world.findPath(u, wTarR, wTarC);
                        if (p != null) u.setPath(p);
                    }
                    wRetargetT = rand(WANDER_RETARGET_MIN, WANDER_RETARGET_MAX);
                    wLastNudgeAtX = u.getX(); wLastNudgeAtY = u.getY();
                    wNudgesThisLeg++;
                    if (DEBUG) System.out.println(tag(u)+" WANDER nudge -> ("+wTarR+","+wTarC+")");
                } else {
                    wRetargetT = 1.2;
                }
            } else {
                wRetargetT = 1.2;
            }
        }

        if (Math.hypot(wTarR - u.getY(), wTarC - u.getX()) <= ARRIVE_EPS) {
            wandering = false;
            wTarR = wTarC = Integer.MIN_VALUE;
            wPause = rand(WANDER_PAUSE_MIN, WANDER_PAUSE_MAX);
            if (DEBUG) System.out.println(tag(u)+" WANDER arrived; pausing "+String.format("%.2f",wPause));
        }
    }

    /* ---------- Aiming & LOS (Hunter parity) ---------- */

    /** Turn toward (tx,ty). Returns true if sufficiently aligned to shoot. */
    private boolean aimToward(Unit u, double tx, double ty, double dt) {
        double desired = Math.atan2(ty - u.getY(), tx - u.getX());
        double cur = u.getOrientRad();

        double diff = Math.atan2(Math.sin(desired - cur), Math.cos(desired - cur));
        double maxStep = Math.PI * dt; // 180°/s
        if (Math.abs(diff) <= maxStep) cur = desired;
        else cur += Math.copySign(maxStep, diff);

        if (cur <= -Math.PI) cur += 2 * Math.PI;
        if (cur >   Math.PI) cur -= 2 * Math.PI;
        u.setOrientRad(cur);

        double ang = cur < 0 ? cur + 2*Math.PI : cur;
        int sector = (int) Math.round(ang / (Math.PI/4.0)) & 7; // 0..7
        switch (sector) {
            case 0 -> u.setFacing(Unit.Facing.E);
            case 1 -> u.setFacing(Unit.Facing.SE);
            case 2 -> u.setFacing(Unit.Facing.S);
            case 3 -> u.setFacing(Unit.Facing.SW);
            case 4 -> u.setFacing(Unit.Facing.W);
            case 5 -> u.setFacing(Unit.Facing.NW);
            case 6 -> u.setFacing(Unit.Facing.N);
            case 7 -> u.setFacing(Unit.Facing.NE);
        }

        double dot = Math.cos(desired - cur);
        return dot >= AIM_DOT_MIN;
    }

    /** Tile LOS (Bresenham). */
    private boolean hasLineOfSight(world.World world, double r0, double c0, double r1, double c1) {
        int x0 = (int)Math.floor(c0), y0 = (int)Math.floor(r0);
        int x1 = (int)Math.floor(c1), y1 = (int)Math.floor(r1);
        int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        while (true) {
            if (world.inBoundsRC(y0, x0) && world.isOpaque(y0, x0)) return false;
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
        return true;
    }
}


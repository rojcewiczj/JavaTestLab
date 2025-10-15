package intelligence;

import characters.Team;
import characters.Unit;

import java.util.Map;
import java.util.Random;

/** Mounted melee AI (2-tile ok): hunts enemy humans (opposite team) and wolves. */
public class HorseManAI implements Unit.UnitAI {

    private enum State { INIT, SEARCH, MOVE_TO_STRIKE, STRIKE, IDLE }

    private final Random rng = new Random();

    // --- Tunables (parity with WolfAI / ManAtArmsAI) ---
    private static final double MELEE_RANGE    = 1.5;
    private static final double TRACE_TTL_SEC  = 5.0;
    private static final double SWITCH_MARGIN  = 0.5;
    private static final double ARRIVE_EPS     = 0.6;
    private static final double AIM_DOT_MIN    = 0.95;
    private static final double RING_STICK_SEC = 1.2;

    // Target memory ("trace")
    private Integer targetId = null;
    private double lastSeenX = 0, lastSeenY = 0;

    // Nav target + watchdog
    private int    navTargetR = Integer.MIN_VALUE, navTargetC = Integer.MIN_VALUE;
    private double lastDistToTarget = Double.POSITIVE_INFINITY;
    private double repathCooldown   = 0.8;
    private double repathTimer      = 0.0;

    // Sticky approach ring
    private boolean haveRing = false;
    private int ringR, ringC;
    private double ringStickTimer = 0.0;

    // Wander (same as WolfAI)
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
        return "[HORSEMAN " + name + " id=" + u.getId() + "]";
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

    @Override
    public void update(world.World world, Unit u, double dt) {
        if (u.isDead()) return;

        // Maintain trace: aim at ACTUAL target while trace is active.
        if (hasTarget()) {
            if (isTraceActive(world, u)) {
                Unit tgt = getEnemyOrWolfById(world, u.getTeam(), targetId);
                if (tgt != null) u.setAimTarget(tgt.getX(), tgt.getY());
                else             u.setAimTarget(lastSeenX, lastSeenY);
            } else {
                u.clearAimTarget();
            }
        } else {
            u.clearAimTarget();
        }

        // timers
        repathTimer   -= dt;
        wPause        -= dt;
        wRepathT      -= dt;
        if (ringStickTimer > 0) ringStickTimer -= dt;

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
                // 1) Board-driven closest pick (enemy human or wolf)
                TeamSightings.Sighting cand = pickClosestFromBoard(world, u);
                if (cand != null && isBetterThanCurrent(u, cand)) {
                    adoptSighting(cand);
                    u.setAimTarget(lastSeenX, lastSeenY);

                    Unit live = getEnemyOrWolfById(world, u.getTeam(), targetId);
                    if (live != null && !tryApproachForStrike(world, u, live)) {
                        goToLastSeen(world, u);
                    } else if (live == null) {
                        goToLastSeen(world, u);
                    }

                    state = State.MOVE_TO_STRIKE;
                    logStateIfChanged(world, u);
                    break;
                }

                // 2) Keep chasing current if its trace is alive
                if (hasTarget() && isTraceActive(world, u)) {
                    goToLastSeen(world, u);
                    state = State.MOVE_TO_STRIKE;
                    logStateIfChanged(world, u);
                    break;
                }

                // 3) Meander
                tickSearchWander(world, u, dt);
            }

            case MOVE_TO_STRIKE -> {
                if (!hasTarget()) {
                    log(u, world, "MOVE_TO_STRIKE: no target; SEARCH");
                    clearNavTarget();
                    state = State.SEARCH; break;
                }
                if (!isTraceActive(world, u)) {
                    log(u, world, "MOVE_TO_STRIKE: trace expired (" + fmt(traceAgeSec(world, u)) + "s); clearing target");
                    clearTarget();
                    clearNavTarget();
                    state = State.SEARCH; break;
                }

                Unit tgt = getEnemyOrWolfById(world, u.getTeam(), targetId);
                if (tgt == null) {
                    log(u, world, "MOVE_TO_STRIKE: target missing; SEARCH");
                    clearTarget();
                    clearNavTarget();
                    state = State.SEARCH; break;
                }
                if (tgt.isDead()) {
                    clearNavTarget();
                    u.clearAimTarget();
                    clearTarget();
                    state = State.SEARCH; break;
                }

                double d = Math.hypot(tgt.getY() - u.getY(), tgt.getX() - u.getX());
                if (d <= MELEE_RANGE - 0.10) {
                    clearNavTarget();
                    log(u, world, "MOVE_TO_STRIKE: in range (" + fmt(d) + "), -> STRIKE");
                    state = State.STRIKE; break;
                }

                // If sticky ring exists, ride it until arrival/expire
                if (haveRing && ringStickTimer > 0) {
                    double rcx = ringC + 0.5, rcy = ringR + 0.5;
                    double dnRing = Math.hypot(rcy - u.getY(), rcx - u.getX());

                    if (dnRing <= ARRIVE_EPS) {
                        clearNavTarget();
                        double dNow = Math.hypot(tgt.getY() - u.getY(), tgt.getX() - u.getX());
                        if (dNow <= MELEE_RANGE - 0.10) { state = State.STRIKE; return; }
                        // recompute approach
                        haveRing = false; ringStickTimer = 0;
                        if (!tryApproachForStrike(world, u, tgt)) {
                            int rr = (int)Math.round(tgt.getY()), cc = (int)Math.round(tgt.getX());
                            setNavTarget(rr, cc);
                            if (!world.commandMove(u, rr, cc)) {
                                var p = world.findPath(u, rr, cc);
                                if (p != null) u.setPath(p);
                            }
                        }
                        return;
                    } else {
                        if (!hasNavTarget() || navTargetR != ringR || navTargetC != ringC) {
                            setNavTarget(ringR, ringC);
                        }
                    }
                }

                // (Re)plan ring if none or stick expired
                if (!haveRing || ringStickTimer <= 0) {
                    if (!tryApproachForStrike(world, u, tgt)) {
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

            case STRIKE -> {
                if (!hasTarget()) { log(u, world, "STRIKE: no target; SEARCH"); state = State.SEARCH; break; }
                if (!isTraceActive(world, u)) { log(u, world, "STRIKE: trace expired; SEARCH"); clearTarget(); state = State.SEARCH; break; }

                Unit tgt = getEnemyOrWolfById(world, u.getTeam(), targetId);
                if (tgt == null) { log(u, world, "STRIKE: target missing; SEARCH"); clearTarget(); state = State.SEARCH; break; }
                if (tgt.isDead()) {
                    clearNavTarget();
                    u.clearAimTarget();
                    clearTarget();
                    state = State.SEARCH; break;
                }

                double d = Math.hypot(tgt.getY()-u.getY(), tgt.getX()-u.getX());
                boolean losOk = world.hasLineOfSight(u.getRowRounded(), u.getColRounded(),
                        tgt.getRowRounded(), tgt.getColRounded());
                if (d > MELEE_RANGE - 0.05 || !losOk) {
                    haveRing = false; ringStickTimer = 0; // recompute approach
                    log(u, world, "STRIKE: need reposition (d="+fmt(d)+", los="+losOk+"); -> MOVE_TO_STRIKE");
                    state = State.MOVE_TO_STRIKE; break;
                }

                // Aim (same gate as bow/wolf)
                boolean aligned = aimToward(u, tgt.getX(), tgt.getY(), dt);
                if (!aligned) { log(u, world, "STRIKE: aligning..."); break; }

                double now = world.nowSeconds();
                if (now >= u.getNextMeleeAt()) {
                    world.resolveMeleeHit(u, tgt);
                    u.setNextMeleeAt(now + u.getMeleeCooldownSec());
                    log(u, world, "STRIKE: hit target "+tgt.getId()+" d="+fmt(d));
                }
            }

            case IDLE -> { /* not used */ }
        }
    }

    /* ---------- Targeting & trace ---------- */

    private boolean hasTarget() { return targetId != null; }

    private boolean isTraceActive(world.World world, Unit me) {
        if (!hasTarget()) return false;
        Map<Integer, TeamSightings.Sighting> book = world.getSightingsForTeam(me.getTeam());
        if (book == null) return false;
        var s = book.get(targetId);
        if (s == null) return false;
        long ageNanos = System.nanoTime() - s.seenNanos;
        return ageNanos <= (long)(TRACE_TTL_SEC * 1e9);
    }

    private double traceAgeSec(world.World world, Unit me){
        if (!hasTarget()) return Double.POSITIVE_INFINITY;
        var book = world.getSightingsForTeam(me.getTeam());
        if (book == null) return Double.POSITIVE_INFINITY;
        var s = book.get(targetId);
        if (s == null) return Double.POSITIVE_INFINITY;
        return (System.nanoTime() - s.seenNanos) / 1e9;
    }

    private void adoptSighting(TeamSightings.Sighting s){
        targetId = s.unitId;
        lastSeenX = s.x; lastSeenY = s.y;
        haveRing = false; ringStickTimer = 0;
    }

    private void clearTarget() {
        targetId = null;
        haveRing = false; ringStickTimer = 0;
    }

    /** True if 'other' is an enemy human (opposite color) or a wolf. */
    private static boolean isEnemyForHorseman(Team myTeam, Unit other){
        if (other.getTeam() == Team.WOLF) return true;
        // treat RED/BLUE as human factions
        if ((other.getTeam() == Team.RED || other.getTeam() == Team.BLUE) && other.getTeam() != myTeam) return true;
        return false;
    }

    private Unit getEnemyOrWolfById(world.World world, Team myTeam, Integer id) {
        if (id == null) return null;
        for (Unit d : world.getUnits()) {
            if (d.getId() == id && !d.isDead() && isEnemyForHorseman(myTeam, d)) return d;
        }
        return null;
    }

    /** Board-driven: closest allowed sighting (enemy human or wolf). */
    private TeamSightings.Sighting pickClosestFromBoard(world.World world, Unit me) {
        Map<Integer, TeamSightings.Sighting> book = world.getSightingsForTeam(me.getTeam());
        if (book == null || book.isEmpty()) return null;

        double best = Double.POSITIVE_INFINITY; TeamSightings.Sighting bestS = null;
        for (TeamSightings.Sighting s : book.values()) {
            // allowed: WOLF or HUMAN of opposite team
            boolean allowed =
                    (s.actorType == characters.ActorType.WOLF) ||
                            (s.actorType == characters.ActorType.HUMAN && s.team != me.getTeam());
            if (!allowed) continue;

            // prefer live position if we have it
            Unit live = getEnemyOrWolfById(world, me.getTeam(), s.unitId);
            double tx = (live != null ? live.getX() : s.x);
            double ty = (live != null ? live.getY() : s.y);

            double d2 = sq(tx - me.getX()) + sq(ty - me.getY());
            if (d2 < best) { best = d2; bestS = s; }
        }
        return bestS;
    }

    /** Compare a new board candidate vs. current target, with switch margin. */
    private boolean isBetterThanCurrent(Unit me, TeamSightings.Sighting cand) {
        if (cand == null) return false;

        // Distance to candidate (use cand last-seen here; live is handled after adoption)
        double candD2 = sq(cand.x - me.getX()) + sq(cand.y - me.getY());

        if (!hasTarget()) return true;

        // Current distance (prefer live target pos if available)
        double curX = lastSeenX, curY = lastSeenY;
        // (optional) try live:
        // leave as lastSeen; a fair comparison since both might be stale
        double curD2 = sq(curX - me.getX()) + sq(curY - me.getY());

        double margin2 = SWITCH_MARGIN * SWITCH_MARGIN;
        return candD2 + margin2 < curD2;
    }

    /* ---------- Melee approach ring ---------- */
    private boolean tryApproachForStrike(world.World world, Unit u, Unit tgt) {
        final double desiredR   = Math.max(0.7, MELEE_RANGE - 0.25);
        final int    samples    = 18;
        final int    maxSnap    = 3;
        final double maxAllowed = MELEE_RANGE - 0.05;

        for (int i = 0; i < samples; i++) {
            double ang = (2*Math.PI) * (i + rng.nextDouble()*0.4) / samples;

            double fx = tgt.getX() + Math.cos(ang) * desiredR;
            double fy = tgt.getY() + Math.sin(ang) * desiredR;

            int tr = (int)Math.round(fy);
            int tc = (int)Math.round(fx);

            int[] snap = findReachableNear(world, u, tr, tc, maxSnap);
            if (snap == null) continue;

            double cx = snap[1] + 0.5, cy = snap[0] + 0.5;
            double dist = Math.hypot(cy - tgt.getY(), cx - tgt.getX());
            if (dist > maxAllowed) continue;

            var path = world.findPath(u, snap[0], snap[1]);
            if (path != null && !path.isEmpty()) {
                haveRing = true;
                ringR = snap[0]; ringC = snap[1];
                ringStickTimer = RING_STICK_SEC;

                setNavTarget(ringR, ringC);
                u.setPath(path);
                if (DEBUG) System.out.println(tag(u)+" ring set ("+ringR+","+ringC+"), dist="+String.format("%.2f",dist));
                return true;
            }
        }
        if (DEBUG) System.out.println(tag(u)+" no valid ring found this tick");
        return false;
    }

    // Move toward last known position
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

    /* ---------- Wander (Wolf parity) ---------- */

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

    /* ---------- Aiming & facing ---------- */
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
        int sector = (int) Math.round(ang / (Math.PI/4.0)) & 7;
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
}


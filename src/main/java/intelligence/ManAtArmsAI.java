package intelligence;

import java.util.Random;

import characters.Team;
import characters.Unit;
import world.Building;

public class ManAtArmsAI implements Unit.UnitAI {

    private enum State { INIT, SEARCH, MOVE_TO_STRIKE, STRIKE, IDLE }

    private final Random rng = new Random();

    // Tunables (Hunter parity where applicable)
    private static final double MELEE_RANGE = 1.5;
    private static final double TRACE_TTL_SEC = 5.0;     // informational; board enforces TTL
    private static final double SWITCH_MARGIN = 0.5;     // only switch if strictly closer than this
    private static final double ARRIVE_EPS = 0.6;

    // Aiming / LOS gating (same as Hunter)
    private static final double AIM_DOT_MIN = 0.95;      // ~18° cone

    // Target memory ("trace")
    private Integer targetId = null;
    private double lastSeenX = 0, lastSeenY = 0;
    private double unseenTimer = 0.0;

    // Nav target + watchdog (same as Hunter)
    private int navTargetR = Integer.MIN_VALUE, navTargetC = Integer.MIN_VALUE;
    private double lastDistToTarget = Double.POSITIVE_INFINITY;
    private double repathCooldown = 0.8;
    private double repathTimer = 0.0;

    // Approach ring (same mechanism as Hunter)
    private boolean haveRing = false;
    private int ringR, ringC;
    private double ringStickTimer = 0.0;
    private static final double RING_STICK_SEC = 1.2;

    // Wander (same controller as Hunter)
    private boolean wandering = false;
    private int wTarR = Integer.MIN_VALUE, wTarC = Integer.MIN_VALUE;
    private double wPause = 0.0;
    private double wRepathCD = 1.5, wRepathT = 0.0;
    private double wRetargetT = 0.0;
    private double lastWanderDist = Double.POSITIVE_INFINITY;

    // Wander nudge throttles (same as Hunter)
    private double wLastNudgeAtX = 0, wLastNudgeAtY = 0;
    private int wNudgesThisLeg = 0;
    private static final int    WANDER_MIN_R = 4;
    private static final int    WANDER_MAX_R = 10;
    private static final double WANDER_RETARGET_MIN = 2.5;
    private static final double WANDER_RETARGET_MAX = 4.0;
    private static final double WANDER_PAUSE_MIN    = 1.0;
    private static final double WANDER_PAUSE_MAX    = 2.2;
    private static final double WANDER_MIN_TRAVEL_BETWEEN_NUDGES = 1.75;
    private static final int    WANDER_MAX_NUDGES_PER_LEG        = 2;
    private static final double WANDER_MIN_HEADING_CHANGE_DEG    = 25.0;

    // Local state
    private State state = State.INIT;
    private State lastLoggedState = null;

    private static final boolean DEBUG = true;
    private final Building barracks;
    private final Team team;
    public ManAtArmsAI(Building barracks, Team team) {
        this.barracks = barracks;
        this.team = team;
    }
    private static String tag(Unit u) {
        String name = (u.getActor()!=null && u.getActor().getName()!=null) ? u.getActor().getName() : ("Unit#"+u.getId());
        return "[MAA " + name + " id=" + u.getId() + "]";
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

    @Override
    public void update(world.World world, Unit u, double dt) {
        if (u.isDead()) return;

        // Maintain trace: aim at live position while board trace is active
        if (hasTarget()) {
            if (isTraceActive(world, u)) {
                Unit wolf = getWolfById(world, targetId);
                if (wolf != null) {
                    lastSeenX = wolf.getX(); lastSeenY = wolf.getY();
                    u.setAimTarget(lastSeenX, lastSeenY);
                } else {
                    // No live object -> keep aiming at board last-seen
                    refreshLastSeenFromBoard(world, u);
                    u.setAimTarget(lastSeenX, lastSeenY);
                }
            } else {
                u.clearAimTarget();
            }
        } else {
            u.clearAimTarget();
        }

        // timers
        repathTimer -= dt;
        wPause      -= dt;
        wRepathT    -= dt;
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
                // --- Board-driven closest wolf sighting (ActorType.WOLF) ---
                TeamSightings.Sighting spot =
                        world.getTeamSightings().closestOfActorType(u.getTeam(), u.getX(), u.getY(),
                                characters.ActorType.WOLF);

                // Decide whether to adopt/switch (margin like Hunter)
                boolean shouldAdopt = false;
                double candD2 = Double.POSITIVE_INFINITY;
                if (spot != null) {
                    candD2 = sq(spot.x - u.getX()) + sq(spot.y - u.getY());
                    if (!hasTarget() || !isTraceActive(world, u)) {
                        shouldAdopt = true;
                    } else {
                        // Compare against current target distance (live if available, else lastSeen)
                        Unit curLive = getWolfById(world, targetId);
                        double cx = (curLive != null) ? curLive.getX() : lastSeenX;
                        double cy = (curLive != null) ? curLive.getY() : lastSeenY;
                        double curD2 = sq(cx - u.getX()) + sq(cy - u.getY());
                        double margin2 = SWITCH_MARGIN * SWITCH_MARGIN;
                        if (candD2 + margin2 < curD2) shouldAdopt = true;
                    }
                }

                if (shouldAdopt) {
                    setTargetFromSighting(spot);   // also updates lastSeenX/Y
                    u.setAimTarget(lastSeenX, lastSeenY);
                    haveRing = false; ringStickTimer = 0;

                    Unit live = getWolfById(world, targetId);
                    if (live != null) {
                        if (!tryApproachForStrike(world, u, live)) {
                            goToLastSeen(world, u);
                        }
                    } else {
                        goToLastSeen(world, u);
                    }

                    state = State.MOVE_TO_STRIKE;
                    logStateIfChanged(world, u);
                    break;
                }

                // Keep pursuing current if board trace is alive
                if (hasTarget() && isTraceActive(world, u)) {
                    goToLastSeen(world, u);
                    state = State.MOVE_TO_STRIKE;
                    logStateIfChanged(world, u);
                    break;
                }

                // Nothing to fight: wander
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
                    state = State.SEARCH;
                    break;
                }

                Unit wolf = getWolfById(world, targetId);
                if (wolf == null) {
                    // Still have trace on the board; keep chasing last-seen
                    refreshLastSeenFromBoard(world, u);
                    goToLastSeen(world, u);
                    break;
                }
                if (wolf.isDead()) {
                    clearNavTarget();
                    u.clearAimTarget();
                    clearTarget();
                    state = State.SEARCH;
                    break;
                }

                double d = Math.hypot(wolf.getY() - u.getY(), wolf.getX() - u.getX());
                if (d <= MELEE_RANGE - 0.10) {
                    clearNavTarget();
                    log(u, world, "MOVE_TO_STRIKE: in range (" + fmt(d) + "), switching to STRIKE");
                    state = State.STRIKE;
                    break;
                }

                // Sticky ring ride / arrival handling
                if (haveRing && ringStickTimer > 0) {
                    double rcx = ringC + 0.5, rcy = ringR + 0.5;
                    double dnRing = Math.hypot(rcy - u.getY(), rcx - u.getX());

                    if (dnRing <= ARRIVE_EPS) {
                        clearNavTarget();
                        double dNow = Math.hypot(wolf.getY() - u.getY(), wolf.getX() - u.getX());
                        if (dNow <= MELEE_RANGE - 0.10) {
                            state = State.STRIKE; return;
                        } else {
                            haveRing = false; ringStickTimer = 0;
                            if (!tryApproachForStrike(world, u, wolf)) {
                                int rr = (int)Math.round(wolf.getY()), cc = (int)Math.round(wolf.getX());
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

                // (Re)plan approach
                if (!haveRing || ringStickTimer <= 0) {
                    if (!tryApproachForStrike(world, u, wolf)) {
                        int rr = (int)Math.round(wolf.getY()), cc = (int)Math.round(wolf.getX());
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

                Unit wolf = getWolfById(world, targetId);
                if (wolf == null) { // still have board trace; reposition
                    refreshLastSeenFromBoard(world, u);
                    state = State.MOVE_TO_STRIKE; break;
                }
                if (wolf.isDead()) {
                    clearNavTarget();
                    u.clearAimTarget();
                    clearTarget();
                    state = State.SEARCH; break;
                }

                double d = Math.hypot(wolf.getY()-u.getY(), wolf.getX()-u.getX());
                boolean losOk = hasLineOfSight(world, u.getY(), u.getX(), wolf.getY(), wolf.getX());
                if (d > MELEE_RANGE - 0.05 || !losOk) {
                    haveRing = false; ringStickTimer = 0;
                    log(u, world, "STRIKE: need reposition (d="+fmt(d)+", los="+losOk+"); -> MOVE_TO_STRIKE");
                    state = State.MOVE_TO_STRIKE; break;
                }

                boolean aligned = aimToward(u, wolf.getX(), wolf.getY(), dt);
                if (!aligned) { log(u, world, "STRIKE: aligning..."); break; }

                double now = world.nowSeconds();
                if (now >= u.getNextMeleeAt()) {
                    world.resolveMeleeHit(u, wolf);
                    u.setNextMeleeAt(now + u.getMeleeCooldownSec());
                    log(u, world, "STRIKE: swing at wolf "+wolf.getId()+" d="+fmt(d));
                }
            }

            case IDLE -> { /* not used yet */ }
        }
    }

    /* ---------- helpers mirroring HunterAI (wolf-flavored) ---------- */

    private boolean hasTarget() { return targetId != null; }

    /** Board governs TTL; this just checks presence on the viewer team's book. */
    private boolean isTraceActive(world.World world, Unit me) {
        if (!hasTarget()) return false;
        return world.getTeamSightings().hasActive(me.getTeam(), targetId);
    }

    private double traceAgeSec(world.World world, Unit me){
        if (!hasTarget()) return Double.POSITIVE_INFINITY;
        var book = world.getTeamSightings().view(me.getTeam());
        var s = book.get(targetId);
        if (s == null) return Double.POSITIVE_INFINITY;
        return (System.nanoTime() - s.seenNanos) / 1e9;
    }

    private void refreshLastSeenFromBoard(world.World world, Unit me){
        if (!hasTarget()) return;
        var s = world.getTeamSightings().view(me.getTeam()).get(targetId);
        if (s != null) { lastSeenX = s.x; lastSeenY = s.y; }
    }

    private void setTarget(Unit wolf) {
        targetId = wolf.getId();
        lastSeenX = wolf.getX(); lastSeenY = wolf.getY();
        unseenTimer = 0.0;
        haveRing = false;
    }

    private void setTargetFromSighting(TeamSightings.Sighting s) {
        if (s == null) { clearTarget(); return; }
        targetId = s.unitId;
        lastSeenX = s.x; lastSeenY = s.y;   // IMPORTANT: update last seen from board
        unseenTimer = 0.0;
        haveRing = false;
    }

    private void clearTarget() {
        targetId = null;
        unseenTimer = 0.0;
        haveRing = false;
    }

    private Unit getWolfById(world.World world, Integer id) {
        if (id == null) return null;
        for (Unit d : world.getUnits()) {
            if (d.getTeam() == Team.WOLF && !d.isDead() && d.getId() == id) return d;
        }
        return null;
    }

    /** Choose a reachable cell on a ring that guarantees Euclidean distance <= MELEE_RANGE - 0.05 */
    private boolean tryApproachForStrike(world.World world, Unit u, Unit wolf) {
        final double desiredR = Math.max(0.7, MELEE_RANGE - 0.25);  // slightly inside melee
        final int samples = 18;
        final int maxSnap = 3;
        final double maxAllowed = MELEE_RANGE - 0.05;

        for (int i = 0; i < samples; i++) {
            double ang = (2*Math.PI) * (i + rng.nextDouble()*0.4) / samples;

            double fx = wolf.getX() + Math.cos(ang) * desiredR;
            double fy = wolf.getY() + Math.sin(ang) * desiredR;

            int tr = (int)Math.round(fy);
            int tc = (int)Math.round(fx);

            int[] snap = findReachableNear(world, u, tr, tc, maxSnap);
            if (snap == null) continue;

            double cx = snap[1] + 0.5, cy = snap[0] + 0.5;
            double dist = Math.hypot(cy - wolf.getY(), cx - wolf.getX());
            if (dist > maxAllowed) continue; // must be within melee after snapping

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

    // --- (unused now) direct-visibility helpers kept for reference ---
    private boolean isTeamVisible(world.World world, Unit u) {
        int rr = (int)Math.floor(u.getY()), cc = (int)Math.floor(u.getX());
        return world.isVisible(rr, cc);
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

    private static double deg(double rad){ return rad * 180.0 / Math.PI; }

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

        if (arrived(u, wTarR, wTarC, ARRIVE_EPS)) {
            wandering = false;
            wTarR = wTarC = Integer.MIN_VALUE;
            wPause = rand(WANDER_PAUSE_MIN, WANDER_PAUSE_MAX);
            if (DEBUG) System.out.println(tag(u)+" WANDER arrived; pausing "+String.format("%.2f",wPause));
        }
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

    // --- movement/nav (Hunter parity)
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
        }
        repathTimer = repathCooldown;
        lastDistToTarget = dToNav;
    }

    // --- aiming & LOS (Hunter parity)
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

    /** Bresenham LOS (same as Hunter). */
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
    private void startWander(world.World world, Unit u){
        // Base the wander center on the unit's current tile
        int br = (int)Math.floor(u.getY());
        int bc = (int)Math.floor(u.getX());

        java.awt.Point p = pickNearbyReachableFrom(world, u, br, bc, WANDER_MIN_R, WANDER_MAX_R);
        if (p != null) {
            wTarR = p.y;
            wTarC = p.x;
            wandering = true;

            // reset watchdogs/throttles for this leg
            wRepathT = 0.0;
            lastWanderDist = Double.POSITIVE_INFINITY;
            wNudgesThisLeg = 0;
            wLastNudgeAtX = u.getX();
            wLastNudgeAtY = u.getY();
            wRetargetT = rand(WANDER_RETARGET_MIN, WANDER_RETARGET_MAX);

            // issue movement
            if (!world.commandMove(u, wTarR, wTarC)) {
                var path = world.findPath(u, wTarR, wTarC);
                if (path != null) u.setPath(path);
            }

            if (DEBUG) System.out.println(tag(u)+" WANDER start -> ("+wTarR+","+wTarC+")");
        } else {
            // No reachable wander target right now — short pause and try again later
            wPause = rand(0.6, 1.0);
            wandering = false;
            if (DEBUG) System.out.println(tag(u)+" WANDER no target; pausing "+String.format("%.2f", wPause));
        }
    }

    // math
    private boolean arrived(Unit u, int r, int c, double eps){
        return Math.hypot(r - u.getY(), c - u.getX()) <= eps;
    }
    private double rand(double a, double b){ return a + rng.nextDouble()*(b-a); }
    private static double sq(double v){ return v*v; }
}

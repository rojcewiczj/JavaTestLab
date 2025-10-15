package intelligence;

import characters.Unit;

import java.util.Random;

import static characters.Unit.HunterState.*;

/**
 * Board-driven Hunter AI
 * - Uses TeamSightings for all perception/trace.
 * - Keeps the existing movement/approach ring + bow behavior.
 * - No dependency on player render visibility.
 *
 * NOTE: Call world.updateSightingsForTeam(team) each frame (for all teams)
 *       BEFORE AI ticks so the board is fresh.
 */
public class HunterAI implements Unit.UnitAI {

    private final Random rng = new Random();

    // Tunables
    private static final double BOW_RANGE = 8.0;
    private static final double TRACE_TTL_SEC = 5.0; // governed by TeamSightings TTL, but keep local constant as expectation
    private static final double SWITCH_MARGIN = 0.5; // only switch if strictly closer by this margin

    // Fire cadence (uses Unit's ranged cooldown; this is just a safety)
    private double nextShotAt = 0.0;

    // Target memory ("trace")
    private Integer targetId = null;
    private double lastSeenX = 0, lastSeenY = 0;

    // Nav target + watchdog
    private int navTargetR = Integer.MIN_VALUE, navTargetC = Integer.MIN_VALUE;
    private double lastDistToTarget = Double.POSITIVE_INFINITY;
    private double repathCooldown = 0.8;
    private double repathTimer = 0.0;

    // --- Wander (search) controller ---
    private boolean wandering = false;
    private int wTarR = Integer.MIN_VALUE, wTarC = Integer.MIN_VALUE;
    private double wPause = 0.0;
    private double wRepathCD = 1.5, wRepathT = 0.0;
    private double wRetargetT = 0.0;
    private double lastWanderDist = Double.POSITIVE_INFINITY;

    // NEW: throttle nudges
    private double wLastNudgeAtX = 0, wLastNudgeAtY = 0;
    private int wNudgesThisLeg = 0;

    // wander params
    private static final int    WANDER_MIN_R = 4;
    private static final int    WANDER_MAX_R = 10;
    private static final double WANDER_RETARGET_MIN = 2.5;
    private static final double WANDER_RETARGET_MAX = 4.0;
    private static final double WANDER_PAUSE_MIN    = 1.0;
    private static final double WANDER_PAUSE_MAX    = 2.2;
    private static final double ARRIVE_EPS          = 0.6;

    private static final double WANDER_MIN_TRAVEL_BETWEEN_NUDGES = 1.75; // tiles
    private static final int    WANDER_MAX_NUDGES_PER_LEG        = 2;    // at most 2 nudges per leg
    private static final double WANDER_MIN_HEADING_CHANGE_DEG    = 25.0; // avoid micro-jitters

    // --- Aiming / LOS gating ---
    private static final double AIM_DOT_MIN = 0.95;  // ~18° cone

    // Sticky approach ring to reduce jitter
    private boolean haveRing = false;
    private int ringR, ringC;
    private double ringStickTimer = 0.0;
    private static final double RING_STICK_SEC = 1.2;

    // Debug
    private static final boolean DEBUG = true;
    private static String tag(Unit u) {
        String name = (u.getActor()!=null && u.getActor().getName()!=null) ? u.getActor().getName() : ("Unit#"+u.getId());
        return "[HUNTER " + name + " id=" + u.getId() + "]";
    }
    private static void log(Unit u, String s){ if (DEBUG) System.out.println(tag(u)+" "+s); }
    private Unit.HunterState lastLogged = null;
    private void logState(Unit u){ var s=u.getHunterState(); if(s!=lastLogged){ lastLogged=s; log(u,"STATE → "+s); } }

    // --- small helpers ---
    private void setNavTarget(int r, int c) {
        navTargetR = r; navTargetC = c;
        lastDistToTarget = Double.POSITIVE_INFINITY;
        repathTimer = 0.0;
    }
    private boolean hasNavTarget() { return navTargetR != Integer.MIN_VALUE; }
    private double distToNav(Unit u) { return Math.hypot(navTargetR - u.getY(), navTargetC - u.getX()); }
    private void clearNavTarget() { navTargetR = navTargetC = Integer.MIN_VALUE; }

    @Override
    public void update(world.World world, Unit u, double dt) {
        if (u.getRole() != Unit.UnitRole.HUNTER) return;

        logState(u); // once per transition

        // While we have a target and the team's trace is active, aim toward LIVE position.
        if (hasTarget()) {
            if (isTraceActive(world, u)) {
                Unit tgt = getLiveById(world, targetId); // <-- generic live lookup
                if (tgt != null) {
                    lastSeenX = tgt.getX(); lastSeenY = tgt.getY();
                    u.setAimTarget(lastSeenX, lastSeenY);
                } else {
                    // Live object missing; use last seen from board
                    refreshLastSeenFromBoard(world, u);
                    u.setAimTarget(lastSeenX, lastSeenY);
                }
            } else {
                u.clearAimTarget();
            }
        } else {
            u.clearAimTarget();
        }

        switch (u.getHunterState()) {
            case INIT -> {
                clearTarget();
                clearNavTarget();
                u.setHunterState(GET_BOW);
            }

            case GET_BOW -> {
                var camp = u.getAssignedCamp();
                if (camp == null) { u.setRole(Unit.UnitRole.NONE); u.setHunterState(IDLE); return; }
                int[] ap = world.findApproachTileForBuilding(camp, u);
                if (ap == null)  { u.setRole(Unit.UnitRole.NONE); u.setHunterState(IDLE); return; }

                if (!hasNavTarget()) {
                    setNavTarget(ap[0], ap[1]);
                    log(u, "GET_BOW: heading to camp door at ("+ap[0]+","+ap[1]+")");
                }
                double d = distToNav(u);
                if (d <= 1.2) {
                    u.setHasBow(true);
                    clearNavTarget();
                    log(u, "GET_BOW: armed, entering SEARCH");
                    u.setHunterState(SEARCH);
                    break;
                }
                repathToNavIfStuck(world, u, dt, d);
            }

            case SEARCH -> {
                if (true) {
                    var book = world.getSightingsForTeam(u.getTeam());
                    System.out.println("[HUNTER " + u.getId() + "] boardSize=" + (book==null? -1 : book.size()));
                }

                // 1) Prefer sightings from the TEAM board right now (ActorType-driven board)
                TeamSightings.Sighting spot = pickClosestSighting(world, u); // typically closest DEER (or extend to HORSE)
                if (spot != null) {
                    setTargetFromSighting(spot);

                    // If we already have the live object, plan a ring; else move to last-seen
                    Unit live = getLiveById(world, targetId);
                    if (live != null) {
                        u.setAimTarget(live.getX(), live.getY());
                        if (!tryApproachForShot(world, u, live)) goToLastSeen(world, u);
                    } else {
                        u.setAimTarget(lastSeenX, lastSeenY);
                        goToLastSeen(world, u);
                    }
                    u.setHunterState(MOVE_TO_SHOT);
                    break;
                }

                // 2) If we already have a target and trace is valid (<5s unseen), pursue it
                if (hasTarget() && isTraceActive(world, u)) {
                    goToLastSeen(world, u);
                    u.setHunterState(MOVE_TO_SHOT);
                    break;
                }

                // 3) Nothing to hunt: meander
                tickSearchWander(world, u, dt);
            }

            case MOVE_TO_SHOT -> {
                if (!hasTarget()) { clearNavTarget(); u.setHunterState(SEARCH); break; }
                if (!isTraceActive(world, u)) { clearTarget(); clearNavTarget(); u.setHunterState(SEARCH); break; }

                // If we came from SHOOT and have no nav yet, drop any stale ring to force a recompute
                if (!hasNavTarget()) { haveRing = false; ringStickTimer = 0; }

                // Resolve current target status
                Unit live = getLiveById(world, targetId);
                if (live == null) {
                    // Check if the target died -> go loot; else pursue last board position
                    Unit any = null;
                    for (Unit d : world.getUnits()) { if (d.getId() == targetId) { any = d; break; } }
                    if (any != null && any.isDead()) {
                        clearNavTarget();
                        u.clearAimTarget();
                        u.setHunterState(LOOT);
                        break;
                    }
                    // Still alive but not locally resolved → chase board
                    refreshLastSeenFromBoard(world, u);
                    goToLastSeen(world, u);
                    break;
                }

                double d = Math.hypot(live.getY() - u.getY(), live.getX() - u.getX());
                if (d <= BOW_RANGE - 0.5) {
                    clearNavTarget();
                    log(u, "MOVE_TO_SHOT: in range (" + fmt(d) + "), switching to SHOOT");
                    u.setHunterState(SHOOT);
                    break;
                }

                // If we have a ring target that is still "sticky", decide based on arrival:
                if (haveRing && ringStickTimer > 0) {
                    double rcx = ringC + 0.5, rcy = ringR + 0.5;
                    double dnRing = Math.hypot(rcy - u.getY(), rcx - u.getX());

                    if (dnRing <= ARRIVE_EPS) {
                        clearNavTarget();
                        double dNow = Math.hypot(live.getY() - u.getY(), live.getX() - u.getX());
                        if (dNow <= BOW_RANGE - 0.5) {
                            u.setHunterState(SHOOT);
                            return;
                        } else {
                            haveRing = false; ringStickTimer = 0;
                            if (!tryApproachForShot(world, u, live)) {
                                int rr = (int) Math.round(live.getY()), cc = (int) Math.round(live.getX());
                                setNavTarget(rr, cc);
                                if (!world.commandMove(u, rr, cc)) {
                                    var p = world.findPath(u, rr, cc);
                                    if (p != null) u.setPath(p);
                                }
                            }
                            return;
                        }
                    } else {
                        // Not yet at ring → ensure we have that nav target
                        if (!hasNavTarget() || navTargetR != ringR || navTargetC != ringC) {
                            setNavTarget(ringR, ringC);
                        }
                    }
                }

                // If we get here, either no ring or ring stick expired → (re)plan approach
                if (!haveRing || ringStickTimer <= 0) {
                    if (!tryApproachForShot(world, u, live)) {
                        int rr = (int) Math.round(live.getY()), cc = (int) Math.round(live.getX());
                        setNavTarget(rr, cc);
                        if (!world.commandMove(u, rr, cc)) {
                            var p = world.findPath(u, rr, cc);
                            if (p != null) u.setPath(p);
                        }
                    }
                }

                // Watchdog on whatever nav we currently have (ring or center)
                if (hasNavTarget()) {
                    double dn = distToNav(u);
                    repathToNavIfStuck(world, u, dt, dn);
                    if (dn <= ARRIVE_EPS) {
                        clearNavTarget(); // next tick we’ll re-evaluate
                    }
                }
            }

            case SHOOT -> {
                if (!hasTarget()) { u.setHunterState(SEARCH); break; }
                if (!isTraceActive(world, u)) { clearTarget(); u.setHunterState(SEARCH); break; }

                Unit live = getLiveById(world, targetId);
                if (live == null) {
                    // Don’t drop the target; go back to pursue using the board
                    u.setHunterState(MOVE_TO_SHOT);
                    break;
                }
                if (live.isDead()) {
                    clearNavTarget();
                    u.clearAimTarget();
                    u.setHunterState(LOOT);
                    break;
                }
                double d = Math.hypot(live.getY()-u.getY(), live.getX()-u.getX());

                // If target ran out of range or LOS is blocked, immediately go pursue again
                boolean losOk = hasLineOfSight(world, u.getY(), u.getX(), live.getY(), live.getX());
                if (d > BOW_RANGE - 0.25 || !losOk) {
                    haveRing = false; ringStickTimer = 0; // force fresh ring next
                    u.setHunterState(MOVE_TO_SHOT);
                    break;
                }

                // Aim toward the target before firing
                boolean aligned = aimToward(u, live.getX(), live.getY(), dt);
                if (!aligned) break;

                double now = world.nowSeconds();
                if (now >= nextShotAt) {
                    world.fireArrowShot(u, live);
                    nextShotAt = now + u.getRangedCooldownSec();
                }
            }

            case LOOT -> {
                if (!hasTarget()) { u.setHunterState(SEARCH); break; }

                // Fetch by id even if dead
                Unit corpse = null;
                for (Unit d : world.getUnits()) { if (d.getId() == targetId) { corpse = d; break; } }
                if (corpse == null) { clearTarget(); u.setHunterState(SEARCH); break; }

                // If somehow not dead yet, go back to pursuing/shooting
                if (!corpse.isDead()) { u.setHunterState(MOVE_TO_SHOT); break; }

                int tr = (int)Math.round(corpse.getY());
                int tc = (int)Math.round(corpse.getX());

                // Move to corpse
                if (!u.isMoving() && dist(u.getY(),u.getX(), tr,tc) > 1.1) {
                    if (!world.commandMove(u, tr, tc)) {
                        var p = world.findPath(u, tr, tc);
                        if (p != null) u.setPath(p);
                    }
                    break;
                }

                double d = dist(u.getY(),u.getX(), tr,tc);
                if (d <= 1.1) {
                    boolean ok = corpse.lootCorpse(u);
                    // optional: log result
                    clearTarget();
                    u.clearAimTarget();
                    u.setHunterState(SEARCH); // later: RETURN_TO_CAMP
                }
            }

            case RETURN_TO_CAMP, UNLOAD, IDLE -> { /* later */ }
        }
    }

    /* ---------- TeamSightings helpers & math ---------- */

    private static String fmt(double v){ return String.format("%.2f", v); }
    private static double dist(double r1, double c1, double r2, double c2) {
        return Math.hypot(r2 - r1, c2 - c1);
    }
    private static double sq(double v){ return v*v; }

    private double traceAgeSec(world.World world, Unit u) {
        if (targetId == null) return Double.POSITIVE_INFINITY;
        var book = world.getTeamSightings().view(u.getTeam());
        var s = book.get(targetId);
        if (s == null) return Double.POSITIVE_INFINITY;
        return (System.nanoTime() - s.seenNanos) / 1e9;
    }

    /** True if this team still has an active sighting for the current target. */
    private boolean isTraceActive(world.World world, Unit u) {
        if (targetId == null) return false;
        var s = world.getTeamSightings().view(u.getTeam()).get(targetId);
        return s != null && System.nanoTime() - s.seenNanos <= (long)(TRACE_TTL_SEC * 1e9);
    }

    /** Update lastSeenX/Y from the team board if present. */
    private void refreshLastSeenFromBoard(world.World world, Unit u){
        var book = world.getTeamSightings().view(u.getTeam());
        var s = (targetId != null) ? book.get(targetId) : null;
        if (s != null) { lastSeenX = s.x; lastSeenY = s.y; }
    }

    private void setTarget(Unit deer) {
        targetId = deer.getId();
        lastSeenX = deer.getX(); lastSeenY = deer.getY();
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

    private boolean hasTarget() { return targetId != null; }

    // NEW
    private Unit getLiveById(world.World world, Integer id) {
        if (id == null) return null;
        for (Unit d : world.getUnits()) {
            if (d.getId() == id && !d.isDead()) return d;
        }
        return null;
    }

    /** Board-driven: closest deer sighting for the hunter’s team. */
// NEW (uses your ActorType-based board)
    private TeamSightings.Sighting pickClosestSighting(world.World world, Unit u) {
        // If you want horses too, also check HORSE and pick nearer of the two.
        return world.getTeamSightings().closestOfActorType(u.getTeam(), u.getX(), u.getY(), characters.ActorType.DEER);
    }

    // ---- approach ring selection ----
    // pick a reachable cell that guarantees Euclidean distance <= BOW_RANGE - 0.25
    private boolean tryApproachForShot(world.World world, Unit u, Unit deer) {
        final double desiredR = Math.max(2.0, BOW_RANGE - 0.75); // slightly inside range
        final int samples = 18;         // angle samples around deer
        final int maxSnap = 3;          // how far we'll search to snap to a reachable cell
        final double maxAllowed = BOW_RANGE - 0.25; // conservative margin inside range

        for (int i = 0; i < samples; i++) {
            double ang = (2*Math.PI) * (i + rng.nextDouble()*0.4) / samples;

            // floating target around deer
            double fx = deer.getX() + Math.cos(ang) * desiredR;
            double fy = deer.getY() + Math.sin(ang) * desiredR;

            int tr = (int)Math.round(fy);
            int tc = (int)Math.round(fx);

            int[] snap = findReachableNear(world, u, tr, tc, maxSnap);
            if (snap == null) continue;

            // check Euclidean distance from snapped cell CENTER to deer
            double cx = snap[1] + 0.5;
            double cy = snap[0] + 0.5;
            double dist = Math.hypot(cy - deer.getY(), cx - deer.getX());
            if (dist > maxAllowed) continue; // reject out-of-range candidate

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

    /** Move toward last known position (snapped to a reachable nearby tile). */
    private void goToLastSeen(world.World world, Unit u) {
        int tr = (int)Math.round(lastSeenY);
        int tc = (int)Math.round(lastSeenX);

        int[] dest = findReachableNear(world, u, tr, tc, 5); // search up to radius 5
        if (dest == null) return;

        setNavTarget(dest[0], dest[1]);
        if (!world.commandMove(u, dest[0], dest[1])) {
            var path = world.findPath(u, dest[0], dest[1]);
            if (path != null && !path.isEmpty()) u.setPath(path);
        }
    }

    /** Spiral out to find a reachable cell near (r,c). Returns {r,c} or null. */
    private int[] findReachableNear(world.World world, Unit u, int r, int c, int maxRadius) {
        if (world.inBoundsRC(r, c) && !world.isBlocked(r, c, u)) return new int[]{r, c};
        for (int rad = 1; rad <= maxRadius; rad++) {
            int r0 = r - rad, r1 = r + rad;
            int c0 = c - rad, c1 = c + rad;
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

    // ---- SEARCH wander ----

    private double rand(double a, double b){ return a + rng.nextDouble()*(b-a); }

    private boolean arrived(Unit u, int r, int c, double eps){
        return Math.hypot(r - u.getY(), c - u.getX()) <= eps;
    }

    /** Pick a reachable point around a base (br,bc) */
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

    /** Lightweight jitter retarget near current wander target to create a curve */
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

    private void startWander(world.World world, Unit u){
        int br, bc;
        var camp = u.getAssignedCamp();
        if (camp != null) {
            br = camp.getRow() + camp.getType().h/2;
            bc = camp.getCol() + camp.getType().w/2;
        } else {
            br = (int)Math.floor(u.getY());
            bc = (int)Math.floor(u.getX());
        }
        java.awt.Point p = pickNearbyReachableFrom(world, u, br, bc, WANDER_MIN_R, WANDER_MAX_R);
        if (p != null) {
            wTarR = p.y; wTarC = p.x;
            wandering = true;
            wRepathT = 0.0;
            lastWanderDist = Double.POSITIVE_INFINITY;

            // reset nudge throttles
            wNudgesThisLeg = 0;
            wLastNudgeAtX = u.getX();
            wLastNudgeAtY = u.getY();
            wRetargetT = rand(WANDER_RETARGET_MIN, WANDER_RETARGET_MAX);

            if (!world.commandMove(u, wTarR, wTarC)) {
                var path = world.findPath(u, wTarR, wTarC);
                if (path != null) u.setPath(path);
            }
            if (DEBUG) System.out.println("[HUNTER id="+u.getId()+"] WANDER start -> ("+wTarR+","+wTarC+")");
        } else {
            wPause = rand(0.6, 1.0);
            wandering = false;
            if (DEBUG) System.out.println("[HUNTER id="+u.getId()+"] WANDER no target; pausing "+String.format("%.2f",wPause));
        }
    }

    private static double deg(double rad){ return rad * 180.0 / Math.PI; }

    /** Tick meander: short pauses, occasional retargets, and watchdog repaths */
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
        }
    }

    // ---- aiming/LOS ----

    /** Turn the unit toward (tx,ty). Returns true if sufficiently aligned to shoot. */
    private boolean aimToward(Unit u, double tx, double ty, double dt) {
        double desired = Math.atan2(ty - u.getY(), tx - u.getX());
        double cur = u.getOrientRad();

        // shortest angular diff
        double diff = Math.atan2(Math.sin(desired - cur), Math.cos(desired - cur));

        double maxStep = Math.PI * dt; // 180°/s; tweak to taste or wire to Unit.turnRateRad
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

    /** Optional: tile LOS. Requires World.isOpaque(..). */
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
}

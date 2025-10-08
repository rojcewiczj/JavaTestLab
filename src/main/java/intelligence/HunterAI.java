package intelligence;

import java.util.Map;
import java.util.Random;

import characters.Unit;


import static characters.Unit.HunterState.*;

public class HunterAI implements Unit.UnitAI {

    private final Random rng = new Random();

    // Tunables
    private static final double BOW_RANGE = 8.0;
    private static final double SHOOT_COOLDOWN = 1.0;
    private static final double TRACE_TTL_SEC = 5.0; // <-- exact requirement
    private static final double SWITCH_MARGIN = 0.5; // only switch if strictly closer than this

    // Fire cadence
    private double nextShotAt = 0.0;

    // Target memory ("trace")
    private Integer targetId = null;
    private double lastSeenX = 0, lastSeenY = 0;
    private double unseenTimer = 0.0; // seconds since team last saw target

    // Nav target + watchdog
    private int navTargetR = Integer.MIN_VALUE, navTargetC = Integer.MIN_VALUE;
    private double lastDistToTarget = Double.POSITIVE_INFINITY;
    private double repathCooldown = 0.8;
    private double repathTimer = 0.0;
    // --- Aiming / LOS gating ---
    private static final double AIM_DOT_MIN = 0.95;  // ~18° cone

    private void setNavTarget(int r, int c) {
        navTargetR = r; navTargetC = c;
        lastDistToTarget = Double.POSITIVE_INFINITY;
        repathTimer = 0.0;
    }
    private boolean hasNavTarget() { return navTargetR != Integer.MIN_VALUE; }
    private double distToNav(Unit u) { return Math.hypot(navTargetR - u.getY(), navTargetC - u.getX()); }
    private void clearNavTarget() { navTargetR = navTargetC = Integer.MIN_VALUE; }

    // Sticky approach ring to reduce jitter
    private boolean haveRing = false;
    private static final double ARRIVAL_EPS = 0.6; // tiles; treat as "at ring
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

    @Override
    public void update(world.World world, Unit u, double dt) {
        if (u.getRole() != Unit.UnitRole.HUNTER) return;

        // --- EVENT LOGGING ---
        logStateIfChanged(u, world); // prints "STATE → ..." once per transition

        // --- Maintain 5s trace: while active, follow ACTUAL deer position ---
        if (hasTarget()) {
            boolean trace = isTraceActive(world, u);
            Unit deer = getDeerById(world, targetId);
            if (trace && deer != null) {
                lastSeenX = deer.getX();
                lastSeenY = deer.getY();
            }
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
                if (ap == null) { u.setRole(Unit.UnitRole.NONE); u.setHunterState(IDLE); return; }

                if (!hasNavTarget()) {
                    setNavTarget(ap[0], ap[1]);
                    log(u, world, "GET_BOW: heading to camp door at ("+ap[0]+","+ap[1]+")");
                }
                double d = distToNav(u);
                if (d <= 1.2) {
                    u.setHasBow(true);
                    clearNavTarget();
                    log(u, world, "GET_BOW: armed, entering SEARCH");
                    u.setHunterState(SEARCH);
                    break;
                }
                repathToNavIfStuck(world, u, dt, d);
            }

            case SEARCH -> {
                // If any deer is team-visible RIGHT NOW, lock & pursue
                Unit vis = pickTargetConsideringCloser(world, u);
                if (vis != null) {
                    if (targetId == null || !targetId.equals(vis.getId())) {
                        setTarget(vis);
                        log(u, world, "SEARCH: LOCK via VISION deer="+vis.getId()
                                +" at ("+fmt(vis.getY())+","+fmt(vis.getX())+")");
                    }
                    if (!tryApproachForShot(world, u, vis)) {
                        int rr = (int)Math.round(vis.getY()), cc = (int)Math.round(vis.getX());
                        setNavTarget(rr, cc);
                        if (!world.commandMove(u, rr, cc)) {
                            var p = world.findPath(u, rr, cc);
                            if (p != null) u.setPath(p);
                        }
                        log(u, world, "SEARCH: approach fallback to deer center ("+rr+","+cc+")");
                    }
                    u.setHunterState(MOVE_TO_SHOT);
                    break;
                }

                // If we already have a target and its trace is still valid (<=5s), keep pursuing it
                if (hasTarget() && isTraceActive(world, u)) {
                    log(u, world, "SEARCH: trace active ("+fmt(traceAgeSec(world,u))+"s), pursuing target "+targetId);
                    u.setHunterState(MOVE_TO_SHOT);
                    break;
                }

                // Otherwise, no deer seen by anyone -> actually roam (stay in SEARCH)
                roamNearCamp(world, u);
            }

            case MOVE_TO_SHOT -> {
                if (!hasTarget()) {
                    log(u, world, "MOVE_TO_SHOT: no target; back to SEARCH");
                    clearNavTarget();
                    u.setHunterState(SEARCH);
                    break;
                }

                if (!isTraceActive(world, u)) {
                    log(u, world, "MOVE_TO_SHOT: trace expired (" + fmt(traceAgeSec(world, u)) + "s); clearing target");
                    clearTarget();
                    clearNavTarget();
                    u.setHunterState(SEARCH);
                    break;
                }
                // If we came from SHOOT and have no nav yet, drop any stale ring to force a recompute
                if (!hasNavTarget()) { haveRing = false; ringStickTimer = 0; }
                // Trace active: use ACTUAL deer position
                Unit deer = getDeerById(world, targetId);
                if (deer == null) {
                    log(u, world, "MOVE_TO_SHOT: deer object missing; back to SEARCH");
                    clearTarget();
                    clearNavTarget();
                    u.setHunterState(SEARCH);
                    break;
                }

                double d = Math.hypot(deer.getY() - u.getY(), deer.getX() - u.getX());
                if (d <= BOW_RANGE - 0.5) {
                    clearNavTarget();
                    log(u, world, "MOVE_TO_SHOT: in range (" + fmt(d) + "), switching to SHOOT");
                    u.setHunterState(SHOOT);
                    break;
                }

                // If we have a ring target that is still "sticky", decide based on arrival:
                if (haveRing && ringStickTimer > 0) {
                    // distance to ring cell center
                    double rcx = ringC + 0.5, rcy = ringR + 0.5;
                    double dnRing = Math.hypot(rcy - u.getY(), rcx - u.getX());

                    if (dnRing <= ARRIVAL_EPS) {
                        // We're AT the ring. Either shoot, or recompute a closer ring.
                        System.out.println("[HUNTER id=" + u.getId() + "] arrived ring (" + ringR + "," + ringC + ") dn=" + String.format("%.2f", dnRing));
                        clearNavTarget();

                        double dNow = Math.hypot(deer.getY() - u.getY(), deer.getX() - u.getX());
                        if (dNow <= BOW_RANGE - 0.5) {
                            System.out.println("[HUNTER id=" + u.getId() + "] in range after arrival (" + String.format("%.2f", dNow) + ") -> SHOOT");
                            u.setHunterState(SHOOT);
                            return;
                        } else {
                            // ring was valid when chosen but deer moved / diagonal overshoot; recompute
                            haveRing = false;
                            ringStickTimer = 0;
                            System.out.println("[HUNTER id=" + u.getId() + "] not in range after arrival (" + String.format("%.2f", dNow) + "); recomputing approach");
                            if (!tryApproachForShot(world, u, deer)) {
                                int rr = (int) Math.round(deer.getY()), cc = (int) Math.round(deer.getX());
                                setNavTarget(rr, cc);
                                if (!world.commandMove(u, rr, cc)) {
                                    var p = world.findPath(u, rr, cc);
                                    if (p != null) u.setPath(p);
                                }
                                System.out.println("[HUNTER id=" + u.getId() + "] fallback to deer center (" + rr + "," + cc + ")");
                            }
                            // stay in MOVE_TO_SHOT
                            return;
                        }
                    } else {
                        // Not yet at ring → ensure we have that nav target
                        if (!hasNavTarget() || navTargetR != ringR || navTargetC != ringC) {
                            setNavTarget(ringR, ringC);
                            System.out.println("[HUNTER id=" + u.getId() + "] heading to ring (" + ringR + "," + ringC + ") dn=" + String.format("%.2f", dnRing));
                        }
                    }
                }

// If we have a ring target that is still "sticky", decide based on arrival:
                if (haveRing && ringStickTimer > 0) {
                    // distance to ring cell center
                    double rcx = ringC + 0.5, rcy = ringR + 0.5;
                    double dnRing = Math.hypot(rcy - u.getY(), rcx - u.getX());

                    if (dnRing <= ARRIVAL_EPS) {
                        // We're AT the ring. Either shoot, or recompute a closer ring.
                        System.out.println("[HUNTER id=" + u.getId() + "] arrived ring (" + ringR + "," + ringC + ") dn=" + String.format("%.2f", dnRing));
                        clearNavTarget();

                        double dNow = Math.hypot(deer.getY() - u.getY(), deer.getX() - u.getX());
                        if (dNow <= BOW_RANGE - 0.5) {
                            System.out.println("[HUNTER id=" + u.getId() + "] in range after arrival (" + String.format("%.2f", dNow) + ") -> SHOOT");
                            u.setHunterState(SHOOT);
                            return;
                        } else {
                            // ring was valid when chosen but deer moved / diagonal overshoot; recompute
                            haveRing = false;
                            ringStickTimer = 0;
                            System.out.println("[HUNTER id=" + u.getId() + "] not in range after arrival (" + String.format("%.2f", dNow) + "); recomputing approach");
                            if (!tryApproachForShot(world, u, deer)) {
                                int rr = (int) Math.round(deer.getY()), cc = (int) Math.round(deer.getX());
                                setNavTarget(rr, cc);
                                if (!world.commandMove(u, rr, cc)) {
                                    var p = world.findPath(u, rr, cc);
                                    if (p != null) u.setPath(p);
                                }
                                System.out.println("[HUNTER id=" + u.getId() + "] fallback to deer center (" + rr + "," + cc + ")");
                            }
                            // stay in MOVE_TO_SHOT
                            return;
                        }
                    } else {
                        // Not yet at ring → ensure we have that nav target
                        if (!hasNavTarget() || navTargetR != ringR || navTargetC != ringC) {
                            setNavTarget(ringR, ringC);
                            System.out.println("[HUNTER id=" + u.getId() + "] heading to ring (" + ringR + "," + ringC + ") dn=" + String.format("%.2f", dnRing));
                        }
                    }
                }

// If we get here, either no ring or ring stick expired → (re)plan approach
                if (!haveRing || ringStickTimer <= 0) {
                    if (!tryApproachForShot(world, u, deer)) {
                        int rr = (int) Math.round(deer.getY()), cc = (int) Math.round(deer.getX());
                        setNavTarget(rr, cc);
                        if (!world.commandMove(u, rr, cc)) {
                            var p = world.findPath(u, rr, cc);
                            if (p != null) u.setPath(p);
                        }
                        System.out.println("[HUNTER id=" + u.getId() + "] (re)plan fallback to deer center (" + rr + "," + cc + ")");
                    }
                }

// Watchdog on whatever nav we currently have (ring or center)
                if (hasNavTarget()) {
                    double dn = distToNav(u);
                    repathToNavIfStuck(world, u, dt, dn);
                    if (dn <= ARRIVAL_EPS) {
                        // Don't bounce to SEARCH; just clear nav so next tick we re-evaluate range/approach
                        clearNavTarget();
                        System.out.println("[HUNTER id=" + u.getId() + "] nav arrived (" + navTargetR + "," + navTargetC + "), re-evaluate next tick");
                    }
                }
            }

            case SHOOT -> {
                if (!hasTarget()) { log(u, world, "SHOOT: no target; SEARCH"); u.setHunterState(SEARCH); break; }
                if (!isTraceActive(world, u)) { log(u, world, "SHOOT: trace expired; SEARCH"); clearTarget(); u.setHunterState(SEARCH); break; }

                Unit deer = getDeerById(world, targetId);
                if (deer == null) { log(u, world, "SHOOT: deer missing; SEARCH"); clearTarget(); u.setHunterState(SEARCH); break; }

                double d = Math.hypot(deer.getY()-u.getY(), deer.getX()-u.getX());

                // If deer ran out of range or LOS is blocked, immediately go pursue again
                boolean losOk = hasLineOfSight(world, u.getY(), u.getX(), deer.getY(), deer.getX());
                if (d > BOW_RANGE - 0.25 || !losOk) {
                    haveRing = false; ringStickTimer = 0; // force fresh ring next
                    log(u, world, "SHOOT: need reposition (d="+fmt(d)+", los="+losOk+"); -> MOVE_TO_SHOT");
                    u.setHunterState(MOVE_TO_SHOT);
                    break;
                }

                // Aim toward the deer before firing
                boolean aligned = aimToward(u, deer.getX(), deer.getY(), dt);
                if (!aligned) {
                    log(u, world, "SHOOT: aiming...");
                    break; // stay in SHOOT, keep turning
                }

                double now = world.nowSeconds();
                if (now >= nextShotAt) {
                    world.fireArrowVisual(u, deer.getX(), deer.getY());
                    nextShotAt = now + SHOOT_COOLDOWN;
                    log(u, world, "SHOOT: fired at deer "+deer.getId()+" d="+fmt(d));
                }
            }

            case LOOT, RETURN_TO_CAMP, UNLOAD, IDLE -> { /* later */ }
        }
    }

    /* ---------- tiny logging & trace helpers ---------- */

    private static String fmt(double v){ return String.format("%.2f", v); }
    private static void log(Unit u, world.World w, String msg){
        System.out.println("[HUNTER id="+u.getId()+" t="+fmt(w.nowSeconds())+"] "+msg);
    }
    private Unit.HunterState lastLoggedState = null;
    private void logStateIfChanged(Unit u, world.World w){
        var s = u.getHunterState();
        if (s != lastLoggedState) {
            lastLoggedState = s;
            log(u, w, "STATE → " + s);
        }
    }
    private double traceAgeSec(world.World world, Unit u){
        var book = world.getSightingsForTeam(u.getTeam());
        if (book == null || !hasTarget()) return Double.POSITIVE_INFINITY;
        var s = book.get(targetId);
        if (s == null) return Double.POSITIVE_INFINITY;
        return world.nowSeconds() - (s.seenNanos / 1e9);
    }
    /** Turn the unit toward (tx,ty). Returns true if sufficiently aligned to shoot. */
    private boolean aimToward(Unit u, double tx, double ty, double dt) {
        double desired = Math.atan2(ty - u.getY(), tx - u.getX());
        double cur = u.getOrientRad();

        // shortest angular diff
        double diff = Math.atan2(Math.sin(desired - cur), Math.cos(desired - cur));

        // conservative max turn rate (radians/sec); use your engine’s value if you expose it
        double maxStep = Math.PI * dt; // 180°/s; tweak to taste or wire to Unit.turnRateRad
        if (Math.abs(diff) <= maxStep) cur = desired;
        else cur += Math.copySign(maxStep, diff);

        // normalize and apply
        if (cur <= -Math.PI) cur += 2 * Math.PI;
        if (cur >   Math.PI) cur -= 2 * Math.PI;
        u.setOrientRad(cur);

        // also update discrete facing for your rendering/footprint, so it looks right
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

        // alignment after the turn we just applied
        double dot = Math.cos(desired - cur);
        return dot >= AIM_DOT_MIN;
    }

    /** Optional: tile LOS. If you don't have a public isOpaque(..), add one in World. */
    private boolean hasLineOfSight(world.World world, double r0, double c0, double r1, double c1) {
        int x0 = (int)Math.floor(c0), y0 = (int)Math.floor(r0);
        int x1 = (int)Math.floor(c1), y1 = (int)Math.floor(r1);
        int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        while (true) {
            if (world.inBoundsRC(y0, x0) && world.isOpaque(y0, x0)) return false; // needs public isOpaque
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
        return true;
    }
    // ---- TRACE helpers ----

    private boolean isTraceActive(world.World world, Unit hunter) {
        Map<Integer, TeamSightings.Sighting> book = world.getSightingsForTeam(hunter.getTeam());
        if (book == null) return false;
        TeamSightings.Sighting s = book.get(targetId);
        if (s == null) return false;
        double lastSeenSec = ((double) s.seenNanos) / 1_000_000_000.0;
        double now = world.nowSeconds();
        return (now - lastSeenSec) <= TRACE_TTL_SEC;
    }

    private void setTarget(Unit deer) {
        targetId = deer.getId();
        lastSeenX = deer.getX(); lastSeenY = deer.getY();
        unseenTimer = 0.0;
        haveRing = false;
    }

    private void setTargetFromSighting(TeamSightings.Sighting s) {
        targetId = s.unitId;
        // lastSeenX/Y will be immediately updated to ACTUAL deer position when traceActive
        unseenTimer = 0.0;
        haveRing = false;
    }

    private void clearTarget() {
        targetId = null;
        unseenTimer = 0.0;
        haveRing = false;
    }

    private boolean hasTarget() { return targetId != null; }

    private Unit getDeerById(world.World world, Integer id) {
        if (id == null) return null;
        for (Unit d : world.getUnits()) {
            if ((d.getActor() instanceof characters.Deer) && d.getId() == id) return d;
        }
        return null;
    }

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
            if (dist > maxAllowed) {
                System.out.println("[HUNTER id="+u.getId()+"] ring candidate ("+snap[0]+","+snap[1]+") rejected: dist="+String.format("%.2f",dist));
                continue; // reject out-of-range candidate
            }

            var path = world.findPath(u, snap[0], snap[1]);
            if (path != null && !path.isEmpty()) {
                haveRing = true;
                ringR = snap[0]; ringC = snap[1];
                ringStickTimer = RING_STICK_SEC;

                setNavTarget(ringR, ringC);
                u.setPath(path);

                System.out.println("[HUNTER id="+u.getId()+"] ring set ("+ringR+","+ringC+"), dist="+String.format("%.2f",dist));
                return true;
            }
        }
        System.out.println("[HUNTER id="+u.getId()+"] no valid ring found this tick");
        return false;
    }
    // Move toward the last known position of the current target.
// DOES NOT change state; callers decide the state transition.
    private void goToLastSeen(world.World world, Unit u) {
        int tr = (int)Math.round(lastSeenY);
        int tc = (int)Math.round(lastSeenX);

        // Find a reachable tile near (tr,tc) to avoid stalls if that cell is blocked
        int[] dest = findReachableNear(world, u, tr, tc, 5); // search up to radius 5
        if (dest == null) return; // nowhere reasonable to go this tick

        setNavTarget(dest[0], dest[1]);
        if (!world.commandMove(u, dest[0], dest[1])) {
            var path = world.findPath(u, dest[0], dest[1]);
            if (path != null && !path.isEmpty()) u.setPath(path);
        }
    }

    /** Returns {r,c} of a reachable cell near (r,c), searching outward in a spiral up to maxRadius. */
    private int[] findReachableNear(world.World world, Unit u, int r, int c, int maxRadius) {
        // Prefer the exact tile first
        if (world.inBoundsRC(r, c) && !world.isBlocked(r, c, u)) return new int[]{r, c};

        for (int rad = 1; rad <= maxRadius; rad++) {
            // scan the square ring at distance 'rad'
            int r0 = r - rad, r1 = r + rad;
            int c0 = c - rad, c1 = c + rad;
            // top & bottom edges
            for (int cc = c0; cc <= c1; cc++) {
                if (world.inBoundsRC(r0, cc) && !world.isBlocked(r0, cc, u)) return new int[]{r0, cc};
                if (world.inBoundsRC(r1, cc) && !world.isBlocked(r1, cc, u)) return new int[]{r1, cc};
            }
            // left & right edges (skip corners already checked)
            for (int rr = r0 + 1; rr <= r1 - 1; rr++) {
                if (world.inBoundsRC(rr, c0) && !world.isBlocked(rr, c0, u)) return new int[]{rr, c0};
                if (world.inBoundsRC(rr, c1) && !world.isBlocked(rr, c1, u)) return new int[]{rr, c1};
            }
        }
        return null; // nothing found nearby
    }

    // ---- SEARCH helpers ----

    private Unit pickTargetConsideringCloser(world.World world, Unit hunter) {
        Unit cur = getDeerById(world, targetId);
        boolean curVisible = cur != null && isTeamVisible(world, cur);

        Unit best = null; double bestD2 = Double.POSITIVE_INFINITY;
        for (Unit d : world.getUnits()) {
            if (!(d.getActor() instanceof characters.Deer)) continue;
            if (!isTeamVisible(world, d)) continue;
            double d2 = sq(d.getY() - hunter.getY()) + sq(d.getX() - hunter.getX());
            if (d2 < bestD2) { bestD2 = d2; best = d; }
        }

        if (curVisible) {
            double curD2 = sq(cur.getY() - hunter.getY()) + sq(cur.getX() - hunter.getX());
            if (best == null) return cur;
            double margin2 = SWITCH_MARGIN * SWITCH_MARGIN;
            if (best.getId() != cur.getId() && bestD2 + margin2 < curD2) return best;
            return cur;
        } else if (best != null) {
            return best;
        } else {
            return null;
        }
    }

    private TeamSightings.Sighting pickClosestSighting(world.World world, Unit u) {
        Map<Integer, TeamSightings.Sighting> book = world.getSightingsForTeam(u.getTeam());
        if (book == null || book.isEmpty()) return null;
        double best = Double.POSITIVE_INFINITY; TeamSightings.Sighting bestS = null;
        for (TeamSightings.Sighting s : book.values()) {
            double d2 = sq(s.x - u.getX()) + sq(s.y - u.getY());
            if (d2 < best) { best = d2; bestS = s; }
        }
        return bestS;
    }

    private boolean isTeamVisible(world.World world, Unit u) {
        int rr = (int)Math.floor(u.getY()), cc = (int)Math.floor(u.getX());
        return world.isVisible(rr, cc);
    }

    private void roamNearCamp(world.World world, Unit u) {
        var camp = u.getAssignedCamp(); if (camp == null) return;
        int cr = camp.getRow() + camp.getType().h/2, cc = camp.getCol() + camp.getType().w/2;
        int r = 6 + rng.nextInt(8);
        double ang = rng.nextDouble()*Math.PI*2;
        int rr = cr + (int)Math.round(Math.sin(ang)*r);
        int cc2= cc + (int)Math.round(Math.cos(ang)*r);
        if (!world.inBoundsRC(rr, cc2) || world.isBlocked(rr, cc2, u)) return;
        // issue a casual move but KEEP state in SEARCH
        if (!world.commandMove(u, rr, cc2)) {
            var p = world.findPath(u, rr, cc2);
            if (p != null) u.setPath(p);
        }
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

    // math
    private double sq(double v){ return v*v; }
}
package intelligence;

import characters.Unit;

import java.awt.*;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

public class WolfAI implements Unit.UnitAI {
    public enum State { INIT, ROAM, CHASE, BITE, LOOT, RETURN_HOME, UNLOAD }
    // --- Anti-jitter ---
    private static final double ARRIVE_EPS            = 0.49;  // consider arrived if within ~1/2 tile
    private static final double NUDGE_STICK_SEC       = 0.28;  // keep a nudge direction briefly
    private static final double ANTI_OSC_BACKOFF_SEC  = 0.22;  // short pause when we detect ping-pong
    // ====== CONFIG / TUNING ======
    // --- Perf throttles ---
    private static final double VISION_COOLDOWN_SEC  = 0.25;  // how often a wolf "looks" (4x/sec)
    private static final double REPORT_COOLDOWN_SEC  = 0.50;  // how often it reports to the pack (2x/sec)
    private static final double RETARGET_COOLDOWN_SEC= 0.50;  // how often we consider switching to a closer target
    private double visionT = 0.0, reportT = 0.0, retargetT = 0.0;
    // private static final double APPROACH_STICK_SEC = 0.9;
    private static final double APPROACH_STICK_SEC = 1.4;  // fewer retarget flips near the prey
    // Toggle noisy logs (console I/O is expensive!)
    private static final boolean LOG_MOVES    = false;
    private static final boolean LOG_APPROACH = false;
    private static final boolean LOG_STATE    = false;

    private static final double VIEW = 8.0;                 // wolf vision range (tiles)
    private static final double BITE_RANGE = 0;           // distance to consider "in bite range"
    private static final double REPATH_COOLDOWN = 0.45;     // seconds between re-issue path checks
    private static final double PROGRESS_EPS    = 0.04;     // minimal progress threshold
    private static final int    REROLL_AFTER    = 3;        // watchdog: re-pick after N non-progress strikes

    private static final double OCCUPIED_HOLD_SEC  = 0.7;   // wait near reserved tile if a mate sits on it

    private static final double REISSUE_MIN = 0.20;         // throttle exact same commandMove re-issues
    private static final double TARGET_JUMP2 = 4.0;         // re-eval if target moved > 2 tiles (squared)

    private static final double WANDER_MIN = 1.2;           // roam: retarget window
    private static final double WANDER_MAX = 2.4;

    // ====== RETURN_HOME safety (all cheap) ======
    private static final double STUCK_HOME_SEC       = 1.0;   // consider stuck if grid pos unchanged ~1s
    private static final double RETURN_HOME_FAILSAFE = 6.0;   // force UNLOAD if homing takes too long
    private static final int    HOME_FAILS_BEFORE_ABORT = 4;  // fallback to ROAM if no progress repeatedly
    private static final int    HOME_FALLBACK_RADIUS = 3;     // try small ring around anchor if blocked
    // Track last two grid cells to detect A<->B oscillation
    private int gridRPrev1 = Integer.MIN_VALUE, gridCPrev1 = Integer.MIN_VALUE;
    private int gridRPrev2 = Integer.MIN_VALUE, gridCPrev2 = Integer.MIN_VALUE;
    private int oscillationCount = 0;

    // Sticky nudge memory
    private int lastNudgeDR = 0, lastNudgeDC = 0;
    private double nudgeStickT = 0.0;

    // Short pause when oscillating
    private double antiOscBackoffT = 0.0;
    // ====== PACK / DEN ======
    private final int denId;
    private final Random rng = new Random();

    // ====== STATE ======
    private State state = State.INIT;
    private State pendingState = null; // defer state flips during sensitive ops
    private Integer targetId = null;

    // reserved approach tile (for CHASE/BITE)
    private int approachR = Integer.MIN_VALUE, approachC = Integer.MIN_VALUE;
    private double approachStick = 0.0;   // seconds left to keep the same approach reservation

    // nav watchdog / throttles
    private int navR = Integer.MIN_VALUE, navC = Integer.MIN_VALUE;
    private double lastDistToNav = Double.POSITIVE_INFINITY;
    private int stuckStrikes = 0;
    private double repathT = 0.0;

    private int lastIssuedNavR = Integer.MIN_VALUE, lastIssuedNavC = Integer.MIN_VALUE;
    private double reissueTimer = 0.0;

    // target motion tracking (to avoid over-reacting to tiny jitter)
    private double lastTgtX = Double.NaN, lastTgtY = Double.NaN;

    // short wait if our reserved tile is temporarily occupied by a packmate
    private double occupiedHoldT = 0.0;

    // roaming cadence
    private double wanderT = 0.0;

    // ====== "Home" anchor (no building calls) ======
    private int homeAnchorR = Integer.MIN_VALUE, homeAnchorC = Integer.MIN_VALUE;
    private boolean homeAnchorSet = false;
    private double returnHomeTimer = 0.0;
    private int    lastHomeRow = Integer.MIN_VALUE, lastHomeCol = Integer.MIN_VALUE;
    private double stillHomeT = 0.0;
    private int    homeConsecutiveFails = 0;

    // ====== Global melee gate (serialize heavy world.resolveMeleeHit) ======
    private static final AtomicBoolean MELEE_GATE = new AtomicBoolean(false);

    // Reentrancy shield so engine callbacks can't recurse into issuing more orders.
    private boolean issuingMoveOrder = false;

    public WolfAI(int denId) { this.denId = denId; }

    // ====== UPDATE ======
    @Override
    public void update(world.World world, Unit w, double dt) {
        repathT      -= dt;
        reissueTimer -= dt;
        approachStick-= dt;
        visionT      -= dt;
        reportT      -= dt;
        retargetT    -= dt;
        nudgeStickT     -= dt;
        antiOscBackoffT -= dt;

// detect A<->B<->A grid oscillation
        int gr = w.getRowRounded(), gc = w.getColRounded();
        if (gr == gridRPrev2 && gc == gridCPrev2 && !(gr == gridRPrev1 && gc == gridCPrev1)) {
            oscillationCount++;
        } else if (gr != gridRPrev1 || gc != gridCPrev1) {
            oscillationCount = 0;
        }
        gridRPrev2 = gridRPrev1; gridCPrev2 = gridCPrev1;
        gridRPrev1 = gr;         gridCPrev1 = gc;

        if (oscillationCount >= 2) {           // saw A↔B at least twice
            antiOscBackoffT = ANTI_OSC_BACKOFF_SEC;
            nudgeStickT = 0.0;                 // allow direction reconsideration
            // optional: cool down planning a touch
            reissueTimer = Math.max(reissueTimer, ANTI_OSC_BACKOFF_SEC);
            stuckStrikes = 0;
        }
        if (occupiedHoldT > 0) occupiedHoldT -= dt;
        if (w.isDead()) return;

        // Commit any deferred state transitions from the previous tick *before* we do work.
        if (pendingState != null) {
            if (LOG_STATE) System.out.println("[WOLF id=" + w.getId() + "] (deferred) " + state + "→" + pendingState);
            state = pendingState;
            pendingState = null;
        }

        long nowN = System.nanoTime();

        // Only look if we don't already have a valid target, and not while homing/unloading
        Unit tgt = getById(world, targetId);
        boolean haveValidTgt = isValidTarget(world, tgt);
        if (!haveValidTgt && state != State.RETURN_HOME && state != State.UNLOAD) {
            if (visionT <= 0.0) {
                Unit seen = nearestVisibleTarget(world, w); // O(N) -> throttled
                visionT = VISION_COOLDOWN_SEC;

                if (seen != null) {
                    if (reportT <= 0.0) {
                        world.getPackSightings().packReportSighting(denId, seen, nowN);
                        reportT = REPORT_COOLDOWN_SEC;
                    }
                    targetId = seen.getId();
                    tgt = seen;
                    haveValidTgt = true;
                }
            }
        }

        if (!haveValidTgt) tgt = null;

        switch (state) {
            case INIT -> {
                targetId = null; clearNav();
                // Cache a cheap, always-valid "home" to avoid any building/path queries
                homeAnchorR = w.getRowRounded();
                homeAnchorC = w.getColRounded();
                homeAnchorSet = true;

                wanderT = rand(0.4, 1.0); // small stagger on spawn
                state = State.ROAM;
                if (LOG_STATE) System.out.println("[WOLF id=" + w.getId() + "] → ROAM");
            }

            case ROAM -> {
                if (tgt == null) {
                    var s = world.getPackSightings().packPickClosestSighting(denId, w.getY(), w.getX());
                    if (s != null) {
                        targetId = s.targetId;
                        setNav((int) Math.round(s.y), (int) Math.round(s.x));
                        ensureMovingTo(world, w, navR, navC);
                    }
                    tgt = getById(world, targetId);
                }
                if (isValidTarget(world, tgt)) {
                    state = State.CHASE;
                    if (LOG_STATE) System.out.println("[WOLF id=" + w.getId() + "] ROAM→CHASE target=" + tgt.getId());
                    break;
                }

                // wander
                wanderT -= dt;
                if (!w.isMoving() || wanderT <= 0) {
                    Point p = pickNearbyReachable(world, w, 3, 8); // trimmed tries internally
                    if (p != null) {
                        setNav(p.y, p.x);
                        ensureMovingTo(world, w, p.y, p.x);
                        w.clearAimTarget();
                        wanderT = rand(WANDER_MIN, WANDER_MAX);
                    }
                }
            }

            case CHASE -> {
                boolean traceActive = (targetId != null) &&
                        world.getPackSightings().hasActiveSighting(denId, targetId);
                if (!isValidTarget(world, tgt) || !traceActive) {
                    targetId = null; clearNav(); w.clearAimTarget();
                    wanderT = rand(WANDER_MIN, WANDER_MAX);
                    state = State.ROAM;
                    break;
                }

                // Opportunistic switch to clearly closer target (throttled)
                if (retargetT <= 0.0) {
                    Unit closer = closestValidTarget(world, w);
                    retargetT = RETARGET_COOLDOWN_SEC;
                    if (closer != null && !closer.isDead() && closer.getId() != targetId) {
                        double curD2 = sq(tgt.getY() - w.getY()) + sq(tgt.getX() - w.getX());
                        double newD2 = sq(closer.getY() - w.getY()) + sq(closer.getX() - w.getX());
                        if (newD2 + 0.25 < curD2) {
                            targetId = closer.getId();
                            tgt = closer;
                            clearNav();
                            approachR = approachC = Integer.MIN_VALUE;
                            approachStick = 0;
                        }
                    }
                }

                // face target
                w.setAimTarget(tgt.getX(), tgt.getY());

                // bite if close enough
                double dNow = Math.hypot(tgt.getY() - w.getY(), tgt.getX() - w.getX());
                if (dNow <= BITE_RANGE) {
                    clearNav();
                    state = State.BITE;
                    if (LOG_STATE) System.out.println("[WOLF id=" + w.getId() + "] CHASE→BITE target=" + tgt.getId());
                    break;
                }

                // detect larger target jumps
                boolean targetJumped = Double.isNaN(lastTgtX) ||
                        (sq(tgt.getY() - lastTgtY) + sq(tgt.getX() - lastTgtX)) > TARGET_JUMP2;
                lastTgtX = tgt.getX(); lastTgtY = tgt.getY();

                // if we don't have a nav yet, pick an approach once
                if (!hasNav()) {
                    if (!pickApproachTile(world, w, tgt)) {
                        int tr = (int) Math.round(tgt.getY()), tc = (int) Math.round(tgt.getX());
                        setNav(tr, tc);
                        ensureMovingTo(world, w, tr, tc);
                    }
                    lastDistToNav = Math.hypot(navR - w.getY(), navC - w.getX());
                    break;
                }

                // otherwise keep moving to reserved approach; only re-pick if stick expired AND stuck or target jumped
                if (approachStick <= 0 && (stuckStrikes >= REROLL_AFTER || targetJumped)) {
                    stuckStrikes = 0;
                    if (!pickApproachTile(world, w, tgt)) {
                        int tr = (int) Math.round(tgt.getY()), tc = (int) Math.round(tgt.getX());
                        setNav(tr, tc);
                        ensureMovingTo(world, w, tr, tc);
                    }
                    lastDistToNav = Math.hypot(navR - w.getY(), navC - w.getX());
                    repathT = REPATH_COOLDOWN + rng.nextDouble() * 0.25;
                } else {
                    maintainProgress(world, w, dt, tgt);
                }
            }

            case BITE -> {
                // opportunistic switch throttled
                if (retargetT <= 0.0) {
                    Unit maybeCloser = closestValidTarget(world, w);
                    retargetT = RETARGET_COOLDOWN_SEC;
                    if (maybeCloser != null && maybeCloser.getId() != targetId) {
                        double curD2 = sq(tgt.getY() - w.getY()) + sq(tgt.getX() - w.getX());
                        double newD2 = sq(maybeCloser.getY() - w.getY()) + sq(maybeCloser.getX() - w.getX());
                        if (newD2 + 0.25 < curD2) {
                            targetId = maybeCloser.getId();
                            clearNav();
                            tgt = maybeCloser;
                        }
                    }
                }

                boolean traceActive = (targetId != null) &&
                        world.getPackSightings().hasActiveSighting(denId, targetId);
                if (!isValidTarget(world, tgt) || !traceActive) {
                    targetId = null; clearNav(); w.clearAimTarget();
                    wanderT = rand(WANDER_MIN, WANDER_MAX);
                    state = State.ROAM;
                    break;
                }

                // keep facing the target
                w.setAimTarget(tgt.getX(), tgt.getY());

                double d = Math.hypot(tgt.getY() - w.getY(), tgt.getX() - w.getX());
                if (d > BITE_RANGE + 0.25) {
                    state = State.CHASE;
                    break;
                }

                double now = world.nowSeconds();
                if (now >= w.getNextMeleeAt()) {
                    // Serialize heavy world call; if busy, wait a tick (prevents perf cliff)
                    if (MELEE_GATE.compareAndSet(false, true)) {
                        try {
                            world.resolveMeleeHit(w, tgt);
                        } finally {
                            MELEE_GATE.set(false);
                        }
                        w.setNextMeleeAt(now + w.getMeleeCooldownSec());

                        if (tgt.isDead()) {
                            // Do NOT flip states in the same tick as a heavy world op; defer.
                            targetId = null;
                            clearNav();
                            approachR = approachC = Integer.MIN_VALUE;
                            approachStick = 0;
                            returnHomeTimer = 0;
                            stillHomeT = 0;
                            homeConsecutiveFails = 0;
                            pendingState = State.RETURN_HOME; // will apply at top of next update()
                            if (LOG_STATE) System.out.println("[WOLF id=" + w.getId() + "] BITE→RETURN_HOME (deferred)");
                            break;
                        }
                    } else {
                        // gate busy — small delay to avoid hammering
                        w.setNextMeleeAt(now + 0.05);
                    }
                }
            }

            case RETURN_HOME -> {
                // Purely cheap logic: move toward anchor with commandMove only; no A*, no building queries
                if (!homeAnchorSet) {
                    homeAnchorR = w.getRowRounded();
                    homeAnchorC = w.getColRounded();
                    homeAnchorSet = true;
                }

                returnHomeTimer += dt;
                if (returnHomeTimer >= RETURN_HOME_FAILSAFE) {
                    if (LOG_STATE) System.out.println("[WOLF id=" + w.getId() + "] RETURN_HOME failsafe → UNLOAD");
                    state = State.UNLOAD;
                    break;
                }

                // Set/refresh nav to anchor
                int tr = homeAnchorR, tc = homeAnchorC;
                if (!hasNav() || navR != tr || navC != tc) {
                    setNav(tr, tc);
                }

                // Arrive?
                double toNav = Math.hypot(navR - w.getY(), navC - w.getX());
                if (toNav <= 1.2) {
                    state = State.UNLOAD;
                    if (LOG_STATE) System.out.println("[WOLF id=" + w.getId() + "] RETURN_HOME→UNLOAD");
                    break;
                }

                // If the reserved tile is currently occupied by someone else, wait briefly; then try nearby fallback
                if (toNav <= 0.9 && occupiedByOther(world, navR, navC, w)) {
                    occupiedHoldT += dt;
                    if (repathT <= 0) {
                        issueMoveNoAStar(world, w, navR, navC);
                        repathT = REPATH_COOLDOWN * 0.5;
                    }
                    if (occupiedHoldT >= OCCUPIED_HOLD_SEC) {
                        occupiedHoldT = 0;
                        Point alt = pickNearbyReachableByIssuing(world, w, tr, tc, HOME_FALLBACK_RADIUS);
                        if (alt != null) {
                            setNav(alt.y, alt.x);
                            issueMoveNoAStar(world, w, alt.y, alt.x);
                            lastDistToNav = Math.hypot(navR - w.getY(), navC - w.getX());
                            repathT = REPATH_COOLDOWN + rng.nextDouble() * 0.25;
                        } else {
                            if (++homeConsecutiveFails >= HOME_FAILS_BEFORE_ABORT) {
                                homeConsecutiveFails = 0;
                                state = State.ROAM; // keep sim breathing
                            }
                        }
                    }
                    break;
                } else {
                    occupiedHoldT = 0;
                }

                // Stuck detection (based on integer grid position not changing)
                int cr = w.getRowRounded(), cc = w.getColRounded();
                if (cr == lastHomeRow && cc == lastHomeCol) {
                    stillHomeT += dt;
                } else {
                    stillHomeT = 0;
                }
                lastHomeRow = cr; lastHomeCol = cc;

                // Re-issue commandMove (throttled), never A*
                if (repathT <= 0 || !w.isMoving()) {
                    issueMoveNoAStar(world, w, navR, navC);
                    repathT = REPATH_COOLDOWN + rng.nextDouble() * 0.25;
                }

                // If stuck for a bit, try a nearby tile by issuing only
                if (stillHomeT >= STUCK_HOME_SEC) {
                    stillHomeT = 0;
                    Point alt = pickNearbyReachableByIssuing(world, w, tr, tc, HOME_FALLBACK_RADIUS);
                    if (alt != null) {
                        setNav(alt.y, alt.x);
                        issueMoveNoAStar(world, w, alt.y, alt.x);
                        lastDistToNav = Math.hypot(navR - w.getY(), navC - w.getX());
                        repathT = REPATH_COOLDOWN + rng.nextDouble() * 0.25;
                        homeConsecutiveFails = 0;
                    } else {
                        if (++homeConsecutiveFails >= HOME_FAILS_BEFORE_ABORT) {
                            homeConsecutiveFails = 0;
                            state = State.ROAM;
                        }
                    }
                }
            }

            case UNLOAD -> {
                // drop carried stuff instantly for now (hook up to den inventory later)
                targetId = null;
                state = State.ROAM;
                wanderT = rand(WANDER_MIN, WANDER_MAX);
                returnHomeTimer = 0;
                if (LOG_STATE) System.out.println("[WOLF id=" + w.getId() + "] UNLOAD→ROAM");
            }
        }
    }

    // ====== HELPERS ======

    private double rand(double a, double b) { return a + rng.nextDouble() * (b - a); }

    private void setNav(int r, int c) { navR = r; navC = c; }
    private void clearNav() { navR = navC = Integer.MIN_VALUE; lastDistToNav = Double.POSITIVE_INFINITY; }
    private boolean hasNav() { return navR != Integer.MIN_VALUE; }

    // prefer visible, LOS-limited, nearest (wolf-only perception)
    // NOTE: now called only when we *don't* have a valid target, and on cooldown.
    private Unit nearestVisibleTarget(world.World world, Unit w) {
        int wr = (int)Math.floor(w.getY()), wc = (int)Math.floor(w.getX());
        double range2 = VIEW * VIEW;

        Unit best = null; double bestD2 = Double.POSITIVE_INFINITY;
        for (Unit u : world.getUnits()) {
            if (!isValidTarget(world, u)) continue;
            double d2 = sq(u.getY() - w.getY()) + sq(u.getX() - w.getX());
            if (d2 > range2) continue;
            int ur = (int)Math.floor(u.getY()), uc = (int)Math.floor(u.getX());
            if (!world.hasLineOfSight(wr, wc, ur, uc)) continue;
            if (d2 < bestD2) { bestD2 = d2; best = u; }
        }
        return best;
    }

    // Throttled by caller (retargetT)
    private Unit closestValidTarget(world.World world, Unit w) {
        Unit best = null; double bestD2 = Double.POSITIVE_INFINITY;
        for (Unit u : world.getUnits()) {
            if (!isValidTarget(world, u)) continue;
            double d2 = sq(u.getY() - w.getY()) + sq(u.getX() - w.getX());
            if (d2 < bestD2) { bestD2 = d2; best = u; }
        }
        return best;
    }

    private boolean isValidTarget(world.World world, Unit u) {
        if (u == null || u.isDead()) return false;
        if (u.getTeam() == characters.Team.WOLF) return false;
        boolean deer  = (u.getActor() instanceof characters.Deer);
        boolean human = (u.getTeam() == characters.Team.RED || u.getTeam() == characters.Team.BLUE);
        return deer || human;
    }

    private Unit getById(world.World world, Integer id) {
        if (id == null) return null;
        for (Unit u : world.getUnits()) if (u.getId() == id) return u;
        return null;
    }

    // choose an adjacent/near-adjacent tile near the target's footprint; keep reservation sticky
    private boolean pickApproachTile(world.World world, Unit w, Unit tgt) {
        var occ = footprintTiles(tgt);
        java.util.ArrayList<int[]> candidates = new java.util.ArrayList<>();

        // helper to test "on target footprint"
        java.util.function.BiPredicate<Integer,Integer> onFoot = (rr,cc)->{
            for (int[] f : occ) if (f[0] == rr && f[1] == cc) return true;
            return false;
        };

        // --- Generate Chebyshev rings (includes diagonals) around each occupied footprint cell ---
        // radius 1 (8 neighbors) then radius 2 (outer ring). Break as soon as we found any.
        for (int radius = 1; radius <= 2; radius++) {
            for (int[] cell : occ) {
                int cr = cell[0], cc = cell[1];
                for (int dr = -radius; dr <= radius; dr++) {
                    for (int dc = -radius; dc <= radius; dc++) {
                        if (dr == 0 && dc == 0) continue;
                        if (Math.max(Math.abs(dr), Math.abs(dc)) != radius) continue; // ring only
                        int rr = cr + dr, c2 = cc + dc;
                        if (!world.inBoundsRC(rr, c2)) continue;
                        if (onFoot.test(rr, c2)) continue;                // don't step onto target footprint
                        if (world.isBlocked(rr, c2, w)) continue;         // respect collisions/footprints
                        candidates.add(new int[]{rr, c2});
                    }
                }
            }
            if (!candidates.isEmpty()) break;
        }
        if (candidates.isEmpty()) return false;

        // --- Score: prefer closer; slight bias toward true diagonals at radius 1 (helps encirclement) ---
        final int hR = (int)Math.floor(tgt.getY());
        final int hC = (int)Math.floor(tgt.getX());
        candidates.sort((a,b)->{
            double da = Math.hypot(a[0]-w.getY(), a[1]-w.getX());
            double db = Math.hypot(b[0]-w.getY(), b[1]-w.getX());
            if (da != db) return Double.compare(da, db);

            // tie-break: prefer immediate diagonals around the head cell
            boolean adiag = (Math.abs(a[0]-hR)==1 && Math.abs(a[1]-hC)==1);
            boolean bdiag = (Math.abs(b[0]-hR)==1 && Math.abs(b[1]-hC)==1);
            if (adiag != bdiag) return adiag ? -1 : 1;

            return 0;
        });

        int rr = candidates.get(0)[0], cc = candidates.get(0)[1];

        // keep existing reservation while stick lasts, unless tile became permanently blocked
        if (approachStick > 0 && approachR != Integer.MIN_VALUE) {
            boolean stillValid = !world.isBlocked(approachR, approachC, w);
            if (stillValid) {
                setNav(approachR, approachC);
                ensureMovingTo(world, w, approachR, approachC);
                return true;
            }
        }

        // reserve new approach
        approachR = rr; approachC = cc;
        approachStick = APPROACH_STICK_SEC;

        setNav(rr, cc);
        ensureMovingTo(world, w, rr, cc);
        if (LOG_APPROACH) System.out.println("[WOLF id=" + w.getId() + "] approach-> (" + rr + "," + cc + ")");
        return true;
    }
    /** Returns all tiles currently occupied by tgt's footprint (len 1 or 2). */
    private java.util.ArrayList<int[]> footprintTiles(Unit tgt) {
        java.util.ArrayList<int[]> cells = new java.util.ArrayList<>(2);
        int hr = (int)Math.floor(tgt.getY());
        int hc = (int)Math.floor(tgt.getX());
        cells.add(new int[]{hr, hc});           // head/primary cell
        if (tgt.getLength() >= 2) {
            int dcol = step8X(tgt.getOrientRad());
            int drow = step8Y(tgt.getOrientRad());
            int tr = hr - drow;
            int tc = hc - dcol;
            cells.add(new int[]{tr, tc});       // tail cell
        }
        return cells;
    }
    private static int step8X(double ang) {
        double c = Math.cos(ang);
        if (c >  0.707) return  1;
        if (c < -0.707) return -1;
        return 0;
    }
    private static int step8Y(double ang) {
        double s = Math.sin(ang);
        if (s >  0.707) return  1;
        if (s < -0.707) return -1;
        return 0;
    }

    // Re-issue current nav if stuck or cooldown fired; handle occupied-reservation wait
    private void maintainProgress(world.World world, Unit w, double dt, Unit tgt) {
        if (!hasNav()) return;

        double toNav = Math.hypot(navR - w.getY(), navC - w.getX());
        boolean navOccupied = occupiedByOther(world, navR, navC, w);

        // close to reserved tile but someone sits on it -> wait briefly, then reroll after stick expires
        if (toNav <= 0.9) {
            if (navOccupied) {
                occupiedHoldT += dt;
                if (repathT <= 0) {
                    ensureMovingTo(world, w, navR, navC);
                    repathT = REPATH_COOLDOWN * 0.5;
                }
                if (occupiedHoldT >= OCCUPIED_HOLD_SEC && approachStick <= 0) {
                    occupiedHoldT = 0;
                    if (!pickApproachTile(world, w, tgt)) {
                        int tr = (int)Math.round(tgt.getY()), tc = (int)Math.round(tgt.getX());
                        setNav(tr, tc);
                        ensureMovingTo(world, w, tr, tc);
                    }
                    lastDistToNav = Math.hypot(navR - w.getY(), navC - w.getX());
                    repathT = REPATH_COOLDOWN + rng.nextDouble() * 0.25;
                }
                return; // keep waiting
            } else {
                occupiedHoldT = 0;
            }
        } else {
            occupiedHoldT = 0;
        }

        boolean improving = (toNav < lastDistToNav - PROGRESS_EPS);
        if (!improving) stuckStrikes++; else stuckStrikes = 0;

        if (repathT <= 0 || !w.isMoving() || !improving) {
            if (stuckStrikes >= REROLL_AFTER && approachStick <= 0) {
                stuckStrikes = 0;
                if (!pickApproachTile(world, w, tgt)) {
                    int tr = (int)Math.round(tgt.getY()), tc = (int)Math.round(tgt.getX());
                    setNav(tr, tc);
                    ensureMovingTo(world, w, tr, tc);
                }
                lastDistToNav = Math.hypot(navR - w.getY(), navC - w.getX());
                repathT = REPATH_COOLDOWN + rng.nextDouble() * 0.25;
            } else {
                ensureMovingTo(world, w, navR, navC);
                repathT = REPATH_COOLDOWN + rng.nextDouble() * 0.25;
                lastDistToNav = toNav;
            }
        } else {
            lastDistToNav = toNav;
        }
    }

    // Use commandMove; if it fails, do not pathfind (RETURN_HOME is kept cheap)
    private void issueMoveNoAStar(world.World world, Unit w, int r, int c) {
        if (w.isMoving() && r == lastIssuedNavR && c == lastIssuedNavC && reissueTimer > 0) return;
        boolean ok = world.commandMove(w, r, c);
        if (!ok) {
            if (LOG_MOVES) System.out.println("[WOLF id=" + w.getId() + "] (home) commandMove refused (" + r + "," + c + ")");
        }
        lastIssuedNavR = r;
        lastIssuedNavC = c;
        reissueTimer = REISSUE_MIN;
    }

    // Is (r,c) currently occupied by anyone other than 'ignore'?
    private boolean occupiedByOther(world.World world, int r, int c, Unit ignore) {
        return world.isOccupiedFast(r, c, ignore);
    }

    // Try to pick a random nearby reachable tile (returns Point(col, row))
    // Uses A* to confirm reachability and respects 2-tile footprints via world.isBlocked(...)
    // PERF: reduced random tries from 24 -> 10
    private Point pickNearbyReachable(world.World world, Unit u, int minR, int maxR) {
        int baseR = (int)Math.floor(u.getY());
        int baseC = (int)Math.floor(u.getX());

        // First: random ring samples
        for (int tries = 0; tries < 10; tries++) {
            double ang = rng.nextDouble() * Math.PI * 2.0;
            double r   = minR + rng.nextDouble() * Math.max(1, (maxR - minR));
            int rr = baseR + (int)Math.round(Math.sin(ang) * r);
            int cc = baseC + (int)Math.round(Math.cos(ang) * r);

            if (!world.inBoundsRC(rr, cc)) continue;
            if (world.isBlocked(rr, cc, u)) continue;

            var path = world.findPath(u, rr, cc);
            if (path != null && !path.isEmpty()) {
                return new Point(cc, rr); // x=col, y=row
            }
        }

        // Fallback: expand in Manhattan rings (lightweight sample)
        for (int rad = minR; rad <= maxR; rad++) {
            int[][] cand = {
                    { baseR + rad, baseC       },
                    { baseR - rad, baseC       },
                    { baseR,       baseC + rad },
                    { baseR,       baseC - rad }
            };
            for (int[] p : cand) {
                int rr = p[0], cc = p[1];
                if (!world.inBoundsRC(rr, cc)) continue;
                if (world.isBlocked(rr, cc, u)) continue;
                var path = world.findPath(u, rr, cc);
                if (path != null && !path.isEmpty()) {
                    return new Point(cc, rr);
                }
            }
        }
        return null; // nowhere sensible found
    }

    // Use commandMove first; avoid zero-length/nearby orders; A* only on cooldown;
    // while in RETURN_HOME we *never* A* (cheap-only), to avoid stalls.
    // Reentrancy shield stays in your class.
    private void ensureMovingTo(world.World world, Unit w, int r, int c) {
        if (issuingMoveOrder) return;
        if (antiOscBackoffT > 0) return; // brief pause when we detect ping-pong

        issuingMoveOrder = true;
        try {
            // Arrived / near-enough guard (avoid micro orders)
            double dy = r - w.getY(), dx = c - w.getX();
            if (Math.hypot(dy, dx) <= ARRIVE_EPS) return;

            // De-dupe identical orders while a recent one is still "hot"
            if (w.isMoving() && r == lastIssuedNavR && c == lastIssuedNavC && reissueTimer > 0) return;

            boolean issued = false;

            // 1) Cheap direct order
            if (world.commandMove(w, r, c)) {
                issued = true;
                // reset nudge memory when we get a clean direct order
                lastNudgeDR = 0; lastNudgeDC = 0; nudgeStickT = 0.0;
                if (LOG_MOVES) System.out.println("[WOLF id="+w.getId()+"] commandMove-> ("+r+","+c+")");
            }

            // 2) If that failed, pick strategy
            if (!issued) {
                int cr = w.getRowRounded(), cc = w.getColRounded();
                int dr = Integer.compare(r, cr), dc = Integer.compare(c, cc);

                if (state == State.RETURN_HOME || repathT > 0) {
                    // --- Cheap nudge mode (RETURN_HOME or planning on cooldown) ---

                    // 2a) Try to keep the last nudge direction for a short time (if it still helps)
                    if (nudgeStickT > 0 && (lastNudgeDR != 0 || lastNudgeDC != 0)) {
                        // Don’t immediately reverse direction if we haven’t moved tiles yet
                        if (!(lastNudgeDR == -dr && lastNudgeDC == -dc)) {
                            if (tryIssue(world, w, cr + lastNudgeDR, cc + lastNudgeDC)) {
                                issued = true;
                            }
                        }
                    }

                    // 2b) If not issued, try axis-aligned then diagonal — but never the immediate opposite of last nudge
                    if (!issued) {
                        int[][] order = { {dr,0}, {0,dc}, {dr,dc} };
                        for (int[] step : order) {
                            int sdr = step[0], sdc = step[1];
                            if (sdr == 0 && sdc == 0) continue;

                            boolean immediateOpp = (sdr == -lastNudgeDR && sdc == -lastNudgeDC);
                            if (immediateOpp && nudgeStickT > 0) continue;

                            if (tryIssue(world, w, cr + sdr, cc + sdc)) {
                                issued = true;
                                lastNudgeDR = sdr; lastNudgeDC = sdc;
                                nudgeStickT = NUDGE_STICK_SEC;
                                break;
                            }
                        }
                    }

                } else {
                    // --- Planning allowed but throttled globally ---
                    var path = world.findPath(w, r, c);
                    if (path != null && !path.isEmpty()) {
                        w.setPath(path);
                        issued = true;
                        lastNudgeDR = 0; lastNudgeDC = 0; nudgeStickT = 0.0;
                        if (LOG_MOVES) System.out.println("[WOLF id="+w.getId()+"] setPath("+path.size()+") -> ("+r+","+c+")");
                    }
                    // throttle future planning attempts regardless of success
                    repathT = REPATH_COOLDOWN + rng.nextDouble() * 0.25;

                    // While waiting next chance to plan, give a tiny axis nudge (no diagonal) if nothing was issued
                    // While waiting next chance to plan, give a tiny nudge (axis first, then diagonal)
                    if (!issued) {
                        int cr2 = w.getRowRounded(), cc2 = w.getColRounded();
                        int dr2 = Integer.compare(r, cr2), dc2 = Integer.compare(c, cc2);

                        int[][] order = { {dr2,0}, {0,dc2}, {dr2,dc2} }; // <-- include diagonal
                        for (int[] step : order) {
                            int sdr = step[0], sdc = step[1];
                            if (sdr == 0 && sdc == 0) continue;
                            boolean immediateOpp = (sdr == -lastNudgeDR && sdc == -lastNudgeDC);
                            if (immediateOpp && nudgeStickT > 0) continue;

                            if (tryIssue(world, w, cr2 + sdr, cc2 + sdc)) {
                                issued = true;
                                lastNudgeDR = sdr; lastNudgeDC = sdc;
                                nudgeStickT = NUDGE_STICK_SEC;
                                break;
                            }
                        }
                    }
                }
            }

            // Bookkeeping for de-dupe/throttle even if we didn’t manage to issue
            lastIssuedNavR = r;
            lastIssuedNavC = c;
            reissueTimer   = REISSUE_MIN;

        } finally {
            issuingMoveOrder = false;
        }
    }

    // Try a single-tile order; refuse if out of bounds or blocked.
// Prevent diagonal "corner-cutting" if both adjacent orthogonals are blocked.
    private boolean tryIssue(world.World world, Unit w, int rr, int cc) {
        if (!world.inBoundsRC(rr, cc)) return false;

        int cr = w.getRowRounded(), cc0 = w.getColRounded();
        int dr = Integer.compare(rr, cr), dc = Integer.compare(cc, cc0);

        if (dr != 0 && dc != 0) {
            // moving diagonally: if both orthogonal neighbors are blocked, don't try
            if (world.isBlocked(cr + dr, cc0, w) && world.isBlocked(cr, cc0 + dc, w)) return false;
        }

        if (world.isBlocked(rr, cc, w)) return false;
        return world.commandMove(w, rr, cc);
    }

    /** Try tiles in growing rings around (tr,tc) and *issue* commandMove to the first that accepts. */
    private Point pickNearbyReachableByIssuing(world.World world, Unit self, int tr, int tc, int radius) {
        // Try exact target first
        if (world.inBoundsRC(tr, tc) && !world.isBlocked(tr, tc, self) && world.commandMove(self, tr, tc)) {
            return new Point(tc, tr);
        }
        for (int d = 1; d <= radius; d++) {
            int r0 = tr - d, r1 = tr + d, c0 = tc - d, c1 = tc + d;
            // Top & bottom rows
            for (int c = c0; c <= c1; c++) {
                if (world.inBoundsRC(r0, c) && !world.isBlocked(r0, c, self) && world.commandMove(self, r0, c)) {
                    return new Point(c, r0);
                }
                if (world.inBoundsRC(r1, c) && !world.isBlocked(r1, c, self) && world.commandMove(self, r1, c)) {
                    return new Point(c, r1);
                }
            }
            // Left & right cols (skip corners already tried)
            for (int r = r0 + 1; r <= r1 - 1; r++) {
                if (world.inBoundsRC(r, c0) && !world.isBlocked(r, c0, self) && world.commandMove(self, r, c0)) {
                    return new Point(c0, r);
                }
                if (world.inBoundsRC(r, c1) && !world.isBlocked(r, c1, self) && world.commandMove(self, r, c1)) {
                    return new Point(c1, r);
                }
            }
        }
        return null;
    }

    // How many packmates (wolves of same den) are *aiming for* (r,c)?
    // (Kept for future use, but not called in hot path anymore)
    private int packClaimsFor(world.World world, int denId, int r, int c) {
        int claims = 0;
        for (Unit u : world.getUnits()) {
            if (u == null || u.isDead()) continue;
            if (u.getTeam() != characters.Team.WOLF) continue;
            var ai = u.getAI();
            if (!(ai instanceof WolfAI wAI)) continue;
            if (wAI.denId != denId) continue;
            if (wAI.navR == r && wAI.navC == c) claims++;
        }
        return claims;
    }

    private static double dist(double r1,double c1,double r2,double c2){ return Math.hypot(r2-r1, c2-c1); }
    private static double sq(double v){ return v*v; }
}
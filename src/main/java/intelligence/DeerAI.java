package intelligence;

import characters.Team;
import characters.Unit;

import java.awt.*;

public class DeerAI implements Unit.UnitAI {
    enum State { GRAZE, WANDER, FLEE }

    private final java.util.Random rng = new java.util.Random();
    private State state = State.GRAZE;

    // --- Threat radii with hysteresis ---
    private double fearRadius = 7.0;   // enter FLEE if any threat is within this
    private double safeRadius = 12.0;  // exit FLEE only when all threats are beyond this

    // --- Meander pacing ---
    private double restTimer = 0.0;            // time left to idle (GRAZE)
    private double restMin = 4.0, restMax = 8.0; // idle longer
    private int    wanderMinR = 2,  wanderMaxR = 5; // shorter, lazier steps
    private double wanderReplanTimer = 0.0;    // when to issue next wander hop
    private double wanderHopMin = 3.0, wanderHopMax = 7.0; // seconds between wander hops

    // --- Flee behavior ---
    private Point fleeTarget = null;     // committed target while fleeing
    private double fleeRepathCD = 0.0;   // cooldown before choosing a new flee path
    private double fleeRepathEvery = 0.9; // seconds
    private int fleeMinR = 8, fleeMaxR = 16; // flee hop distance (tiles)

    @Override
    public void update(world.World world, Unit u, double dt) {
        // --- threat + hysteresis as you already have ---
        double minThreatDist = Double.POSITIVE_INFINITY;
        Unit nearest = null;
        for (Unit other : world.getUnits()) {
            if (other == u) continue;
            if (!isThreat(u, other)) continue;
            double dx = other.getX() - u.getX(), dy = other.getY() - u.getY();
            double d2 = dx*dx + dy*dy;
            if (d2 < minThreatDist*minThreatDist) { minThreatDist = Math.sqrt(d2); nearest = other; }
        }

        // hysteresis
        if (state == State.FLEE) {
            if (minThreatDist > safeRadius) { state = State.GRAZE; fleeTarget = null; restTimer = rand(restMin, restMax); }
        } else {
            if (minThreatDist < fearRadius) {
                state = State.FLEE;
                fleeTarget = null;

                // NEW: cancel any wander path so we don’t “stick” to it
                u.setPath(java.util.Collections.emptyList()); // moving=false
                // Also make sure we recompute immediately
                fleeRepathCD = 0.0;
            }
        }

        // FLEE takes priority
        if (state == State.FLEE) { updateFlee(world, u, dt, nearest, minThreatDist); return; }

        // ===== GRAZE: pause; when timer elapses, issue ONE wander hop and enter WANDER =====
        if (state == State.GRAZE) {
            if (restTimer > 0.0) { restTimer -= dt; return; }

            // time to move once
            Point tgt = pickNearbyReachable(world, u, wanderMinR, wanderMaxR);
            if (tgt != null) {
                world.commandMove(u, tgt.y, tgt.x);
                state = State.WANDER;
                // optional: set a max duration before we force back to graze if stuck
                wanderReplanTimer = rand(wanderHopMin, wanderHopMax);
            } else {
                // couldn't find a hop; wait a bit and try again
                restTimer = rand(1.0, 2.0);
            }
            return;
        }

        // ===== WANDER: let the issued hop run. When it finishes, go back to GRAZE (pause). =====
        if (state == State.WANDER) {
            // If the hop finished (no path), pause again
            if (!u.isMoving()) {
                state = State.GRAZE;
                restTimer = rand(restMin, restMax);  // longer pauses between hops
                return;
            }

            // Optional “safety valve” in case path stalls: if too long, end hop early
            wanderReplanTimer -= dt;
            if (wanderReplanTimer <= 0.0) {
                // Don’t chain another hop here; we want a clear pause, so go to GRAZE
                state = State.GRAZE;
                restTimer = rand(restMin, restMax);
            }
        }
    }


    // ---------------- FLEE ----------------
    private void updateFlee(world.World world, Unit u, double dt, Unit nearest, double minThreatDist) {
        // Cooldown between recomputing escapes (prevents thrash)
        fleeRepathCD -= dt;

        // If we have no fleeTarget, or we reached it, or we’re too close again: pick a new one
        boolean needNew = (fleeTarget == null) || !u.isMoving() || minThreatDist < fearRadius * 0.7 || fleeRepathCD <= 0.0;
        if (needNew) {
            Point away = pickAwayBeyondSafe(world, u, nearest, fleeMinR, fleeMaxR, safeRadius);
            if (away != null) {
                fleeTarget = away;
                world.commandMove(u, away.y, away.x);
                fleeRepathCD = fleeRepathEvery;
            } else {
                // fallback: try any nearby reachable tile, just to keep moving
                Point any = pickNearbyReachable(world, u, 4, 8);
                if (any != null) {
                    fleeTarget = any;
                    world.commandMove(u, any.y, any.x);
                    fleeRepathCD = fleeRepathEvery * 0.5;
                }
            }
        }
    }

    // ---------------- Helpers ----------------
    // Define "threat": by default, any non-neutral, non-same-team unit.
    // Adjust if you want deer to ignore horses, etc.
    private boolean isThreat(Unit self, Unit other) {
        if (other.getTeam() == Team.NEUTRAL) return false;
        if (other.getTeam() == self.getTeam()) return false;
        return true;
    }

    private Point pickNearbyReachable(world.World world, Unit u, int minR, int maxR) {
        int tries = 16;
        int br = (int)Math.floor(u.getY()), bc = (int)Math.floor(u.getX());
        while (tries-- > 0) {
            double ang = rand(0, Math.PI*2), r = rand(minR, maxR);
            int cc = bc + (int)Math.round(Math.cos(ang) * r);
            int rr = br + (int)Math.round(Math.sin(ang) * r);
            if (!world.inBoundsRC(rr, cc) || world.isBlocked(rr, cc, u)) continue;
            var path = world.findPath(u, rr, cc);
            if (path != null && !path.isEmpty()) return new Point(cc, rr);
        }
        return null;
    }

    // Try to choose a target that is geometrically away from 'threat' AND, if possible,
    // will end up beyond 'safeRadius' from it.
    private Point pickAwayBeyondSafe(world.World world, Unit u, Unit threat,
                                     int minR, int maxR, double safeRadius) {
        if (threat == null) return pickNearbyReachable(world, u, minR, maxR);

        int tries = 24;
        int br = (int)Math.floor(u.getY()), bc = (int)Math.floor(u.getX());
        double ax = u.getX() - threat.getX(), ay = u.getY() - threat.getY();
        double curDist = Math.hypot(ax, ay);
        double len = curDist < 1e-6 ? 1 : curDist;
        ax /= len; ay /= len;

        // Phase 1: prefer destinations beyond safeRadius
        for (int i = 0; i < tries; i++) {
            double jitter = rand(-Math.PI/5, Math.PI/5); // ±36°
            double c = Math.cos(jitter), s = Math.sin(jitter);
            double rx = ax*c - ay*s, ry = ax*s + ay*c;
            double r  = rand(minR, maxR);
            int cc = bc + (int)Math.round(rx * r);
            int rr = br + (int)Math.round(ry * r);
            if (!world.inBoundsRC(rr, cc) || world.isBlocked(rr, cc, u)) continue;

            // must be beyond safe radius
            double dSafe = Math.hypot(cc - threat.getX(), rr - threat.getY());
            if (dSafe < safeRadius) continue;

            var path = world.findPath(u, rr, cc);
            if (path != null && !path.isEmpty()) return new Point(cc, rr);
        }

        // Phase 2: if not possible, at least increase distance by ~20%
        for (int i = 0; i < tries; i++) {
            double jitter = rand(-Math.PI/3, Math.PI/3);
            double c = Math.cos(jitter), s = Math.sin(jitter);
            double rx = ax*c - ay*s, ry = ax*s + ay*c;
            double r  = rand(minR, maxR);
            int cc = bc + (int)Math.round(rx * r);
            int rr = br + (int)Math.round(ry * r);
            if (!world.inBoundsRC(rr, cc) || world.isBlocked(rr, cc, u)) continue;

            double dNext = Math.hypot(cc - threat.getX(), rr - threat.getY());
            if (dNext <= curDist * 1.2) continue; // require some meaningful increase

            var path = world.findPath(u, rr, cc);
            if (path != null && !path.isEmpty()) return new Point(cc, rr);
        }

        // Phase 3: total fallback—any reachable nearby tile
        return pickNearbyReachable(world, u, 4, 8);
    }

    private double rand(double a, double b) { return a + rng.nextDouble()*(b-a); }
}
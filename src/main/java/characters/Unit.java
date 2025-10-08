package characters;
import characters.Actor;
import characters.Team;

import java.awt.*;
import java.util.ArrayDeque;
import java.util.Deque;

public class Unit {
    private final Actor actor;

    private double x;         // col (continuous)
    private double y;         // row (continuous)
    private int targetCol;
    private int targetRow;
    private boolean moving;
    private boolean selected;
    // Ranged attack cooldown (absolute time in seconds)
    private double nextRangedAttackAt = 0.0;
    public int length = 1;         // 1 = normal; 2 = horse or mounted
    public Unit rider = null;      // when this unit is a horse carrying a rider
    public boolean mounted = false; // true when the horse is carrying a rider

    // --- NEW: team ---
    private Team team = Team.NEUTRAL;

    // NEW: path as queue of grid waypoints (row,col)
    private final Deque<Point> path = new ArrayDeque<>();
    // in characters/Unit.java
    public enum UnitRole { NONE, LUMBER }

    public static enum LumberState {
        SEEK_TREE, MOVE_TO_TREE, CHOPPING, MOVE_TO_CAMP, IDLE
    }

    private UnitRole role = UnitRole.NONE;
    private LumberState lumberState = LumberState.IDLE;
    private world.Building assignedCamp;   // Logging camp assigned
    private boolean carryingLog = false;

    // target tree (top-left of 2x2)
    private int treeTop = -1, treeLeft = -1;
    // where to stand to chop (adjacent tile)
    private int standRow = -1, standCol = -1;
    // chopping timer
    private double chopTimer = 0.0;

    public Unit(Actor actor, int row, int col) {
        this.actor = actor;
        this.x = col;
        this.y = row;
        this.targetCol = col;
        this.targetRow = row;
        this.moving = false;
        // team stays NEUTRAL unless set later
    }

    // --- NEW: convenience constructor that sets team immediately ---
    public Unit(Actor actor, int row, int col, Team team) {
        this(actor, row, col);
        this.team = (team == null ? Team.NEUTRAL : team);
    }

    public Actor getActor() { return actor; }

    /** Replace current path with a new one (in grid coords row/col). */
    public void setPath(java.util.List<Point> waypoints) {
        path.clear();
        if (waypoints != null) {
            for (Point p : waypoints) path.addLast(p);
        }
        // if there is at least one waypoint, start moving toward it
        moving = !path.isEmpty();
        if (moving) {
            Point last = ((ArrayDeque<Point>) path).peekLast();
            targetRow = last.y;
            targetCol = last.x;
        }
    }
    private long lastMoveNanos = System.nanoTime(); // updated whenever movement stops
    private int id; // set by World.spawnActor

    public void setMounted(boolean v) { mounted = v; }

    public void setRider(Unit r) { rider = r; }
    public int getId() { return id; }
    public void __engine_setId(int id) { this.id = id; } // only World should call
    // in characters.Unit
    // only the world/engine should set this:
    public void __engine_setLength(int len) { this.length = Math.max(1, len); }
    public long getLastMoveNanos() { return lastMoveNanos; }
    public double getNextRangedAttackAt() { return nextRangedAttackAt; }
    public void setNextRangedAttackAt(double t) { nextRangedAttackAt = t; }
    // in characters.Unit
    // in characters.Unit
    public enum Facing { N, NE, E, SE, S, SW, W, NW }

    private Facing facing = Facing.S;  // default

    public Facing getFacing() { return facing; }
    public void setFacing(Facing f) { facing = f; }

    public int getLength() { return length; }
    public boolean isMounted() { return mounted; }
    public Unit getRider() { return rider; }

    // convenience: read agility from actor
    public int getAgility() { return actor.getAgility(); }

    /** True if currently has a path/target. */
    public boolean isMoving() { return moving; }

    public boolean isSelected() { return selected; }
    public void setSelected(boolean sel) { this.selected = sel; }

    public double getX() { return x; } // col
    public double getY() { return y; } // row

    public int getRowRounded() { return (int) Math.round(y); }
    public int getColRounded() { return (int) Math.round(x); }

    // --- NEW: team accessors ---
    public Team getTeam() { return team; }
    public void setTeam(Team team) { this.team = (team == null ? Team.NEUTRAL : team); }
    // --- orientation for smooth facing (radians, 0 = +X (east), increasing CCW) ---
    private double orientRad = 0.0;          // continuous orientation for smooth render
    private double turnRateRad = Math.PI;    // max turn rate (radians/sec), tweak (PI ≈ 180°/s)

    public double getOrientRad() { return orientRad; }
    public void setOrientRad(double a) { orientRad = a; }
    public void setTurnRateRad(double r) { turnRateRad = Math.max(0.1, r); }
    // how fast a unit can rotate (already added in your build)

    // how much turning reduces forward speed (0 = none, 1 = strong)
    private double turnSlowdownStrength = 0.85; // tweak to taste

    public void setTurnSlowdownStrength(double k) { turnSlowdownStrength = Math.min(Math.max(0.0, k), 1.0); }
    // getters/setters
    public UnitRole getRole() { return role; }
    public void setRole(UnitRole r) { role = r; }
    public LumberState getLumberState() { return lumberState; }
    public void setLumberState(LumberState s) { lumberState = s; }
    public world.Building getAssignedCamp() { return assignedCamp; }
    public void setAssignedCamp(world.Building b) { assignedCamp = b; }
    public boolean isCarryingLog() { return carryingLog; }
    public void setCarryingLog(boolean v) { carryingLog = v; }
    public void setTreeTarget(int top, int left) { treeTop = top; treeLeft = left; }
    public int getTreeTop() { return treeTop; }
    public int getTreeLeft() { return treeLeft; }
    public void setStandTile(int r, int c) { standRow = r; standCol = c; }
    public int getStandRow() { return standRow; }
    public int getStandCol() { return standCol; }
    public void setChopTimer(double t) { chopTimer = t; }
    public double getChopTimer() { return chopTimer; }

    // --- NEW: quick helper for combat/ownership logic ---
    public boolean isEnemyOf(Unit other) {
        if (other == null) return false;
        if (this.team == Team.NEUTRAL || other.team == Team.NEUTRAL) return false;
        return this.team != other.team;
    }

    /** Advance along the path by speed * dt (cells/sec). */
    public void update(double dt) {
        if (!moving) return;
        if (path.isEmpty()) {
            moving = false;
            return;
        }

        Point waypoint = path.peekFirst();
        double wx = waypoint.x, wy = waypoint.y;
        double dx = wx - x, dy = wy - y;
        double dist = Math.hypot(dx, dy);
        // Smoothly rotate toward desired direction while moving
        double step;
        if (dist > 1e-6) {
            double desired = Math.atan2(dy, dx);           // [-pi,pi]
            double diff = desired - orientRad;
            // wrap to [-pi, pi]
            diff = Math.atan2(Math.sin(diff), Math.cos(diff));

            double maxStep = turnRateRad * dt;
            if (Math.abs(diff) <= maxStep) orientRad = desired;
            else orientRad += Math.copySign(maxStep, diff);

            if (orientRad <= -Math.PI) orientRad += 2 * Math.PI;
            if (orientRad >   Math.PI) orientRad -= 2 * Math.PI;

            // --- Slow down while turning ---
            double align = Math.max(0.0, Math.cos(diff));        // 1 aligned, 0 sideways
            double k = 0.85;                                     // turning slowdown strength
            double minFrac = 0.20;                               // <- optional min speed (20%)
            double speedFrac = Math.max(minFrac, (1 - k) + k * align);

            if (getLength() >= 2) {                              // small extra penalty for long body
                speedFrac *= 0.95 + 0.05 * align;
            }

            step = actor.getMovement() * speedFrac * dt;
        } else {
            step = actor.getMovement() * dt;
        }

// arrived at this waypoint?
        if (dist < 1e-3) {
            x = wx; y = wy;
            path.removeFirst();
            if (path.isEmpty()) {
                moving = false;
                lastMoveNanos = System.nanoTime();
                return;
            }
            waypoint = path.peekFirst();
        }

// advance toward waypoint using slowed 'step'
        if (step >= dist) {
            x = wx; y = wy;
            path.removeFirst();
            if (path.isEmpty()) {
                moving = false;
                lastMoveNanos = System.nanoTime();
            }
        } else {
            x += (dx / dist) * step;
            y += (dy / dist) * step;
        }

/* CHANGE THIS PART: derive discrete 8-way facing from SMOOTH orientation,
   not from atan2(dy,dx), so footprint/rendering match the smooth turn. */
        double ang = orientRad;
        if (ang < 0) ang += 2 * Math.PI;
        int sector = (int) Math.round(ang / (Math.PI / 4.0)) & 7; // 0..7
        switch (sector) {
            case 0: facing = Facing.E;  break;
            case 1: facing = Facing.SE; break;
            case 2: facing = Facing.S;  break;
            case 3: facing = Facing.SW; break;
            case 4: facing = Facing.W;  break;
            case 5: facing = Facing.NW; break;
            case 6: facing = Facing.N;  break;
            case 7: facing = Facing.NE; break;
        }
    }
    // in characters.Unit
    public interface UnitAI { void update(world.World world, characters.Unit u, double dtSeconds); }

    private UnitAI ai;
    public void setAI(UnitAI ai) { this.ai = ai; }
    public UnitAI getAI() { return ai; }

    // call from your game loop (World.update or Panel timer):
    public void tickAI(world.World world, double dtSeconds) {
        if (ai != null) ai.update(world, this, dtSeconds);
    }
}
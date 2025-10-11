package characters;
import characters.Actor;
import characters.Team;

import java.awt.*;
import java.util.ArrayDeque;
import java.util.Deque;

public class Unit {
    private final Actor actor;
    // --- Combat stats ---
    public enum LifeState { ALIVE, WOUNDED, DEAD }
    private LifeState life = LifeState.ALIVE;

    private int maxWounds = 2;
    private int wounds    = 2;

    private int aimSkill  = 0;  // 0..10 -> 0..100% hit chance
    private int power     = 0;  // 0..10 -> 0..100% damage chance

    private double movementScale = 1.0; // 1.0 normal; <1 when wounded
    private double timeOfDeathSec = -1.0; // world.nowSeconds() when DEAD
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
    // --- Health / wounds ---
    private boolean dead = false;
    private long deathNanos = 0L;

    // --- Corpse / loot ---
    private boolean lootable = false;   // corpse can be looted
    private boolean looted   = false;   // already looted once
    private int lootMeat = 0, lootHide = 0;

    // --- Carry (optional, simple booleans for now) ---
    private boolean carryingMeat = false, carryingHide = false;
    public boolean isCarryingMeat(){ return carryingMeat; }
    public boolean isCarryingHide(){ return carryingHide; }
    // --- NEW: team ---
    private Team team = Team.NEUTRAL;

    // NEW: path as queue of grid waypoints (row,col)
    private final Deque<Point> path = new ArrayDeque<>();
    // in characters/Unit.java
    public enum UnitRole { NONE, LUMBER, HUNTER, WOLF }

    public static enum LumberState {
        SEEK_TREE, MOVE_TO_TREE, CHOPPING, MOVE_TO_CAMP, IDLE
    }
    // in characters.Unit (near your LumberState enum)
    public enum HunterState { INIT, GET_BOW, SEARCH, MOVE_TO_SHOT, SHOOT, LOOT, RETURN_TO_CAMP, UNLOAD, IDLE }
    // fields (put near other role-specific fields)
    private HunterState hunterState = HunterState.IDLE;
    private boolean hasBow = false;
    private double rangedCooldownSec = 1.0;
    private int meleeSkill = 0;             // 0..10 => 0..100%
    private double meleeCooldownSec = 1.0;  // seconds
    private double nextMeleeAt = 0.0;
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
        // inside Unit(...) constructors, after existing init:
        this.maxWounds = Math.max(1, actor.defaultMaxWounds());
        this.wounds    = this.maxWounds;
        this.aimSkill  = clamp01(actor.defaultAimSkill());
        this.power     = clamp01(actor.defaultPower());
        this.rangedCooldownSec = clampCooldown(actor.defaultRangedCooldown());
        this.setMeleeSkill(actor.defaultMeleeSkill());
        this.setMeleeCooldownSec(actor.defaultMeleeCooldown());
        // team stays NEUTRAL unless set later
    }

    // --- NEW: convenience constructor that sets team immediately ---
    public Unit(Actor actor, int row, int col, Team team) {
        this(actor, row, col);
        this.team = (team == null ? Team.NEUTRAL : team);
    }
    public double getRangedCooldownSec() { return rangedCooldownSec; }
    public void setRangedCooldownSec(double s) { rangedCooldownSec = Math.max(0.1, s); }
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
    private int meatCount = 0, hideCount = 0;
    public int getMeatCount() { return meatCount; }
    public int getHideCount() { return hideCount; }
    public void addMeat(int n) { meatCount += Math.max(0, n); }
    public void addHide(int n) { hideCount += Math.max(0, n); }
    public void clearHuntLoot() { meatCount = 0; hideCount = 0; }
    public void setMounted(boolean v) { mounted = v; }
    // Unit.java (fields)
    private boolean aimOverride = false;
    private double aimX = 0.0, aimY = 0.0;
    public void setAimSkill(int v){ aimSkill = Math.max(0, Math.min(10, v)); }
    public int  getAimSkill(){ return aimSkill; }
    public int getMeleeSkill() { return meleeSkill; }
    public void setMeleeSkill(int s) { meleeSkill = Math.max(0, Math.min(10, s)); }

    public double getMeleeCooldownSec() { return meleeCooldownSec; }
    public void setMeleeCooldownSec(double s) { meleeCooldownSec = Math.max(0.2, s); }

    public double getNextMeleeAt() { return nextMeleeAt; }
    public void setNextMeleeAt(double t) { nextMeleeAt = t; }

    public void setPower(int v){ power = Math.max(0, Math.min(10, v)); }
    public int  getPower(){ return power; }

    public int  getMaxWounds(){ return maxWounds; }
    public void setMaxWounds(int mw){ maxWounds = Math.max(1, mw); wounds = Math.min(wounds, maxWounds); }
    public int  getWounds(){ return wounds; }

    public LifeState getLife(){ return life; }
    public boolean isDead(){ return life == LifeState.DEAD; }
    public boolean isWounded(){ return life == LifeState.WOUNDED; }
    public double  getDeathTimeSec(){ return timeOfDeathSec; }

    /** Apply one wound. Moves -> WOUNDED (slow) or DEAD (stops, leaves corpse). */
    public void applyWound(world.World world) {
        // Fast guard
        if (isDead()) return;

        // Decrement first; branch predictability improves vs double checks
        if (--wounds > 0) {
            // Non-lethal: very cheap
            if (life != LifeState.WOUNDED) life = LifeState.WOUNDED;
            // Avoid oscillating this if you already cap speed elsewhere; otherwise keep it:
            movementScale = 0.5;
            return;
        }

        // ---- Lethal hit: do the absolute minimum here ----
        life = LifeState.DEAD;

        // Stop motion cheaply
        moving = false;
        movementScale = 0.0;

        // Kill steering/aim (cheap fields only)
        clearAimTarget();

        // Stop following the current path without heavy churn:
        // If path can be large, clearing allocates/frees per node; prefer a fast clear.
        // (If Unit exposes a fast-path like setPath(null) or setPathLen(0), use that.)
        if (path != null && !path.isEmpty()) {
            path.clear();            // keep if this list is small; otherwise replace with a fast flag in Unit
            // e.g., if you can: this.pathLen = 0; // O(1) logical clear
        }

        // Timestamp (if this is expensive, let caller pass a cached "now")
        timeOfDeathSec = world.nowSeconds();

        // IMPORTANT: Do NOT null out AI here. The AI update already early-outs on isDead().
        // Nulling here causes GC churn and can fight with other code reading ai this frame.
        // ai = null;

        // Optional UI thing is often non-trivial; defer it if your UI listens synchronously.
        // selected = false;

        // If you have a post-tick cleanup queue, enqueue and return (best perf).
        // world.queueUnitDeath(this); // <-- uncomment if you implement the small helper below.
    }
    public long getDeathNanos(){ return deathNanos; }

    public boolean isLootable(){ return dead && lootable && !looted; }

    public boolean lootCorpse(Unit looter){
        if (!isLootable()) return false;
        if (lootMeat <= 0 && lootHide <= 0) return false;
        // take 1/1 as requested
        if (lootMeat > 0) { looter.carryingMeat = true; lootMeat = 0; }
        if (lootHide > 0) { looter.carryingHide = true; lootHide = 0; }
        looted = true;
        return true;
    }
    // Unit.java (API)
    public void setAimTarget(double tx, double ty) { aimOverride = true;  aimX = tx; aimY = ty; }
    public void clearAimTarget()                  { aimOverride = false; }
    public boolean hasAimTarget()                 { return aimOverride; }
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
    // Hunter accessors
    public HunterState getHunterState() { return hunterState; }
    public void setHunterState(HunterState s) { hunterState = (s == null ? HunterState.IDLE : s); }

    public boolean hasBow() { return hasBow; }
    public void setHasBow(boolean v) { hasBow = v; }

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

    /** Advance along the path by speed * dt (cells/sec), but always turn toward aim target if set. */
    public void update(double dt) {
        // ------------- peek movement waypoint (if any) -------------
        boolean havePath = moving && !path.isEmpty();
        Point waypoint = null;
        double wx = 0, wy = 0, dx = 0, dy = 0, dist = 0;

        if (havePath) {
            waypoint = path.peekFirst();
            wx = waypoint.x;  // col
            wy = waypoint.y;  // row
            dx = wx - x;
            dy = wy - y;
            dist = Math.hypot(dx, dy);
            if (dist < 1e-6) { dx = dy = 0; }
        }

        // ------------- choose the orientation target -------------
        // If AI set an aim target (e.g., deer), look at that; otherwise look where we move.
        double desired = orientRad;
        boolean haveMoveDir = havePath && dist > 1e-6;
        if (aimOverride)           desired = Math.atan2(aimY - y, aimX - x);
        else if (haveMoveDir)      desired = Math.atan2(dy, dx);
        // else keep current orientRad

        // ------------- smooth turn toward desired -------------
        double diff = Math.atan2(Math.sin(desired - orientRad), Math.cos(desired - orientRad));
        double maxStep = turnRateRad * dt;
        if (Math.abs(diff) <= maxStep) orientRad = desired;
        else                           orientRad += Math.copySign(maxStep, diff);

        if (orientRad <= -Math.PI) orientRad += 2 * Math.PI;
        if (orientRad >   Math.PI) orientRad -= 2 * Math.PI;

        // ------------- if no path, we're just turning in place -------------
        if (!havePath) {
            // discrete 8-way facing from smooth orientation
            double ang = orientRad < 0 ? orientRad + 2 * Math.PI : orientRad;
            int sector = (int) Math.round(ang / (Math.PI / 4.0)) & 7; // 0..7
            switch (sector) {
                case 0 -> facing = Facing.E;
                case 1 -> facing = Facing.SE;
                case 2 -> facing = Facing.S;
                case 3 -> facing = Facing.SW;
                case 4 -> facing = Facing.W;
                case 5 -> facing = Facing.NW;
                case 6 -> facing = Facing.N;
                case 7 -> facing = Facing.NE;
            }
            return;
        }

        // ------------- movement speed with strafing slowdown -------------
        // Slow down only a bit when aiming somewhere different than our move direction.
        double align = Math.max(0.0, Math.cos(Math.atan2(dy, dx) - orientRad)); // 1 aligned, 0 sideways
        double k = aimOverride ? 0.35 : 0.85;   // much less slowdown while aiming at a target
        double minFrac = 0.25;                  // allow decent strafing speed
        double speedFrac = Math.max(minFrac, (1 - k) + k * align);
        if (getLength() >= 2) speedFrac *= 0.95 + 0.05 * align;

        // CHANGED: apply wounded/debuff scaling to base movement
        double baseSpeed = actor.getMovement() * movementScale;

        // CHANGED: use no-turn fallback when we essentially have no move direction
        double step = haveMoveDir ? (baseSpeed * speedFrac * dt)
                : (baseSpeed * dt);

        // ------------- waypoint arrival / advancement -------------
        if (dist < 1e-3 || step >= dist) {
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

        // ------------- discrete 8-way facing from smooth orientation -------------
        double ang = orientRad < 0 ? orientRad + 2 * Math.PI : orientRad;
        int sector = (int) Math.round(ang / (Math.PI / 4.0)) & 7; // 0..7
        switch (sector) {
            case 0 -> facing = Facing.E;
            case 1 -> facing = Facing.SE;
            case 2 -> facing = Facing.S;
            case 3 -> facing = Facing.SW;
            case 4 -> facing = Facing.W;
            case 5 -> facing = Facing.NW;
            case 6 -> facing = Facing.N;
            case 7 -> facing = Facing.NE;
        }
    }
    // in characters.Unit
    public interface UnitAI { void update(world.World world, characters.Unit u, double dtSeconds); }
    private static int clamp01(int v){ return Math.max(0, Math.min(10, v)); }
    private static double clampCooldown(double s){ return Math.max(0.1, s); }
    private UnitAI ai;
    public void setAI(UnitAI ai) { this.ai = ai; }
    public UnitAI getAI() { return ai; }

    // call from your game loop (World.update or Panel timer):
    public void tickAI(world.World world, double dtSeconds) {
        if (ai != null) ai.update(world, this, dtSeconds);
    }
}
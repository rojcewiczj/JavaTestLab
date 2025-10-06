package world;

import characters.Actor;
import characters.Human;
import characters.Team;
import characters.Unit;
import java.util.*;
import java.util.List;


public class World {
    private final List<List<List<Integer>>> worldMap; // row, col, layer
    private final int height;
    private final int width;
    private final int layers;

    public static final int LAYER_GROUND = 0;
    public static final int LAYER_UNIT   = 1;

    public static final int TILE_GRASS   = 0;
    public static final int UNIT_NONE    = -1;
    public static final int UNIT_SOLDIER = 1;

    // NEW: units in the world
    private final List<Unit> units = new ArrayList<>();
    private final List<ControlPoint> controlPoints = new ArrayList<>();
    private final ResourceManager resources = new ResourceManager();
    private final java.util.List<Building> buildings = new java.util.ArrayList<>();
    public java.util.List<Building> getBuildings() { return buildings; }
    public World(int height, int width, int layers) {
        this.height = height;
        this.width = width;
        this.layers = layers;

        worldMap = new ArrayList<>();
        for (int r = 0; r < height; r++) {
            List<List<Integer>> row = new ArrayList<>();
            for (int c = 0; c < width; c++) {
                List<Integer> cell = new ArrayList<>();
                for (int z = 0; z < layers; z++) {
                    if (z == LAYER_GROUND) cell.add(TILE_GRASS);
                    else cell.add(UNIT_NONE);
                }
                row.add(cell);
            }
            worldMap.add(row);
        }
    }
    // is a 3x3 house footprint fully in-bounds and unblocked?
    public boolean canPlaceHouse(int topRow, int leftCol) {
        for (int r = topRow; r < topRow + 3; r++) {
            for (int c = leftCol; c < leftCol + 3; c++) {
                if (!inBoundsRC(r, c)) return false;
                // reuse your terrain/wall/unit checks
                if (isBlocked(r, c, null)) return false;
                // also: forbid overlap with existing buildings
                if (buildingAt(r, c) != null) return false;
            }
        }
        return true;
    }

    public Building buildingAt(int r, int c) {
        for (Building b : buildings) {
            int br=b.getRow(), bc=b.getCol();
            if (r >= br && r < br+3 && c >= bc && c < bc+3) return b;
        }
        return null;
    }

    // add a house, then spawn arrivals
    public boolean addHouse(int topRow, int leftCol, characters.Team team) {
        if (!canPlaceHouse(topRow, leftCol)) return false;
        buildings.add(new Building(Building.Type.HOUSE, topRow, leftCol, team));
        // Houses block movement: nothing to write to LAYER_UNIT; we rely on isBlocked() to check buildings (below).
        spawnHouseArrivals(topRow, leftCol, team, 4);
        return true;
    }
    /** Try to mount: footman mounts horse (same team, adjacency). Returns true if success. */
    // In World
    public boolean mount(Unit footman, Unit horse) {
        if (footman == null || horse == null) return false;
        if (footman.getTeam() != horse.getTeam()) return false;
        if (!(horse.getActor() instanceof characters.Horse)) return false;

        // Adjacent to ANY tile of the horse's footprint (head or tail)
        int fr = footman.getRowRounded(), fc = footman.getColRounded();
        int hr = horse.getRowRounded(),  hc = horse.getColRounded();
        boolean adjacent = false;
        for (int[] cell : footprintCells(hr, hc, horse.getFacing(), horse.getLength())) {
            if (Math.abs(fr - cell[0]) + Math.abs(fc - cell[1]) <= 1) { adjacent = true; break; }
        }
        if (!adjacent) return false;

        // transfer selection to the mounted unit (nice UX)
        boolean wasSelected = footman.isSelected();

        // Remove the footman as a standalone unit
        units.remove(footman);

        // Mark the horse as a mounted, 2-tile carrier and attach the rider
        horse.__engine_setLength(2);     // ensure 2 tiles
        horse.setMounted(true);
        horse.setRider(footman);

        // keep facing; speed already comes from the Horse actor’s movement

        // keep selection on the mounted unit
        if (wasSelected) horse.setSelected(true);

        syncUnitsToLayer();
        return true;
    }
    // Try a diagonal as two cardinal micro-steps; allow if EITHER order is clear.
// current head at (r,c), moving (dr,dc) where |dr|==1 and |dc|==1
    // infer facing from a single step delta (dr,dc) to test destination footprint
    private characters.Unit.Facing facingFromDelta(int dr, int dc) {
        if (dr == -1 && dc ==  0) return characters.Unit.Facing.N;
        if (dr == -1 && dc ==  1) return characters.Unit.Facing.NE;
        if (dr ==  0 && dc ==  1) return characters.Unit.Facing.E;
        if (dr ==  1 && dc ==  1) return characters.Unit.Facing.SE;
        if (dr ==  1 && dc ==  0) return characters.Unit.Facing.S;
        if (dr ==  1 && dc == -1) return characters.Unit.Facing.SW;
        if (dr ==  0 && dc == -1) return characters.Unit.Facing.W;
        if (dr == -1 && dc == -1) return characters.Unit.Facing.NW;
        return characters.Unit.Facing.S;
    }

    private boolean canStepMountedCardinal(int r, int c, int dr, int dc, characters.Unit mover) {
        if (Math.abs(dr) + Math.abs(dc) != 1) return false; // must be cardinal
        int nr = r + dr, nc = c + dc;
        if (!inBoundsRC(nr, nc)) return false;
        characters.Unit.Facing nf = facingFromDelta(dr, dc);
        for (int[] cell : footprintCells(nr, nc, nf, 2)) {
            if (isBlocked(cell[0], cell[1], mover)) return false;
        }
        return true;
    }

    private boolean canStepMountedDiagonal(int r, int c, int dr, int dc, characters.Unit mover) {
        // Order A: vertical then horizontal
        if (canStepMountedCardinal(r, c, dr, 0, mover) &&
                canStepMountedCardinal(r + dr, c, 0, dc, mover)) return true;

        // Order B: horizontal then vertical
        if (canStepMountedCardinal(r, c, 0, dc, mover) &&
                canStepMountedCardinal(r, c + dc, dr, 0, mover)) return true;

        return false;
    }

    /** Get first unit at tile (row,col) */
    public Unit getUnitAt(int r, int c) {
        for (Unit u : units) {
            int hr = u.getRowRounded(), hc = u.getColRounded();
            for (int[] cell : footprintCells(hr, hc, u.getFacing(), u.getLength())) {
                if (cell[0]==r && cell[1]==c) return u;
            }
        }
        return null;
    }

    /** For a head cell (r,c) and facing, return the 2-tile footprint cells. */
    // in world.World

    static int[] facingOffset(characters.Unit.Facing f) {
        switch (f) {
            case N:  return new int[]{-1,  0};
            case NE: return new int[]{-1,  1};
            case E:  return new int[]{ 0,  1};
            case SE: return new int[]{ 1,  1};
            case S:  return new int[]{ 1,  0};
            case SW: return new int[]{ 1, -1};
            case W:  return new int[]{ 0, -1};
            case NW: return new int[]{-1, -1};
        }
        return new int[]{0,0};
    }

    /** Cells covered by a unit given head row/col and facing. length=1 or 2 supported. */
    public java.util.List<int[]> footprintCells(int headR, int headC,
                                                characters.Unit.Facing f, int length) {
        java.util.ArrayList<int[]> out = new java.util.ArrayList<>(length);
        out.add(new int[]{headR, headC}); // head always occupies its cell
        if (length >= 2) {
            int[] off = facingOffset(f);
            // tail is one cell *behind* the head (opposite of facing)
            int tr = headR - off[0];
            int tc = headC - off[1];
            out.add(new int[]{tr, tc});
        }
        return out;
    }
    private int nextUnitId = 1;
    // --- fields ---
    private final java.util.List<Arrow> arrows = new java.util.ArrayList<>();

    public java.util.List<Arrow> getArrows() { return arrows; }

    // Spawn an arrow (use continuous positions)
    public void spawnArrow(double sx, double sy, double tx, double ty, double speedCellsPerSec) {
        arrows.add(new Arrow(sx, sy, tx, ty, speedCellsPerSec));
    }

    // Advance & prune arrows each tick
    public void updateArrows(double dt) {
        java.util.Iterator<Arrow> it = arrows.iterator();
        while (it.hasNext()) {
            Arrow a = it.next();
            if (a.update(dt)) it.remove();
        }
    }

    private static final class Engagement {
        final int aId, bId;
        // next attack times (in seconds, monotonic)
        double nextAttackA, nextAttackB;
        boolean initialized;

        Engagement(int aId, int bId) {
            // store sorted to keep key stable
            if (aId < bId) { this.aId = aId; this.bId = bId; }
            else { this.aId = bId; this.bId = aId; }
        }
    }

    private final Map<Long, Engagement> engagements = new HashMap<>();

    private long pairKey(int id1, int id2) {
        int a = Math.min(id1, id2), b = Math.max(id1, id2);
        return (((long)a) << 32) ^ (b & 0xffffffffL);
    }

    // simple footman check (customize later)
    private boolean isFootman(characters.Unit u) {
        return u.getActor() instanceof characters.Human; // or use a type/tag on Actor
    }

    // attacks per second: agility -> rate (tune later)
    private double attackInterval(characters.Unit u) {
        int agi = Math.max(1, u.getAgility());
        return 1.0 / agi; // e.g., Agi 3 = 3 hits/sec (fast). Adjust later if too fast.
    }

    public int getHeight() { return height; }
    public int getWidth() { return width; }
    public int getLayers() { return layers; }
    public List<ControlPoint> getControlPoints() { return controlPoints; }
    public void addControlPoint(ControlPoint cp) { controlPoints.add(cp); }

    public ResourceManager getResources() { return resources; }
    public int getCell(int row, int col, int layer) {
        return worldMap.get(row).get(col).get(layer);
    }
    public void setCell(int row, int col, int layer, int value) {
        worldMap.get(row).get(col).set(layer, value);
    }
    public boolean inBounds(int r, int c) {
        return r >= 0 && r < height && c >= 0 && c < width;
    }

    /** True if no Human currently occupies (r,c). */
    public boolean isFree(int r, int c, Collection<Human> humans) {
        for (Human h : humans) {
            if (h.getRow() == r && h.getCol() == c) return false;
        }
        return true;
    }
    // Add near your other constants
    public static final int GROUND_EMPTY = 0;
    public static final int GROUND_WALL  = 1;

    public boolean isWalkable(int r, int c) {
        int ground = getCell(r, c, LAYER_GROUND);
        return ground != GROUND_WALL;
    }

    public void setWall(int r, int c, boolean wall) {
        if (!inBounds(r, c)) return;
        setCell(r, c, LAYER_GROUND, wall ? GROUND_WALL : GROUND_EMPTY);
    }
    /**
     * Find K closest free tiles to (tr,tc), avoiding positions currently occupied by 'humans'
     * and also avoiding 'reserved' already chosen in this assignment round.
     * Simple BFS expanding ring; returns up to K cells.
     */
    public List<int[]> findClosestFreeCells(int tr, int tc, int k,
                                            Collection<Human> humans,
                                            Set<Long> reserved) {
        List<int[]> result = new ArrayList<>();
        if (!inBounds(tr, tc)) return result;

        boolean[][] visited = new boolean[height][width];
        ArrayDeque<int[]> q = new ArrayDeque<>();
        q.add(new int[]{tr, tc});
        visited[tr][tc] = true;

        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
        while (!q.isEmpty() && result.size() < k) {
            int[] cur = q.poll();
            int r = cur[0], c = cur[1];

            long key = (((long)r)<<32) ^ (c & 0xffffffffL);
            boolean occupied = !isFree(r, c, humans) || reserved.contains(key);
            if (!occupied) {
                result.add(new int[]{r, c});
                reserved.add(key); // reserve so next unit won’t get the same cell
                if (result.size() == k) break;
            }

            for (int[] d : dirs) {
                int nr = r + d[0], nc = c + d[1];
                if (inBounds(nr, nc) && !visited[nr][nc]) {
                    visited[nr][nc] = true;
                    q.add(new int[]{nr, nc});
                }
            }
        }
        return result;
    }
    /** Toggle on unit layer (kept for convenience) */
    public void toggleUnit(int row, int col) {
        int current = getCell(row, col, LAYER_UNIT);
        setCell(row, col, LAYER_UNIT, current == UNIT_NONE ? UNIT_SOLDIER : UNIT_NONE);
    }

    // --- NEW: Unit management ---
    public void addUnit(Unit u) {
        units.add(u);
    }
    public List<Unit> getUnits() {
        return units;
    }
    // NEW: convenience to create and register a Unit from an Actor
    public Unit spawnActor(characters.Actor actor, int r, int c) {
        Unit u = new Unit(actor, c, r);
        u.__engine_setId(nextUnitId++);   // <<< give each unit an id
        // Horses occupy 2 tiles
        if (actor instanceof characters.Horse) {
            u.__engine_setLength(2);
        }

        units.add(u);
        syncUnitsToLayer();
        return u;
    }
    // Chebyshev distance (good for grid range “tiles”)
    private int chebyshev(int r1, int c1, int r2, int c2) {
        return Math.max(Math.abs(r1 - r2), Math.abs(c1 - c2));
    }

    // Adjacent contact (melee)
    private boolean adjacent(int r1, int c1, int r2, int c2) {
        return Math.abs(r1 - r2) + Math.abs(c1 - c2) == 1;
    }

    // Line of sight using Bresenham; walls block vision. Units do NOT block LOS for now.
    public boolean hasLineOfSight(int r1, int c1, int r2, int c2) {
        int x0 = c1, y0 = r1, x1 = c2, y1 = r2;
        int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
        int err = dx + dy; // error term

        int x = x0, y = y0;
        while (true) {
            if (!(y == y0 && x == x0) && !(y == y1 && x == x1)) {
                // Skip start & end; check tiles in between
                if (!isWalkable(y, x)) return false; // wall blocks vision
            }
            if (x == x1 && y == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x += sx; }
            if (e2 <= dx) { err += dx; y += sy; }
        }
        return true;
    }

    // Weapon name to print for melee (auto-swap to sword if available)
    private String meleeWeaponName(characters.Unit u) {
        return u.getActor().hasSword() ? "Sword" : "Fists";
    }

    public void processCombat(double nowSeconds) {
        // ----- Ranged phase -----
        for (characters.Unit archer : units) {
            var a = archer.getActor();
            if (!a.hasShortBow()) continue;

            int ar = archer.getRowRounded(), ac = archer.getColRounded();

            // skip if engaged in melee
            boolean engaged = false;
            for (characters.Unit other : units) {
                if (other == archer) continue;
                if (other.getTeam() == archer.getTeam()) continue;
                if (adjacent(ar, ac, other.getRowRounded(), other.getColRounded())) { engaged = true; break; }
            }
            if (engaged) continue;

            // pick nearest enemy in LOS, 3–10 tiles
            characters.Unit best = null;
            int bestDist = Integer.MAX_VALUE;
            for (characters.Unit enemy : units) {
                if (enemy.getTeam() == archer.getTeam()) continue;
                int er = enemy.getRowRounded(), ec = enemy.getColRounded();
                int d = chebyshev(ar, ac, er, ec);
                if (d < 3 || d > 10) continue;
                if (!hasLineOfSight(ar, ac, er, ec)) continue;
                if (d < bestDist) { best = enemy; bestDist = d; }
            }
            if (best == null) continue;

            // fire if cooldown is up
            if (nowSeconds >= archer.getNextRangedAttackAt()) {
                System.out.println(archer.getActor().getName() + " (" + archer.getTeam()
                        + ", Short Bow) shoots at "
                        + best.getActor().getName() + " (" + best.getTeam() + ")");

                // spawn a flying arrow (use 'this.' if inside World)
                spawnArrow(
                        archer.getX(), archer.getY(),
                        best.getX(),   best.getY(),
                        12.0 // tiles/sec
                );

                archer.setNextRangedAttackAt(nowSeconds + attackInterval(archer));
            }
        }

        // ----- Melee phase (your existing engagement system) -----
        // ... your existing contact-pair build, charger init, and hit printing ...
        // Make sure your melee print uses meleeWeaponName(u) to show Sword/Fists.
        // (Paste your earlier melee Engagement code here unchanged, except weapon string:)
        Set<Long> seenThisFrame = new HashSet<>();

        for (int i = 0; i < units.size(); i++) {
            Unit ui = units.get(i);
            int ri = ui.getRowRounded(), ci = ui.getColRounded();
            for (int j = i + 1; j < units.size(); j++) {
                Unit uj = units.get(j);
                if (ui.getTeam() == uj.getTeam()) continue;          // must be enemies
                if (!isFootman(ui) || !isFootman(uj)) continue;      // only footmen for now

                int rj = uj.getRowRounded(), cj = uj.getColRounded();
                if (manhattan(ri, ci, rj, cj) != 1) continue;        // adjacent/contact

                long key = pairKey(ui.getId(), uj.getId());
                seenThisFrame.add(key);

                Engagement eg = engagements.computeIfAbsent(key, k -> new Engagement(ui.getId(), uj.getId()));
                if (!eg.initialized) {
                    // initiative to the charger: the one who moved most recently
                    long li = ui.getLastMoveNanos();
                    long lj = uj.getLastMoveNanos();
                    boolean iCharges = li > lj;

                    if (iCharges) {
                        eg.nextAttackA = nowSeconds;                         // ui first
                        eg.nextAttackB = nowSeconds + attackInterval(uj);    // uj after its interval
                    } else {
                        eg.nextAttackA = nowSeconds + attackInterval(ui);
                        eg.nextAttackB = nowSeconds;                         // uj first
                    }
                    eg.initialized = true;
                }

                // 2) drive attacks
                // Map eg.a to the actual unit reference
                Unit a = (eg.aId == ui.getId()) ? ui : uj;
                Unit b = (eg.bId == ui.getId()) ? ui : uj;

                // a’s turn?
                if (nowSeconds >= eg.nextAttackA) {
                    System.out.println(a.getActor().getName() + " (" + a.getTeam() + ", " + a.getActor().getEquipment()
                            + ") hits " + b.getActor().getName() + " (" + b.getTeam() + ")");
                    eg.nextAttackA += attackInterval(a);
                }

                // b’s turn?
                if (nowSeconds >= eg.nextAttackB) {
                    System.out.println(b.getActor().getName() + " (" + b.getTeam() + ", " + b.getActor().getEquipment()
                            + ") hits " + a.getActor().getName() + " (" + a.getTeam() + ")");
                    eg.nextAttackB += attackInterval(b);
                }
            }
        }

        // 3) clean up engagements that are no longer in contact
        engagements.keySet().removeIf(k -> !seenThisFrame.contains(k));
    }
    private static final class Node {
        final int r, c;
        Node(int r, int c) { this.r = r; this.c = c; }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Node)) return false;
            Node n = (Node) o;
            return r == n.r && c == n.c;
        }
        @Override public int hashCode() { return 31 * r + c; }
    }

    /** Clear unit layer and re-place units based on their current positions. */
    public void syncUnitsToLayer() {
        // clear unit layer
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                setCell(r, c, LAYER_UNIT, UNIT_NONE); // row/col/layer
            }
        }

        // stamp footprints
        int id = 1;
        for (Unit u : units) {
            int hr = u.getRowRounded(), hc = u.getColRounded();
            for (int[] cell : footprintCells(hr, hc, u.getFacing(), u.getLength())) {
                int rr = cell[0], cc = cell[1];
                if (inBoundsRC(rr, cc)) setCell(rr, cc, LAYER_UNIT, id); // row/col/layer
            }
            id++;
        }
    }

    private int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
    // --- OCCUPANCY & TERRAIN ---

    /** True if (row,col) is within the grid. */
    public boolean inBoundsRC(int row, int col) {
        return row >= 0 && row < height && col >= 0 && col < width;
    }

    /** True if the tile blocks movement (extend with terrain checks as needed). */
    public boolean isBlockedTerrain(int row, int col) {
        // Example: treat only grid bounds as blocking; all ground (grass) traversable.
        // If you add walls/trees, consult ground layer here.
        return !inBoundsRC(row, col);
    }

    /** True if a unit occupies (row,col), excluding 'ignore' (the moving unit). */
    public boolean isOccupiedByUnit(int row, int col, Unit ignore) {
        for (Unit u : units) {
            if (u == ignore) continue;
            if (Math.round(u.getY()) == row && Math.round(u.getX()) == col) return true;
        }
        return false;
    }

    /** A tile is blocked if terrain blocks OR another unit stands there (except 'ignore'). */
    private boolean isBlocked(int r, int c, Unit ignore) {
        if (!inBoundsRC(r,c) || !isWalkable(r,c)) return true;
        for (Unit other : units) {
            if (other == ignore) continue;
            int hr = other.getRowRounded(), hc = other.getColRounded();
            for (int[] cell : footprintCells(hr, hc, other.getFacing(), other.getLength())) {
                if (cell[0]==r && cell[1]==c) return true;
            }
        }
        // buildings (NEW): simple AABB test for 3x3 house
        if (buildingAt(r, c) != null) return true;
        return false;
    }
    private void spawnHouseArrivals(int topRow, int leftCol, characters.Team team, int count) {
        // target = center tile of 3x3 (row+1, col+1)
        int targetR = topRow + 1;
        int targetC = leftCol + 1;

        // choose 4 spawn edge positions (L, R, T, B) spaced near house direction
        int[][] candidates = {
                {0, targetC},                           // top edge
                {height - 1, targetC},                  // bottom edge
                {targetR, 0},                           // left edge
                {targetR, width - 1}                    // right edge
        };

        int spawned = 0;
        for (int i = 0; i < candidates.length && spawned < count; i++) {
            int sr = clamp(candidates[i][0], 0, height - 1);
            int sc = clamp(candidates[i][1], 0, width - 1);

            // nudge sideways if blocked
            int wiggle = 0;
            boolean placed = false;
            while (wiggle < Math.max(height, width)) {
                int rr = sr, cc = sc + wiggle;
                if (inBoundsRC(rr, cc) && !isBlocked(rr, cc, null)) {
                    Human h = Human.basicFootman(); h.setName("Arrival");
                    characters.Unit u = spawnActor(h, rr, cc);
                    u.setTeam(team);
                    java.util.List<java.awt.Point> path = findPathAStar(rr, cc, targetR, targetC, u);
                    if (path != null && !path.isEmpty()) { u.setPath(path); }
                    spawned++; placed = true; break;
                }
                cc = sc - wiggle;
                if (wiggle > 0 && inBoundsRC(sr, cc) && !isBlocked(sr, cc, null)) {
                    Human h = Human.basicFootman(); h.setName("Arrival");
                    characters.Unit u = spawnActor(h, sr, cc);
                    u.setTeam(team);
                    java.util.List<java.awt.Point> path = findPathAStar(sr, cc, targetR, targetC, u);
                    if (path != null && !path.isEmpty()) { u.setPath(path); }
                    spawned++; placed = true; break;
                }
                wiggle++;
            }
            if (!placed) {
                // couldn't find a free spawn near this edge; try next candidate
            }
        }
    }

// --- PATHFINDING (A*) ---

    // 8-connected neighborhood
    private static final int[][] DIR8 = {
            { 1, 0}, {-1, 0}, {0, 1}, {0,-1},
            { 1, 1}, { 1,-1}, {-1, 1}, {-1,-1}
    };

    // Euclidean heuristic (admissible for 8-way movement)
    private double heuristicEuclid(int r1, int c1, int r2, int c2) {
        return Math.hypot(r1 - r2, c1 - c2);
    }

    /**
     * A* path from (startRow,startCol) to (goalRow,goalCol).
     * 8-direction movement; no diagonal corner-cutting; other units are obstacles (except 'ignore').
     * Returns waypoints as Points (x=col, y=row), including start & goal, or null if no path.
     */
    public java.util.List<java.awt.Point> findPathAStar(
            int startRow, int startCol, int goalRow, int goalCol, Unit ignore) {

        if (!inBoundsRC(startRow, startCol) || !inBoundsRC(goalRow, goalCol)) return null;
        if (isBlocked(goalRow, goalCol, ignore)) return null;

        Node start = new Node(startRow, startCol);
        Node goal  = new Node(goalRow,  goalCol);

        java.util.Map<Node, Node>   cameFrom = new java.util.HashMap<>();
        java.util.Map<Node, Double> gScore   = new java.util.HashMap<>();
        java.util.Map<Node, Double> fScore   = new java.util.HashMap<>();

        gScore.put(start, 0.0);
        fScore.put(start, heuristicEuclid(start.r, start.c, goal.r, goal.c));

        java.util.PriorityQueue<Node> open = new java.util.PriorityQueue<>(
                java.util.Comparator.comparingDouble(n ->
                        fScore.getOrDefault(n, java.lang.Double.POSITIVE_INFINITY))
        );
        open.add(start);

        final double SQRT2 = Math.sqrt(2.0);

        while (!open.isEmpty()) {
            Node current = open.poll();
            if (current.equals(goal)) {
                return reconstructPath(cameFrom, current); // Points with x=col, y=row
            }

            for (int[] d : DIR8) {
                int nr = current.r + d[0];
                int nc = current.c + d[1];
                if (!inBoundsRC(nr, nc)) continue;

                boolean diagonal = (d[0] != 0 && d[1] != 0);
                boolean moverMounted = (ignore != null && ignore.getLength() >= 2);

// prevent diagonal corner-cutting for ALL units (your original rule)
                if (diagonal) {
                    if (isBlocked(current.r + d[0], current.c, ignore) ||
                            isBlocked(current.r, current.c + d[1], ignore)) {
                        continue;
                    }
                }

                if (moverMounted) {
                    if (diagonal) {
                        // allow diagonal only if a valid two-step clearance exists
                        if (!canStepMountedDiagonal(current.r, current.c, d[0], d[1], ignore)) continue;
                    } else {
                        // cardinal: ensure BOTH head & tail free at destination
                        if (!canStepMountedCardinal(current.r, current.c, d[0], d[1], ignore)) continue;
                    }
                } else {
                    // normal 1-tile unit
                    if (isBlocked(nr, nc, ignore)) continue;
                }

                Node neigh = new Node(nr, nc);

                double stepCost   = diagonal ? SQRT2 : 1.0;
                double tentativeG = gScore.getOrDefault(current, Double.POSITIVE_INFINITY) + stepCost;

                if (tentativeG < gScore.getOrDefault(neigh, Double.POSITIVE_INFINITY)) {
                    cameFrom.put(neigh, current);
                    gScore.put(neigh, tentativeG);
                    double f = tentativeG + heuristicEuclid(nr, nc, goal.r, goal.c);
                    fScore.put(neigh, f);
                    // re-insert is fine (no decrease-key in PQ)
                    open.add(neigh);
                }
            }
        }
        return null; // no path found
    }
    // --- add to World ---


    // Build list of Points with x=col, y=row (includes start & goal)
    private java.util.List<java.awt.Point> reconstructPath(
            java.util.Map<Node, Node> cameFrom, Node current) {
        java.util.LinkedList<java.awt.Point> out = new java.util.LinkedList<>();
        while (current != null) {
            out.addFirst(new java.awt.Point(current.c, current.r));
            current = cameFrom.get(current);
        }
        return out;
    }

    // --- Control logic ---

    /** Returns the owning team for a control point, or null if neutral/contested. */
    public Team evaluateOwner(ControlPoint cp) {
        boolean anyRed = false, anyBlue = false;

        for (Unit u : units) {
            int ur = (int)Math.round(u.getY());
            int uc = (int)Math.round(u.getX());
            if (manhattan(ur, uc, cp.getRow(), cp.getCol()) <= cp.getRadius()) {
                if (u.getTeam() == Team.RED) anyRed = true;
                else if (u.getTeam() == Team.BLUE) anyBlue = true;
            }
            if (anyRed && anyBlue) return null; // contested
        }

        if (anyRed && !anyBlue) return Team.RED;
        if (anyBlue && !anyRed) return Team.BLUE;
        return null; // neutral (no one nearby)
    }

    public void payIncome(double dtSec) {
        for (ControlPoint cp : controlPoints) {
            Team owner = evaluateOwner(cp);
            if (owner != null) resources.add(owner, cp.getIncomePerSec() * dtSec);
        }
    }
    private int manhattan(int r1, int c1, int r2, int c2) {
        return Math.abs(r1 - r2) + Math.abs(c1 - c2);
    }

}
package world;

import characters.Actor;
import characters.Human;
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

    public int getHeight() { return height; }
    public int getWidth() { return width; }
    public int getLayers() { return layers; }

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
    public Unit spawnActor(Actor actor, int row, int col) {
        Unit u = new Unit(actor, row, col);
        addUnit(u);
        syncUnitsToLayer(); // reflect on the unit layer
        return u;
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
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                setCell(r, c, LAYER_UNIT, UNIT_NONE);
            }
        }
        for (Unit u : units) {
            int rr = clamp((int)Math.round(u.getY()), 0, height - 1);
            int cc = clamp((int)Math.round(u.getX()), 0, width  - 1);
            setCell(rr, cc, LAYER_UNIT, UNIT_SOLDIER);
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
    public boolean isBlocked(int row, int col, Unit ignore) {
        if (!inBoundsRC(row, col)) return true;
        if (isBlockedTerrain(row, col)) return true;
        if (isOccupiedByUnit(row, col, ignore)) return true;
        return false;
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
                Node neigh = new Node(nr, nc);

                // skip blocked tiles
                if (isBlocked(nr, nc, ignore)) continue;

                // prevent diagonal corner-cutting
                boolean diagonal = (d[0] != 0 && d[1] != 0);
                if (diagonal) {
                    if (isBlocked(current.r + d[0], current.c, ignore) ||
                            isBlocked(current.r, current.c + d[1], ignore)) {
                        continue;
                    }
                }

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

}
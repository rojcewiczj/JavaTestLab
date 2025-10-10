package world;

import characters.Actor;
import characters.Human;
import characters.Team;
import characters.Unit;
import intelligence.TeamSightings;

import java.awt.*;
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
    // FOW
    // World.java fields
    private final int[][] fogDist;
    private final boolean[][] explored; // seen at least once
    private final boolean[][] visible;  // currently visible this tick

    // Opaque mask cache (updated when trees/buildings change)
    private final boolean[][] opaque;

    // NEW: units in the world
    private final List<Unit> units = new ArrayList<>();
    private final List<ControlPoint> controlPoints = new ArrayList<>();
    private final ResourceManager resources = new ResourceManager();
    private final java.util.List<Building> buildings = new java.util.ArrayList<>();
    public java.util.List<Building> getBuildings() { return buildings; }
    private final java.util.List<Terrain.TreePatch> treePatches = new java.util.ArrayList<>();
    public java.util.List<Terrain.TreePatch> getTreePatches() { return treePatches; }
    public int getFogDist(int r, int c) { return fogDist[r][c]; }
    private boolean[][] buildingMask;  // set true for any building footprint tile

    // quick helper: check if a tile is inside any 2×2 tree
    // World.java: fields
    private boolean[][] treeMask;  // [height][width]
    // world/World.java
    private characters.Team playerVisionTeam = characters.Team.RED;

    public characters.Team getPlayerVisionTeam() { return playerVisionTeam; }
    private final TeamSightings teamSightings = new TeamSightings();

    public java.util.Map<Integer, TeamSightings.Sighting> getSightingsForTeam(characters.Team t) {
        return teamSightings.view(t);
    }
    public void setPlayerVisionTeam(characters.Team t) {
        playerVisionTeam = (t == null ? characters.Team.RED : t);
    }

    // call when world is constructed (or whenever size known)
    private void initMasks() {
        treeMask = new boolean[height][width];
        buildingMask = new boolean[height][width];
    }

    // whenever you add/remove trees:
    private void addTreeBlock(int top, int left) {
        for (int r = top; r < top + 2; r++)
            for (int c = left; c < left + 2; c++)
                treeMask[r][c] = true;
    }

    // replace isTreeAt loop:
    private boolean isTreeAt(int r, int c) {
        return treeMask[r][c];
    }

    public World(int height, int width, int layers) {
        this.height = height;
        this.width = width;
        this.layers = layers;
        fogDist = new int[height][width];
        explored = new boolean[height][width];
        visible  = new boolean[height][width];
        opaque   = new boolean[height][width];
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
            initMasks();
            rebuildOpaqueMask();
            worldMap.add(row);
        }
        teamSightings.setTtlSeconds(10.0);
    }
    /** A tile blocks vision if any static blocker occupies it. */
    private void rebuildOpaqueMask() {
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                boolean op = false;
                if (treeMask[r][c]) op = true;
                if (buildingMask[r][c]) {
                    // If you want some buildings (e.g., FARM) not to block, keep a per-cell
                    // building type map. Otherwise, treat any buildingMask as opaque:
                    op = true;
                }
                opaque[r][c] = op;
            }
        }
    }
    public boolean isOpaque(int r, int c) {
        if (!inBoundsRC(r,c)) return true;
        return opaque[r][c];
    }

    // --- Fog arrays accessors for renderer ---
    public boolean isVisible(int r, int c)  { return inBoundsRC(r,c) && visible[r][c]; }
    public boolean isExplored(int r, int c) { return inBoundsRC(r,c) && explored[r][c]; }
    // world/World.java
    public boolean addHuntingCamp(int top, int left, Team team) {
        if (!canPlaceBuilding(Building.Type.HUNTING_CAMP,top, left)) return false;
        var b = new Building(Building.Type.HUNTING_CAMP, top, left, team);
        buildings.add(b);
        stampBuilding(b.getRow(), b.getCol(), b.getType().h, b.getType().w, true);
//        rebuildOpaqueMask();                 // single rebuild here
        return true;
    }
    // Returns a walkable perimeter tile around the building (row,col), nearest to 'u'.
// Prefers tiles that are in-bounds and not blocked for 'u'.
    public int[] findApproachTileForBuilding(Building b, characters.Unit u) {
        int top = b.getRow(), left = b.getCol();
        int h = b.getType().h, w = b.getType().w;

        int bestR = -1, bestC = -1;
        double best = Double.POSITIVE_INFINITY;

        // One-tile border ring around the rectangle [top..top+h-1, left..left+w-1]
        for (int r = top - 1; r <= top + h; r++) {
            for (int c = left - 1; c <= left + w; c++) {
                boolean onPerim = (r == top - 1 || r == top + h || c == left - 1 || c == left + w);
                if (!onPerim) continue;
                if (!inBoundsRC(r, c)) continue;
                if (isBlocked(r, c, u)) continue;

                double dr = r - u.getY(), dc = c - u.getX();
                double d2 = dr*dr + dc*dc;
                if (d2 < best) { best = d2; bestR = r; bestC = c; }
            }
        }
        if (bestR == -1) return null; // fully surrounded / no access
        return new int[]{ bestR, bestC };
    }


    /** Call once per tick before painting. */
    public void computeVisibility() {
        // clear current visibility
        for (int r = 0; r < height; r++) java.util.Arrays.fill(visible[r], false);

        // Only units on the player’s vision team contribute to visibility
        for (characters.Unit u : units) {
            if (u.getTeam() != playerVisionTeam) continue;       // <-- ignore enemies/neutral (e.g., deer)
            applyUnitFOVShadow(u);
        }

        // explored := explored OR visible
        for (int r = 0; r < height; r++)
            for (int c = 0; c < width;  c++)
                if (visible[r][c]) explored[r][c] = true;
        long nowNanos = System.nanoTime();
// Record deer seen by the PLAYER’s vision team (you already restrict FOV to that team)
        teamSightings.updateDeerFromVisibility(this, playerVisionTeam, nowNanos);
        teamSightings.expireOld(playerVisionTeam, nowNanos);
        // NEW: compute feather distances (blocked by opaque)
        computeFogFeatherDistances(3); // feather radius in tiles; tweak 2–4
    }

    private void computeFogFeatherDistances(int maxSteps) {
        java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
        // init
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                if (visible[r][c]) {
                    fogDist[r][c] = 0;
                    q.add(new int[]{r,c});
                } else {
                    fogDist[r][c] = Integer.MAX_VALUE;
                }
            }
        }
        // 4-neighbor BFS; stop at opaque and clamp by maxSteps
        final int[] DR = {1,-1,0,0};
        final int[] DC = {0,0,1,-1};
        while (!q.isEmpty()) {
            int[] t = q.pollFirst();
            int r = t[0], c = t[1];
            int d = fogDist[r][c];
            if (d >= maxSteps) continue;
            for (int k=0;k<4;k++) {
                int rr = r + DR[k], cc = c + DC[k];
                if (!inBoundsRC(rr, cc)) continue;
                if (isOpaque(rr, cc)) continue;                 // <-- don’t feather through walls/trees
                int nd = d + 1;
                if (nd < fogDist[rr][cc]) {
                    fogDist[rr][cc] = nd;
                    q.addLast(new int[]{rr,cc});
                }
            }
        }
    }
    // World.java
    private void applyUnitFOVShadow(characters.Unit u) {
        final double range   = u.getActor().getVisionRangeTiles();
        final double coneRad = u.getActor().getVisionConeRad();
        final boolean useCone = coneRad < Math.PI * 1.999;

        final double ux = u.getX();  // <— continuous col
        final double uy = u.getY();  // <— continuous row
        final int ur = (int)Math.floor(uy);
        final int uc = (int)Math.floor(ux);
        if (!inBoundsRC(ur, uc)) return;

        visible[ur][uc] = true;

        final double faceX = Math.cos(u.getOrientRad());
        final double faceY = Math.sin(u.getOrientRad());
        final double halfConeCos = Math.cos(coneRad * 0.5);
        final double range2 = range * range;
        final int radius = (int)Math.floor(range);

        castOctant(uy, ux, radius, 1, 0, 0, 1,  1.0, 0.0, faceX, faceY, halfConeCos, useCone, range2);
        castOctant(uy, ux, radius, 0, 1, 1, 0,  1.0, 0.0, faceX, faceY, halfConeCos, useCone, range2);
        castOctant(uy, ux, radius, 0,-1, 1, 0,  1.0, 0.0, faceX, faceY, halfConeCos, useCone, range2);
        castOctant(uy, ux, radius,-1, 0, 0, 1,  1.0, 0.0, faceX, faceY, halfConeCos, useCone, range2);
        castOctant(uy, ux, radius,-1, 0, 0,-1,  1.0, 0.0, faceX, faceY, halfConeCos, useCone, range2);
        castOctant(uy, ux, radius, 0,-1,-1, 0,  1.0, 0.0, faceX, faceY, halfConeCos, useCone, range2);
        castOctant(uy, ux, radius, 0, 1,-1, 0,  1.0, 0.0, faceX, faceY, halfConeCos, useCone, range2);
        castOctant(uy, ux, radius, 1, 0, 0,-1,  1.0, 0.0, faceX, faceY, halfConeCos, useCone, range2);
    }

    /**
     * Recursive shadowcasting (RogueBasin-style), adapted for tile centers, range, and optional vision cone.
     * (xx,xy,yx,yy) map the octant; start=1.0,end=0.0 define the initial slopes.
     */

    private void castOctant(
            double oy, double ox, int radius,
            int xx, int xy, int yx, int yy,
            double startSlope, double endSlope,
            double faceX, double faceY, double halfConeCos, boolean useCone,
            double range2
    ) {
        if (startSlope < endSlope) return;

        final int r0 = (int)Math.floor(oy);
        final int c0 = (int)Math.floor(ox);

        for (int row = 1; row <= radius; row++) {
            int dx = -row;
            int minCol = (int)Math.floor((row * endSlope) + 0.5);
            int maxCol = (int)Math.ceil ((row * startSlope) - 0.5);

            boolean blocked = false;
            double newStart = 0.0;

            for (int col = maxCol; col >= minCol; col--) {
                int rr = r0 + dx * yx + col * yy;
                int cc = c0 + dx * xx + col * xy;

                if (!inBoundsRC(rr, cc)) continue;

                // CENTER-TO-CENTER from the unit’s continuous position
                double dxw = (cc + 0.5) - ox;  // col delta in world cells
                double dyw = (rr + 0.5) - oy;  // row delta in world cells
                double d2  = dxw*dxw + dyw*dyw;
                if (d2 > range2) continue;

                // cone test using continuous vector
                boolean insideCone = true;
                if (useCone && d2 > 1e-9) {
                    double inv = 1.0 / Math.sqrt(d2);
                    double dot = (dxw*inv) * faceX + (dyw*inv) * faceY;
                    insideCone = (dot >= halfConeCos);
                }
                if (insideCone) visible[rr][cc] = true;

                boolean opaque = isOpaque(rr, cc);
                if (!insideCone) opaque = false; // outside cone is transparent to shadows

                if (blocked) {
                    if (opaque) {
                        newStart = (col + 0.5) / (row - 0.5);
                    } else {
                        blocked = false;
                        startSlope = newStart;
                    }
                } else {
                    if (opaque && row < radius) {
                        blocked = true;
                        double newEnd = (col - 0.5) / (row + 0.5);
                        castOctant(oy, ox, radius, xx, xy, yx, yy,
                                startSlope, newEnd, faceX, faceY, halfConeCos, useCone, range2);
                        newStart = (col + 0.5) / (row - 0.5);
                    }
                }
            }
            if (blocked) return;
        }
    }

    private void castLightRecurse(int row0, int col0, int depth, double startSlope, double endSlope,
                                  int xx, int xy, int yx, int yy,
                                  double faceX, double faceY, double halfConeCos, boolean useCone,
                                  int maxRange, double range2) {
        if (startSlope < endSlope) return;
        if (depth > maxRange) return;

        int blocked = 0;
        double nextStartSlope = startSlope;

        for (int dist = depth; dist <= maxRange && blocked == 0; dist++) {
            int r = -dist;
            int c = (int)Math.round(-dist * startSlope);

            // Walk the row from left slope to right slope
            while ((double)c >= -dist * endSlope) {
                int rr = row0 + r * yx + c * yy;
                int cc = col0 + r * xx + c * xy;

                double centerDx = (cc + 0.5) - col0;
                double centerDy = (rr + 0.5) - row0;
                double d2 = centerDx*centerDx + centerDy*centerDy;

                boolean inBounds = inBoundsRC(rr, cc);
                boolean withinRange = d2 <= range2;

                // Cone test: only "see" tiles within cone, but IMPORTANT:
                // tiles OUTSIDE cone are treated as transparent so they don't cast shadows into the cone.
                boolean insideCone = true;
                if (useCone && d2 > 1e-9) {
                    double len = Math.sqrt(d2);
                    double dot = (centerDx/len) * faceX + (centerDy/len) * faceY; // cos(theta)
                    insideCone = (dot >= halfConeCos);
                }

                if (inBounds && withinRange && insideCone) {
                    visible[rr][cc] = true;
                }

                // Check blocking **only** if inside bounds and within range.
                boolean tileOpaque = false;
                if (inBounds && withinRange) {
                    tileOpaque = isOpaque(rr, cc);
                }

                // Outside cone? Treat as transparent (do not affect shadows)
                if (!insideCone) tileOpaque = false;

                if (blocked == 1) {
                    // we are scanning a shadow
                    if (tileOpaque) {
                        // still in shadow: adjust nextStartSlope
                        nextStartSlope = slope(c, r - 0.5);
                    } else {
                        // end of the shadow; resume scanning
                        blocked = 0;
                        startSlope = nextStartSlope;
                    }
                } else {
                    // not in shadow
                    if (tileOpaque && dist < maxRange) {
                        // beginning of a new shadow; recurse to scan the area beyond this blocker
                        blocked = 1;
                        castLightRecurse(row0, col0, dist + 1, startSlope, slope(c - 0.5, r + 0.5),
                                xx, xy, yx, yy, faceX, faceY, halfConeCos, useCone, maxRange, range2);
                        nextStartSlope = slope(c, r - 0.5);
                    }
                }
                c--;
            }
        }
    }

    private double slope(double col, double row) {
        if (row == 0.0) return (col > 0 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY);
        return col / row;
    }
    private void stampBuilding(int top, int left, int h, int w, boolean value) {
        for (int r = top; r < top + h; r++) {
            for (int c = left; c < left + w; c++) {
                if (inBoundsRC(r, c)) buildingMask[r][c] = value;
            }
        }
    }
    // World.java


    public boolean canPlaceBuilding(Building.Type type, int topRow, int leftCol) {
        for (int r = topRow; r < topRow + type.h; r++) {
            for (int c = leftCol; c < leftCol + type.w; c++) {
                if (!inBoundsRC(r, c)) return false;
                if (treeMask != null && treeMask[r][c]) return false;
                if (buildingMask != null && buildingMask[r][c]) return false;
                // optional: disallow if a unit currently occupies it
                // if (isUnitAt(r,c)) return false;
            }
        }
        return true;
    }

    public Building buildingAt(int r, int c) {
        for (Building b : buildings) {
            int br = b.getRow(), bc = b.getCol();
            if (r >= br && r < br + b.getType().h && c >= bc && c < bc + b.getType().w) return b;
        }
        return null;
    }

    public java.util.List<Building> buildingsOf(Team team, Building.Type t) {
        java.util.ArrayList<Building> out = new java.util.ArrayList<>();
        for (Building b : buildings) if (b.getTeam() == team && b.getType() == t) out.add(b);
        return out;
    }

    // add a house, then spawn arrivals
    public boolean addHouse(int topRow, int leftCol, characters.Team team) {
        if (!canPlaceBuilding(Building.Type.HOUSE, topRow, leftCol)) return false;
        buildings.add(new Building(Building.Type.HOUSE, topRow, leftCol, team));
        stampBuilding(topRow, leftCol, Building.Type.HOUSE.h, Building.Type.HOUSE.w, true);
//        rebuildOpaqueMask();
        return true; // spawning handled in trySpawnArrivalsForHouses()
    }

    public boolean addFarm(int topRow, int leftCol, characters.Team team) {
        if (!canPlaceBuilding(Building.Type.FARM, topRow, leftCol)) return false;
        buildings.add(new Building(Building.Type.FARM, topRow, leftCol, team));
        stampBuilding(topRow, leftCol, Building.Type.FARM.h, Building.Type.FARM.w, true);
        return true;
    }

    public boolean addBarn(int topRow, int leftCol, characters.Team team) {
        if (!canPlaceBuilding(Building.Type.BARN, topRow, leftCol)) return false;
        buildings.add(new Building(Building.Type.BARN, topRow, leftCol, team));
        stampBuilding(topRow, leftCol, Building.Type.BARN.h, Building.Type.BARN.w, true);
//        rebuildOpaqueMask();
        return true;
    }
    public boolean addLoggingCamp(int topRow, int leftCol, characters.Team team) {
        if (!canPlaceLoggingCamp(topRow, leftCol)) return false;
        buildings.add(new Building(Building.Type.LOGGING_CAMP, topRow, leftCol, team));
        // stamp to building mask for collision:
        stampBuilding(topRow, leftCol, Building.Type.LOGGING_CAMP.h, Building.Type.LOGGING_CAMP.w, true);
//        rebuildOpaqueMask();
        return true;
    }
    // Return all control points that belong to tree patches (forests)
    private java.util.List<ControlPoint> forestControlPoints() {
        java.util.ArrayList<ControlPoint> out = new java.util.ArrayList<>();
        for (Terrain.TreePatch patch : treePatches) {
            int id = patch.getControlPointId();
            for (ControlPoint cp : controlPoints) {
                if (cp.getId() == id) { out.add(cp); break; }
            }
        }
        return out;
    }
    // Assign a unit to a logging camp
    public boolean assignLumberWorker(characters.Unit u, Building camp) {
        if (camp == null || camp.getType() != Building.Type.LOGGING_CAMP) return false;
        if (u == null) return false;
        if (u.getTeam() != camp.getTeam()) return false;

        u.setAssignedCamp(camp);
        u.setRole(characters.Unit.UnitRole.LUMBER);
        u.setCarryingLog(false);
        u.setTreeTarget(-1, -1);
        u.setStandTile(-1, -1);
        u.setChopTimer(0);
        u.setLumberState(characters.Unit.LumberState.SEEK_TREE);
        return true;
    }
    // Search all tree blocks, pick nearest by manhattan/chebyshev, then find an adjacent stand tile
    private static final int[][] ADJ8 = {
            {-1,0},{1,0},{0,-1},{0,1},{-1,-1},{-1,1},{1,-1},{1,1}
    };

    // returns int[]{top,left, standR, standC} or null if none
    private int[] findNearestTreeAndStand(characters.Unit u) {
        int ur = u.getRowRounded(), uc = u.getColRounded();

        Terrain.TreePatch bestPatch = null;
        Terrain.TreeBlock bestBlock = null;
        int bestDist = Integer.MAX_VALUE;
        int bestStandR = -1, bestStandC = -1;

        // iterate all tree blocks
        for (Terrain.TreePatch patch : treePatches) {
            for (Terrain.TreeBlock b : patch.trees()) {
                int top = b.r, left = b.c;
                // choose candidate stand tiles around 2x2
                int[][] ring = {
                        {top-1, left}, {top-1, left+1},
                        {top+2, left}, {top+2, left+1},
                        {top, left-1}, {top+1, left-1},
                        {top, left+2}, {top+1, left+2},
                        // corners optional
                        {top-1, left-1},{top-1,left+2},{top+2,left-1},{top+2,left+2}
                };
                // pick the first reachable (try nearest-first by sorting ring by distance)
                int localBestDist = Integer.MAX_VALUE;
                int localStandR = -1, localStandC = -1;

                for (int[] rc : ring) {
                    int rr = rc[0], cc = rc[1];
                    if (!inBoundsRC(rr, cc) || isBlocked(rr, cc, u)) continue;
                    int d = Math.abs(rr - ur) + Math.abs(cc - uc);
                    if (d < localBestDist) {
                        // also try A* to ensure reachable
                        var pth = findPathAStar(ur, uc, rr, cc, u);
                        if (pth != null && !pth.isEmpty()) {
                            localBestDist = d;
                            localStandR = rr; localStandC = cc;
                        }
                    }
                }
                if (localStandR != -1 && localBestDist < bestDist) {
                    bestDist = localBestDist;
                    bestBlock = b; bestPatch = patch;
                    bestStandR = localStandR; bestStandC = localStandC;
                }
            }
        }

        if (bestBlock == null) return null;
        return new int[]{bestBlock.r, bestBlock.c, bestStandR, bestStandC};
    }
    private void clearTreeBlock(int top, int left) {
        // clear mask
        for (int rr = top; rr < top+2; rr++)
            for (int cc = left; cc < left+2; cc++)
                if (inBoundsRC(rr, cc)) treeMask[rr][cc] = false;

        // remove from lists
        for (Terrain.TreePatch patch : treePatches) {
            java.util.Iterator<Terrain.TreeBlock> it = patch.trees().iterator();
            while (it.hasNext()) {
                Terrain.TreeBlock b = it.next();
                if (b.r == top && b.c == left) { it.remove(); return; }
            }
        }
        // if you pre-render terrain, call invalidateTerrain();
    }
    // any free tile around the camp rectangle
    private int[] findCampDropTile(Building camp, characters.Unit u) {
        int top = camp.getRow(), left = camp.getCol();
        int h = camp.getType().h, w = camp.getType().w;

        java.util.ArrayList<int[]> ring = new java.util.ArrayList<>();
        for (int c = left; c < left + w; c++) { ring.add(new int[]{top-1, c}); ring.add(new int[]{top+h, c}); }
        for (int r = top; r < top + h; r++) { ring.add(new int[]{r, left-1}); ring.add(new int[]{r, left+w}); }

        int ur = u.getRowRounded(), uc = u.getColRounded();
        int bestD = Integer.MAX_VALUE;
        int[] best = null;

        for (int[] rc : ring) {
            int rr = rc[0], cc = rc[1];
            if (!inBoundsRC(rr, cc) || isBlocked(rr, cc, u)) continue;
            int d = Math.abs(rr - ur) + Math.abs(cc - uc);
            var path = findPathAStar(ur, uc, rr, cc, u);
            if (path != null && d < bestD) { bestD = d; best = new int[]{rr, cc}; }
        }
        return best;
    }
    private double chopDuration(characters.Unit u) {
        int STR = u.getActor().getStrength();
        // base 3.5s, -0.1s per STR, clamped to [1.2, 5.0]
        return Math.max(1.2, Math.min(5.0, 3.5 - 0.1 * STR));
    }
    public void updateLumberJobs(double dt) {
        for (characters.Unit u : units) {
            if (u.getRole() != characters.Unit.UnitRole.LUMBER) continue;
            Building camp = u.getAssignedCamp();
            if (camp == null) { u.setRole(characters.Unit.UnitRole.NONE); continue; }

            switch (u.getLumberState()) {

                case SEEK_TREE -> {
                    int[] res = findNearestTreeAndStand(u);
                    if (res == null) { u.setLumberState(characters.Unit.LumberState.IDLE); break; }
                    u.setTreeTarget(res[0], res[1]);
                    u.setStandTile(res[2], res[3]);
                    var path = findPathAStar(u.getRowRounded(), u.getColRounded(), res[2], res[3], u);
                    if (path != null) { u.setPath(path); u.setLumberState(characters.Unit.LumberState.MOVE_TO_TREE); }
                    else { u.setLumberState(characters.Unit.LumberState.IDLE); }
                }

                case MOVE_TO_TREE -> {
                    // when not moving and adjacent to target stand tile, start chopping
                    if (!u.isMoving()) {
                        // ensure still near the same tree (it may have been cut already)
                        int top = u.getTreeTop(), left = u.getTreeLeft();
                        // if tree already gone, seek next
                        if (!isTreeAt(top, left)) { u.setLumberState(characters.Unit.LumberState.SEEK_TREE); break; }
                        // start chopping
                        u.setChopTimer(chopDuration(u));
                        u.setLumberState(characters.Unit.LumberState.CHOPPING);
                    }
                }

                case CHOPPING -> {
                    double t = u.getChopTimer() - dt;
                    if (t > 0) { u.setChopTimer(t); break; }

                    // finished: remove tree, pick up log
                    int top = u.getTreeTop(), left = u.getTreeLeft();
                    clearTreeBlock(top, left);
                    u.setCarryingLog(true);

                    // path to camp drop tile
                    int[] drop = findCampDropTile(camp, u);
                    if (drop == null) { u.setLumberState(characters.Unit.LumberState.IDLE); break; }
                    var path = findPathAStar(u.getRowRounded(), u.getColRounded(), drop[0], drop[1], u);
                    if (path != null) { u.setPath(path); u.setLumberState(characters.Unit.LumberState.MOVE_TO_CAMP); }
                    else { u.setLumberState(characters.Unit.LumberState.IDLE); }
                }

                case MOVE_TO_CAMP -> {
                    if (!u.isMoving()) {
                        // deposit log (you can increment a team wood counter here)
                        u.setCarryingLog(false);
                        // immediately seek next tree
                        u.setLumberState(characters.Unit.LumberState.SEEK_TREE);
                    }
                }

                case IDLE -> {
                    // try again occasionally if there might be trees later
                    // (no-op here; SEEK_TREE will be set by external trigger or we can retry every few seconds)
                }
            }
        }
    }
    // Chebyshev distance from a rectangle footprint (top..top+h-1, left..left+w-1)
// to a single tile (pr,pc). Returns 0 if overlapping, 1 if touching (edge or corner), etc.
    private int chebyshevDistFootprintToPoint(int top, int left, int h, int w, int pr, int pc) {
        int rMin = top, rMax = top + h - 1;
        int cMin = left, cMax = left + w - 1;

        int dr = 0;
        if (pr < rMin) dr = rMin - pr;
        else if (pr > rMax) dr = pr - rMax;

        int dc = 0;
        if (pc < cMin) dc = cMin - pc;
        else if (pc > cMax) dc = pc - cMax;

        return Math.max(dr, dc); // 0 = overlap, 1 = touch, 2+ = separated
    }
    // World.java
    public boolean canPlaceLoggingCamp(int topRow, int leftCol) {
        // basic blockers
        if (!canPlaceBuilding(Building.Type.LOGGING_CAMP, topRow, leftCol)) return false;

        // must touch at least one forest CP
        java.util.List<ControlPoint> cps = forestControlPoints();
        if (cps.isEmpty()) return false;

        for (ControlPoint cp : cps) {
            int d = chebyshevDistFootprintToPoint(
                    topRow, leftCol,
                    Building.Type.LOGGING_CAMP.h, Building.Type.LOGGING_CAMP.w,
                    cp.getRow(), cp.getCol()
            );
            if (d == 1) return true;           // touching (edge or diagonal)
            // (d == 0 would overlap CP tile; we can forbid that)
        }
        return false;
    }

    public boolean assignHunter(characters.Unit u, Building camp) {
        // Must click a Hunting Camp owned by the same team
        if (camp == null || camp.getType() != Building.Type.HUNTING_CAMP) return false;
        if (u == null || u.getTeam() != camp.getTeam()) return false;

        // If this unit was doing something else, clear that
        interruptForManualControl(u); // safe to call even if not in an AI loop

        // Put them into the hunter loop
        u.setRole(characters.Unit.UnitRole.HUNTER);
        u.setAssignedCamp(camp);
        u.setHunterState(characters.Unit.HunterState.INIT); // HunterAI will GET_BOW next
        // Keep hasBow as-is; if they already had one, AI will pass GET_BOW quickly
        if (u.getAI() == null || !(u.getAI() instanceof intelligence.HunterAI)) {
            u.setAI(new intelligence.HunterAI());
        }

        // Optional: stop any current manual path so they immediately start the loop
        u.setPath(java.util.Collections.emptyList());

        return true;
    }
    public void fireArrowShot(characters.Unit shooter, characters.Unit target) {
        // spawn from the shooter’s center toward the *current* target pos
        arrows.add(new Arrow(
                shooter.getX(), shooter.getY(),
                target.getX(),   target.getY(),
                12.0, // speed in tiles/sec (tweak)
                shooter.getId(),
                target.getId()
        ));
    }

    /** Called before issuing a manual player move (or any time you want to cancel job loops). */
    public void interruptForManualControl(characters.Unit u) {
        if (u == null) return;

        // Cancel Hunter loop
        if (u.getRole() == characters.Unit.UnitRole.HUNTER) {
            u.setRole(characters.Unit.UnitRole.NONE);
            u.setHunterState(characters.Unit.HunterState.IDLE);
            // Keep their bow; the player may still want to shoot manually.
        }

        // (If you add other job loops later, clear them here similarly.)

        // Stop any AI brain and current path so manual orders take over immediately
        u.setAI(null);
        u.setPath(java.util.Collections.emptyList());
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


    // 4) Time & arrow visual (stubs if you don’t have them yet)
    public double nowSeconds() { return System.nanoTime() / 1e9; }

    // Tunable default arrow speed (tiles per second)
    private static final double ARROW_SPEED_CPS = 14.0;

    // Advance & prune arrows each tick
    public void updateArrows(double dt) {
        java.util.Iterator<Arrow> it = arrows.iterator();
        while (it.hasNext()) {
            Arrow a = it.next();
            boolean arrived = a.step(dt);
            if (arrived) {
                resolveRangedHit(a);
                it.remove();
            }
        }
    }
    public void cleanupDead(){
        double now = nowSeconds();
        units.removeIf(u -> u.isDead() && (now - u.getDeathTimeSec()) >= 30.0);
    }
    private final java.util.Random rng = new java.util.Random();

    private characters.Unit unitById(int id){
        for (characters.Unit u : units) if (u.getId() == id) return u;
        return null;
    }

    private void resolveRangedHit(Arrow a){
        if (a.targetId == null) return; // purely visual arrow
        var shooter = unitById(a.shooterId);
        var target  = unitById(a.targetId);
        if (shooter == null || target == null || target.isDead()) return;

        double hitP = Math.max(0, Math.min(10, shooter.getAimSkill())) * 0.10;  // 0..1
        double dmgP = Math.max(0, Math.min(10, shooter.getPower()))    * 0.10;

        boolean hit = rng.nextDouble() < hitP;
        if (!hit) {
            System.out.println("[COMBAT] " + shooter.getActor().getName() + " MISSED " + target.getActor().getName());
            return;
        }

        boolean dmg = rng.nextDouble() < dmgP;
        if (!dmg) {
            System.out.println("[COMBAT] " + shooter.getActor().getName() + " grazed " + target.getActor().getName());
            return;
        }

        target.applyWound(this);
        if (target.isDead()) {
            System.out.println("[COMBAT] " + shooter.getActor().getName() + " KILLED " + target.getActor().getName());
        } else if (target.isWounded()) {
            System.out.println("[COMBAT] " + shooter.getActor().getName() + " WOUNDED " + target.getActor().getName()
                    + " (wounds left: " + target.getWounds() + ")");
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
            // CHANGED: skip dead
            if (archer.isDead()) continue;

            var a = archer.getActor();
            if (!a.hasShortBow()) continue;

            // CHANGED: let Hunter AI handle its own shots to avoid double-firing
            if (archer.getRole() == characters.Unit.UnitRole.HUNTER) continue;

            int ar = archer.getRowRounded(), ac = archer.getColRounded();

            // skip if engaged in melee
            boolean engaged = false;
            for (characters.Unit other : units) {
                if (other == archer) continue;
                if (other.isDead()) continue; // CHANGED
                if (!archer.isEnemyOf(other)) continue; // CHANGED: proper enemy check
                if (adjacent(ar, ac, other.getRowRounded(), other.getColRounded())) { engaged = true; break; }
            }
            if (engaged) continue;

            // pick nearest enemy in LOS, 3–10 tiles
            characters.Unit best = null;
            int bestDist = Integer.MAX_VALUE;
            for (characters.Unit enemy : units) {
                if (enemy == archer) continue;
                if (enemy.isDead()) continue;               // CHANGED
                if (!archer.isEnemyOf(enemy)) continue;     // CHANGED (avoids shooting NEUTRAL deer, etc.)

                int er = enemy.getRowRounded(), ec = enemy.getColRounded();
                int d  = chebyshev(ar, ac, er, ec);
                if (d < 3 || d > 10) continue;
                if (!hasLineOfSight(ar, ac, er, ec)) continue;

                if (d < bestDist) { best = enemy; bestDist = d; }
            }
            if (best == null) continue;

            // fire if cooldown is up
            if (nowSeconds >= archer.getNextRangedAttackAt()) {
                // Optional: face the target while shooting
                archer.setAimTarget(best.getX(), best.getY()); // will keep facing during/after move

                System.out.println(archer.getActor().getName() + " (" + archer.getTeam()
                        + ", Short Bow) shoots at "
                        + best.getActor().getName() + " (" + best.getTeam() + ")");

                // CHANGED: use the new resolver-carrying shot
                fireArrowShot(archer, best);

                // CHANGED: use per-unit cooldown (or keep your attackInterval if you prefer)
                double cd = (archer.getRangedCooldownSec() > 0) ? archer.getRangedCooldownSec() : 1.0;
                archer.setNextRangedAttackAt(nowSeconds + cd);
            }
        }

        // ----- Melee phase (unchanged logic, but skip dead units) -----
        java.util.Set<Long> seenThisFrame = new java.util.HashSet<>();

        for (int i = 0; i < units.size(); i++) {
            Unit ui = units.get(i);
            if (ui.isDead()) continue; // CHANGED
            int ri = ui.getRowRounded(), ci = ui.getColRounded();

            for (int j = i + 1; j < units.size(); j++) {
                Unit uj = units.get(j);
                if (uj.isDead()) continue; // CHANGED

                if (ui.getTeam() == uj.getTeam()) continue;          // enemies only (you can switch to isEnemyOf if desired)
                if (!isFootman(ui) || !isFootman(uj)) continue;      // only footmen for now

                int rj = uj.getRowRounded(), cj = uj.getColRounded();
                if (manhattan(ri, ci, rj, cj) != 1) continue;        // adjacent/contact

                long key = pairKey(ui.getId(), uj.getId());
                seenThisFrame.add(key);

                Engagement eg = engagements.computeIfAbsent(key, k -> new Engagement(ui.getId(), uj.getId()));
                if (!eg.initialized) {
                    long li = ui.getLastMoveNanos();
                    long lj = uj.getLastMoveNanos();
                    boolean iCharges = li > lj;

                    if (iCharges) {
                        eg.nextAttackA = nowSeconds;                       // ui first
                        eg.nextAttackB = nowSeconds + attackInterval(uj);  // uj after its interval
                    } else {
                        eg.nextAttackA = nowSeconds + attackInterval(ui);
                        eg.nextAttackB = nowSeconds;                       // uj first
                    }
                    eg.initialized = true;
                }

                // drive attacks (still printing for now)
                Unit a = (eg.aId == ui.getId()) ? ui : uj;
                Unit b = (eg.bId == ui.getId()) ? ui : uj;

                if (nowSeconds >= eg.nextAttackA) {
                    System.out.println(a.getActor().getName() + " (" + a.getTeam() + ", " + a.getActor().getEquipment()
                            + ") hits " + b.getActor().getName() + " (" + b.getTeam() + ")");
                    eg.nextAttackA += attackInterval(a);
                }

                if (nowSeconds >= eg.nextAttackB) {
                    System.out.println(b.getActor().getName() + " (" + b.getTeam() + ", " + b.getActor().getEquipment()
                            + ") hits " + a.getActor().getName() + " (" + a.getTeam() + ")");
                    eg.nextAttackB += attackInterval(b);
                }
            }
        }

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
    private boolean isBuildingAt(int r, int c) { return buildingMask[r][c]; }
    /** A tile is blocked if terrain blocks OR another unit stands there (except 'ignore'). */
    public boolean isBlocked(int r, int c, characters.Unit ignore) {
        if (!inBoundsRC(r, c) || !isWalkable(r, c)) return true;

        // O(1) terrain & static blockers first
        if (treeMask[r][c])     return true;
        if (buildingMask[r][c]) return true;

        // Optional: if you maintain a unitMask each tick,
        // you can do this and return early unless the ignore unit occupies (r,c):
        // if (unitMask[r][c]) {
        //     // If the only occupant is 'ignore', allow it; otherwise blocked.
        //     // For simplicity, fall through to the precise check below.
        // }

        // Precise check against unit footprints (expensive, keep last)
        for (characters.Unit other : units) {
            if (other == ignore) continue;
            int hr = other.getRowRounded(), hc = other.getColRounded();
            for (int[] cell : footprintCells(hr, hc, other.getFacing(), other.getLength())) {
                if (cell[0] == r && cell[1] == c) return true;
            }
        }
        return false;
    }
    // World.java
    public boolean isBlockedContinuous(double x, double y, characters.Unit moving) {
        // Convert the unit’s continuous anchor to grid coords
        int anchorR = (int)Math.floor(y);
        int anchorC = (int)Math.floor(x);

        // Get the unit’s footprint cells at that anchor
        int[][] cells = footprintCells(anchorR, anchorC, moving.getFacing(), moving.getLength()).toArray(new int[0][]);

        // Check each covered cell using your existing method, ignoring the mover
        for (int[] cell : cells) {
            int r = cell[0], c = cell[1];
            if (isBlocked(r, c, moving)) return true;
        }
        return false;
    }
    // World.java
    public boolean commandMove(Unit u, int destRow, int destCol) {
        // If the goal tile is hard-blocked for this unit, try nearby tiles (small ring search)
        java.util.List<Point> path = null;
        if (!isBlocked(destRow, destCol, u)) {
            path = findPath(u, destRow, destCol);
        }
        if (path == null || path.isEmpty()) {
            // try a few nearby candidates
            final int MAX_RADIUS = 4;
            outer:
            for (int r = 1; r <= MAX_RADIUS; r++) {
                for (int dc = -r; dc <= r; dc++) {
                    for (int dr = -r; dr <= r; dr++) {
                        int rr = destRow + dr, cc = destCol + dc;
                        if (!inBoundsRC(rr, cc) || isBlocked(rr, cc, u)) continue;
                        path = findPath(u, rr, cc);
                        if (path != null && !path.isEmpty()) break outer;
                    }
                }
            }
        }
        if (path == null || path.isEmpty()) return false;
        u.setPath(path);
        return true;
    }
    // World.java
    public java.util.List<Point> findPath(Unit u, int destRow, int destCol) {
        int sr = (int)Math.floor(u.getY());
        int sc = (int)Math.floor(u.getX());
        return findPathAStar(sr, sc, destRow, destCol, u); // pass mover as 'ignore'
    }

    /** Generate several tree patches. Each patch grows around a seed,
     * placing 2×2 tree blocks snapped to even cells with gaps (“corridors”).
     * corridorsEvery: keep every Nth row/col free of trees (>=3 recommended).
     * attemptsPerPatch: how many random candidates to try for a patch.
     */
    // Stamp a 2x2 block into the mask:
    private void stampTreeBlock(int top, int left) {
        for (int rr = top; rr < top + 2; rr++) {
            for (int cc = left; cc < left + 2; cc++) {
                if (rr >= 0 && rr < height && cc >= 0 && cc < width) {
                    treeMask[rr][cc] = true;
                }
            }
        }
    }
    public void generateWoods(int numPatches, int attemptsPerPatch, int corridorsEvery, long seed) {
        java.util.Random rng = new java.util.Random(seed);

        for (int p = 0; p < numPatches; p++) {
            // 1) pick a rough center
            int centerR = rng.nextInt(Math.max(2, height - 2));
            int centerC = rng.nextInt(Math.max(2, width - 2));

            // 2) create a control point roughly at center; woods income example: 1.0/sec
            int cpId = treePatches.size() + 1000; // id offset for terrain CPs
            int cpRow = clamp(centerR, 0, height - 1);
            int cpCol = clamp(centerC, 0, width - 1);
            ControlPoint cp = new ControlPoint(cpId, cpRow, cpCol, /*radius*/5, /*income*/1.0);
            addControlPoint(cp);

            Terrain.TreePatch patch = new Terrain.TreePatch(cpId);

            // 3) try to place several 2×2 blocks near center
            int placed = 0;
            for (int t = 0; t < attemptsPerPatch; t++) {
                // random offset around center in a diamond-ish range
                int dr = rng.nextInt(11) - 5; // -5..+5
                int dc = rng.nextInt(11) - 5;
                int top = clamp(centerR + dr, 0, height - 2);
                int left = clamp(centerC + dc, 0, width - 2);

                // snap to even cells so blocks align and corridors stay clear
                top  = (top  / 2) * 2;
                left = (left / 2) * 2;

                // enforce “corridor” rows/cols (keep them empty)
                if (corridorsEvery >= 3) {
                    if (top % corridorsEvery == 0 || (top + 1) % corridorsEvery == 0) continue;
                    if (left % corridorsEvery == 0 || (left + 1) % corridorsEvery == 0) continue;
                }

                // check 2×2 fits and doesn’t overlap trees/buildings
                boolean ok = true;
                for (int rr = top; rr < top + 2 && ok; rr++) {
                    for (int cc = left; cc < left + 2; cc++) {
                        if (!inBoundsRC(rr, cc)) { ok = false; break; }
                        if (isTreeAt(rr, cc))    { ok = false; break; }          // O(1) mask check
                        if (buildingAt(rr, cc) != null) { ok = false; break; }
                    }
                }
// keep at least 1-tile gap to other trees (so paths weave)
                if (ok) {
                    for (int rr = top - 1; rr < top + 3 && ok; rr++) {
                        for (int cc = left - 1; cc < left + 3; cc++) {
                            if (!inBoundsRC(rr, cc)) continue;
                            if (isTreeAt(rr, cc)) { ok = false; break; }         // mask again
                        }
                    }
                }
                if (!ok) continue;

// accept this 2×2 block
                patch.trees().add(new Terrain.TreeBlock(top, left));
                stampTreeBlock(top, left);   // <<< update mask so future checks see it
                placed++;
            }

            if (placed > 0) {
                treePatches.add(patch);
            } else {
                // no trees placed: remove CP we added
                controlPoints.remove(cp);
            }
        }
    }
    public void trySpawnArrivalsForHouses() {
        for (Building b : buildings) {
            if (b.getType() != Building.Type.HOUSE) continue;
            if (b.isArrivalsSpawned()) continue;

            characters.Team team = b.getTeam();
            boolean hasFarm = !buildingsOf(team, Building.Type.FARM).isEmpty();
            boolean hasBarn = !buildingsOf(team, Building.Type.BARN).isEmpty();

            if (hasFarm && hasBarn) {
                // center tile of house footprint
                int targetR = b.getRow() + 1;
                int targetC = b.getCol() + 1;
                spawnArrivalsTo(targetR, targetC, team, 4);
                b.setArrivalsSpawned(true);
            }
        }
    }
    private void spawnArrivalsTo(int targetR, int targetC, characters.Team team, int count) {
        int[][] edges = {
                {0,              targetC},
                {height - 1,     targetC},
                {targetR,        0},
                {targetR,        width - 1}
        };
        int spawned = 0;
        for (int i = 0; i < edges.length && spawned < count; i++) {
            int sr = clamp(edges[i][0], 0, height - 1);
            int sc = clamp(edges[i][1], 0, width  - 1);

            // wiggle if start is blocked
            int wiggle = 0;
            while (wiggle < Math.max(height, width)) {
                int[][] tries = {{sr, sc + wiggle}, {sr, sc - wiggle}};
                for (int[] t : tries) {
                    int rr = t[0], cc = t[1];
                    if (inBoundsRC(rr, cc) && !isBlocked(rr, cc, null)) {
                        characters.Human h = characters.Human.basicFootman();
                        h.setName("Arrival");
                        characters.Unit u = spawnActor(h, rr, cc);
                        u.setTeam(team);
                        var path = findPathAStar(rr, cc, targetR, targetC, u);
                        if (path != null && !path.isEmpty()) u.setPath(path);
                        spawned++;
                        break;
                    }
                }
                if (spawned > i) break; // placed this arrival
                wiggle++;
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
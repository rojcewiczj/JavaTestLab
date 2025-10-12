package world;
import characters.Actor;
import characters.Unit;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

import static world.World.facingOffset;

public class WorldPanel extends JPanel {

    private final World world;
    private final JLabel statusLabel;
    private int cellSize = 28; // pixels per cell
    private int currentLayer = 1; // default: unit layer

    // drag-select state
    private Point dragStartPixel = null;
    private Point dragEndPixel = null;
    // World-space drag rectangle (pixels in world coords)
    private java.awt.geom.Point2D.Double dragStartWorld = null;
    private java.awt.geom.Point2D.Double dragEndWorld   = null;

    // animation timing
    private long lastNanos = System.nanoTime();
    // ... existing fields ...
    private JButton buildButton;

    private enum BuildMode { NONE, PICK, WALL_PAINT, HOUSE_PLACE, FARM_PLACE, BARN_PLACE, LOGGING_CAMP_PLACE, HUNTING_CAMP_PLACE }
    private BuildMode buildMode = BuildMode.NONE;

    // for wall painting
    // at the top of WorldPanel
    private final java.util.List<Point> paintPreview = new java.util.ArrayList<>();
    private boolean painting = false;
    // --- Camera / viewport ---
    private int viewportW, viewportH;      // pixels of the on-screen viewport
    private double camX = 0, camY = 0;     // camera top-left in pixels (world coords)
    private double camVX = 0, camVY = 0;   // current camera velocity (px/s)
    private double targetVX = 0, targetVY = 0; // desired cam velocity from mouse edges

    // Edge-pan tuning
    private int edgeBand = 28;             // px from each edge to start panning
    private double panMaxSpeed = 900;      // px/s maximum panning speed
    private double panAccel = 2800;        // px/s^2 acceleration to target speed
    // keep these fields in WorldPanel
    private Point lastPaintedCell = null; // to avoid duplicates while painting
    // --- FPS counter ---
    private int frames = 0;
    private long lastFpsTime = System.nanoTime();
    private int currentFps = 0;
    private double emaTickMs = 0, emaPaintMs = 0;
    private static final double EMA = 0.1;
    // Camera / viewport
    private void updatePanFromMouse(int mx, int my) {
        // Horizontal target vel
        if (mx < edgeBand)
            targetVX = -panMaxSpeed * (1.0 - (double) mx / edgeBand);
        else if (mx > viewportW - edgeBand)
            targetVX = panMaxSpeed * (1.0 - (double) (viewportW - mx) / edgeBand);
        else
            targetVX = 0;

        // Vertical target vel
        if (my < edgeBand)
            targetVY = -panMaxSpeed * (1.0 - (double) my / edgeBand);
        else if (my > viewportH - edgeBand)
            targetVY = panMaxSpeed * (1.0 - (double) (viewportH - my) / edgeBand);
        else
            targetVY = 0;
    }
    // Bresenham-style interpolation between two grid cells
    private void addLineCellsToPreview(Point a, Point b) {
        int x0 = a.x, y0 = a.y, x1 = b.x, y1 = b.y;
        int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
        int err = dx + dy, e2;
        int x = x0, y = y0;
        while (true) {
            Point p = new Point(x, y);
            if (inBounds(y, x) && (paintPreview.isEmpty() || !paintPreview.get(paintPreview.size()-1).equals(p))) {
                paintPreview.add(p);
            }
            if (x == x1 && y == y1) break;
            e2 = 2 * err;
            if (e2 >= dy) { err += dy; x += sx; }
            if (e2 <= dx) { err += dx; y += sy; }
        }
    }
    private int screenToWorldCol(int mx) { return (int) ((mx + camX) / cellSize); }
    private int screenToWorldRow(int my) { return (int) ((my + camY) / cellSize); }
    public WorldPanel(World world, JLabel statusLabel, JButton buildButton,
                      int viewportW, int viewportH) {
        this.world = world;
        this.statusLabel = statusLabel;
        this.buildButton = buildButton;
        this.viewportW = viewportW;
        this.viewportH = viewportH;

        setPreferredSize(new Dimension(viewportW, viewportH));
        setBackground(Color.WHITE);
        setFocusable(true);
        // Build button opens a tiny palette (only "Wall" for now)
        JPopupMenu buildMenu = new JPopupMenu();

        JMenuItem wallItem = new JMenuItem("Wall");
        wallItem.addActionListener(e -> {
            buildMode = BuildMode.WALL_PAINT;
            paintPreview.clear();
            statusLabel.setText("Build: Wall — right-drag to paint, release to commit. ESC to cancel.");
        });
        buildMenu.add(wallItem);

        JMenuItem houseItem = new JMenuItem("House (3x3)");
        houseItem.addActionListener(e -> {
            buildMode = BuildMode.HOUSE_PLACE;
            statusLabel.setText("Build: House — right-click to place (3×3).");
        });
        buildMenu.add(houseItem);

        JMenuItem farmItem = new JMenuItem("Farm (4x8)");
        farmItem.addActionListener(e -> {
            buildMode = BuildMode.FARM_PLACE;
            statusLabel.setText("Build: Farm — right-click top-left to place (4×8).");
        });
        buildMenu.add(farmItem);

        JMenuItem barnItem = new JMenuItem("Barn (3x4)");
        barnItem.addActionListener(e -> {
            buildMode = BuildMode.BARN_PLACE;
            statusLabel.setText("Build: Barn — right-click top-left to place (3×4).");
        });
        buildMenu.add(barnItem);
        JMenuItem loggingItem = new JMenuItem("Logging Camp (3x4)");
        loggingItem.addActionListener(e -> {
            buildMode = BuildMode.LOGGING_CAMP_PLACE;
            statusLabel.setText("Build: Logging Camp — right-click top-left to place (3×4), must touch a forest CP.");
        });
        buildMenu.add(loggingItem);

        JMenuItem miHuntingCamp = new JMenuItem("Hunting Camp (3×4) — U");
        miHuntingCamp.addActionListener(e -> {
            buildMode = BuildMode.HUNTING_CAMP_PLACE;
            statusLabel.setText("Place Hunting Camp (3×4): right-click to place. Select a builder first.");
        });
        buildMenu.add(miHuntingCamp);
        // Show the build palette when clicking the button
        buildButton.addActionListener(e -> {
            if (!buildButton.isEnabled()) return; // safety
            buildMode = BuildMode.PICK;
            // show the popup aligned under the button
            buildMenu.show(buildButton, 0, buildButton.getHeight());
        });


        // ESC cancels build mode
        setFocusable(true);
        registerKeyboardAction(_e -> {
            buildMode = BuildMode.NONE;
            paintPreview.clear();
        }, KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_FOCUSED);

        // Mouse
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();

                final int mx = e.getX(), my = e.getY();
                final int col = (int) ((mx + camX) / cellSize);
                final int row = (int) ((my + camY) / cellSize);
                if (!inBounds(row, col)) return;

                if (SwingUtilities.isLeftMouseButton(e)) {
                    if (buildMode == BuildMode.NONE) {
                        double wx = mx + camX, wy = my + camY;
                        dragStartWorld = new java.awt.geom.Point2D.Double(wx, wy);
                        dragEndWorld   = new java.awt.geom.Point2D.Double(wx, wy);
                    }
                    updateStatus(row, col);
                    return;
                }

                if (!SwingUtilities.isRightMouseButton(e)) {
                    updateStatus(row, col);
                    return;
                }

                boolean consumed = false; // did this right-click do something already?
                characters.Team team;
                int top = row, left = col;

                // ===== BUILD / PAINT MODES =====
                switch (buildMode) {
                    case WALL_PAINT -> {
                        painting = true;
                        lastPaintedCell = null;
                        paintPreview.clear();
                        paintPreview.add(new Point(col, row));
                        consumed = true;
                    }
                    case HOUSE_PLACE -> {
                        team = selectedBuilderTeamOrNull();
                        if (team == null) { statusLabel.setText("No builder selected."); buildMode = BuildMode.NONE; }
                        else {
                            top = row - 1; left = col - 1;
                            if (world.addHouse(top, left, team)) { statusLabel.setText("House built."); buildMode = BuildMode.NONE; consumed = true; }
                            else { statusLabel.setText("Can't place house here."); buildMode = BuildMode.NONE; }
                        }
                    }
                    case FARM_PLACE -> {
                        team = selectedBuilderTeamOrNull();
                        if (team == null) { statusLabel.setText("No builder selected."); buildMode = BuildMode.NONE; }
                        else {
                            if (world.addFarm(top, left, team)) { statusLabel.setText("Farm built."); buildMode = BuildMode.NONE; consumed = true; }
                            else { statusLabel.setText("Can't place farm here."); buildMode = BuildMode.NONE; }
                        }
                    }
                    case BARN_PLACE -> {
                        team = selectedBuilderTeamOrNull();
                        if (team == null) { statusLabel.setText("No builder selected."); buildMode = BuildMode.NONE; }
                        else {
                            if (world.addBarn(top, left, team)) { statusLabel.setText("Barn built."); buildMode = BuildMode.NONE; consumed = true; }
                            else { statusLabel.setText("Can't place barn here."); buildMode = BuildMode.NONE; }
                        }
                    }
                    case LOGGING_CAMP_PLACE -> {
                        team = selectedBuilderTeamOrNull();
                        if (team == null) { statusLabel.setText("No builder selected."); buildMode = BuildMode.NONE; }
                        else {
                            if (world.addLoggingCamp(top, left, team)) { statusLabel.setText("Logging Camp built."); buildMode = BuildMode.NONE; consumed = true; }
                            else { statusLabel.setText("Can't place Logging Camp here (must touch a forest CP and avoid blockers)."); buildMode = BuildMode.NONE; }
                        }
                    }
                    case HUNTING_CAMP_PLACE -> {
                        team = selectedBuilderTeamOrNull();
                        if (team == null) { statusLabel.setText("No builder selected."); buildMode = BuildMode.NONE; }
                        else {
                            if (world.addHuntingCamp(top, left, team)) {
                                statusLabel.setText("Hunting Camp built.");
                                buildMode = BuildMode.NONE;
                                consumed = true;
                            } else {
                                statusLabel.setText("Can't place Hunting Camp here.");
                                buildMode = BuildMode.NONE;
                            }
                        }
                    }
                    case NONE -> { /* do nothing here; fall through to interaction/move */ }
                }

                // ===== IF NOT CONSUMED, DO INTERACTIONS/MOVE =====
                if (!consumed) {
                    characters.Unit selected = null;
                    for (characters.Unit u : world.getUnits()) { if (u.isSelected()) { selected = u; break; } }

                    // try assign lumber worker (only if clicked a camp)
                    world.Building b = world.buildingAt(row, col);
                    if (selected != null && b != null
                            && b.getType() == Building.Type.LOGGING_CAMP
                            && selected.getTeam() == b.getTeam()) {
                        if (world.assignLumberWorker(selected, b)) {
                            statusLabel.setText("Assigned " + selected.getActor().getName() + " as lumber worker.");
                            updateStatus(row, col);
                            return;
                        }
                    }
                    // try assign hunter (only if clicked a hunting camp)
                    if (selected != null && b != null
                            && b.getType() == Building.Type.HUNTING_CAMP
                            && selected.getTeam() == b.getTeam()) {
                        if (world.assignHunter(selected, b)) {
                            statusLabel.setText("Assigned " + selected.getActor().getName() + " as hunter.");
                            updateStatus(row, col);
                            return; // consume click
                        }
                    }

                    // try mount
                    characters.Unit clicked = world.getUnitAt(row, col);
                    if (selected != null && clicked != null
                            && clicked.getTeam() == selected.getTeam()
                            && clicked.getActor() instanceof characters.Horse) {
                        if (world.mount(selected, clicked)) {
                            statusLabel.setText("Mounted: " + selected.getActor().getName());
                            updateStatus(row, col);
                            return;
                        }
                    }
                    for (characters.Unit u : world.getUnits()) {
                        if (!u.isSelected()) continue;
                        world.interruptForManualControl(u);
                    }
                    // otherwise: normal move
                    moveSelectedToFanOut(row, col);
                }

                updateStatus(row, col);
            }
            // In your panel class (the one with mousePressed)
            private characters.Team selectedBuilderTeamOrNull() {
                characters.Team team = null;
                for (characters.Unit u : world.getUnits()) {
                    if (!u.isSelected()) continue;
                    if (team == null) team = u.getTeam();
                    // If you want to forbid mixed-team selections:
                    // else if (team != u.getTeam()) return null;
                    return team; // first selected unit's team
                }
                return null; // nothing selected
            }
            @Override public void mouseReleased(MouseEvent e) {
                // camera-aware mouse (only needed for right-click build branches)
                int mx = e.getX(), my = e.getY();

                // --- LEFT: apply selection from WORLD drag, then clear ---
                if (SwingUtilities.isLeftMouseButton(e)
                        && dragStartWorld != null
                        && buildMode == BuildMode.NONE) {
                    applySelectionFromDragWorld(e.isShiftDown() || e.isControlDown());
                    dragStartWorld = null;
                    dragEndWorld   = null;
                    // remove rubber-band immediately
                    return;
                }

                // --- RIGHT: commit walls if painting ---
                if (SwingUtilities.isRightMouseButton(e) && painting && buildMode == BuildMode.WALL_PAINT) {
                    java.util.HashSet<Long> seen = new java.util.HashSet<>();
                    for (Point p : paintPreview) {
                        int r = Math.max(0, Math.min(world.getHeight()-1, p.y));
                        int c = Math.max(0, Math.min(world.getWidth()-1,  p.x));
                        long key = (((long) r) << 32) ^ (c & 0xffffffffL);
                        if (seen.add(key)) world.setWall(r, c, true);
                    }
                    painting = false;
                    lastPaintedCell = null;
                    paintPreview.clear();
                    statusLabel.setText("Walls built.");
                    return;
                }

            }
        });

        addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                int mx = e.getX(), my = e.getY();
                updatePanFromMouse(mx, my);

                int col = screenToWorldCol(mx);
                int row = screenToWorldRow(my);
                if (inBounds(row, col)) updateStatus(row, col);
                else statusLabel.setText("—");
            }

            @Override public void mouseDragged(MouseEvent e) {
                int mx = e.getX(), my = e.getY();
                updatePanFromMouse(mx, my); // keep edge-pan while dragging, too

                // Rubber-band selection (store pixel coords; apply using cam-adjusted tiles elsewhere)
                if (dragStartWorld != null && buildMode == BuildMode.NONE) {
                    dragEndWorld = new java.awt.geom.Point2D.Double(mx + camX, my + camY);
                }


                // Wall painting uses world coords
                if (painting && buildMode == BuildMode.WALL_PAINT) {
                    int col = screenToWorldCol(mx);
                    int row = screenToWorldRow(my);
                    if (inBounds(row, col)) {
                        Point curr = new Point(col, row);
                        // Option A: only add if changed (prevents duplicates)
                        if (!curr.equals(lastPaintedCell)) {
                            // Option B (better): interpolate line between last and curr to avoid gaps
                            if (lastPaintedCell != null) {
                                addLineCellsToPreview(lastPaintedCell, curr); // see helper below
                            } else {
                                paintPreview.add(curr);
                            }
                            lastPaintedCell = curr;
                        }
                    }
                }
            }
        });
        java.util.concurrent.ScheduledExecutorService exec =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        lastNanos = System.nanoTime();
        exec.scheduleAtFixedRate(() -> {
            long now = System.nanoTime();
            double dt = Math.min(0.05, (now - lastNanos) / 1_000_000_000.0);
            lastNanos = now;

            // ===== FRAME START =====
            // 0) Snapshot current occupancy so AI & movement see a consistent world this tick.
            world.rebuildUnitMask();

            // 1) Decide first (no movement here)
            for (Unit u : world.getUnits()) {
                u.tickAI(world, dt);
            }
            world.beginMoveReservations();
            // 2) Then move everyone (movement uses isBlockedContinuous & the snapshot above)
            for (Unit u : world.getUnits()) {
                u.update(world, dt);
            }

            // 3) Despawn/cleanup before we publish the new mask
            world.cleanupDead();

            // 4) Publish new occupancy for the NEXT frame (and for post-sim systems below)
            world.rebuildUnitMask();

            // 5) Post-sim systems that want fresh positions/visibility
            world.syncUnitsToLayer();
            world.updateWolfPackSightings();
            world.payIncome(dt);
            // world.processCombat(now / 1e9); // if you re-enable, keep it AFTER movement
            world.updateArrows(dt);
            world.trySpawnArrivalsForHouses();
            world.updateLumberJobs(dt);
            world.computeVisibility();

            // Camera integration
            camVX = approach(camVX, targetVX, panAccel * dt);
            camVY = approach(camVY, targetVY, panAccel * dt);
            camX = clamp(camX + camVX * dt, 0, Math.max(0, world.getWidth() * cellSize - viewportW));
            camY = clamp(camY + camVY * dt, 0, Math.max(0, world.getHeight() * cellSize - viewportH));

            // request a repaint safely on the EDT
            SwingUtilities.invokeLater(this::repaint);
        }, 0, 16, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    // inside WorldPanel.java
    private Color teamFill(characters.Unit u) {
        switch (u.getTeam()) {
            case RED:  return new Color(220, 70, 70);    // warm red
            case BLUE: return new Color(70, 120, 220);
            case WOLF: return new Color(90,90,90,220);// cool blue
            default:   return new Color(80, 80, 80);     // neutral/gray
        }
    }
    private void updateBuildButtonEnabled() {
        boolean enable = false;
        for (characters.Unit u : world.getUnits()) {
            if (u.isSelected() && u.getActor().canBuildWalls()) {
                enable = true;
                break;
            }
        }
        buildButton.setEnabled(enable);

        // If no builder is selected, cancel build mode
        if (!enable && buildMode == BuildMode.WALL_PAINT) {
            buildMode = BuildMode.NONE;
            paintPreview.clear();
        }
        System.out.println("Selected builder? " + enable);
    }

    // --- helper: an arrow is visible if any sampled point along its shaft is in a visible tile
    private boolean isArrowCurrentlyVisible(world.World world, world.Arrow a) {
        // sample from tail (hx,hy) to head (px,py)
        double sx = a.getX(); // world coords (cells) at arrow center
        double sy = a.getY();

        // direction unit vector (you already compute dx,dy,len below)
        double dx = (a.tx - a.sx);
        double dy = (a.ty - a.sy);
        double len = Math.hypot(dx, dy);
        if (len < 1e-6) {
            int r = (int)Math.floor(sy), c = (int)Math.floor(sx);
            return world.isVisible(r, c);
        }
        double ux = dx / len, uy = dy / len;

        // pick a modest physical length in cells for how much of the “shaft” we render
        double body = Math.max(0.5, 0.5); // ~half a tile

        // head = (sx,sy), tail = head - body * u
        double hx = sx - ux * body;
        double hy = sy - uy * body;

        // sample a few points between tail and head
        final int SAMPLES = 4;
        for (int i = 0; i <= SAMPLES; i++) {
            double t = (double)i / SAMPLES;
            double x = hx * (1 - t) + sx * t;
            double y = hy * (1 - t) + sy * t;
            int r = (int)Math.floor(y), c = (int)Math.floor(x);
            if (world.isVisible(r, c)) return true;
        }
        return false;
    }
    private static double approach(double cur, double tgt, double maxDelta) {
        double d = tgt - cur;
        return (Math.abs(d) <= maxDelta) ? tgt : cur + Math.copySign(maxDelta, d);
    }
    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private boolean inBounds(int r, int c) {
        return r >= 0 && r < world.getHeight() && c >= 0 && c < world.getWidth();
    }

    // (kept for single-click selection behavior if you want it)
    private void selectUnitAt(int row, int col) {
        boolean any = false;
        for (Unit u : world.getUnits()) {
            if (Math.round(u.getY()) == row && Math.round(u.getX()) == col) {
                u.setSelected(true);
                any = true;
            } else {
                u.setSelected(false);
            }
        }
        if (!any) {
            for (Unit u : world.getUnits()) u.setSelected(false);
        }

    }

    // (kept for single-target move; not used by fan-out, but harmless to keep)
    private void moveSelectedTo(int row, int col) {
        for (Unit u : world.getUnits()) {
            if (!u.isSelected()) continue;

            int sr = (int) Math.round(u.getY());
            int sc = (int) Math.round(u.getX());

            java.util.List<java.awt.Point> path = world.findPathAStar(sr, sc, row, col, u);
            if (path != null && !path.isEmpty()) {
                if (path.size() >= 2 && path.get(0).x == sc && path.get(0).y == sr) {
                    path.remove(0);
                }
                u.setPath(path);
            } else {
                statusLabel.setText(String.format("No path from (%d,%d) to (%d,%d)", sr, sc, row, col));
            }
        }
    }

    private void updateStatus(int r, int c) {
        int ground = world.getCell(r, c, World.LAYER_GROUND);
        int unitVal = world.getCell(r, c, World.LAYER_UNIT);

        StringBuilder info = new StringBuilder();
        info.append(String.format("Row: %d, Col: %d | Ground: %d | Unit: %s",
                r, c, ground, (unitVal == World.UNIT_NONE ? "empty" : String.valueOf(unitVal))));

        // If a unit entity is actually at this tile, show its type/name and some stats
        for (Unit u : world.getUnits()) {
            if (Math.round(u.getY()) == r && Math.round(u.getX()) == c) {
                Actor a = u.getActor();
                info.append(String.format(" | %s %s (mov=%.1f, STR=%d, AGI=%d, INT=%d, VIT=%d)",
                        a.getType(), a.getName(), a.getMovement(),
                        a.getStrength(), a.getAgility(), a.getIntellect(), a.getVitality()));
                break;
            }
            world.Building bb = world.buildingAt(r, c);
            if (bb != null) {
                info.append(" | Building: ").append(bb.getType()).append(" (").append(bb.getTeam()).append(")");
            }
        }
        var rm = world.getResources();
        String totals = String.format(" | RED=%d  BLUE=%d", rm.getWhole(characters.Team.RED), rm.getWhole(characters.Team.BLUE));
        statusLabel.setText(statusLabel.getText() + totals);
        statusLabel.setText(info.toString());
        world.Building b = world.buildingAt(r, c);
        if (b != null) {
            info.append(" | Building: HOUSE (" + b.getTeam() + ")");
        }
    }
    // helper: fraction of 4 sub-samples that are currently visible
    private double subtileCoverage(world.World w, int r, int c) {
        // sample the centers of the 4 subcells: (±0.25, ±0.25) around tile center
        int visCount = 0;
        // map sub-samples to neighbor tiles
        int[][] offs = { {-1,-1}, {-1,0}, {0,-1}, {0,0} };
        for (int i=0;i<4;i++) {
            int rr = r + offs[i][0];
            int cc = c + offs[i][1];
            if (w.inBoundsRC(rr, cc) && w.isVisible(rr, cc)) visCount++;
        }
        return visCount / 4.0;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        long p0 = System.nanoTime();
        Graphics2D g2 = (Graphics2D) g.create();

        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        Font font = getFont().deriveFont(Font.BOLD, Math.max(10f, cellSize * 0.5f));
        g2.setFont(font);
        FontMetrics fm = g2.getFontMetrics();

        // --- CAMERA: shift world by camera offset ---
        g2.translate(-camX, -camY);

        // --- Visible tile window (world coords) ---
        int firstCol = Math.max(0, (int)Math.floor(camX / cellSize));
        int firstRow = Math.max(0, (int)Math.floor(camY / cellSize));
        int lastCol  = Math.min(world.getWidth()  - 1, (int)Math.ceil((camX + viewportW) / cellSize));
        int lastRow  = Math.min(world.getHeight() - 1, (int)Math.ceil((camY + viewportH) / cellSize));

        // --- Ground + grid: draw ONLY visible tiles ---
        for (int r = firstRow; r <= lastRow; r++) {
            for (int c = firstCol; c <= lastCol; c++) {
                int x = c * cellSize, y = r * cellSize;

                boolean vis = world.isVisible(r, c);
                boolean exp = world.isExplored(r, c);

                // 1) Never-seen: draw solid black and skip everything else
                if (!exp) {
                    g2.setColor(Color.BLACK);
                    g2.fillRect(x, y, cellSize, cellSize);
                    continue;
                }

                int ground = world.getCell(r, c, World.LAYER_GROUND);
                g2.setColor(ground == 0 ? new Color(230,245,230) : new Color(200,200,200));
                g2.fillRect(x, y, cellSize, cellSize);

                g2.setColor(new Color(180,180,180));
                g2.drawRect(x, y, cellSize, cellSize);
            }
        }


        // --- Control Points overlay (moved OUTSIDE the grid loops) ---
        for (world.ControlPoint cp : world.getControlPoints()) {
            int x = cp.getCol() * cellSize;
            int y = cp.getRow() * cellSize;

            // owner color ring
            characters.Team owner = world.evaluateOwner(cp);
            Color ring = (owner == characters.Team.RED) ? new Color(220,60,60)
                    : (owner == characters.Team.BLUE) ? new Color(60,120,220)
                    : new Color(160,160,160);

            g2.setStroke(new BasicStroke(2f));
            g2.setColor(ring);
            g2.drawOval(x + 2, y + 2, cellSize - 4, cellSize - 4);

            // id badge
            String idStr = String.valueOf(cp.getId());
            int tx = x + (cellSize - fm.stringWidth(idStr)) / 2;
            int ty = y + (cellSize + fm.getAscent()) / 2 - 2;
            g2.setColor(Color.BLACK);
            g2.drawString(idStr, tx, ty);

            // optional radius ring
            g2.setColor(new Color(ring.getRed(), ring.getGreen(), ring.getBlue(), 60));
            int diam = (cp.getRadius() * 2 + 1) * cellSize;
            int cx = x + cellSize/2 - diam/2;
            int cy = y + cellSize/2 - diam/2;
            g2.drawOval(cx, cy, diam, diam);
        }

        // --- Smooth unit rendering using continuous position ---
        for (characters.Unit u : world.getUnits()) {
            // hide units in fog
            if (!isUnitCurrentlyVisible(world, u)) continue;
            if (u.getLength() == 1) {
                // center in pixels for a 1x1
                double cxp = (u.getX() + 0.5) * cellSize;
                double cyp = (u.getY() + 0.5) * cellSize;
                double px = u.getX() * cellSize;
                double py = u.getY() * cellSize;

                int size = Math.max(10, cellSize - 6);
                int cx = (int) Math.round(px + (cellSize - size) / 2.0);
                int cy = (int) Math.round(py + (cellSize - size) / 2.0);

                // fill by team, dim slightly if not selected
                Color fill = teamFill(u);
                if (!u.isSelected()) {
                    fill = new Color(fill.getRed(), fill.getGreen(), fill.getBlue(), 220); // slight transparency if you like
                }
                g2.setColor(fill);
                g2.fillOval(cx, cy, size, size);

                // crisp outline: brighter when selected
                if (u.isSelected()) {
                    g2.setStroke(new BasicStroke(2.2f));
                    g2.setColor(new Color(255, 255, 255));
                    g2.drawOval(cx, cy, size, size);
                    g2.setStroke(new BasicStroke(1.5f));
                    g2.setColor(new Color(90, 160, 255)); // selection glow
                    g2.drawOval(cx - 2, cy - 2, size + 4, size + 4);
                } else {
                    g2.setStroke(new BasicStroke(1.2f));
                    g2.setColor(new Color(30, 30, 30, 180)); // subtle rim
                    g2.drawOval(cx, cy, size, size);
                }
                // carried log marker for 1x1 lumber workers (small brown chip)
                if (u.getRole() == Unit.UnitRole.LUMBER && u.isCarryingLog()) {
                    int lw = Math.max(4, cellSize / 5);
                    int lh = Math.max(3, cellSize / 8);
                    int lx = (int)Math.round(cxp) + cellSize/6 - lw/2;
                    int ly = (int)Math.round(cyp) - cellSize/6 - lh/2;
                    g2.setColor(new Color(139,101,67));
                    g2.fillRoundRect(lx, ly, lw, lh, lh, lh);
                    g2.setColor(new Color(80,55,30,180));
                    g2.drawRoundRect(lx, ly, lw, lh, lh, lh);
                }

            } else {
                // centers (continuous) in pixels
                double hx = (u.getX() + 0.5) * cellSize;
                double hy = (u.getY() + 0.5) * cellSize;

// unit vector along body from tail -> head using continuous orientation
                double ux = Math.cos(u.getOrientRad());
                double uy = Math.sin(u.getOrientRad());

// the distance between tail & head centers is exactly one tile
                double span = cellSize;

// compute tail center one tile behind the head
                double tx = hx - ux * span;
                double ty = hy - uy * span;

// perpendicular (for thickness)
                double pxn = -uy, pyn = ux;

                int thick = Math.max(10, cellSize - 8);
                double halfW = thick / 2.0;

// quad around the segment [tail..head]
                int x1 = (int)Math.round(tx + pxn * halfW);
                int y1 = (int)Math.round(ty + pyn * halfW);
                int x2 = (int)Math.round(tx - pxn * halfW);
                int y2 = (int)Math.round(ty - pyn * halfW);
                int x3 = (int)Math.round(hx - pxn * halfW);
                int y3 = (int)Math.round(hy - pyn * halfW);
                int x4 = (int)Math.round(hx + pxn * halfW);
                int y4 = (int)Math.round(hy + pyn * halfW);

// fill + caps + outline (as you already do)
                g2.setColor(teamFill(u));
                g2.fillPolygon(new int[]{x1,x2,x3,x4}, new int[]{y1,y2,y3,y4}, 4);
                int cap = thick;
                g2.fillOval((int)Math.round(tx - halfW), (int)Math.round(ty - halfW), cap, cap);
                g2.fillOval((int)Math.round(hx - halfW), (int)Math.round(hy - halfW), cap, cap);

                if (u.isSelected()) {
                    g2.setStroke(new BasicStroke(2f));
                    g2.setColor(Color.WHITE);
                    g2.drawPolygon(new int[]{x1,x2,x3,x4}, new int[]{y1,y2,y3,y4}, 4);
                    g2.drawOval((int)Math.round(tx - halfW), (int)Math.round(ty - halfW), cap, cap);
                    g2.drawOval((int)Math.round(hx - halfW), (int)Math.round(hy - halfW), cap, cap);
                }

            }
        }

        // --- Draw arrows ---
        for (world.Arrow a : world.getArrows()) {
            if (!isArrowCurrentlyVisible(world, a)) continue;
            // position in pixels
            double xCells = a.getX(); // col
            double yCells = a.getY(); // row
            double px = xCells * cellSize + cellSize / 2.0;
            double py = yCells * cellSize + cellSize / 2.0;
            // direction (from start to target) for orientation
            double dx = (a.tx - a.sx);
            double dy = (a.ty - a.sy);
            double len = Math.hypot(dx, dy);
            if (len < 1e-6) continue;
            double ux = dx / len, uy = dy / len;  // unit vector

            // arrow body length in pixels
            double body = Math.max(8, cellSize * 0.5);   // tweak
            double hx = px - ux * body;                  // tail point

            // draw shaft
            g2.setStroke(new BasicStroke(2f));
            g2.setColor(new Color(40, 40, 40));
            g2.drawLine((int)Math.round(hx), (int)Math.round(py - uy*0), (int)Math.round(px), (int)Math.round(py));

            // simple arrowhead (a small V)
            double headSize = Math.max(6, cellSize * 0.25);
            // perpendicular
            double pxn = -uy, pyn = ux;
            int x1 = (int)Math.round(px - ux * headSize + pxn * headSize * 0.6);
            int y1 = (int)Math.round(py - uy * headSize + pyn * headSize * 0.6);
            int x2 = (int)Math.round(px - ux * headSize - pxn * headSize * 0.6);
            int y2 = (int)Math.round(py - uy * headSize - pyn * headSize * 0.6);

            g2.drawLine((int)Math.round(px), (int)Math.round(py), x1, y1);
            g2.drawLine((int)Math.round(px), (int)Math.round(py), x2, y2);
        }
        // --- Woods ---
        for (world.Terrain.TreePatch patch : world.getTreePatches()) {
            for (world.Terrain.TreeBlock b : patch.trees()) {
                int x = b.c * cellSize;
                int y = b.r * cellSize;
                int w = 2 * cellSize, h = 2 * cellSize;

                // simple stylized canopy
                g2.setColor(new Color(44, 102, 56, 215)); // dark green
                g2.fillRoundRect(x + 1, y + 1, w - 2, h - 2, cellSize, cellSize);

                // trunk hints
                g2.setColor(new Color(80, 55, 30, 200));
                int trunkW = Math.max(3, cellSize / 5);
                int trunkH = Math.max(6, cellSize / 2);
                g2.fillRect(x + w / 2 - trunkW / 2, y + h / 2, trunkW, trunkH);

                // subtle outline
                g2.setColor(new Color(20, 20, 20, 150));
                g2.drawRoundRect(x + 1, y + 1, w - 2, h - 2, cellSize, cellSize);
            }
        }
        // --- Buildings ---
        for (world.Building b : world.getBuildings()) {
            int x = b.getCol() * cellSize;
            int y = b.getRow() * cellSize;
            int w = b.getType().w * cellSize;
            int h = b.getType().h * cellSize;

            // Fill color by team and type
            java.awt.Color base =
                    (b.getTeam() == characters.Team.RED)  ? new java.awt.Color(200,70,70,200) :
                            (b.getTeam() == characters.Team.BLUE) ? new java.awt.Color(70,110,200,200) :
                                    new java.awt.Color(120,120,120,200);

            // Slight hue per type
            java.awt.Color fill = switch (b.getType()) {
                case HOUSE -> base;
                case FARM  -> new Color(base.getRed(), Math.min(255, base.getGreen()+40), base.getBlue(), 180);
                case BARN  -> new Color(Math.min(255, base.getRed()+40), base.getGreen(), base.getBlue(), 200);
                case LOGGING_CAMP -> new Color(139, 101, 67, 190); // brown-ish for logging
                case HUNTING_CAMP -> new Color(168, 142, 92, 190);
                case WOLF_DEN -> new Color(90, 90, 90, 200); // dark grey
            };

            g2.setColor(fill);
            g2.fillRect(x, y, w, h);

            g2.setStroke(new BasicStroke(2f));
            g2.setColor(new java.awt.Color(30,30,30,200));
            g2.drawRect(x, y, w, h);

            // tiny icon/text
            g2.setColor(java.awt.Color.BLACK);
            String label = switch (b.getType()) {
                case HOUSE         -> "H";
                case FARM          -> "F";
                case BARN          -> "B";
                case LOGGING_CAMP  -> "LC";
                case HUNTING_CAMP  -> "HC";
                case WOLF_DEN -> "WD";// <-- add this
            };
            g2.drawString(label, x + 4, y + 14);
        }
        // Fog overlay (world space), after terrain/units/buildings
        final int FEATHER = 5;            // must match computeFogFeatherDistances(...)
        final int ALPHA_MAX = 40;        // darkest grey for far unseen
        final int ALPHA_MIN = 15;         // light veil right next to visible

        for (int r = firstRow; r <= lastRow; r++) {
            for (int c = firstCol; c <= lastCol; c++) {
                int x = c * cellSize, y = r * cellSize;
                boolean vis = world.isVisible(r,c), exp = world.isExplored(r,c);

                if (!exp) { g2.setColor(Color.BLACK); g2.fillRect(x,y,cellSize,cellSize); continue; }
                // draw ground/buildings here ...

                if (!vis) {
                    int d = world.getFogDist(r,c);
                    int clamped = Math.min(FEATHER, (d == Integer.MAX_VALUE ? FEATHER : d));
                    int baseAlpha = ALPHA_MIN + (ALPHA_MAX - ALPHA_MIN) * clamped / FEATHER;

                    // soften edge by reducing alpha where nearby sub-samples are visible
                    double cov = subtileCoverage(world, r, c);   // 0..1 visible around edges
                    int alpha = (int)Math.round(baseAlpha * (1.0 - 0.55 * cov)); // 55% soften
                    if (alpha <= 0) continue;

                    g2.setColor(new Color(0,0,0, Math.max(0, Math.min(255, alpha))));
                    g2.fillRect(x, y, cellSize, cellSize);
                }
            }
        }
        emaPaintMs = (1-EMA)*emaPaintMs + EMA*((System.nanoTime()-p0)/1_000_000.0);
        // Screen-space overlay layer
        Graphics2D gScreen = (Graphics2D) g.create();
        gScreen.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        if (dragStartWorld != null && dragEndWorld != null) {
            // Convert world->screen by subtracting camera
            double sx1 = dragStartWorld.x - camX;
            double sy1 = dragStartWorld.y - camY;
            double sx2 = dragEndWorld.x   - camX;
            double sy2 = dragEndWorld.y   - camY;

            int x = (int)Math.round(Math.min(sx1, sx2));
            int y = (int)Math.round(Math.min(sy1, sy2));
            int w = (int)Math.round(Math.abs(sx2 - sx1));
            int h = (int)Math.round(Math.abs(sy2 - sy1));

            gScreen.setColor(new Color(80, 150, 255, 60));
            gScreen.fillRect(x, y, w, h);
            gScreen.setColor(new Color(80, 150, 255, 180));
            gScreen.setStroke(new BasicStroke(1.5f));
            gScreen.drawRect(x, y, w, h);
        }
        gScreen.dispose();
        // draw FPS + timings (no AA)
        Graphics2D osd = (Graphics2D) g.create();
        osd.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        osd.setColor(Color.BLACK);
        osd.setFont(getFont().deriveFont(Font.BOLD, 12f));
        osd.drawString("FPS: " + currentFps + "  tick: " + String.format("%.2fms", emaTickMs)
                + "  paint: " + String.format("%.2fms", emaPaintMs), 8, 18);
        osd.dispose();
    }
    private boolean isUnitCurrentlyVisible(world.World world, characters.Unit u) {
        int r = u.getRowRounded(), c = u.getColRounded();
        if (u.getLength() <= 1) return world.isVisible((int)Math.floor(u.getY()), (int)Math.floor(u.getX()));

        // Check footprint cells (you already have footprintCells used in isBlocked)
        for (int[] cell : world.footprintCells(r, c, u.getFacing(), u.getLength())) {
            int rr = cell[0], cc = cell[1];
            if (world.isVisible(rr, cc)) return true;
        }
        // Fallback: also check head/tail tiles along orient
        int hr = (int)Math.floor(u.getY());
        int hc = (int)Math.floor(u.getX());
        double ux = Math.cos(u.getOrientRad()), uy = Math.sin(u.getOrientRad());
        int tr = (int)Math.floor(u.getY() - uy); // one tile behind
        int tc = (int)Math.floor(u.getX() - ux);
        return world.isVisible(hr, hc) || world.isVisible(tr, tc);
    }

    public void setCellSize(int newSize) {
        cellSize = Math.max(12, Math.min(newSize, 80));
        setPreferredSize(new Dimension(world.getWidth() * cellSize,
                world.getHeight() * cellSize));
        revalidate();
    }

    public void setLayer(int z) {
        currentLayer = z;
    }

    public int getCurrentLayer() {
        return currentLayer;
    }

    // ---------- Drag-select helpers ----------

    private Rectangle pixelDragRect() {
        if (dragStartPixel == null || dragEndPixel == null) return null;
        int x = Math.min(dragStartPixel.x, dragEndPixel.x);
        int y = Math.min(dragStartPixel.y, dragEndPixel.y);
        int w = Math.abs(dragStartPixel.x - dragEndPixel.x);
        int h = Math.abs(dragStartPixel.y - dragEndPixel.y);
        return new Rectangle(x, y, w, h);
    }

    private void applySelectionFromDragWorld(boolean additive) {
        if (dragStartWorld == null || dragEndWorld == null) return;

        double wx1 = Math.min(dragStartWorld.x, dragEndWorld.x);
        double wy1 = Math.min(dragStartWorld.y, dragEndWorld.y);
        double wx2 = Math.max(dragStartWorld.x, dragEndWorld.x);
        double wy2 = Math.max(dragStartWorld.y, dragEndWorld.y);

        // Treat tiny drag as a click
        boolean tiny = (Math.hypot(wx2 - wx1, wy2 - wy1) < 3.0);

        int c1 = Math.max(0, (int)Math.floor(wx1 / cellSize));
        int r1 = Math.max(0, (int)Math.floor(wy1 / cellSize));
        int c2 = Math.min(world.getWidth()  - 1, (int)Math.floor(wx2 / cellSize));
        int r2 = Math.min(world.getHeight() - 1, (int)Math.floor(wy2 / cellSize));

        if (!additive) for (characters.Unit u : world.getUnits()) u.setSelected(false);

        boolean selectedAny = false;
        for (characters.Unit u : world.getUnits()) {
            int ur = u.getRowRounded(), uc = u.getColRounded();
            boolean inside = (ur >= r1 && ur <= r2 && uc >= c1 && uc <= c2);
            if (inside) { u.setSelected(true); selectedAny = true; if (tiny) break; }
        }

        if (tiny && !selectedAny && !additive) {
            for (characters.Unit u : world.getUnits()) u.setSelected(false);
        }

        // If you enable/disable the Build button based on selection:
        updateBuildButtonEnabled();
    }

    // ---------- Fan-out move (no overlapping) ----------

    private void moveSelectedToFanOut(int targetRow, int targetCol) {
        // 1) collect selected units
        java.util.List<Unit> selected = new java.util.ArrayList<>();
        for (Unit u : world.getUnits()) if (u.isSelected()) selected.add(u);
        if (selected.isEmpty()) return;

        // 2) sort by distance to target (closer first)
        selected.sort(java.util.Comparator.comparingInt(
                u -> manhattan((int) Math.round(u.getY()), (int) Math.round(u.getX()), targetRow, targetCol)
        ));

        // 3) reserve: mark currently occupied cells so we don't assign them
        java.util.Set<Long> reserved = new java.util.HashSet<>();
        for (Unit u : world.getUnits()) {
            int r = (int) Math.round(u.getY());
            int c = (int) Math.round(u.getX());
            reserved.add(pack(r, c));
        }

        // 4) assign nearest free tile per selected unit, path to it
        for (Unit u : selected) {
            int[] dest = findClosestFreeTileAround(targetRow, targetCol, reserved);
            if (dest == null) {
                statusLabel.setText("No free tile near target.");
                continue;
            }

            reserved.add(pack(dest[0], dest[1])); // reserve for this assignment round

            int sr = (int) Math.round(u.getY());
            int sc = (int) Math.round(u.getX());

            java.util.List<java.awt.Point> path = world.findPathAStar(sr, sc, dest[0], dest[1], u);
            if (path != null && !path.isEmpty()) {
                if (path.size() >= 2 && path.get(0).x == sc && path.get(0).y == sr) {
                    path.remove(0);
                }
                u.setPath(path);
            } else {
                statusLabel.setText(String.format("No path from (%d,%d) to (%d,%d)", sr, sc, dest[0], dest[1]));
            }
        }
    }

    private int manhattan(int r1, int c1, int r2, int c2) {
        return Math.abs(r1 - r2) + Math.abs(c1 - c2);
    }

    private long pack(int r, int c) { return (((long) r) << 32) ^ (c & 0xffffffffL); }

    private int[] findClosestFreeTileAround(int tr, int tc, java.util.Set<Long> reserved) {
        if (!inBounds(tr, tc)) return null;

        boolean[][] visited = new boolean[world.getHeight()][world.getWidth()];
        java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
        q.add(new int[]{tr, tc});
        visited[tr][tc] = true;

        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
        while (!q.isEmpty()) {
            int[] cur = q.poll();
            int r = cur[0], c = cur[1];

            if (isFreeForAssignment(r, c, reserved)) {
                return new int[]{r, c};
            }

            for (int[] d : dirs) {
                int nr = r + d[0], nc = c + d[1];
                if (inBounds(nr, nc) && !visited[nr][nc]) {
                    visited[nr][nc] = true;
                    q.add(new int[]{nr, nc});
                }
            }
        }
        return null; // packed area / no space
    }

    /** A tile is assignable if in-bounds, not currently occupied (LAYER_UNIT), and not reserved.
     *  If you have impassable terrain, also check it here (e.g., ground != WALL).
     */
    private boolean isFreeForAssignment(int r, int c, java.util.Set<Long> reserved) {
        if (!inBounds(r, c)) return false;

        // If you have blocked terrain, add a check here:
        // int ground = world.getCell(r, c, World.LAYER_GROUND);
        // if (!isWalkable(ground)) return false;

        // If a unit is already on the tile (from layer mirror), skip
        int unitVal = world.getCell(r, c, World.LAYER_UNIT);
        if (unitVal != World.UNIT_NONE) return false;

        // Also skip cells reserved (occupied now or assigned to another moving unit)
        return !reserved.contains(pack(r, c));
    }
}
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

    private enum BuildMode { NONE, PICK, WALL_PAINT, HOUSE_PLACE }
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
        // NEW: House 3x3
        JMenuItem houseItem = new JMenuItem("House (3x3)");
        houseItem.addActionListener(e -> {
            buildMode = BuildMode.HOUSE_PLACE;
            statusLabel.setText("Build: House — right-click a tile to place 3×3. ESC to cancel.");
        });
        buildMenu.add(houseItem);
        buildButton.addActionListener(e -> {
            if (buildMode == BuildMode.WALL_PAINT) { // toggling off
                buildMode = BuildMode.NONE; paintPreview.clear(); repaint();
                return;
            }
            buildMode = BuildMode.PICK;
            buildMenu.show(buildButton, 0, buildButton.getHeight());
        });

        // ESC cancels build mode
        setFocusable(true);
        registerKeyboardAction(_e -> {
            buildMode = BuildMode.NONE;
            paintPreview.clear();
            repaint();
        }, KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_FOCUSED);

        // Mouse
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {

                requestFocusInWindow(); // so ESC, etc. work

                final int mx = e.getX(), my = e.getY();
                // camera-aware tile coords
                final int col = (int) ((mx + camX) / cellSize);
                final int row = (int) ((my + camY) / cellSize);
                if (!inBounds(row, col)) return;

                if (SwingUtilities.isLeftMouseButton(e)) {
                    if (buildMode == BuildMode.NONE) {
                        // start drag in WORLD coords
                        double wx = mx + camX;
                        double wy = my + camY;
                        dragStartWorld = new java.awt.geom.Point2D.Double(wx, wy);
                        dragEndWorld   = new java.awt.geom.Point2D.Double(wx, wy);
                    }
                } else if (SwingUtilities.isRightMouseButton(e)) {
                    if (buildMode == BuildMode.WALL_PAINT) {
                        // start painting walls in WORLD coords
                        painting = true;
                        lastPaintedCell = null; // reset painter trail
                        Point p = new Point(col, row);
                        if (paintPreview.isEmpty() || !paintPreview.get(paintPreview.size() - 1).equals(p)) {
                            paintPreview.add(p);
                        }
                    } else if (buildMode == BuildMode.HOUSE_PLACE) {
                        // find the team of the selected builder (fallback: no build if none)
                        characters.Team team = null;
                        for (characters.Unit u : world.getUnits()) {
                            if (u.isSelected() && u.getActor().canBuildWalls()) { team = u.getTeam(); break; }
                        }
                        if (team == null) { statusLabel.setText("No builder selected."); repaint(); return; }

                        // place top-left of 3x3 so the clicked cell becomes the center (row-1,col-1)
                        int top = row - 1, left = col - 1;
                        if (world.canPlaceHouse(top, left)) {
                            world.addHouse(top, left, team);
                            statusLabel.setText("House built. Arrivals en route.");
                            buildMode = BuildMode.NONE;
                            repaint();
                        } else {
                            statusLabel.setText("Can't place house here.");
                        }
                    }  else {
                        // Try to mount if we right-clicked a friendly horse
                        characters.Unit selected = null;
                        for (characters.Unit u : world.getUnits()) {
                            if (u.isSelected()) { selected = u; break; }
                        }
                        characters.Unit clicked = world.getUnitAt(row, col); // footprint-aware

                        if (selected != null && clicked != null
                                && clicked.getTeam() == selected.getTeam()
                                && clicked.getActor() instanceof characters.Horse) {

                            if (world.mount(selected, clicked)) {
                                statusLabel.setText("Mounted: " + selected.getActor().getName());
                                repaint();
                                return; // don't issue a move if we mounted
                            }
                        }

                        // normal move (fan-out)
                        moveSelectedToFanOut(row, col);
                    }
                }

                updateStatus(row, col); // world coords for hover text
                repaint();
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
                    repaint();               // remove rubber-band immediately
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
                    repaint();
                    return;
                }

                repaint();
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
                    repaint();
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

                repaint();
            }
        });

        // 60 FPS timer (keep your existing tick)
        new Timer(16, e -> tick()).start();
    }
    public WorldPanel(World world, JLabel statusLabel) {
        this.world = world;
        this.statusLabel = statusLabel;

        setPreferredSize(new Dimension(world.getWidth() * cellSize,
                world.getHeight() * cellSize));
        setBackground(Color.WHITE);

        // Mouse: left = select (drag or click), right = move selected (fan-out to nearest free tiles)
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int col = e.getX() / cellSize;
                int row = e.getY() / cellSize;
                if (!inBounds(row, col)) return;

                if (SwingUtilities.isLeftMouseButton(e)) {
                    // begin drag-select
                    dragStartPixel = e.getPoint();
                    dragEndPixel = e.getPoint();
                } else if (SwingUtilities.isRightMouseButton(e)) {
                    moveSelectedToFanOut(row, col);
                }
                updateStatus(row, col);
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && dragStartPixel != null) {
                    // apply selection from rubber-band
                    applySelectionFromDragWorld(e.isShiftDown() || e.isControlDown());
                    dragStartPixel = dragEndPixel = null;
                    repaint();
                }
            }
        });

        // Hover + drag updates
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int mx = e.getX();
                int my = e.getY();

                // --- EDGE-PANNING LOGIC ---
                if (mx < edgeBand) {
                    targetVX = -panMaxSpeed * (1.0 - (double) mx / edgeBand);
                } else if (mx > viewportW - edgeBand) {
                    targetVX =  panMaxSpeed * (1.0 - (double) (viewportW - mx) / edgeBand);
                } else {
                    targetVX = 0;
                }

                if (my < edgeBand) {
                    targetVY = -panMaxSpeed * (1.0 - (double) my / edgeBand);
                } else if (my > viewportH - edgeBand) {
                    targetVY =  panMaxSpeed * (1.0 - (double) (viewportH - my) / edgeBand);
                } else {
                    targetVY = 0;
                }

                // --- HOVER CELL STATUS (convert mouse->world coords) ---
                int col = (int) ((mx + camX) / cellSize);
                int row = (int) ((my + camY) / cellSize);
                if (inBounds(row, col)) updateStatus(row, col);
                else statusLabel.setText("—");
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragStartPixel != null) {
                    dragEndPixel = e.getPoint();
                    repaint();
                }
            }
        });

        // Start timer ~60 FPS
        Timer timer = new Timer(16, e -> tick());
        timer.start();
    }
    // inside WorldPanel.java
    private Color teamFill(characters.Unit u) {
        switch (u.getTeam()) {
            case RED:  return new Color(220, 70, 70);    // warm red
            case BLUE: return new Color(70, 120, 220);   // cool blue
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
            repaint();
        }
        System.out.println("Selected builder? " + enable);
    }
    private void tick() {
        long now = System.nanoTime();
        double dt = (now - lastNanos) / 1_000_000_000.0;
        if (dt > 0.1) dt = 0.1; // clamp (pause safety)
        lastNanos = now;

        // --- game updates ---
        for (Unit u : world.getUnits()) u.update(dt);
        world.syncUnitsToLayer();
        world.payIncome(dt);
        world.processCombat(now / 1_000_000_000.0);
        world.updateArrows(dt);

        // --- CAMERA: ease velocity, integrate, clamp ---
        camVX = approach(camVX, targetVX, panAccel * dt);
        camVY = approach(camVY, targetVY, panAccel * dt);

        camX += camVX * dt;
        camY += camVY * dt;

        int worldPxW = world.getWidth()  * cellSize;
        int worldPxH = world.getHeight() * cellSize;
        camX = clamp(camX, 0, Math.max(0, worldPxW - viewportW));
        camY = clamp(camY, 0, Math.max(0, worldPxH - viewportH));

        repaint();
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

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
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
            if (u.getLength() == 1) {
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
        // --- Buildings ---
        for (world.Building b : world.getBuildings()) {
            if (b.getType() == Building.Type.HOUSE) {
                int x = b.getCol() * cellSize;
                int y = b.getRow() * cellSize;
                int w = 3 * cellSize;
                int h = 3 * cellSize;

                // team color fill
                Color fill = (b.getTeam() == characters.Team.RED) ? new Color(200, 70, 70, 200)
                        : (b.getTeam() == characters.Team.BLUE) ? new Color(70, 110, 200, 200)
                        : new Color(120,120,120,200);
                g2.setColor(fill);
                g2.fillRect(x, y, w, h);

                // roof outline
                g2.setStroke(new BasicStroke(2f));
                g2.setColor(new Color(30,30,30,200));
                g2.drawRect(x, y, w, h);

                // door mark at center
                g2.setColor(new Color(0,0,0,140));
                int cx = x + w/2 - cellSize/6;
                int cy = y + h - cellSize + cellSize/4;
                g2.fillRect(cx, cy, cellSize/3, cellSize/2);
            }
        }

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

        g2.dispose();
    }

    public void setCellSize(int newSize) {
        cellSize = Math.max(12, Math.min(newSize, 80));
        setPreferredSize(new Dimension(world.getWidth() * cellSize,
                world.getHeight() * cellSize));
        revalidate();
        repaint();
    }

    public void setLayer(int z) {
        currentLayer = z;
        repaint();
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
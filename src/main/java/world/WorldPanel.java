package world;
import characters.Actor;
import characters.Unit;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

public class WorldPanel extends JPanel {

    private final World world;
    private final JLabel statusLabel;
    private int cellSize = 28; // pixels per cell
    private int currentLayer = 1; // default: unit layer

    // drag-select state
    private Point dragStartPixel = null;
    private Point dragEndPixel = null;

    // animation timing
    private long lastNanos = System.nanoTime();

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
                    applySelectionFromDrag(e.isShiftDown() || e.isControlDown());
                    dragStartPixel = dragEndPixel = null;
                    repaint();
                }
            }
        });

        // Hover + drag updates
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int col = e.getX() / cellSize;
                int row = e.getY() / cellSize;
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

    private void tick() {
        long now = System.nanoTime();
        double dt = (now - lastNanos) / 1_000_000_000.0;
        if (dt > 0.1) dt = 0.1; // clamp (pause safety)
        lastNanos = now;

        // update all units
        for (Unit u : world.getUnits()) {
            u.update(dt);
        }

        // mirror units onto unit layer (so LAYER_UNIT reflects current positions)
        world.syncUnitsToLayer();

        repaint();
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

        statusLabel.setText(info.toString());
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        Font font = getFont().deriveFont(Font.BOLD, Math.max(10f, cellSize * 0.5f));
        g2.setFont(font);
        FontMetrics fm = g2.getFontMetrics();

        for (int r = 0; r < world.getHeight(); r++) {
            for (int c = 0; c < world.getWidth(); c++) {
                int x = c * cellSize, y = r * cellSize;

                // ground
                int ground = world.getCell(r, c, World.LAYER_GROUND);
                g2.setColor(ground == 0 ? new Color(230, 245, 230) : new Color(200, 200, 200));
                g2.fillRect(x, y, cellSize, cellSize);

                // unit overlay (from mirrored unit layer)
                int unit = world.getCell(r, c, World.LAYER_UNIT);
                if (unit != World.UNIT_NONE) {
                    g2.setColor(new Color(60, 60, 60, 200));
                    g2.fillRect(x, y, cellSize, cellSize);

                    String s = String.valueOf(unit);
                    int tx = x + (cellSize - fm.stringWidth(s)) / 2;
                    int ty = y + (cellSize + fm.getAscent()) / 2 - 2;
                    g2.setColor(Color.WHITE);
                    g2.drawString(s, tx, ty);
                }

                // highlight selection
                for (Unit u : world.getUnits()) {
                    if (u.isSelected()) {
                        int sr = (int) Math.round(u.getY());
                        int sc = (int) Math.round(u.getX());
                        if (sr == r && sc == c) {
                            g2.setColor(new Color(255, 215, 0));
                            g2.drawRect(x + 1, y + 1, cellSize - 2, cellSize - 2);
                            g2.drawRect(x + 2, y + 2, cellSize - 4, cellSize - 4);
                        }
                    }
                }

                // grid
                g2.setColor(new Color(180, 180, 180));
                g2.drawRect(x, y, cellSize, cellSize);
            }
        }

        // selection rectangle overlay (rubber-band)
        if (dragStartPixel != null && dragEndPixel != null) {
            Rectangle rr = pixelDragRect();
            if (rr != null) {
                g2.setColor(new Color(80, 150, 255, 60));
                g2.fill(rr);
                g2.setColor(new Color(80, 150, 255, 180));
                g2.setStroke(new BasicStroke(1.5f));
                g2.draw(rr);
            }
        }

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

    private void applySelectionFromDrag(boolean additive) {
        Rectangle pixRect = pixelDragRect();
        if (pixRect == null) return;

        // Treat tiny click as single-tile selection
        boolean tiny = pixRect.width < 3 && pixRect.height < 3;
        int c1 = Math.max(0, pixRect.x / cellSize);
        int r1 = Math.max(0, pixRect.y / cellSize);
        int c2 = Math.min(world.getWidth() - 1, (pixRect.x + pixRect.width) / cellSize);
        int r2 = Math.min(world.getHeight() - 1, (pixRect.y + pixRect.height) / cellSize);

        if (!additive) {
            for (Unit u : world.getUnits()) u.setSelected(false);
        }

        boolean selectedAny = false;
        for (Unit u : world.getUnits()) {
            int ur = (int) Math.round(u.getY());
            int uc = (int) Math.round(u.getX());
            boolean inside = ur >= r1 && ur <= r2 && uc >= c1 && uc <= c2;
            if (inside) {
                u.setSelected(true);
                selectedAny = true;
                if (tiny) break; // single click: pick the first unit found
            }
        }

        // if tiny click hit nothing and not additive, clear selection
        if (tiny && !selectedAny && !additive) {
            for (Unit u : world.getUnits()) u.setSelected(false);
        }
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
package characters;
import java.awt.Point;
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

    // NEW: path as queue of grid waypoints (row,col)
    private final Deque<Point> path = new ArrayDeque<>();

    public Unit(Actor actor, int row, int col) {
        this.actor = actor;
        this.x = col;
        this.y = row;
        this.targetCol = col;
        this.targetRow = row;
        this.moving = false;
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

    /** True if currently has a path/target. */
    public boolean isMoving() { return moving; }

    public boolean isSelected() { return selected; }
    public void setSelected(boolean sel) { this.selected = sel; }

    public double getX() { return x; } // col
    public double getY() { return y; } // row

    public int getRowRounded() { return (int) Math.round(y); }
    public int getColRounded() { return (int) Math.round(x); }

    /** Advance along the path by speed * dt (cells/sec). */
    public void update(double dt) {
        if (!moving) return;
        if (path.isEmpty()) { moving = false; return; }

        Point waypoint = path.peekFirst(); // next grid cell (col=x, row=y)
        double wx = waypoint.x;
        double wy = waypoint.y;

        double dx = wx - x;
        double dy = wy - y;
        double dist = Math.hypot(dx, dy);

        // arrived at this waypoint?
        if (dist < 1e-3) {
            x = wx; y = wy;
            path.removeFirst();
            if (path.isEmpty()) { moving = false; return; }
            waypoint = path.peekFirst();
        }

        double step = actor.getMovement() * dt; // cells per second
        if (step >= dist) {
            x = wx; y = wy;
            path.removeFirst();
            if (path.isEmpty()) moving = false;
        } else {
            x += (dx / dist) * step;
            y += (dy / dist) * step;
        }
    }
}
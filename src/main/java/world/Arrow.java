package world;

public class Arrow {
    // positions in *grid* space (same as Unit x/y, i.e., col/row as doubles)
    public final double sx, sy;     // start (archer)
    public final double tx, ty;     // target (snapshot at fire time)
    public double progress = 0.0;   // 0..1 along the path
    public final double speedCellsPerSec; // how fast the arrow travels in tiles/sec

    private final double dist; // cached distance in cells

    public Arrow(double sx, double sy, double tx, double ty, double speedCellsPerSec) {
        this.sx = sx; this.sy = sy; this.tx = tx; this.ty = ty;
        this.speedCellsPerSec = speedCellsPerSec;
        this.dist = Math.hypot(tx - sx, ty - sy);
    }

    /** advance progress by dt; returns true when the arrow should be removed */
    public boolean update(double dt) {
        if (dist <= 1e-6) { progress = 1.0; return true; }
        double dp = (speedCellsPerSec * dt) / dist;
        progress += dp;
        return progress >= 1.0;
    }

    public double getX() { return sx + (tx - sx) * Math.min(progress, 1.0); } // col
    public double getY() { return sy + (ty - sy) * Math.min(progress, 1.0); } // row
}
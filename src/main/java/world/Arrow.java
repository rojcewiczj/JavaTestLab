package world;

public class Arrow {
    public final double sx, sy, tx, ty;
    public final double speedCellsPerSec;
    public final int shooterId;
    public final Integer targetId; // nullable
    public double dist;
    public double t; // 0..1 param along the segment

    // for rendering current position if you keep it
    private double cx, cy;
    public double getX(){ return (t >= 1.0 ? tx : cx); }
    public double getY(){ return (t >= 1.0 ? ty : cy); }

    public Arrow(double sx, double sy, double tx, double ty, double speedCellsPerSec,
                 int shooterId, Integer targetId) {
        this.sx = sx; this.sy = sy; this.tx = tx; this.ty = ty;
        this.speedCellsPerSec = speedCellsPerSec;
        this.shooterId = shooterId;
        this.targetId = targetId;
        this.dist = Math.hypot(tx - sx, ty - sy);
        this.t = 0.0;
        this.cx = sx; this.cy = sy;
    }

    /** advance; returns true when reached target */
    public boolean step(double dt) {
        if (dist <= 1e-6) { t = 1.0; cx = tx; cy = ty; return true; }
        t += (speedCellsPerSec * dt) / dist;
        if (t >= 1.0) { cx = tx; cy = ty; return true; }
        cx = sx + (tx - sx) * t;
        cy = sy + (ty - sy) * t;
        return false;
    }
}
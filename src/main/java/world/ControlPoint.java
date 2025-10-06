package world;

public class ControlPoint {
    private final int id;
    private final int row, col;
    private final int radius;        // tiles (Manhattan)
    private final double incomePerSec;
    private Integer ownerTickCacheHash = null; // optional micro-opt

    public ControlPoint(int id, int row, int col, int radius, double incomePerSec) {
        this.id = id; this.row = row; this.col = col;
        this.radius = radius; this.incomePerSec = incomePerSec;
    }

    public int getId() { return id; }
    public int getRow() { return row; }
    public int getCol() { return col; }
    public int getRadius() { return radius; }
    public double getIncomePerSec() { return incomePerSec; }
}

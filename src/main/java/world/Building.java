package world;


import characters.Team;


public class Building {
    public enum Type {
        HOUSE(3, 3),
        FARM(4, 8),     // 4 high, 8 wide
        BARN(3, 4),    // 3 high, 4 wide
        LOGGING_CAMP(3,4);
        public final int h, w;
        Type(int h, int w) { this.h = h; this.w = w; }
    }

    private final Type type;
    private final int row, col; // top-left tile
    private final Team team;

    // for houses: have we already spawned their arrivals?
    private boolean arrivalsSpawned = false;

    public Building(Type type, int row, int col, Team team) {
        this.type = type; this.row = row; this.col = col; this.team = team;
    }

    public Type getType() { return type; }
    public int getRow() { return row; }
    public int getCol() { return col; }
    public Team getTeam() { return team; }

    public boolean isArrivalsSpawned() { return arrivalsSpawned; }
    public void setArrivalsSpawned(boolean v) { arrivalsSpawned = v; }
}

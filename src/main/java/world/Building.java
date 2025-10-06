package world;


import characters.Team;

public class Building {
    public enum Type { HOUSE }

    private final Type type;
    private final int row, col; // top-left cell of the 3x3 footprint
    private final Team team;

    public Building(Type type, int row, int col, Team team) {
        this.type = type; this.row = row; this.col = col; this.team = team;
    }
    public Type getType() { return type; }
    public int getRow() { return row; }
    public int getCol() { return col; }
    public Team getTeam() { return team; }
}

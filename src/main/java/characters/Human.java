package characters;

public class Human extends Actor {
    // You can add Human-specific fields here (faction, class, perks, etc.)
    public Human(String name,
                 int strength, int agility, int intellect, int vitality,
                 double movement) {
        super(ActorType.HUMAN, name, strength, agility, intellect, vitality, movement);
    }
    // characters/Footman.java  (or whatever your basic human is)
    @Override public int defaultAimSkill()       { return 3; }
    @Override public int defaultPower()          { return 3; }
    @Override public int defaultMaxWounds()      { return 2; }
    @Override public double defaultRangedCooldown(){ return 1.0; }
    // Convenience default Human
    public static Human basicFootman() {
        return new Human("Footman", 3, 3, 2, 3, 3.0); // movement = 3 cells/sec
    }
    // === Placement & UI state ===
    private int row, col;
    private boolean selected;

    // --- Stats (getters/setters omitted for brevity — add as needed) ---

    // --- Position ---
    public int getRow() { return row; }
    public int getCol() { return col; }
    public void setPos(int r, int c) { this.row = r; this.col = c; }

    // --- Selection ---
    public boolean isSelected() { return selected; }
    public void setSelected(boolean selected) { this.selected = selected; }

    @Override
    public String toString() {
        return String.format("Human[r=%d,c=%d]", row, col);
    }
}

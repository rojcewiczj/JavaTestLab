package characters;

public class Deer extends Horse { // reuse visuals/move anim for now
    public Deer() { super(); }
    @Override public String getName() { return "Deer"; }
    @Override public int defaultAimSkill()       { return 0; }
    @Override public int defaultPower()          { return 0; }
    @Override public int defaultMaxWounds()      { return 2; }
    @Override public double defaultRangedCooldown(){ return 1.0; }
}
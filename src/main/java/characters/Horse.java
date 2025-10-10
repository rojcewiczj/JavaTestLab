package characters;

public class Horse extends Actor {
    public Horse() {
        super(ActorType.HORSE,"Horse", 3, 4, 1, 1, 8);
    }
    @Override public int defaultAimSkill()       { return 0; }
    @Override public int defaultPower()          { return 0; }
    @Override public int defaultMaxWounds()      { return 2; }
    @Override public double defaultRangedCooldown(){ return 1.0; }
}

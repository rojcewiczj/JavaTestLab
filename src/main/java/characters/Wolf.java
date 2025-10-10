package characters;

public class Wolf extends Actor {
    public Wolf(ActorType type) {
        super(type);
    }

    @Override public String getName() { return "Wolf"; }

    // Movement (tiles/sec)
    @Override public double getMovement() { return 3.2; }

    // Vision: wolves can see all around
    @Override public double getVisionRangeTiles() { return 9.0; }
    @Override public double getVisionConeRad()     { return Math.PI * 2; }

    // Ranged: none
    @Override public boolean hasShortBow() { return false; }
    @Override public double defaultRangedCooldown() { return 1.0; }

    // Melee defaults
    @Override public double defaultMeleeCooldown() { return 0.90; }

    // Default combat stats
    @Override public int defaultAimSkill() { return 0; }   // no bow
    @Override public int defaultPower()    { return 3; }   // 30% damage chance
    @Override public int defaultMeleeSkill(){ return 4; }  // 40% hit chance

    // Default wounds
    @Override public int defaultMaxWounds() { return 2; }
}

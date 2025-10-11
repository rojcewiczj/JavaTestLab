package characters;

public abstract class Actor {
    private final ActorType type;
    private String name;

    // Core attributes (example set — extend as needed)
    private int strength;
    private int agility;
    private int intellect;
    private int vitality;
    private boolean canBuildWalls;
    private String equipment = "Sword";
    private boolean hasSword = true;       // footmen default to sword
    private boolean hasShortBow = false;   // opt-in
    public double getVisionRangeTiles() { return 15.0; }
    public double getVisionConeRad()     { return Math.PI; }

    // Melee defaults
    public double defaultMeleeCooldown() { return 1.0; };

    // characters/Actor.java
    public int    defaultAimSkill()       { return 0; }   // 0..10 -> 0..100%
    public int    defaultPower()          { return 0; }   // 0..10 -> 0..100%

    public int defaultMeleeSkill() { return 6; }  // 40% hit chance
    ;

    public int    defaultMaxWounds()      { return 2; }   // humans & deer = 2
    public double defaultRangedCooldown() { return 4.0; } // seconds between shots
    // Movement speed in cells per second
    private double movement;
    public Actor(ActorType type) {
        // optionally initialize defaults
        this.type = type;
    }
    protected Actor(ActorType type, String name,
                    int strength, int agility, int intellect, int vitality,
                    double movement) {
        this.type = type;
        this.name = name;
        this.strength = strength;
        this.agility = agility;
        this.intellect = intellect;
        this.vitality = vitality;
        this.movement = movement;
    }
    // in characters.Actor (or Human)
    // --- name + type ---

    // in characters.Actor
    // default
    // --- Equipment flags ---
    public boolean hasSword() { return hasSword; }
    public void setHasSword(boolean v) { hasSword = v; }

    public boolean hasShortBow() { return hasShortBow; }
    public void setHasShortBow(boolean v) { hasShortBow = v; }
    public String getEquipment() { return equipment; }
    public void setEquipment(String equipment) { this.equipment = equipment; }
    public boolean canBuildWalls() { return canBuildWalls; }
    public void setCanBuildWalls(boolean v) { this.canBuildWalls = v; }
    public ActorType getType() { return type; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getStrength()  { return strength;  }
    public int getAgility()   { return agility;   }
    public int getIntellect() { return intellect; }
    public int getVitality()  { return vitality;  }

    public void setStrength(int v)  { strength = v; }
    public void setAgility(int v)   { agility = v; }
    public void setIntellect(int v) { intellect = v; }
    public void setVitality(int v)  { vitality = v; }

    public double getMovement() { return movement; }
    public void setMovement(double v) { movement = v; }
}

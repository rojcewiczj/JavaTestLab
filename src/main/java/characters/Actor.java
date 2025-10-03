package characters;

public abstract class Actor {
    private final ActorType type;
    private final String name;

    // Core attributes (example set — extend as needed)
    private int strength;
    private int agility;
    private int intellect;
    private int vitality;

    // Movement speed in cells per second
    private double movement;

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

    public ActorType getType() { return type; }
    public String getName() { return name; }

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

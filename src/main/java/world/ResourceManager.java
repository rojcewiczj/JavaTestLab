package world;

import characters.Team;
import java.util.EnumMap;

public class ResourceManager {
    private final EnumMap<Team, Double> pool = new EnumMap<>(Team.class);

    public ResourceManager() {
        for (Team t : Team.values()) pool.put(t, 0.0);
    }

    public void add(Team t, double amount) { pool.put(t, pool.get(t) + amount); }

    public int getWhole(Team t) { return (int)Math.floor(pool.get(t)); }

    public double getExact(Team t) { return pool.get(t); }

    public void spend(Team t, double amount) { pool.put(t, Math.max(0.0, pool.get(t) - amount)); }
}

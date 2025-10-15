package intelligence;

import characters.ActorType;

public final class TargetPick {
    public final int unitId;
    public final double x, y;
    public final boolean live;
    public final characters.ActorType actorType;

    public TargetPick(int id, double x, double y, boolean live, characters.ActorType type) {
        this.unitId = id;
        this.x = x;
        this.y = y;
        this.live = live;
        this.actorType = type;
    }
}

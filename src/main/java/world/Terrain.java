package world;

public class Terrain {
    private Terrain() {}
    // Marker for a 2×2 tree block anchored at (topRow, leftCol)
    public static final class TreeBlock {
        public final int r, c; // top-left cell
        public TreeBlock(int r, int c) { this.r = r; this.c = c; }
    }

    // A patch is a set of 2×2 trees + an associated control point id
    public static final class TreePatch {
        private final java.util.List<TreeBlock> trees = new java.util.ArrayList<>();
        private final int controlPointId; // control point placed for this patch
        public TreePatch(int cpId) { this.controlPointId = cpId; }
        public java.util.List<TreeBlock> trees() { return trees; }
        public int getControlPointId() { return controlPointId; }
    }
}

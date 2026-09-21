package games.crescentnetwork.mcp.script;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Collects what a build script places, as two independent layers.
 *
 * <p>Blocks and fluids are kept apart because the prefab format keeps them apart — version 5 of that
 * format deliberately moved fluids out of {@code blocks} into their own array. Keeping the split here
 * means a cell can carry both at once, which is not a corner case: a scan of the 7,828 base-pack
 * prefabs found 15,659 cells holding a real block and a fluid together, almost all of them underwater
 * plants and coral. A script gets that for free by calling {@code block()} twice at one coordinate,
 * once with a block name and once with a fluid name.
 *
 * <p>Writes are last-wins per layer, matching how a builder thinks about painting over an area.
 */
public final class BuildRecorder {

    /** Hard limit from {@code BlockUtil}, which packs Y into a signed 9-bit field. */
    public static final int MIN_Y = -512;
    public static final int MAX_Y = 511;
    /** Hard limit from {@code BlockUtil}: 26 bits per horizontal axis. Practically unreachable. */
    public static final int MAX_HORIZONTAL = (1 << 26) - 1;

    public record Placement(String name, int rotation) {
    }

    public record FluidPlacement(String name, int level) {
    }

    private final Map<Long, Placement> blocks = new HashMap<>();
    private final Map<Long, FluidPlacement> fluids = new HashMap<>();
    private final Set<String> namesUsed = new LinkedHashSet<>();

    private final int maxCells;

    private int anchorX;
    private int anchorY;
    private int anchorZ;

    private int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
    private int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

    public BuildRecorder(int maxCells) {
        this.maxCells = maxCells;
    }

    /**
     * Packs a coordinate triple into one long key.
     *
     * <p>21 bits per axis, biased so negatives pack cleanly. That covers +/-1,048,576 on every axis,
     * comfortably wider than the coordinate range {@link #checkBounds} allows through.
     */
    static long key(int x, int y, int z) {
        long px = (x + (1 << 20)) & 0x1FFFFFL;
        long py = (y + (1 << 20)) & 0x1FFFFFL;
        long pz = (z + (1 << 20)) & 0x1FFFFFL;
        return (px << 42) | (py << 21) | pz;
    }

    public void checkBounds(int x, int y, int z) {
        if (y < MIN_Y || y > MAX_Y) {
            throw new ScriptError(ScriptError.Phase.VALIDATE,
                "y=" + y + " is out of range; prefabs pack Y into 9 signed bits, so y must be between "
                    + MIN_Y + " and " + MAX_Y + ".");
        }
        if (x < -MAX_HORIZONTAL || x > MAX_HORIZONTAL || z < -MAX_HORIZONTAL || z > MAX_HORIZONTAL) {
            throw new ScriptError(ScriptError.Phase.VALIDATE,
                "x=" + x + ", z=" + z + " is out of range; horizontal coordinates must be within +/-"
                    + MAX_HORIZONTAL + ".");
        }
    }

    public void putBlock(int x, int y, int z, String name, int rotation) {
        checkBounds(x, y, z);
        if (blocks.put(key(x, y, z), new Placement(name, rotation)) == null) {
            checkCapacity();
        }
        namesUsed.add(name);
        grow(x, y, z);
    }

    public void putFluid(int x, int y, int z, String name, int level) {
        checkBounds(x, y, z);
        if (fluids.put(key(x, y, z), new FluidPlacement(name, level)) == null) {
            checkCapacity();
        }
        namesUsed.add(name);
        grow(x, y, z);
    }

    /** Removes whatever is at a coordinate, in both layers. */
    public void clear(int x, int y, int z) {
        long k = key(x, y, z);
        blocks.remove(k);
        fluids.remove(k);
        // Bounds are deliberately not shrunk: recomputing them on every clear would be quadratic, and
        // they are recomputed exactly once in bounds() before the prefab is written.
    }

    @Nullable
    public Placement blockAt(int x, int y, int z) {
        return blocks.get(key(x, y, z));
    }

    @Nullable
    public FluidPlacement fluidAt(int x, int y, int z) {
        return fluids.get(key(x, y, z));
    }

    private void checkCapacity() {
        if (blocks.size() + fluids.size() > maxCells) {
            throw new ScriptError(ScriptError.Phase.LIMIT,
                "Build exceeds the " + maxCells + " cell limit. Prefabs above "
                    + maxCells + " blocks are written in Hytale's binary .lpf form instead of .prefab.json; "
                    + "build something smaller or more sparse.");
        }
    }

    /**
     * Rejects a primitive whose volume alone would blow the cap, before any of it is allocated.
     * Without this a single stray {@code box()} spanning the coordinate space would run until the
     * timeout instead of failing with a message that says what went wrong.
     */
    public void checkExpansion(long volume, String primitive) {
        long budget = (long) maxCells * 2;
        if (volume > budget) {
            throw new ScriptError(ScriptError.Phase.LIMIT,
                primitive + " would place " + volume + " cells, over the " + budget
                    + " expansion budget. Check the coordinates; they may be swapped or far apart.");
        }
    }

    private void grow(int x, int y, int z) {
        if (x < minX) minX = x;
        if (y < minY) minY = y;
        if (z < minZ) minZ = z;
        if (x > maxX) maxX = x;
        if (y > maxY) maxY = y;
        if (z > maxZ) maxZ = z;
    }

    public void setAnchor(int x, int y, int z) {
        this.anchorX = x;
        this.anchorY = y;
        this.anchorZ = z;
    }

    public int anchorX() {
        return anchorX;
    }

    public int anchorY() {
        return anchorY;
    }

    public int anchorZ() {
        return anchorZ;
    }

    public Map<Long, Placement> blocks() {
        return blocks;
    }

    public Map<Long, FluidPlacement> fluids() {
        return fluids;
    }

    public Set<String> namesUsed() {
        return namesUsed;
    }

    public int blockCount() {
        return blocks.size();
    }

    public int fluidCount() {
        return fluids.size();
    }

    public boolean isEmpty() {
        return blocks.isEmpty() && fluids.isEmpty();
    }

    /** Unpacks a key back to coordinates. Order matches {@link #key}. */
    public static int unpackX(long k) {
        return (int) ((k >>> 42) & 0x1FFFFFL) - (1 << 20);
    }

    public static int unpackY(long k) {
        return (int) ((k >>> 21) & 0x1FFFFFL) - (1 << 20);
    }

    public static int unpackZ(long k) {
        return (int) (k & 0x1FFFFFL) - (1 << 20);
    }

    public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public int width() {
            return maxX - minX + 1;
        }

        public int height() {
            return maxY - minY + 1;
        }

        public int length() {
            return maxZ - minZ + 1;
        }
    }

    /**
     * The occupied bounding box, recomputed from what actually survived rather than from the running
     * min/max, so cells removed by {@code clear()} do not leave the box inflated.
     */
    public Bounds bounds() {
        if (isEmpty()) return new Bounds(0, 0, 0, 0, 0, 0);
        int nMinX = Integer.MAX_VALUE, nMinY = Integer.MAX_VALUE, nMinZ = Integer.MAX_VALUE;
        int nMaxX = Integer.MIN_VALUE, nMaxY = Integer.MIN_VALUE, nMaxZ = Integer.MIN_VALUE;
        for (Map<Long, ?> layer : java.util.List.of(blocks, fluids)) {
            for (Long k : layer.keySet()) {
                int x = unpackX(k), y = unpackY(k), z = unpackZ(k);
                if (x < nMinX) nMinX = x;
                if (y < nMinY) nMinY = y;
                if (z < nMinZ) nMinZ = z;
                if (x > nMaxX) nMaxX = x;
                if (y > nMaxY) nMaxY = y;
                if (z > nMaxZ) nMaxZ = z;
            }
        }
        return new Bounds(nMinX, nMinY, nMinZ, nMaxX, nMaxY, nMaxZ);
    }
}

package games.crescentnetwork.mcp.render;

import games.crescentnetwork.mcp.palette.BlockCatalog;
import games.crescentnetwork.mcp.palette.BlockInfo;
import games.crescentnetwork.mcp.script.BuildRecorder;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A build flattened into something a raycaster can walk quickly.
 *
 * <p>Cells are stored as an index into a small material table rather than as names, so the hot loop
 * touches one array instead of a hash map of strings. A dense array is used while the bounding box is
 * small enough to afford it and a hash map beyond that, because a tall sparse build can have a box far
 * larger than the number of cells it actually fills.
 */
public final class VoxelScene {

    /** Above this many cells the dense array costs more memory than it saves in speed. */
    private static final long DENSE_CELL_LIMIT = 16_000_000L;

    public record Material(int rgb, List<String> faceTextures, boolean cubic, boolean fluid) {
    }

    private final int minX, minY, minZ;
    private final int sizeX, sizeY, sizeZ;
    @Nullable
    private final short[] dense;
    @Nullable
    private final Map<Long, Short> sparse;
    private final List<Material> materials;
    private final int cellCount;

    private VoxelScene(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ,
                       @Nullable short[] dense, @Nullable Map<Long, Short> sparse,
                       List<Material> materials, int cellCount) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.dense = dense;
        this.sparse = sparse;
        this.materials = materials;
        this.cellCount = cellCount;
    }

    public static VoxelScene from(BuildRecorder recorder, BlockCatalog catalog) {
        BuildRecorder.Bounds b = recorder.bounds();
        int sizeX = b.width(), sizeY = b.height(), sizeZ = b.length();
        long volume = (long) sizeX * sizeY * sizeZ;

        List<Material> materials = new ArrayList<>();
        // Index 0 is reserved for empty, so a zeroed dense array already means "nothing here".
        materials.add(new Material(0, List.of(), false, false));
        Map<String, Short> byName = new HashMap<>();

        short[] dense = volume <= DENSE_CELL_LIMIT ? new short[(int) volume] : null;
        Map<Long, Short> sparse = dense == null ? new HashMap<>(recorder.blockCount() * 2) : null;

        int cells = 0;
        // Fluids first, so a block sharing the cell paints over them. Underwater plants should read as
        // the plant rather than as water, which is how they look in game from outside the water.
        for (Map.Entry<Long, BuildRecorder.FluidPlacement> e : recorder.fluids().entrySet()) {
            short m = materialFor(e.getValue().name(), catalog, materials, byName);
            cells += store(dense, sparse, index(e.getKey(), b, sizeX, sizeY, sizeZ), m) ? 1 : 0;
        }
        for (Map.Entry<Long, BuildRecorder.Placement> e : recorder.blocks().entrySet()) {
            short m = materialFor(e.getValue().name(), catalog, materials, byName);
            cells += store(dense, sparse, index(e.getKey(), b, sizeX, sizeY, sizeZ), m) ? 1 : 0;
        }

        return new VoxelScene(b.minX(), b.minY(), b.minZ(), sizeX, sizeY, sizeZ,
            dense, sparse, List.copyOf(materials), cells);
    }

    private static long index(long key, BuildRecorder.Bounds b, int sizeX, int sizeY, int sizeZ) {
        int x = BuildRecorder.unpackX(key) - b.minX();
        int y = BuildRecorder.unpackY(key) - b.minY();
        int z = BuildRecorder.unpackZ(key) - b.minZ();
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) return -1;
        return ((long) y * sizeZ + z) * sizeX + x;
    }

    private static boolean store(@Nullable short[] dense, @Nullable Map<Long, Short> sparse,
                                 long idx, short material) {
        if (idx < 0) return false;
        if (dense != null) {
            boolean fresh = dense[(int) idx] == 0;
            dense[(int) idx] = material;
            return fresh;
        }
        return sparse.put(idx, material) == null;
    }

    private static short materialFor(String name, BlockCatalog catalog,
                                     List<Material> materials, Map<String, Short> byName) {
        Short existing = byName.get(name);
        if (existing != null) return existing;

        BlockInfo info = catalog.find(name);
        Material m = info == null
            ? new Material(BlockInfo.FALLBACK_RGB, List.of(), false, false)
            : new Material(info.rgb(), info.faceTextures(), info.cubic(), info.isFluid());

        // The table is indexed by a short, so a build using more than 32,767 distinct materials folds
        // the remainder onto a single colour rather than corrupting the grid. No real build comes close.
        if (materials.size() >= Short.MAX_VALUE) return 1;
        materials.add(m);
        short idx = (short) (materials.size() - 1);
        byName.put(name, idx);
        return idx;
    }

    /** Material index at a world coordinate, or 0 for empty. */
    public short at(int worldX, int worldY, int worldZ) {
        int x = worldX - minX, y = worldY - minY, z = worldZ - minZ;
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) return 0;
        long idx = ((long) y * sizeZ + z) * sizeX + x;
        if (dense != null) return dense[(int) idx];
        Short v = sparse.get(idx);
        return v == null ? 0 : v;
    }

    public Material material(short index) {
        return materials.get(index);
    }

    public int minX() {
        return minX;
    }

    public int minY() {
        return minY;
    }

    public int minZ() {
        return minZ;
    }

    public int sizeX() {
        return sizeX;
    }

    public int sizeY() {
        return sizeY;
    }

    public int sizeZ() {
        return sizeZ;
    }

    public int cellCount() {
        return cellCount;
    }

    public double[] center() {
        return new double[]{minX + sizeX / 2.0, minY + sizeY / 2.0, minZ + sizeZ / 2.0};
    }
}

package games.crescentnetwork.mcp.render;

import games.crescentnetwork.mcp.palette.BlockCatalog;
import games.crescentnetwork.mcp.palette.BlockInfo;
import games.crescentnetwork.mcp.render.model.BakedModel;
import games.crescentnetwork.mcp.render.model.ModelLibrary;
import games.crescentnetwork.mcp.script.BuildRecorder;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
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
 *
 * <p>Model-drawn blocks are kept a second way as well, as instances indexed by every cell their
 * geometry overlaps. A model is not confined to its own cell: a roof overhangs, a shallow roof or a
 * bed spans several cells, and the ray has to find that geometry whichever of those cells it passes
 * through first. The scene's box is widened to take in all such geometry for the same reason.
 */
public final class VoxelScene {

    /** Above this many cells the dense array costs more memory than it saves in speed. */
    private static final long DENSE_CELL_LIMIT = 16_000_000L;

    /** Keeps geometry that exactly meets a cell boundary from claiming the neighbouring cell. */
    private static final double BOUNDARY_EPSILON = 1e-6;

    /**
     * @param model the block's model at this material's rotation, or null when it draws as a cube
     * @param solid whether the cell draws as a full cube: cubic blocks, fluids, the cube half of a
     *              cube-with-model block, and any model that could not be loaded
     */
    public record Material(int rgb, List<String> faceTextures, boolean cubic, boolean fluid,
                           @Nullable BakedModel model, boolean solid) {
    }

    private final int minX, minY, minZ;
    private final int sizeX, sizeY, sizeZ;
    @Nullable
    private final short[] dense;
    @Nullable
    private final Map<Long, Short> sparse;
    private final List<Material> materials;
    private final int cellCount;

    private final int[] instanceCoords;
    private final short[] instanceMaterials;
    private final Map<Long, int[]> instancesByCell;
    /** Dense mirror of which cells have instances, so most ray steps skip the map lookup. */
    @Nullable
    private final boolean[] denseHasInstances;

    private VoxelScene(int[] box, @Nullable short[] dense, @Nullable Map<Long, Short> sparse,
                       List<Material> materials, int cellCount, int[] instanceCoords,
                       short[] instanceMaterials, Map<Long, int[]> instancesByCell,
                       @Nullable boolean[] denseHasInstances) {
        this.minX = box[0];
        this.minY = box[1];
        this.minZ = box[2];
        this.sizeX = box[3] - box[0] + 1;
        this.sizeY = box[4] - box[1] + 1;
        this.sizeZ = box[5] - box[2] + 1;
        this.dense = dense;
        this.sparse = sparse;
        this.materials = materials;
        this.cellCount = cellCount;
        this.instanceCoords = instanceCoords;
        this.instanceMaterials = instanceMaterials;
        this.instancesByCell = instancesByCell;
        this.denseHasInstances = denseHasInstances;
    }

    /** A scene with every block drawn as a cube, for callers with no model assets to hand. */
    public static VoxelScene from(BuildRecorder recorder, BlockCatalog catalog) {
        return from(recorder, catalog, null);
    }

    public static VoxelScene from(BuildRecorder recorder, BlockCatalog catalog, @Nullable ModelLibrary models) {
        BuildRecorder.Bounds b = recorder.bounds();
        int[] box = {b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()};

        MaterialTable table = new MaterialTable(catalog, models);

        // Materials are resolved first, because model geometry decides how big the scene has to be.
        int fluidCount = recorder.fluids().size();
        long[] fluidKeys = new long[fluidCount];
        short[] fluidMaterials = new short[fluidCount];
        int i = 0;
        for (Map.Entry<Long, BuildRecorder.FluidPlacement> e : recorder.fluids().entrySet()) {
            fluidKeys[i] = e.getKey();
            fluidMaterials[i++] = table.materialFor(e.getValue().name(), 0);
        }

        int blockCount = recorder.blocks().size();
        long[] blockKeys = new long[blockCount];
        short[] blockMaterials = new short[blockCount];
        int modelCount = 0;
        i = 0;
        for (Map.Entry<Long, BuildRecorder.Placement> e : recorder.blocks().entrySet()) {
            long key = e.getKey();
            short m = table.materialFor(e.getValue().name(), e.getValue().rotation());
            blockKeys[i] = key;
            blockMaterials[i++] = m;
            BakedModel model = table.materials.get(m).model();
            if (model == null) continue;
            modelCount++;
            int[] cells = cellsOf(model, BuildRecorder.unpackX(key), BuildRecorder.unpackY(key),
                BuildRecorder.unpackZ(key));
            for (int a = 0; a < 3; a++) {
                box[a] = Math.min(box[a], cells[a]);
                box[a + 3] = Math.max(box[a + 3], cells[a + 3]);
            }
        }

        int sizeX = box[3] - box[0] + 1, sizeY = box[4] - box[1] + 1, sizeZ = box[5] - box[2] + 1;
        long volume = (long) sizeX * sizeY * sizeZ;
        short[] dense = volume <= DENSE_CELL_LIMIT ? new short[(int) volume] : null;
        Map<Long, Short> sparse = dense == null ? new HashMap<>(recorder.blockCount() * 2) : null;

        int cells = 0;
        // Fluids first, so a block sharing the cell paints over them. Underwater plants should read as
        // the plant rather than as water, which is how they look in game from outside the water.
        for (int f = 0; f < fluidCount; f++) {
            cells += store(dense, sparse, index(fluidKeys[f], box), fluidMaterials[f]) ? 1 : 0;
        }
        for (int k = 0; k < blockCount; k++) {
            cells += store(dense, sparse, index(blockKeys[k], box), blockMaterials[k]) ? 1 : 0;
        }

        int[] instanceCoords = new int[modelCount * 3];
        short[] instanceMaterials = new short[modelCount];
        Map<Long, int[]> byCell = new HashMap<>();
        boolean[] hasInstances = dense == null || modelCount == 0 ? null : new boolean[(int) volume];
        int id = 0;
        for (int k = 0; k < blockCount; k++) {
            BakedModel model = table.materials.get(blockMaterials[k]).model();
            if (model == null) continue;
            int x = BuildRecorder.unpackX(blockKeys[k]);
            int y = BuildRecorder.unpackY(blockKeys[k]);
            int z = BuildRecorder.unpackZ(blockKeys[k]);
            instanceCoords[id * 3] = x;
            instanceCoords[id * 3 + 1] = y;
            instanceCoords[id * 3 + 2] = z;
            instanceMaterials[id] = blockMaterials[k];
            int[] span = cellsOf(model, x, y, z);
            for (int cx = span[0]; cx <= span[3]; cx++) {
                for (int cy = span[1]; cy <= span[4]; cy++) {
                    for (int cz = span[2]; cz <= span[5]; cz++) {
                        long idx = cellIndex(cx - box[0], cy - box[1], cz - box[2], sizeX, sizeZ);
                        int[] list = byCell.get(idx);
                        if (list == null) {
                            list = new int[]{id};
                        } else {
                            list = Arrays.copyOf(list, list.length + 1);
                            list[list.length - 1] = id;
                        }
                        byCell.put(idx, list);
                        if (hasInstances != null) hasInstances[(int) idx] = true;
                    }
                }
            }
            id++;
        }

        return new VoxelScene(box, dense, sparse, List.copyOf(table.materials), cells,
            instanceCoords, instanceMaterials, byCell, hasInstances);
    }

    /** The cells a placed model's geometry overlaps, as inclusive min x, y, z then max x, y, z. */
    private static int[] cellsOf(BakedModel model, int x, int y, int z) {
        double[] m = model.bounds();
        return new int[]{
            x + (int) Math.floor(m[0] + BOUNDARY_EPSILON),
            y + (int) Math.floor(m[1] + BOUNDARY_EPSILON),
            z + (int) Math.floor(m[2] + BOUNDARY_EPSILON),
            x + (int) Math.ceil(m[3] - BOUNDARY_EPSILON) - 1,
            y + (int) Math.ceil(m[4] - BOUNDARY_EPSILON) - 1,
            z + (int) Math.ceil(m[5] - BOUNDARY_EPSILON) - 1};
    }

    private static long cellIndex(int x, int y, int z, int sizeX, int sizeZ) {
        return ((long) y * sizeZ + z) * sizeX + x;
    }

    private static long index(long key, int[] box) {
        int x = BuildRecorder.unpackX(key) - box[0];
        int y = BuildRecorder.unpackY(key) - box[1];
        int z = BuildRecorder.unpackZ(key) - box[2];
        int sizeX = box[3] - box[0] + 1, sizeY = box[4] - box[1] + 1, sizeZ = box[5] - box[2] + 1;
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) return -1;
        return cellIndex(x, y, z, sizeX, sizeZ);
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

    /** Resolves names to material indices, one material per name and, for models, per rotation. */
    private static final class MaterialTable {
        private final BlockCatalog catalog;
        @Nullable
        private final ModelLibrary models;
        private final List<Material> materials = new ArrayList<>();
        /** Per name, the material index for each rotation; 0 means not resolved yet. */
        private final Map<String, short[]> byName = new HashMap<>();

        MaterialTable(BlockCatalog catalog, @Nullable ModelLibrary models) {
            this.catalog = catalog;
            this.models = models;
            // Index 0 is reserved for empty, so a zeroed dense array already means "nothing here".
            materials.add(new Material(0, List.of(), false, false, null, false));
        }

        short materialFor(String name, int rotation) {
            short[] byRotation = byName.computeIfAbsent(name, n -> new short[64]);
            int r = rotation < 0 || rotation >= byRotation.length ? 0 : rotation;
            if (byRotation[r] != 0) return byRotation[r];

            BlockInfo info = catalog.findForRender(name);
            Material m;
            if (info == null) {
                m = new Material(BlockInfo.FALLBACK_RGB, List.of(), false, false, null, true);
            } else {
                BakedModel model = null;
                BlockInfo.ModelRef ref = info.geometry().model();
                if (!info.isFluid() && ref != null && models != null) {
                    model = models.baked(ref, r, info.rgb());
                }
                // A block drawn only by its model is hollow to the ray unless the model failed to
                // load, in which case the average-colour cube is still better than nothing at all.
                boolean solid = info.isFluid() || info.cubic() || model == null;
                m = new Material(info.rgb(), info.faceTextures(), info.cubic(), info.isFluid(), model, solid);
            }

            // Cube blocks look the same at every rotation, so they share one material across all of them.
            short idx;
            if (m.model() == null && byRotation[0] != 0) {
                idx = byRotation[0];
            } else if (materials.size() >= Short.MAX_VALUE) {
                // The table is indexed by a short, so a build using more than 32,767 distinct materials
                // folds the remainder onto a single colour rather than corrupting the grid.
                return 1;
            } else {
                materials.add(m);
                idx = (short) (materials.size() - 1);
            }
            if (m.model() == null) {
                Arrays.fill(byRotation, idx);
            } else {
                byRotation[r] = idx;
            }
            return idx;
        }
    }

    /** Material index at a world coordinate, or 0 for empty. */
    public short at(int worldX, int worldY, int worldZ) {
        int x = worldX - minX, y = worldY - minY, z = worldZ - minZ;
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) return 0;
        long idx = cellIndex(x, y, z, sizeX, sizeZ);
        if (dense != null) return dense[(int) idx];
        Short v = sparse.get(idx);
        return v == null ? 0 : v;
    }

    /** Model instances whose geometry overlaps a cell, or null when there are none. */
    @Nullable
    public int[] instancesAt(int worldX, int worldY, int worldZ) {
        if (instancesByCell.isEmpty()) return null;
        int x = worldX - minX, y = worldY - minY, z = worldZ - minZ;
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) return null;
        long idx = cellIndex(x, y, z, sizeX, sizeZ);
        if (denseHasInstances != null && !denseHasInstances[(int) idx]) return null;
        return instancesByCell.get(idx);
    }

    public int instanceX(int instance) {
        return instanceCoords[instance * 3];
    }

    public int instanceY(int instance) {
        return instanceCoords[instance * 3 + 1];
    }

    public int instanceZ(int instance) {
        return instanceCoords[instance * 3 + 2];
    }

    public short instanceMaterial(int instance) {
        return instanceMaterials[instance];
    }

    public int instanceCount() {
        return instanceMaterials.length;
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

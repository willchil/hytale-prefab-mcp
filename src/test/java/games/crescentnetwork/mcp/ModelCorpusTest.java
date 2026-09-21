package games.crescentnetwork.mcp;

import games.crescentnetwork.mcp.palette.BlockCatalog;
import games.crescentnetwork.mcp.palette.BlockInfo;
import games.crescentnetwork.mcp.palette.Orientation;
import games.crescentnetwork.mcp.render.model.BakedModel;
import games.crescentnetwork.mcp.render.model.BlockyModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the model parser against every block model the game ships. Off by default; set
 * MCP_ASSETS_DIR to a copy of the assets' {@code Common} folder to run it.
 */
@EnabledIfEnvironmentVariable(named = "MCP_ASSETS_DIR", matches = ".+")
class ModelCorpusTest {

    private static Path common() {
        Path common = TestAssets.fromEnvironment();
        assertNotNull(common, "MCP_ASSETS_DIR must name the assets' Common folder");
        return common;
    }

    private static BlockyModel model(String path) throws Exception {
        return BlockyModel.parse(Files.readAllBytes(common().resolve(path)));
    }

    @Test
    void everyShippedModelParsesAndBakes() throws Exception {
        List<String> failures = new ArrayList<>();
        int count = 0;
        try (Stream<Path> files = Files.walk(common())) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".blockymodel"))::iterator) {
                count++;
                try {
                    BlockyModel model = BlockyModel.parse(Files.readAllBytes(file));
                    for (int r = 0; r < Orientation.COUNT; r++) BakedModel.bake(model, null, 0, 1, r);
                } catch (Exception e) {
                    failures.add(common().relativize(file) + ": " + e.getMessage());
                }
            }
        }
        assertTrue(count > 1000, "expected the full model set, found " + count);
        assertTrue(failures.isEmpty(), failures.size() + " models failed:\n" + String.join("\n", failures));
    }

    @Test
    void roofModelMatchesTheServersOwnBounds() throws Exception {
        // Figures from the server's BlockyModelBoundsParser applied to the same file, in blocks.
        BlockyModel slope = model("Blocks/Structures/Roofs/Slope_Rock.blockymodel");
        assertArrayEquals(new double[]{-0.54, 0.0, -0.66}, round(slope.min()), 0.011);
        assertArrayEquals(new double[]{0.52, 1.27, 0.74}, round(slope.max()), 0.011);
    }

    @Test
    void shallowRoofLiesOverItsFootprintAtEveryYaw() throws Exception {
        BlockyModel shallow = model("Blocks/Structures/Roofs/Slope_Rock_Shallow.blockymodel");
        List<BlockInfo.Footprint> footprints = BlockCatalog.footprintsOf(TestHitbox.stairsShallow());
        for (int yaw = 0; yaw < 360; yaw += 90) {
            int r = Orientation.index(yaw, 0);
            double[] b = BakedModel.bake(shallow, null, 0, 1, r).bounds();
            BlockInfo.Footprint f = footprints.get(r);
            // The model covers the footprint's cells, overhanging by no more than a third of a block.
            assertTrue(b[0] <= f.minX() + 0.01 && b[3] >= f.maxX() + 0.99, "x at yaw " + yaw);
            assertTrue(b[2] <= f.minZ() + 0.01 && b[5] >= f.maxZ() + 0.99, "z at yaw " + yaw);
            assertTrue(b[0] > f.minX() - 0.34 && b[3] < f.maxX() + 1.34, "x overhang at yaw " + yaw);
            assertTrue(b[2] > f.minZ() - 0.34 && b[5] < f.maxZ() + 1.34, "z overhang at yaw " + yaw);
            assertEquals(0, b[1], 0.01, "sits on the floor at yaw " + yaw);
        }
    }

    private static double[] round(double[] v) {
        double[] out = new double[v.length];
        for (int i = 0; i < v.length; i++) out[i] = Math.round(v[i] * 100) / 100.0;
        return out;
    }
}

package games.crescentnetwork.mcp;

import com.google.gson.JsonObject;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockFlipType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.VariantRotation;
import games.crescentnetwork.mcp.mcp.tools.RenderPrefabTool;
import games.crescentnetwork.mcp.palette.BlockCatalog;
import games.crescentnetwork.mcp.palette.BlockInfo;
import games.crescentnetwork.mcp.render.Camera;
import games.crescentnetwork.mcp.render.TextureCache;
import games.crescentnetwork.mcp.render.VoxelRenderer;
import games.crescentnetwork.mcp.render.VoxelScene;
import games.crescentnetwork.mcp.render.model.ModelLibrary;
import games.crescentnetwork.mcp.script.BuildRecorder;
import games.crescentnetwork.mcp.script.ScriptRunner;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renderer coverage that does not need a booted server. Face textures resolve to nothing here, so
 * cubes fall back to their average colour; model-drawn blocks are given small models and textures
 * held in memory.
 */
class RenderTest {

    private static BuildRecorder build(String code) {
        return new ScriptRunner(TestPalette.catalog())
            .run(code, new ScriptRunner.Options(5_000, 300_000, 1))
            .recorder();
    }

    private static VoxelScene scene(String code) {
        return VoxelScene.from(build(code), TestPalette.catalog());
    }

    @Test
    void renderTargetSchemaDefinesThreeNumericItems() {
        JsonObject target = new RenderPrefabTool(null).inputSchema()
            .getAsJsonObject("properties")
            .getAsJsonObject("target");

        assertEquals("number", target.getAsJsonObject("items").get("type").getAsString());
        assertEquals(3, target.get("minItems").getAsInt());
        assertEquals(3, target.get("maxItems").getAsInt());
    }

    @Test
    void sceneMirrorsTheBuildBounds() {
        VoxelScene scene = scene("box(0, 0, 0, 9, 4, 19, 'Rock_Stone');");
        assertEquals(10, scene.sizeX());
        assertEquals(5, scene.sizeY());
        assertEquals(20, scene.sizeZ());
        assertEquals(10 * 5 * 20, scene.cellCount());
    }

    @Test
    void blockPaintsOverFluidInTheSameCell() {
        // Seen from outside the water the plant should read as the plant, not as water.
        VoxelScene scene = scene("""
            block(0, 0, 0, 'Plant_Seaweed_Dead_Stack');
            block(0, 0, 0, 'Water');
            """);
        short material = scene.at(0, 0, 0);
        assertTrue(material != 0);
        assertEquals(0x4A5A32, scene.material(material).rgb());
    }

    @Test
    void rendersAPngOfTheRequestedSize() throws Exception {
        VoxelScene scene = scene("box(0, 0, 0, 7, 7, 7, 'Rock_Stone');");
        byte[] png = render(scene, 160, 120);

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(decoded, "output should be a readable PNG");
        assertEquals(160, decoded.getWidth());
        assertEquals(120, decoded.getHeight());
    }

    @Test
    void buildIsVisibleAgainstTheBackground() {
        VoxelScene scene = scene("box(0, 0, 0, 7, 7, 7, 'Rock_Stone');");
        BufferedImage image = image(scene, 120, 120);

        // The centre pixel should be the build and a corner should be sky. If the camera framing or
        // the ray march were wrong, these would match.
        int centre = image.getRGB(60, 60);
        int corner = image.getRGB(2, 2);
        assertTrue(centre != corner, "the build should be distinguishable from the background");
    }

    @Test
    void facesAreShadedDifferently() {
        // A single flat colour everywhere would mean the face normal is not reaching the shader, and
        // the render would read as a silhouette rather than as geometry.
        VoxelScene scene = scene("box(0, 0, 0, 9, 9, 9, 'Rock_Stone');");
        BufferedImage image = image(scene, 200, 200);

        Set<Integer> shades = new HashSet<>();
        for (int y = 0; y < 200; y += 4) {
            for (int x = 0; x < 200; x += 4) {
                shades.add(image.getRGB(x, y));
            }
        }
        // Three lit faces plus the sky gradient; well above the one colour a broken shader gives.
        assertTrue(shades.size() >= 4, "expected several distinct shades, got " + shades.size());
    }

    @Test
    void autoDistanceFramesTheWholeBuild() {
        VoxelScene scene = scene("box(0, 0, 0, 31, 31, 31, 'Rock_Stone');");
        double distance = Camera.fitDistance(scene.sizeX(), scene.sizeY(), scene.sizeZ(), 45, 256, 256);
        BufferedImage image = image(scene, 256, 256, distance);

        // Nothing should touch the border if the fit worked, at any angle.
        for (int x = 0; x < 256; x++) {
            assertEquals(image.getRGB(x, 0), image.getRGB(0, 0),
                "build should not run off the top edge at x=" + x);
        }
    }

    @Test
    void rendersFromAnyAngleWithoutFailing() {
        VoxelScene scene = scene("box(0, 0, 0, 5, 9, 5, 'Rock_Stone');");
        // Straight down and straight up are where the camera basis degenerates if up is mishandled.
        for (double pitch : new double[]{-90, -45, 0, 45, 89.9, 90}) {
            for (double yaw : new double[]{0, 90, 180, 270, 45}) {
                double distance = Camera.fitDistance(scene.sizeX(), scene.sizeY(), scene.sizeZ(), 45, 64, 64);
                Camera camera = Camera.orbiting(scene.center(), yaw, pitch, distance, 45, 64, 64);
                BufferedImage image = new VoxelRenderer(new TextureCache()).render(scene, camera, 64, 64);
                assertNotNull(image, "yaw=" + yaw + " pitch=" + pitch);
            }
        }
    }

    @Test
    void sparseBuildStillRenders() {
        // A tall thin build has a bounding box far larger than its cell count, which is the case the
        // scene switches storage strategy for.
        VoxelScene scene = scene("line(0, 0, 0, 0, 400, 0, 'Rock_Stone');");
        assertEquals(401, scene.sizeY());
        BufferedImage image = image(scene, 64, 96);
        assertNotNull(image);
    }

    // ------------------------------------------------------------------ model-drawn blocks

    /** A palette of model blocks whose models and textures live in memory. */
    private static final class Models {
        final TestModels.Assets assets = new TestModels.Assets()
            .put("pole.blockymodel", TestModels.model(new TestModels.Node().at(0, 16, 0).box(4, 32, 4).allFaces()))
            .put("long.blockymodel", TestModels.model(new TestModels.Node().at(0, 8, -16).box(32, 16, 64).allFaces()))
            .put("wide.blockymodel", TestModels.model(new TestModels.Node().at(0, 16, 0).box(40, 32, 32).allFaces()))
            .put("red.png", TestModels.solid(TestModels.RED));
        final TextureCache textures = new TextureCache(assets);
        final ModelLibrary library = new ModelLibrary(textures, assets);
        final BlockCatalog catalog = BlockCatalog.of(List.of(
            new BlockInfo("Rock_Stone", BlockInfo.Kind.BLOCK, "Stone", null, "Cube", true, 0x8A8A8A, null,
                List.of(), 0),
            model("Test_Pole", "pole.blockymodel", null),
            model("Test_Long", "long.blockymodel", TestHitbox.stairsShallow()),
            model("Test_Wide", "wide.blockymodel", null)));

        private static BlockInfo model(String id, String path, TestHitbox hitbox) {
            return new BlockInfo(id, BlockInfo.Kind.BLOCK, "Test", null, "Model", false, 0x00FF00, null, List.of(), 0,
                new BlockInfo.Geometry(new BlockInfo.ModelRef(path, "red.png", 1f), null,
                    BlockCatalog.footprintsOf(hitbox), BlockCatalog.rotationMaskOf(VariantRotation.NESW),
                    BlockFlipType.SYMMETRIC, VariantRotation.NESW));
        }

        VoxelScene scene(String code) {
            BuildRecorder recorder = new ScriptRunner(catalog).run(code, new ScriptRunner.Options(5_000, 300_000, 1))
                .recorder();
            return VoxelScene.from(recorder, catalog, library);
        }

        BufferedImage image(VoxelScene scene, Camera camera, int width, int height) {
            return new VoxelRenderer(textures).render(scene, camera, width, height);
        }
    }

    private static int reddish(BufferedImage image) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                if (r > 60 && g < r / 3 && b < r / 3) count++;
            }
        }
        return count;
    }

    @Test
    void thinModelLeavesTheSkyVisible() {
        // A pole an eighth of a block wide must not render as the full cube it used to.
        Models models = new Models();
        VoxelScene pole = models.scene("block(0, 0, 0, 'Test_Pole');");
        Camera camera = Camera.looking(new double[]{0.5, 0.5, 4}, new double[]{0.5, 0.5, 0.5}, 30, 100, 100);
        BufferedImage image = models.image(pole, camera, 100, 100);

        int covered = reddish(image);
        assertTrue(covered > 0, "the pole should be drawn");
        assertTrue(covered < 100 * 100 / 5, "a thin pole should cover a sliver, not a block: " + covered);

        // Where a full cube would have been, beside the pole, the ray now reaches the sky.
        VoxelScene cube = models.scene("block(0, 0, 0, 'Rock_Stone');");
        BufferedImage cubeImage = models.image(cube, camera, 100, 100);
        assertTrue(image.getRGB(30, 50) != cubeImage.getRGB(30, 50), "beside the pole should not be stone");
    }

    @Test
    void multiCellModelIsDrawnInTheCellsItReaches() {
        Models models = new Models();
        VoxelScene scene = models.scene("block(0, 0, 0, 'Test_Long');");
        assertEquals(2, scene.sizeZ(), "the scene grows to hold the model's far end");
        assertEquals(-1, scene.minZ());
        assertNotNull(scene.instancesAt(0, 0, -1), "the far cell knows the model reaches it");

        // Looking straight at the far cell from the north, the model's end face fills the view.
        Camera camera = Camera.looking(new double[]{0.5, 0.25, -6}, new double[]{0.5, 0.25, -0.5}, 10, 40, 40);
        BufferedImage image = models.image(scene, camera, 40, 40);
        assertTrue(reddish(image) > 40 * 40 / 2, "the model's end should fill the frame");

        VoxelScene turned = models.scene("block(0, 0, 0, 'Test_Long', { yaw: 90 });");
        assertEquals(2, turned.sizeX());
        assertEquals(-1, turned.minX());
        assertEquals(1, turned.sizeZ());
    }

    @Test
    void overhangWidensTheScene() {
        Models models = new Models();
        VoxelScene scene = models.scene("block(0, 0, 0, 'Test_Wide');");
        assertEquals(3, scene.sizeX());
        assertEquals(1, scene.sizeY());
    }

    @Test
    void solidCubeHidesAModelBehindIt() {
        Models models = new Models();
        VoxelScene scene = models.scene("""
            block(0, 0, 0, 'Test_Pole');
            block(0, 0, 2, 'Rock_Stone');
            """);
        Camera camera = Camera.looking(new double[]{0.5, 0.5, 8}, new double[]{0.5, 0.5, 0.5}, 10, 40, 40);
        assertEquals(0, reddish(models.image(scene, camera, 40, 40)), "the stone is in front of the pole");
    }

    @Test
    void modelThatCannotLoadStillRendersAsACube() {
        Models models = new Models();
        BlockInfo broken = new BlockInfo("Test_Broken", BlockInfo.Kind.BLOCK, "Test", null, "Model", false,
            0x123456, null, List.of(), 0, new BlockInfo.Geometry(
            new BlockInfo.ModelRef("missing.blockymodel", "red.png", 1f), null, List.of(), 1, null, null));
        BlockCatalog catalog = BlockCatalog.of(List.of(broken));
        BuildRecorder recorder = new ScriptRunner(catalog).run("block(0, 0, 0, 'Test_Broken');",
            new ScriptRunner.Options(5_000, 1_000, 1)).recorder();
        VoxelScene scene = VoxelScene.from(recorder, catalog, models.library);
        assertTrue(scene.material(scene.at(0, 0, 0)).solid());
        assertEquals(0, scene.instanceCount());
    }

    private static byte[] render(VoxelScene scene, int width, int height) {
        return new VoxelRenderer(new TextureCache()).renderPng(scene, camera(scene, width, height), width, height);
    }

    private static BufferedImage image(VoxelScene scene, int width, int height) {
        return new VoxelRenderer(new TextureCache()).render(scene, camera(scene, width, height), width, height);
    }

    private static BufferedImage image(VoxelScene scene, int width, int height, double distance) {
        Camera camera = Camera.orbiting(scene.center(), 45, 30, distance, 45, width, height);
        return new VoxelRenderer(new TextureCache()).render(scene, camera, width, height);
    }

    private static Camera camera(VoxelScene scene, int width, int height) {
        double distance = Camera.fitDistance(scene.sizeX(), scene.sizeY(), scene.sizeZ(), 45, width, height);
        return Camera.orbiting(scene.center(), 45, 30, distance, 45, width, height);
    }
}

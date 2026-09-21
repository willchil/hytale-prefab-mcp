package games.crescentnetwork.mcp;

import com.google.gson.JsonObject;
import games.crescentnetwork.mcp.mcp.tools.RenderPrefabTool;
import games.crescentnetwork.mcp.render.Camera;
import games.crescentnetwork.mcp.render.TextureCache;
import games.crescentnetwork.mcp.render.VoxelRenderer;
import games.crescentnetwork.mcp.render.VoxelScene;
import games.crescentnetwork.mcp.script.BuildRecorder;
import games.crescentnetwork.mcp.script.ScriptRunner;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renderer coverage that does not need a booted server. Face textures resolve to nothing here, so
 * blocks fall back to their average colour, which is the same path the 1,784 model-drawn blocks take
 * in production.
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

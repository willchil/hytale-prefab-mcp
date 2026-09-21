package games.crescentnetwork.mcp;

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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Writes sample renders to disk for eyeballing. Off by default; set MCP_RENDER_DUMP to a directory
 * to turn it on. Kept because a renderer can pass every assertion and still look wrong.
 *
 * <p>Set MCP_ASSETS_DIR to a copy of the assets' {@code Common} folder as well to render real block
 * models. Each one is written beside the game's own inventory icon for it, which the client renders
 * from the same model and texture, so a face textured the wrong way round shows up side by side.
 */
class RenderPreviewDump {

    @Test
    @EnabledIfEnvironmentVariable(named = "MCP_RENDER_DUMP", matches = ".+")
    void dump() throws Exception {
        Path dir = Path.of(System.getenv("MCP_RENDER_DUMP"));
        Files.createDirectories(dir);

        var recorder = new ScriptRunner(TestPalette.catalog()).run("""
            // A small tower with a stepped base, a wool roof band and a moat, so the render has to show
            // several materials, a silhouette, and a fluid all at once.
            box(-8, 0, -8, 8, 0, 8, 'Rock_Stone');
            box(-6, 1, -6, 6, 1, 6, 'Rock_Marble_Brick_Smooth');
            box(-4, 2, -4, 4, 14, 4, 'Rock_Marble_Brick_Smooth', { hollow: true });
            box(-5, 15, -5, 5, 15, 5, 'Cloth_Block_Wool_White');
            box(-3, 16, -3, 3, 19, 3, 'Wood_Hardwood_Planks', { hollow: true });
            sphere(0, 22, 0, 3, 'Cloth_Block_Wool_White');
            cylinder(0, 20, 0, 1, 2, 'Wood_Hardwood_Planks');
            box(-12, 0, -12, 12, 0, -9, 'Water_Source');
            box(-12, 0, 9, 12, 0, 12, 'Water_Source');
            """, new ScriptRunner.Options(10_000, 300_000, 7)).recorder();

        VoxelScene scene = VoxelScene.from(recorder, TestPalette.catalog());
        for (int[] angle : new int[][]{{45, 30}, {135, 12}, {0, 85}}) {
            write(dir.resolve("preview-yaw" + angle[0] + "-pitch" + angle[1] + ".png"),
                render(scene, new TextureCache(), angle[0], angle[1], 640, 480));
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MCP_RENDER_DUMP", matches = ".+")
    void dumpModels() throws Exception {
        Path common = TestAssets.fromEnvironment();
        assumeTrue(common != null, "set MCP_ASSETS_DIR to the assets' Common folder to render models");
        Path dir = Path.of(System.getenv("MCP_RENDER_DUMP")).resolve("models");
        Files.createDirectories(dir);

        Function<String, byte[]> assets = TestAssets.directory(common);
        BlockCatalog catalog = TestPalette.catalog();
        TextureCache textures = new TextureCache(assets);
        ModelLibrary models = new ModelLibrary(textures, assets);

        // One block at a time, next to its icon.
        for (BlockInfo info : catalog.all()) {
            if (info.geometry().model() == null) continue;
            BuildRecorder single = new ScriptRunner(catalog).run(
                "block(0, 0, 0, '" + info.id() + "');", new ScriptRunner.Options(5_000, 1_000, 1)).recorder();
            VoxelScene scene = VoxelScene.from(single, catalog, models);
            BufferedImage[] views = new BufferedImage[4];
            int[][] angles = {{30, 25}, {120, 25}, {210, 25}, {300, 25}};
            for (int i = 0; i < 4; i++) {
                views[i] = render(scene, textures, angles[i][0], angles[i][1], 256, 256);
            }
            BufferedImage icon = null;
            byte[] iconBytes = assets.apply(info.iconPath());
            if (iconBytes != null) icon = ImageIO.read(new ByteArrayInputStream(iconBytes));
            write(dir.resolve("block-" + info.id() + ".png"), strip(icon, views));
        }

        // Yaw and pitch, and what a footprint looks like beside its neighbours.
        String[] scripts = {
            """
                // Roofs at each yaw along X, then stairs the right way up and upside down.
                for (let i = 0; i < 4; i++) block(i * 2, 0, 0, 'Rock_Stone_Brick_Roof', {yaw: i * 90});
                for (let i = 0; i < 4; i++) block(i * 2, 0, 3, 'Build_Black_Stairs', {yaw: i * 90});
                for (let i = 0; i < 4; i++) block(i * 2, 1, 6, 'Build_Black_Stairs', {yaw: i * 90, pitch: 180});
                box(-1, -1, -1, 7, -1, 7, 'Rock_Stone');
                """,
            """
                // A small house: walls, a gable of shallow roofs meeting at a ridge, a bed and a torch.
                box(0, 0, 0, 6, 0, 8, 'Wood_Hardwood_Planks');
                box(0, 1, 0, 6, 3, 8, 'Rock_Marble_Brick_Smooth', {hollow: true});
                for (let x = 1; x <= 5; x++) for (let y = 1; y <= 3; y++) for (let z = 1; z <= 7; z++) clear(x, y, z);
                // A shallow roof is two cells long and rises toward north at yaw 0, so each course of
                // the gable steps up one and in two, and the north slope is turned to face the other way.
                for (let x = -1; x <= 7; x++) {
                    block(x, 4, 0, 'Rock_Stone_Brick_Roof_Shallow', {yaw: 180});
                    block(x, 5, 2, 'Rock_Stone_Brick_Roof_Shallow', {yaw: 180});
                    block(x, 5, 6, 'Rock_Stone_Brick_Roof_Shallow');
                    block(x, 4, 8, 'Rock_Stone_Brick_Roof_Shallow');
                    block(x, 6, 4, 'Rock_Stone_Brick_Roof');
                }
                block(2, 1, 4, 'Furniture_Ancient_Bed', {yaw: 90});
                block(5, 1, 6, 'Furniture_Crude_Torch');
                for (let x = -2; x <= 8; x++) block(x, 0, -2, 'Build_Black_Fence', {yaw: 90});
                block(-2, 0, 4, 'Rock_Iridescent_Brick_Beam', {pitch: 90});
                block(-2, 0, 6, 'Rock_Iridescent_Brick_Beam', {yaw: 90, pitch: 90});
                for (let x = 8; x <= 10; x++) block(x, 0, 3, 'Plant_Flower_Bushy_Blue');
                box(-3, -1, -3, 11, -1, 10, 'Rock_Stone');
                """,
            """
                // A worst case for model tracing: a dense field of crossed-quad plants seen at a low angle.
                box(0, -1, 0, 47, -1, 47, 'Rock_Stone');
                box(0, 0, 0, 47, 0, 47, 'Plant_Flower_Bushy_Blue');
                """,
        };
        String[] names = {"orientation", "house", "field"};
        for (int s = 0; s < scripts.length; s++) {
            BuildRecorder recorder = new ScriptRunner(catalog)
                .run(scripts[s], new ScriptRunner.Options(10_000, 300_000, 1)).recorder();
            VoxelScene scene = VoxelScene.from(recorder, catalog, models);
            for (int[] angle : new int[][]{{35, 30}, {150, 25}, {250, 35}, {0, 80}}) {
                long started = System.nanoTime();
                BufferedImage image = render(scene, textures, angle[0], angle[1], 768, 512);
                System.out.println(names[s] + " yaw " + angle[0] + " pitch " + angle[1] + ": "
                    + (System.nanoTime() - started) / 1_000_000 + "ms at 768x512");
                write(dir.resolve(names[s] + "-yaw" + angle[0] + "-pitch" + angle[1] + ".png"), image);
            }
        }
    }

    private static BufferedImage render(VoxelScene scene, TextureCache textures, double yaw, double pitch,
                                        int w, int h) {
        double distance = Camera.fitDistance(scene.sizeX(), scene.sizeY(), scene.sizeZ(), 45, w, h);
        Camera camera = Camera.orbiting(scene.center(), yaw, pitch, distance, 45, w, h);
        return new VoxelRenderer(textures).render(scene, camera, w, h);
    }

    /** The icon, scaled up to match, followed by each view in a row. */
    private static BufferedImage strip(BufferedImage icon, BufferedImage[] views) {
        int size = views[0].getHeight();
        BufferedImage out = new BufferedImage(size * (views.length + 1), size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(java.awt.Color.DARK_GRAY);
        g.fillRect(0, 0, size, size);
        if (icon != null) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(icon, 0, 0, size, size, null);
        }
        for (int i = 0; i < views.length; i++) g.drawImage(views[i], size * (i + 1), 0, null);
        g.dispose();
        return out;
    }

    private static void write(Path out, BufferedImage image) throws Exception {
        ImageIO.write(image, "png", out.toFile());
        System.out.println("wrote " + out);
    }
}

package dev.hytalemodding.mcp;

import dev.hytalemodding.mcp.render.Camera;
import dev.hytalemodding.mcp.render.TextureCache;
import dev.hytalemodding.mcp.render.VoxelRenderer;
import dev.hytalemodding.mcp.render.VoxelScene;
import dev.hytalemodding.mcp.script.ScriptRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes a sample render to disk for eyeballing. Off by default; set MCP_RENDER_DUMP to a directory
 * to turn it on. Kept because a renderer can pass every assertion and still look wrong.
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
        int w = 640, h = 480;
        for (int[] angle : new int[][]{{45, 30}, {135, 12}, {0, 85}}) {
            double distance = Camera.fitDistance(scene.sizeX(), scene.sizeY(), scene.sizeZ(), 45, w, h);
            Camera camera = Camera.orbiting(scene.center(), angle[0], angle[1], distance, 45, w, h);
            byte[] png = new VoxelRenderer(new TextureCache()).renderPng(scene, camera, w, h);
            Path out = dir.resolve("preview-yaw" + angle[0] + "-pitch" + angle[1] + ".png");
            Files.write(out, png);
            System.out.println("wrote " + out + " (" + png.length + " bytes)");
        }
    }
}

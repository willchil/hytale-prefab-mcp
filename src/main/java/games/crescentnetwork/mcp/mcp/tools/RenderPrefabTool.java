package games.crescentnetwork.mcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import games.crescentnetwork.mcp.http.JsonRpc;
import games.crescentnetwork.mcp.mcp.McpServices;
import games.crescentnetwork.mcp.mcp.McpTool;
import games.crescentnetwork.mcp.palette.BlockCatalog;
import games.crescentnetwork.mcp.prefab.PrefabLoader;
import games.crescentnetwork.mcp.prefab.PrefabWriter;
import games.crescentnetwork.mcp.render.Camera;
import games.crescentnetwork.mcp.render.VoxelRenderer;
import games.crescentnetwork.mcp.render.VoxelScene;
import games.crescentnetwork.mcp.script.BuildRecorder;
import games.crescentnetwork.mcp.script.ScriptError;

/** Renders a saved prefab to a PNG from a camera the caller chooses. */
public final class RenderPrefabTool implements McpTool {

    private static final int DEFAULT_WIDTH = 768;
    private static final int DEFAULT_HEIGHT = 512;
    private static final int MAX_DIMENSION = 1600;
    /** Guards against a request that would take minutes and megabytes to answer. */
    private static final int MAX_PIXELS = 1_600_000;

    private final McpServices services;

    public RenderPrefabTool(McpServices services) {
        this.services = services;
    }

    @Override
    public String name() {
        return "render_prefab";
    }

    @Override
    public String description() {
        return """
            Render a saved prefab to an image so you can see what you actually built and fix what \
            looks wrong.

            The camera orbits the build: yaw turns around it, pitch raises the viewpoint, and the \
            distance is solved automatically to frame the whole thing unless you set one. Render a few \
            angles before deciding a build is finished, since silhouettes hide a lot.

            Rendering happens on the server with a plain raycaster. Cubic blocks show their real face \
            textures; blocks drawn from a custom model have no face texture and appear as solid cubes \
            in their average colour, so thin things like ropes, torches and plants look chunkier here \
            than in game. Treat it as a massing and proportion check, not a screenshot.""";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject schema = Schema.object();
        Schema.prop(schema, "name", "string",
            "Prefab name. Defaults to the most recent build from this session.");
        JsonObject yaw = Schema.prop(schema, "yaw", "number",
            "Degrees around the build. 0 looks along +Z; 45 gives a three-quarter view.");
        Schema.defaultValue(yaw, 45);
        JsonObject pitch = Schema.prop(schema, "pitch", "number",
            "Degrees above the horizon. 0 is eye level, 90 is directly overhead.");
        Schema.defaultValue(pitch, 30);
        Schema.prop(schema, "distance", "number",
            "Camera distance. Omit to frame the whole build automatically.");
        JsonObject fov = Schema.prop(schema, "fov", "number", "Vertical field of view in degrees.");
        Schema.defaultValue(fov, 45);
        JsonObject width = Schema.prop(schema, "width", "integer", "Image width in pixels.");
        Schema.defaultValue(width, DEFAULT_WIDTH);
        JsonObject height = Schema.prop(schema, "height", "integer", "Image height in pixels.");
        Schema.defaultValue(height, DEFAULT_HEIGHT);
        Schema.prop(schema, "target", "array",
            "Optional [x, y, z] to aim at. Defaults to the centre of the build.");
        return schema;
    }

    @Override
    public ToolResult call(JsonObject arguments) {
        BlockCatalog catalog = services.catalog();
        if (catalog == null) {
            return ToolResult.failure("The block palette is not loaded yet; the server is still booting.");
        }

        String requested = JsonRpc.stringOr(arguments, "name", null);
        if (requested == null || requested.isBlank()) {
            requested = services.lastBuildName();
            if (requested == null) {
                return ToolResult.failure(
                    "No prefab name given and nothing has been built this session. Pass name.");
            }
        }

        String cleaned;
        BuildRecorder recorder;
        try {
            cleaned = PrefabWriter.cleanName(requested);
            // A build from this session is already in memory; anything else is read back from disk,
            // so prefabs made earlier or by the in-game editor can be inspected too.
            BuildRecorder cached = services.recentBuild(cleaned);
            recorder = cached != null ? cached : PrefabLoader.load(cleaned);
        } catch (ScriptError e) {
            return ToolResult.failure(BuildPrefabTool.format(e));
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResult.failure("Could not load prefab: " + message);
        }

        if (recorder.isEmpty()) {
            return ToolResult.failure("Prefab \"" + cleaned + "\" contains no blocks to render.");
        }

        int width = clampDimension(JsonRpc.intOr(arguments, "width", DEFAULT_WIDTH), DEFAULT_WIDTH);
        int height = clampDimension(JsonRpc.intOr(arguments, "height", DEFAULT_HEIGHT), DEFAULT_HEIGHT);
        if ((long) width * height > MAX_PIXELS) {
            return ToolResult.failure("width x height exceeds " + MAX_PIXELS
                + " pixels. Ask for a smaller image.");
        }

        VoxelScene scene = VoxelScene.from(recorder, catalog);
        double[] target = parseTarget(arguments, scene);

        double yaw = JsonRpc.doubleOr(arguments, "yaw", 45);
        double pitch = JsonRpc.doubleOr(arguments, "pitch", 30);
        double fov = JsonRpc.doubleOr(arguments, "fov", 45);
        double distance = JsonRpc.doubleOr(arguments, "distance", Double.NaN);
        boolean autoDistance = Double.isNaN(distance) || distance <= 0;
        if (autoDistance) {
            distance = Camera.fitDistance(scene.sizeX(), scene.sizeY(), scene.sizeZ(), fov, width, height);
        }

        Camera camera = Camera.orbiting(target, yaw, pitch, distance, fov, width, height);
        VoxelRenderer renderer = new VoxelRenderer(services.textures());

        byte[] png;
        long startedNanos = System.nanoTime();
        try {
            png = renderer.renderPng(scene, camera, width, height);
        } catch (RuntimeException | OutOfMemoryError e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResult.failure("Render failed: " + message);
        }
        long millis = (System.nanoTime() - startedNanos) / 1_000_000L;

        String caption = cleaned
            + " - yaw " + trim(yaw) + " deg, pitch " + trim(pitch) + " deg, distance " + trim(distance)
            + (autoDistance ? " (auto)" : "")
            + "\nbuild " + scene.sizeX() + " x " + scene.sizeY() + " x " + scene.sizeZ()
            + ", " + scene.cellCount() + " cells, rendered " + width + "x" + height + " in " + millis + "ms";
        return ToolResult.image(png, "image/png", caption);
    }

    private static double[] parseTarget(JsonObject arguments, VoxelScene scene) {
        if (arguments != null && arguments.has("target") && arguments.get("target").isJsonArray()) {
            JsonArray array = arguments.getAsJsonArray("target");
            if (array.size() >= 3) {
                try {
                    return new double[]{
                        array.get(0).getAsDouble(), array.get(1).getAsDouble(), array.get(2).getAsDouble()};
                } catch (RuntimeException ignored) {
                    // Fall through to the build centre rather than failing the whole render.
                }
            }
        }
        return scene.center();
    }

    private static int clampDimension(int value, int fallback) {
        if (value <= 0) return fallback;
        return Math.min(value, MAX_DIMENSION);
    }

    private static String trim(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.format("%.1f", value);
    }
}

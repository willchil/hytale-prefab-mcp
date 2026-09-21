package games.crescentnetwork.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import games.crescentnetwork.mcp.mcp.McpCaller;
import games.crescentnetwork.mcp.mcp.McpServices;
import games.crescentnetwork.mcp.mcp.McpTool;
import games.crescentnetwork.mcp.mcp.tools.GetBlockTextureTool;
import games.crescentnetwork.mcp.mcp.tools.SearchBlocksTool;
import games.crescentnetwork.mcp.palette.Orientation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the agent reads about a block's size and orientation. */
class ToolOutputTest {

    private static McpServices services() {
        McpServices services = new McpServices();
        services.setCatalog(TestPalette.catalog());
        return services;
    }

    private static String text(McpTool.ToolResult result) {
        StringBuilder out = new StringBuilder();
        for (JsonElement part : result.content()) {
            JsonObject o = part.getAsJsonObject();
            if (o.has("text")) out.append(o.get("text").getAsString()).append('\n');
        }
        return out.toString();
    }

    private static String call(McpTool tool, String key, String value) {
        JsonObject args = new JsonObject();
        args.addProperty(key, value);
        return text(tool.call(args, McpCaller.ANONYMOUS));
    }

    @Test
    void searchShowsHowManyCellsABlockFills() {
        String out = call(new SearchBlocksTool(services()), "query", "roof");
        assertTrue(out.contains("|  cells  |"), out);
        assertTrue(out.contains("Rock_Stone_Brick_Roof_Shallow  |  block  |  Roof  |  Model  |  1x1x2  |"), out);
        assertTrue(out.contains("Rock_Stone_Brick_Roof  |  block  |  Roof  |  Model  |  1  |"), out);
    }

    @Test
    void blockDetailsListTheFootprintAtEachYaw() {
        String out = call(new GetBlockTextureTool(services()), "name", "Rock_Stone_Brick_Roof_Shallow");
        assertTrue(out.contains("size: 1x1x2 cells at yaw 0 (hitbox Stairs_Shallow)"), out);
        assertTrue(out.contains("yaw 0: x 0, y 0, z -1..0 (extends 1 north (-Z))"), out);
        assertTrue(out.contains("yaw 90: x -1..0, y 0, z 0 (extends 1 west (-X))"), out);
        assertTrue(out.contains("yaw 180: x 0, y 0, z 0..1 (extends 1 south (+Z))"), out);
        assertTrue(out.contains("yaw 270: x 0..1, y 0, z 0 (extends 1 east (+X))"), out);
        assertTrue(out.contains("rotations: yaw 0/90/180/270."), out);
    }

    @Test
    void blockDetailsForAnOrdinaryBlock() {
        String out = call(new GetBlockTextureTool(services()), "name", "Rock_Stone");
        assertTrue(out.contains("size: 1 cell."), out);
        assertTrue(out.contains("rotations: no rotation (yaw 0, pitch 0 only)"), out);
    }

    @Test
    void blockDetailsForAFluid() {
        String out = call(new GetBlockTextureTool(services()), "name", "Water_Source");
        assertTrue(out.contains("Fluids share cells with blocks"), out);
    }

    @Test
    void rotationsAreDescribedTheWayScriptsPassThem() {
        assertEquals("yaw 0/90/180/270 with pitch 0 or 180", Orientation.describe(List.of(0, 1, 2, 3, 8, 9, 10, 11)));
        assertEquals("pitch 0 with yaw 0; pitch 90 with yaw 0/90", Orientation.describe(List.of(0, 4, 5)));
        assertEquals("yaw 0/90", Orientation.describe(List.of(0, 1)));
        assertEquals("no rotation (yaw 0, pitch 0 only)", Orientation.describe(List.of(0)));
    }

    @Test
    void rolledRotationsFoldIntoYawAndPitch() {
        // A half roll with a half yaw is the same turn as upside down.
        assertEquals(Orientation.index(0, 180), Orientation.withoutRoll(34));
        assertEquals(5, Orientation.withoutRoll(5));
        // A quarter roll alone cannot be written with yaw and pitch.
        assertEquals(-1, Orientation.withoutRoll(16));
    }
}

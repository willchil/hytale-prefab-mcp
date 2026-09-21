package games.crescentnetwork.mcp;

import games.crescentnetwork.mcp.palette.BlockCatalog;
import games.crescentnetwork.mcp.palette.BlockInfo;

import java.util.List;

/**
 * A stand-in palette using real Hytale names, so tests exercise the script engine and the geometry
 * helpers without needing a booted server behind them.
 */
public final class TestPalette {

    private TestPalette() {
    }

    public static BlockCatalog catalog() {
        return BlockCatalog.of(List.of(
            block("Rock_Stone", "Stone", "Cube", 0x8A8A8A),
            block("Rock_Marble_Brick_Smooth", "Stone", "Cube", 0xD5D4D1),
            block("Wood_Hardwood_Planks", "Wood", "Cube", 0x5B3822),
            block("Cloth_Block_Wool_White", "Cloth", "Cube", 0xF0F0F0),
            block("Plant_Seaweed_Dead_Stack", "Plant", "Model", 0x4A5A32),
            block("Bench_Alchemy", "Bench", "Model", 0x484736),
            fluid("Water_Source", 1),
            fluid("Water", 8),
            fluid("Lava_Source", 1)));
    }

    private static BlockInfo block(String id, String group, String drawType, int rgb) {
        return new BlockInfo(id, BlockInfo.Kind.BLOCK, group, "Hytale:Hytale", drawType,
            "Cube".equals(drawType), rgb, "Icons/ItemsGenerated/" + id + ".png", List.of(), 0);
    }

    private static BlockInfo fluid(String id, int defaultLevel) {
        return new BlockInfo(id, BlockInfo.Kind.FLUID, "Fluid", "Hytale:Hytale", "Fluid",
            false, 0x2F5FA8, null, List.of(), defaultLevel);
    }
}

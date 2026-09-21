package games.crescentnetwork.mcp;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockFlipType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.VariantRotation;
import games.crescentnetwork.mcp.palette.BlockCatalog;
import games.crescentnetwork.mcp.palette.BlockInfo;

import java.util.List;

/**
 * A stand-in palette using real Hytale names, so tests exercise the script engine and the geometry
 * helpers without needing a booted server behind them.
 *
 * <p>The model-drawn entries point at the real shipped model and texture paths. Nothing resolves them
 * in an ordinary test run, so they fall back to average-colour cubes there, but a test that supplies
 * an asset reader, or the preview dump pointed at a copy of the assets, renders them properly.
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
            model("Rock_Stone_Brick_Roof", "Roof", 0x5D5C49,
                "Blocks/Structures/Roofs/Slope_Rock.blockymodel",
                "Blocks/Structures/Roofs/Slope_Rock_Textures/Stone_Brick.png", 1f,
                null, VariantRotation.NESW, BlockFlipType.SYMMETRIC),
            model("Rock_Stone_Brick_Roof_Shallow", "Roof", 0x5D5C49,
                "Blocks/Structures/Roofs/Slope_Rock_Shallow.blockymodel",
                "Blocks/Structures/Roofs/Slope_Rock_Textures/Stone_Brick.png", 1f,
                TestHitbox.stairsShallow(), VariantRotation.NESW, BlockFlipType.SYMMETRIC),
            model("Rock_Iridescent_Brick_Beam", "Rock", 0x7A6F8E,
                "Blocks/Structures/Tubes/Long_Simple_UV.blockymodel",
                "Blocks/Structures/Tubes/Long_Simple_UV_Textures/Iridescent_Brick.png", 1f,
                null, VariantRotation.Pipe, BlockFlipType.SYMMETRIC),
            model("Plant_Flower_Bushy_Blue", "Plant", 0x4D6FA8,
                "Blocks/Foliage/Plants/Nettle.blockymodel",
                "Blocks/Foliage/Plants/Nettle_Textures/Blue.png", 1f,
                null, null, BlockFlipType.SYMMETRIC),
            model("Furniture_Ancient_Bed", "Furniture", 0x8C6D4E,
                "Blocks/Decorative_Sets/Ancient/Bed.blockymodel",
                "Blocks/Decorative_Sets/Ancient/Bed_Texture.png", 1f,
                TestHitbox.bed(), VariantRotation.NESW, BlockFlipType.SYMMETRIC),
            model("Build_Black_Fence", "Fence", 0x2A2A2A,
                "Blocks/Structures/Fences/Dev_Fence.blockymodel",
                "Blocks/Structures/Fences/Dev_Fence_Texture/Dev_Black_Fence.png", 1f,
                null, VariantRotation.Wall, BlockFlipType.SYMMETRIC),
            model("Furniture_Crude_Torch", "Furniture", 0x6B4A2B,
                "Items/Torch/Torch_New.blockymodel",
                "Items/Torch/Torch_New_Texture.png", 1f,
                null, VariantRotation.NESW, BlockFlipType.SYMMETRIC),
            model("Build_Black_Stairs", "Stairs", 0x2A2A2A,
                "Blocks/Structures/Stairs/Stairs_SimpleUV.blockymodel",
                "Blocks/Structures/Stairs/Stairs_Textures/Dev_Black.png", 1f,
                null, VariantRotation.UpDownNESW, BlockFlipType.SYMMETRIC),
            fluid("Water_Source", 1),
            fluid("Water", 8),
            fluid("Lava_Source", 1)));
    }

    private static BlockInfo block(String id, String group, String drawType, int rgb) {
        return new BlockInfo(id, BlockInfo.Kind.BLOCK, group, "Hytale:Hytale", drawType,
            "Cube".equals(drawType), rgb, "Icons/ItemsGenerated/" + id + ".png", List.of(), 0);
    }

    private static BlockInfo model(String id, String group, int rgb, String modelPath, String texturePath,
                                   float scale, TestHitbox hitbox, VariantRotation variants,
                                   BlockFlipType flipType) {
        BlockInfo.Geometry geometry = new BlockInfo.Geometry(
            new BlockInfo.ModelRef(modelPath, texturePath, scale),
            hitbox == null ? null : hitbox.getId(),
            BlockCatalog.footprintsOf(hitbox),
            BlockCatalog.rotationMaskOf(variants),
            flipType,
            variants);
        return new BlockInfo(id, BlockInfo.Kind.BLOCK, group, "Hytale:Hytale", "Model", false, rgb,
            "Icons/ItemsGenerated/" + id + ".png", List.of(), 0, geometry);
    }

    private static BlockInfo fluid(String id, int defaultLevel) {
        return new BlockInfo(id, BlockInfo.Kind.FLUID, "Fluid", "Hytale:Hytale", "Fluid",
            false, 0x2F5FA8, null, List.of(), defaultLevel);
    }
}

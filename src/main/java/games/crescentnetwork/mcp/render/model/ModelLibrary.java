package games.crescentnetwork.mcp.render.model;

import games.crescentnetwork.mcp.palette.AssetLocator;
import games.crescentnetwork.mcp.palette.BlockInfo;
import games.crescentnetwork.mcp.render.TextureCache;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Parsed and baked block models, kept between renders.
 *
 * <p>A scene draws the same few models thousands of times, so a model is parsed once per path and
 * baked once per rotation. Failures are cached as well, so a missing or malformed model is looked at
 * once and then drawn as a plain cube in its average colour from then on. Cleared with the texture
 * cache whenever assets reload.
 */
public final class ModelLibrary {

    private record BakeKey(BlockInfo.ModelRef ref, int rotation, int fallbackRgb) {
    }

    private final TextureCache textures;
    private final Function<String, byte[]> assets;
    private final Map<String, Optional<BlockyModel>> models = new ConcurrentHashMap<>();
    private final Map<BakeKey, Optional<BakedModel>> baked = new ConcurrentHashMap<>();

    public ModelLibrary(TextureCache textures) {
        this(textures, AssetLocator::readCommonAsset);
    }

    /** @param assets reads a {@code Common/}-relative path, returning null when it does not exist */
    public ModelLibrary(TextureCache textures, Function<String, byte[]> assets) {
        this.textures = textures;
        this.assets = assets;
    }

    /** The parsed model at a {@code Common/}-relative path, or null when it is missing or unreadable. */
    @Nullable
    public BlockyModel model(@Nullable String path) {
        if (path == null || path.isBlank()) return null;
        return models.computeIfAbsent(path, this::load).orElse(null);
    }

    /**
     * A block's model placed in its cell at a rotation.
     *
     * @param fallbackRgb colour to draw with when the model's texture cannot be read
     * @return null when the model cannot be read or has no drawable shapes
     */
    @Nullable
    public BakedModel baked(BlockInfo.ModelRef ref, int rotation, int fallbackRgb) {
        return baked.computeIfAbsent(new BakeKey(ref, rotation, fallbackRgb), key -> {
            BlockyModel model = model(ref.modelPath());
            if (model == null || model.isEmpty()) return Optional.empty();
            TextureCache.Texture texture = textures.get(ref.texturePath());
            BakedModel b = BakedModel.bake(model, texture, fallbackRgb, ref.scale(), rotation);
            return b.isEmpty() ? Optional.empty() : Optional.of(b);
        }).orElse(null);
    }

    private Optional<BlockyModel> load(String path) {
        try {
            byte[] bytes = assets.apply(path);
            if (bytes == null) return Optional.empty();
            return Optional.of(BlockyModel.parse(bytes));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public void clear() {
        models.clear();
        baked.clear();
    }
}

package games.crescentnetwork.mcp.render;

import games.crescentnetwork.mcp.palette.AssetLocator;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Decoded block textures, kept around between renders.
 *
 * <p>Block textures are small (mostly 16x16 to 128x128) and a scene reuses the same handful across
 * millions of ray hits, so decoding once and holding the pixels as a flat int array is worth it.
 * Misses are cached too, so an asset that is not there is only looked for once.
 *
 * <p>Alpha is kept. Cube faces ignore it, but model textures depend on it: a plant is a pair of
 * crossed quads whose texture is mostly transparent, and without the cut-out it renders as two
 * solid squares.
 */
public final class TextureCache {

    /** A decoded texture as packed ARGB, row-major. */
    public record Texture(int width, int height, int[] pixels) {
        /** Opaque RGB at normalised coordinates, clamped to the edge. For cube faces. */
        public int sample(double u, double v) {
            return texel((int) (u * width), (int) (v * height)) & 0xFFFFFF;
        }

        /** ARGB at a texel coordinate, clamped to the edge. */
        public int texel(int x, int y) {
            if (x < 0) x = 0;
            else if (x >= width) x = width - 1;
            if (y < 0) y = 0;
            else if (y >= height) y = height - 1;
            return pixels[y * width + x];
        }
    }

    /** Sentinel for "looked, found nothing", so a miss is not retried. */
    private static final Texture MISSING = new Texture(0, 0, new int[0]);

    private final Map<String, Texture> cache = new ConcurrentHashMap<>();
    private final Function<String, byte[]> assets;

    public TextureCache() {
        this(AssetLocator::readCommonAsset);
    }

    /** @param assets reads a {@code Common/}-relative path, returning null when it does not exist */
    public TextureCache(Function<String, byte[]> assets) {
        this.assets = assets;
    }

    @Nullable
    public Texture get(@Nullable String commonRelativePath) {
        if (commonRelativePath == null || commonRelativePath.isBlank()) return null;
        Texture cached = cache.computeIfAbsent(commonRelativePath, this::load);
        return cached == MISSING ? null : cached;
    }

    private Texture load(String path) {
        byte[] bytes;
        try {
            bytes = assets.apply(path);
        } catch (RuntimeException e) {
            return MISSING;
        }
        if (bytes == null) return MISSING;
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) return MISSING;
            int w = image.getWidth();
            int h = image.getHeight();
            if (w <= 0 || h <= 0) return MISSING;
            return new Texture(w, h, image.getRGB(0, 0, w, h, null, 0, w));
        } catch (Exception e) {
            return MISSING;
        }
    }

    public void clear() {
        cache.clear();
    }
}

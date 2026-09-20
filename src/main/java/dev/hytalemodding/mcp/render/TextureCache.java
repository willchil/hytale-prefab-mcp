package dev.hytalemodding.mcp.render;

import dev.hytalemodding.mcp.palette.AssetLocator;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decoded block textures, kept around between renders.
 *
 * <p>Block textures are small (mostly 16x16 or 32x32) and a scene reuses the same handful across
 * millions of ray hits, so decoding once and holding the pixels as a flat int array is worth it.
 * Misses are cached too: most blocks are model-drawn and have no face texture at all, and re-probing
 * the asset packs for each of them on every render would dominate the frame.
 */
public final class TextureCache {

    /** A decoded texture as packed RGB, row-major. */
    public record Texture(int width, int height, int[] pixels) {
        public int sample(double u, double v) {
            int x = (int) (u * width);
            int y = (int) (v * height);
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

    @Nullable
    public Texture get(@Nullable String commonRelativePath) {
        if (commonRelativePath == null || commonRelativePath.isBlank()) return null;
        Texture cached = cache.computeIfAbsent(commonRelativePath, TextureCache::load);
        return cached == MISSING ? null : cached;
    }

    private static Texture load(String path) {
        byte[] bytes = AssetLocator.readCommonAsset(path);
        if (bytes == null) return MISSING;
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) return MISSING;
            int w = image.getWidth();
            int h = image.getHeight();
            if (w <= 0 || h <= 0) return MISSING;
            int[] pixels = image.getRGB(0, 0, w, h, null, 0, w);
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] &= 0xFFFFFF;
            }
            return new Texture(w, h, pixels);
        } catch (Exception e) {
            return MISSING;
        }
    }

    public void clear() {
        cache.clear();
    }
}

package games.crescentnetwork.mcp.palette;

import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.server.core.asset.AssetModule;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads raw asset bytes (block face textures, item icons) out of whichever loaded pack supplies them.
 *
 * <p>Paths inside asset JSON are written relative to the pack's {@code Common/} directory, e.g.
 * {@code Icons/ItemsGenerated/Rock_Stone.png}, so that prefix is added back here. Works for packs
 * backed by a directory and for packs backed by a zip or jar alike, because {@link AssetPack} hands
 * out a {@link Path} on its own {@link java.nio.file.FileSystem} either way.
 */
public final class AssetLocator {

    private AssetLocator() {
    }

    /**
     * Finds a {@code Common/}-relative asset and returns its bytes.
     *
     * <p>When several packs carry the same path the highest-priority source wins, matching how the
     * server itself resolves an override: a pack passed with {@code --assets} beats a classpath pack,
     * which beats one from {@code mods/}. So a mod that reskins a base block is reflected here.
     *
     * @return the file contents, or null if no loaded pack supplies that path
     */
    @Nullable
    public static byte[] readCommonAsset(@Nullable String commonRelativePath) {
        Path resolved = locateCommonAsset(commonRelativePath);
        if (resolved == null) return null;
        try {
            return Files.readAllBytes(resolved);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Mean colour of a shipped texture, ignoring fully transparent pixels.
     *
     * <p>Used for assets that ship a texture but no declared colour. Water is the case that matters:
     * it has no ParticleColor, and it is by far the most common fluid in the shipped prefabs, so
     * falling back to neutral grey would misrepresent most water in every render.
     *
     * @return packed RGB, or -1 when the texture is missing or unreadable
     */
    public static int averageRgb(@Nullable String commonRelativePath) {
        byte[] bytes = readCommonAsset(commonRelativePath);
        if (bytes == null) return -1;
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) return -1;
            long r = 0, g = 0, b = 0, n = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int argb = image.getRGB(x, y);
                    if (((argb >>> 24) & 0xFF) < 8) continue;
                    r += (argb >> 16) & 0xFF;
                    g += (argb >> 8) & 0xFF;
                    b += argb & 0xFF;
                    n++;
                }
            }
            if (n == 0) return -1;
            return (int) ((r / n) << 16 | (g / n) << 8 | (b / n));
        } catch (Exception e) {
            return -1;
        }
    }

    /** Resolves a {@code Common/}-relative asset to a concrete path without reading it. */
    @Nullable
    public static Path locateCommonAsset(@Nullable String commonRelativePath) {
        if (commonRelativePath == null || commonRelativePath.isBlank()) return null;

        String cleaned = commonRelativePath.trim().replace('\\', '/');
        while (cleaned.startsWith("/")) cleaned = cleaned.substring(1);
        if (cleaned.isEmpty()) return null;
        // Some fields already carry the prefix and some do not, so normalise to exactly one.
        if (!cleaned.startsWith("Common/")) cleaned = "Common/" + cleaned;

        List<AssetPack> packs;
        try {
            AssetModule module = AssetModule.get();
            if (module == null) return null;
            packs = module.getAssetPacks();
        } catch (RuntimeException | LinkageError e) {
            // No asset module, which happens outside a booted server. Callers treat a miss as
            // "no image available" and carry on, so this never needs to be fatal.
            return null;
        }
        if (packs == null || packs.isEmpty()) return null;

        Path best = null;
        AssetPack.PackSource bestSource = null;
        for (AssetPack pack : packs) {
            if (pack == null) continue;
            Path candidate = resolveWithin(pack, cleaned);
            if (candidate == null) continue;
            if (bestSource == null || pack.getSource().overrides(bestSource)) {
                best = candidate;
                bestSource = pack.getSource();
            }
        }
        return best;
    }

    @Nullable
    private static Path resolveWithin(AssetPack pack, String cleaned) {
        try {
            Path root = pack.getRoot();
            // The pack may live on a zip filesystem, so the relative path has to be built with that
            // filesystem's own provider rather than with the default one.
            Path relative = root.getFileSystem().getPath(cleaned);
            // resolve() rejects anything that would climb out of the pack.
            Path candidate = pack.resolve(relative);
            if (candidate == null || !Files.isRegularFile(candidate)) return null;
            return candidate;
        } catch (Exception e) {
            // A pack whose filesystem has been closed underneath us is simply skipped.
            return null;
        }
    }
}

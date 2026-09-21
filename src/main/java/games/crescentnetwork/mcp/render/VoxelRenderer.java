package games.crescentnetwork.mcp.render;

import games.crescentnetwork.mcp.palette.BlockInfo;
import games.crescentnetwork.mcp.render.model.BakedModel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.stream.IntStream;

/**
 * Renders a {@link VoxelScene} to a PNG entirely on the CPU.
 *
 * <p>Hytale renders blocks on the client, so there is no server-side renderer to borrow. This is a
 * plain raycaster: one ray per pixel, marched through the voxel grid with the Amanatides and Woo DDA.
 * Cubic blocks are shaded from the face the ray entered through and sample their real face texture.
 * Blocks drawn from a custom model are traced against the model's own boxes and quads, textured and
 * with transparent texels cut out, in every cell the model reaches. The render is still a preview to
 * iterate against rather than a screenshot: there is no shadowing, no light level and no biome tint.
 */
public final class VoxelRenderer {

    /** Direction the key light comes from. Slightly off-axis so the three visible faces all differ. */
    private static final double LIGHT_X = 0.42, LIGHT_Y = 0.82, LIGHT_Z = 0.39;

    private static final double AMBIENT = 0.42;

    /** Brightness of a model part authored as unshaded, such as hay or cloth drawn flat. */
    private static final double FLAT_SHADE = 0.85;

    /** Hits closer than this to the eye are ignored, so a model the camera sits inside does not fill the frame. */
    private static final double NEAR = 1e-6;

    /** Stops a pathological camera from marching the whole grid for every pixel. */
    private static final int MAX_STEPS = 4096;

    private static final int SKY_TOP = 0xD7E0EA;
    private static final int SKY_BOTTOM = 0x9AAABC;

    private final TextureCache textures;

    public VoxelRenderer(TextureCache textures) {
        this.textures = textures;
    }

    public byte[] renderPng(VoxelScene scene, Camera camera, int width, int height) {
        BufferedImage image = render(scene, camera, width, height);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16)) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public BufferedImage render(VoxelScene scene, Camera camera, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] pixels = new int[width * height];

        double lightLen = Math.sqrt(LIGHT_X * LIGHT_X + LIGHT_Y * LIGHT_Y + LIGHT_Z * LIGHT_Z);
        double lx = LIGHT_X / lightLen, ly = LIGHT_Y / lightLen, lz = LIGHT_Z / lightLen;

        // Rows are independent, and a render is the slowest thing this plugin does.
        IntStream.range(0, height).parallel().forEach(py -> {
            int rowBase = py * width;
            BakedModel.Hit hit = new BakedModel.Hit();
            // +0.5 samples the pixel centre; the Y flip puts +Y up in the image.
            double ndcY = 1.0 - 2.0 * ((py + 0.5) / height);
            for (int px = 0; px < width; px++) {
                double ndcX = 2.0 * ((px + 0.5) / width) - 1.0;
                double[] dir = camera.rayDirection(ndcX, ndcY);
                pixels[rowBase + px] = trace(scene, camera, dir, lx, ly, lz, py, height, hit);
            }
        });

        image.setRGB(0, 0, width, height, pixels, 0, width);
        return image;
    }

    private int trace(VoxelScene scene, Camera cam, double[] dir,
                      double lx, double ly, double lz, int py, int height, BakedModel.Hit hit) {
        double ox = cam.eyeX, oy = cam.eyeY, oz = cam.eyeZ;
        double dx = dir[0], dy = dir[1], dz = dir[2];

        // The grid occupies [min, min+size) on each axis in world units.
        double bMinX = scene.minX(), bMinY = scene.minY(), bMinZ = scene.minZ();
        double bMaxX = bMinX + scene.sizeX(), bMaxY = bMinY + scene.sizeY(), bMaxZ = bMinZ + scene.sizeZ();

        double tEnter = 0, tExit = Double.MAX_VALUE;
        double[] slab;
        slab = slab(ox, dx, bMinX, bMaxX, tEnter, tExit);
        if (slab == null) return sky(py, height);
        tEnter = slab[0];
        tExit = slab[1];
        slab = slab(oy, dy, bMinY, bMaxY, tEnter, tExit);
        if (slab == null) return sky(py, height);
        tEnter = slab[0];
        tExit = slab[1];
        slab = slab(oz, dz, bMinZ, bMaxZ, tEnter, tExit);
        if (slab == null) return sky(py, height);
        tEnter = slab[0];
        tExit = slab[1];

        // Nudge inside so the starting cell is unambiguous on a face-tangent entry.
        double t = Math.max(tEnter, 0) + 1e-6;
        if (t > tExit) return sky(py, height);

        int cx = (int) Math.floor(ox + dx * t);
        int cy = (int) Math.floor(oy + dy * t);
        int cz = (int) Math.floor(oz + dz * t);

        int stepX = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        int stepY = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
        int stepZ = dz > 0 ? 1 : (dz < 0 ? -1 : 0);

        double tDeltaX = stepX == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dx);
        double tDeltaY = stepY == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dy);
        double tDeltaZ = stepZ == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dz);

        double tMaxX = stepX == 0 ? Double.MAX_VALUE : boundary(ox + dx * t, cx, stepX) / Math.abs(dx) + t;
        double tMaxY = stepY == 0 ? Double.MAX_VALUE : boundary(oy + dy * t, cy, stepY) / Math.abs(dy) + t;
        double tMaxZ = stepZ == 0 ? Double.MAX_VALUE : boundary(oz + dz * t, cz, stepZ) / Math.abs(dz) + t;

        // Which axis the ray crossed to enter the current cell, and from which side.
        int axis = -1;
        int axisStep = 0;
        double tHit = t;

        for (int i = 0; i < MAX_STEPS; i++) {
            short material = scene.at(cx, cy, cz);
            boolean solid = material != 0 && scene.material(material).solid();

            int[] instances = scene.instancesAt(cx, cy, cz);
            if (instances != null) {
                // Only geometry inside this cell counts yet: anything further along the ray lies in a
                // later cell, where the same instance is registered and will be found in order. A solid
                // cube here hides whatever of a model sits behind its entry face.
                double cellExit = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
                double limit = solid ? tHit : cellExit + 1e-9;
                boolean found = false;
                for (int instance : instances) {
                    BakedModel model = scene.material(scene.instanceMaterial(instance)).model();
                    if (model == null) continue;
                    if (model.intersect(
                        ox - scene.instanceX(instance), oy - scene.instanceY(instance), oz - scene.instanceZ(instance),
                        dx, dy, dz, NEAR, limit, hit)) {
                        limit = hit.t;
                        found = true;
                    }
                }
                if (found) return shadeModel(hit, lx, ly, lz);
            }

            if (solid) {
                return shade(scene, material, axis, axisStep,
                    ox + dx * tHit, oy + dy * tHit, oz + dz * tHit, lx, ly, lz);
            }
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                tHit = tMaxX;
                cx += stepX;
                tMaxX += tDeltaX;
                axis = 0;
                axisStep = stepX;
            } else if (tMaxY <= tMaxZ) {
                tHit = tMaxY;
                cy += stepY;
                tMaxY += tDeltaY;
                axis = 1;
                axisStep = stepY;
            } else {
                tHit = tMaxZ;
                cz += stepZ;
                tMaxZ += tDeltaZ;
                axis = 2;
                axisStep = stepZ;
            }
            if (tHit > tExit) break;
        }
        return sky(py, height);
    }

    /** Distance from {@code pos} forward to the next cell boundary in the stepping direction. */
    private static double boundary(double pos, int cell, int step) {
        return step > 0 ? (cell + 1 - pos) : (pos - cell);
    }

    /**
     * Clips a ray against one pair of slab planes.
     *
     * @return the narrowed {tEnter, tExit}, or null when the ray misses the slab entirely
     */
    private static double[] slab(double origin, double d, double lo, double hi, double tEnter, double tExit) {
        if (Math.abs(d) < 1e-12) {
            return (origin < lo || origin > hi) ? null : new double[]{tEnter, tExit};
        }
        double t1 = (lo - origin) / d;
        double t2 = (hi - origin) / d;
        if (t1 > t2) {
            double tmp = t1;
            t1 = t2;
            t2 = tmp;
        }
        double newEnter = Math.max(tEnter, t1);
        double newExit = Math.min(tExit, t2);
        return newEnter > newExit ? null : new double[]{newEnter, newExit};
    }

    private int shade(VoxelScene scene, short material, int axis, int axisStep,
                      double hx, double hy, double hz, double lx, double ly, double lz) {
        VoxelScene.Material m = scene.material(material);

        double nx = 0, ny = 0, nz = 0;
        switch (axis) {
            case 0 -> nx = -axisStep;
            case 1 -> ny = -axisStep;
            case 2 -> nz = -axisStep;
            // axis == -1 means the camera started inside the build and the first cell was already
            // solid; light it from above so it is not a flat silhouette.
            default -> ny = 1;
        }

        int rgb = m.rgb();
        // Fluids keep their flat colour. Their textures are near-greyscale and only become the right
        // colour once a biome tints them, which the catalog has already folded into rgb; sampling the
        // raw texture here would paint water almost white.
        if (m.cubic() && !m.fluid() && !m.faceTextures().isEmpty()) {
            int sampled = sampleFace(m, axis, axisStep, hx, hy, hz);
            if (sampled >= 0) rgb = sampled;
        }

        double shade = lambert(nx, ny, nz, lx, ly, lz);
        // Fluids read better slightly luminous; in game they are lit from within rather than shaded.
        if (m.fluid()) shade = Math.min(1.0, shade + 0.12);
        return scale(rgb, shade);
    }

    private static int shadeModel(BakedModel.Hit hit, double lx, double ly, double lz) {
        double shade = switch (hit.shading) {
            case FULLBRIGHT -> 1.0;
            case FLAT -> FLAT_SHADE;
            case STANDARD -> lambert(hit.nx, hit.ny, hit.nz, lx, ly, lz);
        };
        return scale(hit.argb & 0xFFFFFF, shade);
    }

    private static double lambert(double nx, double ny, double nz, double lx, double ly, double lz) {
        double ndotl = nx * lx + ny * ly + nz * lz;
        if (ndotl < 0) ndotl = 0;
        return AMBIENT + (1.0 - AMBIENT) * ndotl;
    }

    private static int scale(int rgb, double shade) {
        int r = clamp((int) (((rgb >> 16) & 0xFF) * shade));
        int g = clamp((int) (((rgb >> 8) & 0xFF) * shade));
        int b = clamp((int) ((rgb & 0xFF) * shade));
        return (r << 16) | (g << 8) | b;
    }

    /** Samples the texture of the face the ray entered through, or -1 when there is nothing to sample. */
    private int sampleFace(VoxelScene.Material m, int axis, int axisStep, double hx, double hy, double hz) {
        int faceIndex;
        double u, v;
        switch (axis) {
            case 0 -> {
                faceIndex = axisStep > 0 ? BlockInfo.WEST : BlockInfo.EAST;
                u = frac(hz);
                v = 1.0 - frac(hy);
            }
            case 1 -> {
                faceIndex = axisStep > 0 ? BlockInfo.DOWN : BlockInfo.UP;
                u = frac(hx);
                v = 1.0 - frac(hz);
            }
            case 2 -> {
                faceIndex = axisStep > 0 ? BlockInfo.SOUTH : BlockInfo.NORTH;
                u = frac(hx);
                v = 1.0 - frac(hy);
            }
            default -> {
                return -1;
            }
        }
        if (faceIndex >= m.faceTextures().size()) return -1;
        TextureCache.Texture texture = textures.get(m.faceTextures().get(faceIndex));
        if (texture == null || texture.width() == 0) return -1;
        return texture.sample(u, v);
    }

    private static double frac(double value) {
        double f = value - Math.floor(value);
        return f < 0 ? f + 1 : f;
    }

    private static int clamp(int channel) {
        return channel < 0 ? 0 : Math.min(channel, 255);
    }

    /** Vertical gradient, so the build has something to read against at any angle. */
    private static int sky(int py, int height) {
        double t = height <= 1 ? 0 : (double) py / (height - 1);
        int r = lerp((SKY_TOP >> 16) & 0xFF, (SKY_BOTTOM >> 16) & 0xFF, t);
        int g = lerp((SKY_TOP >> 8) & 0xFF, (SKY_BOTTOM >> 8) & 0xFF, t);
        int b = lerp(SKY_TOP & 0xFF, SKY_BOTTOM & 0xFF, t);
        return (r << 16) | (g << 8) | b;
    }

    private static int lerp(int a, int b, double t) {
        return (int) Math.round(a + (b - a) * t);
    }
}

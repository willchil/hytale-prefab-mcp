package games.crescentnetwork.mcp.render.model;

import games.crescentnetwork.mcp.palette.Orientation;
import games.crescentnetwork.mcp.render.TextureCache;

import javax.annotation.Nullable;

/**
 * A block model placed in its cell at one rotation, ready to be hit by rays.
 *
 * <p>Coordinates are cell-local: the placed cell spans 0..1 on every axis, and geometry may reach
 * outside it, as a roof's overhang or a bed's far end does. Each shape keeps the inverse of its
 * transform, so a ray is tested against a unit box in the shape's own frame. That keeps the maths
 * to one slab test per shape and means a mirrored shape (negative stretch) keeps its face names,
 * which is what makes its texture come out mirrored too.
 *
 * <p>Placement matches the game: the model's origin sits at the bottom centre of the cell, it is
 * scaled about that origin by the block's model scale, and it is then turned about the cell centre
 * by the block rotation, the same rotation Hytale applies to the block's hitbox.
 */
public final class BakedModel {

    /** Texels with less alpha than this are holes, as a cut-out shader would treat them. */
    private static final int ALPHA_CUTOFF = 128;

    /** Keeps a sample off the very edge of a face, where rounding can pick the neighbouring texel. */
    private static final double EDGE = 1e-4;

    /** The result of a ray hit. Reused per thread, so the hot path allocates nothing. */
    public static final class Hit {
        public double t;
        public double nx, ny, nz;
        public int argb;
        public BlockyModel.Shading shading;
        /** Scratch space for a ray in a shape's own frame. */
        private final double[] local = new double[6];
    }

    private final int count;
    /** Per shape: the inverse linear part, row-major, then the translation it undoes. */
    private final double[] inverse;
    /** Per shape: each local axis's outward normal in cell space, normalised. */
    private final double[] normals;
    /** Per shape: cell-space bounds, min then max. */
    private final double[] shapeBounds;
    private final BlockyModel.Shape[] shapes;
    private final double[] bounds;
    @Nullable
    private final TextureCache.Texture texture;
    private final int fallbackArgb;

    private BakedModel(int count, double[] inverse, double[] normals, double[] shapeBounds,
                       BlockyModel.Shape[] shapes, double[] bounds,
                       @Nullable TextureCache.Texture texture, int fallbackRgb) {
        this.count = count;
        this.inverse = inverse;
        this.normals = normals;
        this.shapeBounds = shapeBounds;
        this.shapes = shapes;
        this.bounds = bounds;
        this.texture = texture;
        this.fallbackArgb = 0xFF000000 | (fallbackRgb & 0xFFFFFF);
    }

    /**
     * @param texture     the model's texture, or null to draw it in {@code fallbackRgb}
     * @param scale       the block's custom model scale
     * @param rotation    the block's rotation index
     */
    public static BakedModel bake(BlockyModel model, @Nullable TextureCache.Texture texture, int fallbackRgb,
                                  double scale, int rotation) {
        double[] rb = Orientation.matrix(rotation);
        BlockyModel.Shape[] kept = new BlockyModel.Shape[model.shapes().size()];
        double[] inverse = new double[kept.length * 12];
        double[] normals = new double[kept.length * 9];
        double[] shapeBounds = new double[kept.length * 6];
        double[] bounds = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE,
            -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};

        int n = 0;
        for (BlockyModel.Shape s : model.shapes()) {
            // Linear part: block rotation, model scale, node orientation, then shape size. A quad has no
            // thickness, so its normal axis keeps a unit scale to stay invertible; the plane test only
            // ever looks at local 0 on that axis.
            double[] m = multiply(rb, s.orientation());
            for (int i = 0; i < 9; i++) m[i] *= scale;
            for (int axis = 0; axis < 3; axis++) {
                double e = s.isQuad() && axis == s.quadAxis() ? 1 : s.extent()[axis];
                for (int row = 0; row < 3; row++) m[row * 3 + axis] *= e;
            }
            double[] inv = invert(m);
            if (inv == null) continue;

            double[] c = s.center();
            double[] placed = {scale * c[0], scale * c[1] - 0.5, scale * c[2]};
            double[] t = {
                rb[0] * placed[0] + rb[1] * placed[1] + rb[2] * placed[2] + 0.5,
                rb[3] * placed[0] + rb[4] * placed[1] + rb[5] * placed[2] + 0.5,
                rb[6] * placed[0] + rb[7] * placed[1] + rb[8] * placed[2] + 0.5};

            System.arraycopy(inv, 0, inverse, n * 12, 9);
            System.arraycopy(t, 0, inverse, n * 12 + 9, 3);
            for (int axis = 0; axis < 3; axis++) {
                // The normal of a local axis plane is that axis's row of the inverse.
                double nx = inv[axis * 3], ny = inv[axis * 3 + 1], nz = inv[axis * 3 + 2];
                double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
                normals[n * 9 + axis * 3] = nx / len;
                normals[n * 9 + axis * 3 + 1] = ny / len;
                normals[n * 9 + axis * 3 + 2] = nz / len;
            }

            double[] sb = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE,
                -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
            for (int i = 0; i < 8; i++) {
                double lx = (i & 1) == 0 ? -0.5 : 0.5;
                double ly = (i & 2) == 0 ? -0.5 : 0.5;
                double lz = (i & 4) == 0 ? -0.5 : 0.5;
                if (s.isQuad()) {
                    if (s.quadAxis() == 0) lx = 0;
                    else if (s.quadAxis() == 1) ly = 0;
                    else lz = 0;
                }
                for (int axis = 0; axis < 3; axis++) {
                    double v = m[axis * 3] * lx + m[axis * 3 + 1] * ly + m[axis * 3 + 2] * lz + t[axis];
                    sb[axis] = Math.min(sb[axis], v);
                    sb[axis + 3] = Math.max(sb[axis + 3], v);
                }
            }
            System.arraycopy(sb, 0, shapeBounds, n * 6, 6);
            for (int i = 0; i < 3; i++) {
                bounds[i] = Math.min(bounds[i], sb[i]);
                bounds[i + 3] = Math.max(bounds[i + 3], sb[i + 3]);
            }
            kept[n++] = s;
        }
        if (n == 0) {
            bounds = new double[]{0, 0, 0, 1, 1, 1};
        }
        return new BakedModel(n, inverse, normals, shapeBounds, kept, bounds, texture, fallbackRgb);
    }

    /** Cell-space bounds of the whole model: min x, y, z then max x, y, z. */
    public double[] bounds() {
        return bounds.clone();
    }

    public boolean isEmpty() {
        return count == 0;
    }

    /**
     * Finds the nearest opaque surface along a ray in cell-local coordinates.
     *
     * @return true when something was hit between {@code tMin} and {@code tMax}; {@code hit} then
     * holds the nearest one
     */
    public boolean intersect(double ox, double oy, double oz, double dx, double dy, double dz,
                             double tMin, double tMax, Hit hit) {
        double best = tMax;
        boolean found = false;
        double[] local = hit.local;

        for (int i = 0; i < count; i++) {
            if (!overlaps(i, ox, oy, oz, dx, dy, dz, tMin, best)) continue;

            int b = i * 12;
            double rx = ox - inverse[b + 9], ry = oy - inverse[b + 10], rz = oz - inverse[b + 11];
            local[0] = inverse[b] * rx + inverse[b + 1] * ry + inverse[b + 2] * rz;
            local[1] = inverse[b + 3] * rx + inverse[b + 4] * ry + inverse[b + 5] * rz;
            local[2] = inverse[b + 6] * rx + inverse[b + 7] * ry + inverse[b + 8] * rz;
            local[3] = inverse[b] * dx + inverse[b + 1] * dy + inverse[b + 2] * dz;
            local[4] = inverse[b + 3] * dx + inverse[b + 4] * dy + inverse[b + 5] * dz;
            local[5] = inverse[b + 6] * dx + inverse[b + 7] * dy + inverse[b + 8] * dz;

            BlockyModel.Shape s = shapes[i];
            double t;
            int axis;
            int side;
            boolean backFace = false;

            if (s.isQuad()) {
                axis = s.quadAxis();
                double d = local[3 + axis];
                if (Math.abs(d) < 1e-12) continue;
                t = -local[axis] / d;
                if (t < tMin || t >= best) continue;
                if (!withinFace(local, t, axis)) continue;
                side = s.quadSign();
                // Front-facing when the ray travels against the quad's normal.
                backFace = d * side > 0;
                if (backFace && !s.doubleSided()) continue;
            } else {
                double tNear = -Double.MAX_VALUE, tFar = Double.MAX_VALUE;
                int nearAxis = -1;
                boolean miss = false;
                for (int a = 0; a < 3; a++) {
                    double o = local[a], d = local[3 + a];
                    if (Math.abs(d) < 1e-12) {
                        if (o < -0.5 || o > 0.5) {
                            miss = true;
                            break;
                        }
                        continue;
                    }
                    double t1 = (-0.5 - o) / d, t2 = (0.5 - o) / d;
                    if (t1 > t2) {
                        double tmp = t1;
                        t1 = t2;
                        t2 = tmp;
                    }
                    if (t1 > tNear) {
                        tNear = t1;
                        nearAxis = a;
                    }
                    if (t2 < tFar) tFar = t2;
                    if (tNear > tFar) {
                        miss = true;
                        break;
                    }
                }
                // A ray starting inside the box would only see its back faces, which are culled.
                if (miss || nearAxis < 0 || tNear < tMin || tNear >= best) continue;
                t = tNear;
                axis = nearAxis;
                side = local[3 + axis] > 0 ? -1 : 1;
            }

            int face = axis * 2 + (side < 0 ? 1 : 0);
            BlockyModel.FaceUv uv = s.faces()[face];
            if (uv == null) continue;

            int argb = sample(s, face, uv,
                local[0] + local[3] * t, local[1] + local[4] * t, local[2] + local[5] * t);
            if ((argb >>> 24) < ALPHA_CUTOFF) continue;

            int nb = i * 9 + axis * 3;
            double sign = backFace ? -side : side;
            hit.t = t;
            hit.nx = normals[nb] * sign;
            hit.ny = normals[nb + 1] * sign;
            hit.nz = normals[nb + 2] * sign;
            hit.argb = argb;
            hit.shading = s.shading();
            best = t;
            found = true;
        }
        return found;
    }

    /** Cheap reject: does the ray pass through shape {@code i}'s bounds within the range? */
    private boolean overlaps(int i, double ox, double oy, double oz, double dx, double dy, double dz,
                             double tMin, double tMax) {
        int b = i * 6;
        double[] range = {tMin, tMax};
        return slab(ox, dx, shapeBounds[b], shapeBounds[b + 3], range)
            && slab(oy, dy, shapeBounds[b + 1], shapeBounds[b + 4], range)
            && slab(oz, dz, shapeBounds[b + 2], shapeBounds[b + 5], range);
    }

    private static boolean slab(double o, double d, double lo, double hi, double[] range) {
        lo -= 1e-9;
        hi += 1e-9;
        if (Math.abs(d) < 1e-12) return o >= lo && o <= hi;
        double t1 = (lo - o) / d, t2 = (hi - o) / d;
        if (t1 > t2) {
            double tmp = t1;
            t1 = t2;
            t2 = tmp;
        }
        if (t1 > range[0]) range[0] = t1;
        if (t2 < range[1]) range[1] = t2;
        return range[0] <= range[1];
    }

    private static boolean withinFace(double[] local, double t, int axis) {
        for (int a = 0; a < 3; a++) {
            if (a == axis) continue;
            double p = local[a] + local[3 + a] * t;
            if (p < -0.5 || p > 0.5) return false;
        }
        return true;
    }

    /**
     * The texel under a point on a face.
     *
     * <p>Each face is read as if looking at it from outside, left to right and top to bottom, the way
     * Blockbench lays faces out; up is +Y on the side faces, north (-Z) is up on the top face and
     * south is up on the bottom face. The face's rectangle then starts at its offset, is mirrored,
     * and is turned clockwise about the offset by its angle. That rule reproduces the Blockbench
     * importer's UV maths for every angle and mirror, and it keeps 99.9% of the face rectangles in the
     * shipped models inside their textures.
     */
    private int sample(BlockyModel.Shape s, int face, BlockyModel.FaceUv uv, double px, double py, double pz) {
        TextureCache.Texture texture = this.texture;
        if (texture == null) return fallbackArgb;
        double u, v, w, h;
        double[] texels = s.texels();
        switch (face) {
            case BlockyModel.RIGHT -> {
                u = 0.5 - pz;
                v = 0.5 - py;
                w = texels[2];
                h = texels[1];
            }
            case BlockyModel.LEFT -> {
                u = pz + 0.5;
                v = 0.5 - py;
                w = texels[2];
                h = texels[1];
            }
            case BlockyModel.TOP -> {
                u = px + 0.5;
                v = pz + 0.5;
                w = texels[0];
                h = texels[2];
            }
            case BlockyModel.BOTTOM -> {
                u = px + 0.5;
                v = 0.5 - pz;
                w = texels[0];
                h = texels[2];
            }
            case BlockyModel.FRONT -> {
                u = px + 0.5;
                v = 0.5 - py;
                w = texels[0];
                h = texels[1];
            }
            default -> {
                u = 0.5 - px;
                v = 0.5 - py;
                w = texels[0];
                h = texels[1];
            }
        }
        u = Math.min(1 - EDGE, Math.max(EDGE, u));
        v = Math.min(1 - EDGE, Math.max(EDGE, v));

        double x = u * w, y = v * h;
        if (uv.mirrorX()) x = -x;
        if (uv.mirrorY()) y = -y;
        double tx, ty;
        switch (uv.angle()) {
            case 90 -> {
                tx = -y;
                ty = x;
            }
            case 180 -> {
                tx = -x;
                ty = -y;
            }
            case 270 -> {
                tx = y;
                ty = -x;
            }
            default -> {
                tx = x;
                ty = y;
            }
        }
        return texture.texel((int) Math.floor(uv.offsetX() + tx), (int) Math.floor(uv.offsetY() + ty));
    }

    private static double[] multiply(double[] a, double[] b) {
        double[] r = new double[9];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                r[row * 3 + col] = a[row * 3] * b[col] + a[row * 3 + 1] * b[3 + col] + a[row * 3 + 2] * b[6 + col];
            }
        }
        return r;
    }

    @Nullable
    private static double[] invert(double[] m) {
        double a = m[0], b = m[1], c = m[2];
        double d = m[3], e = m[4], f = m[5];
        double g = m[6], h = m[7], i = m[8];
        double co0 = e * i - f * h, co1 = f * g - d * i, co2 = d * h - e * g;
        double det = a * co0 + b * co1 + c * co2;
        if (Math.abs(det) < 1e-15 || !Double.isFinite(det)) return null;
        double k = 1.0 / det;
        return new double[]{
            co0 * k, (c * h - b * i) * k, (b * f - c * e) * k,
            co1 * k, (a * i - c * g) * k, (c * d - a * f) * k,
            co2 * k, (b * g - a * h) * k, (a * e - b * d) * k};
    }
}

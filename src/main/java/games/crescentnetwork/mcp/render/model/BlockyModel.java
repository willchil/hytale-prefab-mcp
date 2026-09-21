package games.crescentnetwork.mcp.render.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A {@code .blockymodel} flattened into a list of shapes in model space.
 *
 * <p>Model space is measured in blocks with its origin at the bottom centre of the cell, which is
 * where every shipped block model sits: a full-cell box spans x and z from -0.5 to 0.5 and y from 0
 * to 1. The file itself is in 1/32 block units.
 *
 * <p>The node transform follows the server's own {@code BlockyModelBoundsParser}, which says it
 * mirrors the client: a node's shape sits at its position plus its offset turned by its own
 * orientation, children inherit the parent's world position and orientation, and an invisible node
 * hides everything under it. Quads and the texture layout follow the Hytale Blockbench plugin's
 * importer, the only public reference for how a face maps onto its texture.
 *
 * <p>Across the 1,151 shipped block models only two shape types occur, {@code box} and {@code quad};
 * nodes of type {@code none} are pure transforms. Anything else is skipped.
 */
public final class BlockyModel {

    /** Model units per block. */
    public static final double UNITS_PER_BLOCK = 32.0;

    /**
     * Face indices, {@code axis * 2 + (negative ? 1 : 0)}. The names are Hytale's: front is +Z
     * (south), back is -Z (north), right is +X (east), left is -X (west).
     */
    public static final int RIGHT = 0, LEFT = 1, TOP = 2, BOTTOM = 3, FRONT = 4, BACK = 5;

    private static final String[] FACE_KEYS = {"right", "left", "top", "bottom", "front", "back"};

    public enum Shading { STANDARD, FLAT, FULLBRIGHT }

    /** Where one face's rectangle sits in the texture, in texels. */
    public record FaceUv(double offsetX, double offsetY, boolean mirrorX, boolean mirrorY, int angle) {
    }

    /**
     * One box or quad.
     *
     * @param center      centre in model space, blocks
     * @param orientation world orientation of the node, row-major 3x3
     * @param extent      full size along each local axis in blocks, stretch applied and possibly
     *                    negative (a mirrored shape); zero on a quad's normal axis
     * @param texels      unstretched size along each local axis in model units, which is also the
     *                    face size in texels; zero on a quad's normal axis
     * @param quadAxis    -1 for a box, otherwise the axis a quad faces along
     * @param quadSign    for a quad, +1 when it faces the positive end of that axis
     * @param faces       layout per face index; an entry is null where a face has none and is not drawn
     */
    public record Shape(
        double[] center,
        double[] orientation,
        double[] extent,
        double[] texels,
        int quadAxis,
        int quadSign,
        FaceUv[] faces,
        boolean doubleSided,
        Shading shading
    ) {
        public boolean isQuad() {
            return quadAxis >= 0;
        }
    }

    private final List<Shape> shapes;
    private final double[] min;
    private final double[] max;

    private BlockyModel(List<Shape> shapes, double[] min, double[] max) {
        this.shapes = List.copyOf(shapes);
        this.min = min;
        this.max = max;
    }

    public List<Shape> shapes() {
        return shapes;
    }

    /** Lower corner of the model's bounding box in model space. */
    public double[] min() {
        return min.clone();
    }

    /** Upper corner of the model's bounding box in model space. */
    public double[] max() {
        return max.clone();
    }

    public boolean isEmpty() {
        return shapes.isEmpty();
    }

    /**
     * Parses a model.
     *
     * @throws IllegalArgumentException when the bytes are not a model this parser understands
     */
    public static BlockyModel parse(byte[] bytes) {
        JsonElement root;
        try {
            root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not valid JSON: " + e.getMessage(), e);
        }
        if (!root.isJsonObject()) throw new IllegalArgumentException("model root is not an object");
        JsonArray nodes = array(root.getAsJsonObject(), "nodes");
        if (nodes == null) throw new IllegalArgumentException("model has no nodes");

        List<Shape> shapes = new ArrayList<>();
        for (JsonElement node : nodes) {
            if (node.isJsonObject()) walk(node.getAsJsonObject(), new Vector3d(), new Quaterniond(), shapes);
        }

        double[] min = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        double[] max = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (Shape s : shapes) {
            forEachCorner(s, p -> {
                for (int i = 0; i < 3; i++) {
                    min[i] = Math.min(min[i], p[i]);
                    max[i] = Math.max(max[i], p[i]);
                }
            });
        }
        if (shapes.isEmpty()) {
            min[0] = min[1] = min[2] = 0;
            max[0] = max[1] = max[2] = 0;
        }
        return new BlockyModel(shapes, min, max);
    }

    private static void walk(JsonObject node, Vector3d parentPosition, Quaterniond parentOrientation,
                             List<Shape> out) {
        JsonObject shape = object(node, "shape");
        if (shape != null && shape.has("visible") && !bool(shape, "visible", true)) return;

        Vector3d position = vec(object(node, "position"), 0);
        Quaterniond orientation = quat(object(node, "orientation"));
        Vector3d offset = shape == null ? new Vector3d() : vec(object(shape, "offset"), 0);

        Vector3d local = orientation.transform(new Vector3d(offset)).add(position);
        Vector3d world = parentOrientation.transform(new Vector3d(local)).add(parentPosition);
        Quaterniond worldOrientation = new Quaterniond(parentOrientation).mul(orientation);

        if (shape != null) {
            String type = string(shape, "type", "none").toLowerCase(Locale.ROOT);
            if (type.equals("box") || type.equals("quad")) {
                Shape s = shapeOf(shape, type.equals("quad"), world, worldOrientation);
                if (s != null) out.add(s);
            }
        }

        JsonArray children = array(node, "children");
        if (children != null) {
            for (JsonElement child : children) {
                if (child.isJsonObject()) walk(child.getAsJsonObject(), world, worldOrientation, out);
            }
        }
    }

    @Nullable
    private static Shape shapeOf(JsonObject shape, boolean quad, Vector3d world, Quaterniond orientation) {
        JsonObject settings = object(shape, "settings");
        Vector3d size = vec(settings == null ? null : object(settings, "size"), 0);
        Vector3d stretch = vec(object(shape, "stretch"), 1);

        int quadAxis = -1, quadSign = 1;
        double[] texels = {size.x, size.y, size.z};
        if (quad) {
            String normal = settings == null ? "+Z" : string(settings, "normal", "+Z");
            if (normal.length() != 2) normal = "+Z";
            quadSign = normal.charAt(0) == '-' ? -1 : 1;
            // A quad's size names only the two sides of the plane. The Blockbench importer maps
            // them onto the cube axes like this, which keeps the face texel sizes right below.
            switch (Character.toUpperCase(normal.charAt(1))) {
                case 'X' -> {
                    quadAxis = 0;
                    texels = new double[]{0, size.y, size.x};
                }
                case 'Y' -> {
                    quadAxis = 1;
                    texels = new double[]{size.x, 0, size.y};
                }
                default -> {
                    quadAxis = 2;
                    texels = new double[]{size.x, size.y, 0};
                }
            }
        }

        double[] extent = {
            texels[0] * stretch.x / UNITS_PER_BLOCK,
            texels[1] * stretch.y / UNITS_PER_BLOCK,
            texels[2] * stretch.z / UNITS_PER_BLOCK};
        if (!quad && (extent[0] == 0 || extent[1] == 0 || extent[2] == 0)) return null;
        if (quad && (extent[(quadAxis + 1) % 3] == 0 || extent[(quadAxis + 2) % 3] == 0)) return null;

        FaceUv[] faces = new FaceUv[6];
        JsonObject layout = object(shape, "textureLayout");
        if (layout != null) {
            if (quad) {
                // A quad draws only its facing side, and it always uses the front layout.
                faces[quadAxis * 2 + (quadSign < 0 ? 1 : 0)] = faceUv(object(layout, "front"));
            } else {
                for (int f = 0; f < 6; f++) faces[f] = faceUv(object(layout, FACE_KEYS[f]));
            }
        }

        Matrix3d m = new Matrix3d().set(orientation);
        double[] rows = {
            m.m00, m.m10, m.m20,
            m.m01, m.m11, m.m21,
            m.m02, m.m12, m.m22};

        return new Shape(
            new double[]{world.x / UNITS_PER_BLOCK, world.y / UNITS_PER_BLOCK, world.z / UNITS_PER_BLOCK},
            rows, extent, texels, quadAxis, quadSign, faces,
            bool(shape, "doubleSided", false),
            shading(string(shape, "shadingMode", "standard")));
    }

    @Nullable
    private static FaceUv faceUv(@Nullable JsonObject face) {
        if (face == null) return null;
        Vector3d offset = vec(object(face, "offset"), 0);
        JsonObject mirror = object(face, "mirror");
        int angle = (int) Math.round(number(face, "angle", 0));
        angle = ((angle % 360) + 360) % 360;
        if (angle % 90 != 0) angle = 0;
        return new FaceUv(offset.x, offset.y,
            mirror != null && bool(mirror, "x", false),
            mirror != null && bool(mirror, "y", false),
            angle);
    }

    private static Shading shading(String mode) {
        return switch (mode.toLowerCase(Locale.ROOT)) {
            case "flat" -> Shading.FLAT;
            case "fullbright" -> Shading.FULLBRIGHT;
            default -> Shading.STANDARD;
        };
    }

    interface CornerSink {
        void accept(double[] point);
    }

    /** Visits a shape's corners in model space: eight for a box, four for a quad. */
    static void forEachCorner(Shape s, CornerSink sink) {
        double[] r = s.orientation();
        double[] e = s.extent();
        for (int i = 0; i < 8; i++) {
            double lx = ((i & 1) == 0 ? -0.5 : 0.5) * e[0];
            double ly = ((i & 2) == 0 ? -0.5 : 0.5) * e[1];
            double lz = ((i & 4) == 0 ? -0.5 : 0.5) * e[2];
            if (s.isQuad() && ((i >> s.quadAxis()) & 1) == 1) continue;
            sink.accept(new double[]{
                s.center()[0] + r[0] * lx + r[1] * ly + r[2] * lz,
                s.center()[1] + r[3] * lx + r[4] * ly + r[5] * lz,
                s.center()[2] + r[6] * lx + r[7] * ly + r[8] * lz});
        }
    }

    // ---------------------------------------------------------------- JSON helpers

    @Nullable
    private static JsonObject object(@Nullable JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    @Nullable
    private static JsonArray array(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    private static String string(JsonObject o, String key, String fallback) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : fallback;
    }

    private static boolean bool(JsonObject o, String key, boolean fallback) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return fallback;
        return e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : fallback;
    }

    private static double number(@Nullable JsonObject o, String key, double fallback) {
        if (o == null) return fallback;
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return fallback;
        double d = e.getAsDouble();
        return Double.isFinite(d) ? d : fallback;
    }

    private static Vector3d vec(@Nullable JsonObject o, double fallback) {
        return new Vector3d(number(o, "x", fallback), number(o, "y", fallback), number(o, "z", fallback));
    }

    private static Quaterniond quat(@Nullable JsonObject o) {
        if (o == null) return new Quaterniond();
        Quaterniond q = new Quaterniond(number(o, "x", 0), number(o, "y", 0), number(o, "z", 0), number(o, "w", 1));
        double len = Math.sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w);
        return len < 1e-12 ? new Quaterniond() : q.normalize();
    }
}

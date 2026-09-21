package games.crescentnetwork.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntBinaryOperator;

/**
 * Builds small {@code .blockymodel} files and textures in memory, in the shape the shipped models
 * use, so model tests do not depend on a copy of the game's assets.
 */
final class TestModels {

    static final int RED = 0xFFFF0000, GREEN = 0xFF00FF00, BLUE = 0xFF0000FF, WHITE = 0xFFFFFFFF;

    private TestModels() {
    }

    /** One node of a model, with a box or quad shape or none. Units are 1/32 block, as in the files. */
    static final class Node {
        private final JsonObject json = new JsonObject();
        private final JsonObject shape = new JsonObject();
        private final JsonArray children = new JsonArray();
        private final JsonObject layout = new JsonObject();

        Node() {
            json.addProperty("name", "node");
            json.add("position", vec(0, 0, 0));
            json.add("orientation", quat(0, 0, 0, 1));
            shape.addProperty("type", "none");
            shape.add("offset", vec(0, 0, 0));
            shape.add("stretch", vec(1, 1, 1));
            shape.add("textureLayout", layout);
            shape.addProperty("visible", true);
            shape.addProperty("doubleSided", false);
            shape.addProperty("shadingMode", "standard");
            json.add("shape", shape);
            json.add("children", children);
        }

        Node at(double x, double y, double z) {
            json.add("position", vec(x, y, z));
            return this;
        }

        /** Turns the node about +Y by a whole number of degrees. */
        Node turnedY(double degrees) {
            double half = Math.toRadians(degrees) / 2;
            json.add("orientation", quat(0, Math.sin(half), 0, Math.cos(half)));
            return this;
        }

        Node offset(double x, double y, double z) {
            shape.add("offset", vec(x, y, z));
            return this;
        }

        Node stretch(double x, double y, double z) {
            shape.add("stretch", vec(x, y, z));
            return this;
        }

        Node box(double sx, double sy, double sz) {
            shape.addProperty("type", "box");
            JsonObject settings = new JsonObject();
            settings.add("size", vec(sx, sy, sz));
            shape.add("settings", settings);
            return this;
        }

        Node quad(String normal, double sx, double sy) {
            shape.addProperty("type", "quad");
            JsonObject settings = new JsonObject();
            JsonObject size = new JsonObject();
            size.addProperty("x", sx);
            size.addProperty("y", sy);
            settings.add("size", size);
            settings.addProperty("normal", normal);
            shape.add("settings", settings);
            return this;
        }

        Node face(String name, double ox, double oy, int angle, boolean mirrorX, boolean mirrorY) {
            JsonObject face = new JsonObject();
            JsonObject offset = new JsonObject();
            offset.addProperty("x", ox);
            offset.addProperty("y", oy);
            face.add("offset", offset);
            JsonObject mirror = new JsonObject();
            mirror.addProperty("x", mirrorX);
            mirror.addProperty("y", mirrorY);
            face.add("mirror", mirror);
            face.addProperty("angle", angle);
            layout.add(name, face);
            return this;
        }

        /** Every face textured from the top-left corner of the texture, unrotated. */
        Node allFaces() {
            for (String f : new String[]{"front", "back", "left", "right", "top", "bottom"}) {
                face(f, 0, 0, 0, false, false);
            }
            return this;
        }

        Node doubleSided() {
            shape.addProperty("doubleSided", true);
            return this;
        }

        Node hidden() {
            shape.addProperty("visible", false);
            return this;
        }

        Node child(Node child) {
            children.add(child.json);
            return this;
        }
    }

    static byte[] model(Node... roots) {
        JsonObject model = new JsonObject();
        model.addProperty("lod", "auto");
        JsonArray nodes = new JsonArray();
        for (Node root : roots) nodes.add(root.json);
        model.add("nodes", nodes);
        return model.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** A full-cell cube, textured on every face from the texture's top-left 32x32. */
    static Node fullCube() {
        return new Node().at(0, 16, 0).box(32, 32, 32).allFaces();
    }

    static byte[] png(int width, int height, IntBinaryOperator argbAt) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, argbAt.applyAsInt(x, y));
            }
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 32x32, red top-left, green top-right, blue bottom-left, white bottom-right. */
    static byte[] quadrants() {
        return png(32, 32, (x, y) -> y < 16 ? (x < 16 ? RED : GREEN) : (x < 16 ? BLUE : WHITE));
    }

    static byte[] solid(int argb) {
        return png(32, 32, (x, y) -> argb);
    }

    /** An in-memory asset store for a model library or texture cache. */
    static final class Assets implements Function<String, byte[]> {
        private final Map<String, byte[]> files = new HashMap<>();

        Assets put(String path, byte[] bytes) {
            files.put(path, bytes);
            return this;
        }

        @Override
        public byte[] apply(String path) {
            return files.get(path);
        }
    }

    private static JsonObject vec(double x, double y, double z) {
        JsonObject o = new JsonObject();
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        return o;
    }

    private static JsonObject quat(double x, double y, double z, double w) {
        JsonObject o = new JsonObject();
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        o.addProperty("w", w);
        return o;
    }
}

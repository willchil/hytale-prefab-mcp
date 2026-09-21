package games.crescentnetwork.mcp.palette;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Block orientation as an agent writes it: yaw and pitch in degrees.
 *
 * <p>Hytale packs a block's rotation into one index, {@code roll * 16 + pitch * 4 + yaw}, each a
 * quarter-turn count. Scripts never see that index. Roll is left out entirely: across the shipped
 * assets it is used by one test block and nothing else, while pitch is not optional. Roofs and stairs
 * flip upside down with pitch 180, half slabs stand on end with pitch 90, and pipes and beams lie
 * down with pitch 90.
 *
 * <p>Yaw turns a block about the vertical axis in the same direction as Hytale's hitbox rotation, so
 * yaw 90 carries whatever faced north (-Z) round to face west (-X).
 */
public final class Orientation {

    /** Rotation indices with roll left at zero: four yaws times four pitches. */
    public static final int COUNT = 16;

    private static final int[] DEGREES = {0, 90, 180, 270};

    private Orientation() {
    }

    public static int index(int yawDegrees, int pitchDegrees) {
        return quarterTurns(pitchDegrees) * 4 + quarterTurns(yawDegrees);
    }

    public static int yaw(int index) {
        return (index & 3) * 90;
    }

    public static int pitch(int index) {
        return ((index >> 2) & 3) * 90;
    }

    /** Folds any multiple of 90 into 0..270. */
    public static int normalise(int degrees) {
        return ((degrees % 360) + 360) % 360;
    }

    public static boolean isQuarterTurn(double degrees) {
        return Double.isFinite(degrees) && degrees == Math.rint(degrees) && ((long) degrees) % 90 == 0;
    }

    private static int quarterTurns(int degrees) {
        return normalise(degrees) / 90;
    }

    /**
     * A rotation index as a row-major 3x3 matrix, built from Hytale's own {@link RotationTuple} so it
     * turns things the same way the game turns a block's hitbox and model.
     */
    public static double[] matrix(int index) {
        double[] m = {1, 0, 0, 0, 1, 0, 0, 0, 1};
        if (index <= 0 || index >= RotationTuple.VALUES.length) return m;
        RotationTuple tuple = RotationTuple.get(index);
        for (int column = 0; column < 3; column++) {
            Vector3d r = tuple.rotatedVector(new Vector3d(
                column == 0 ? 1 : 0, column == 1 ? 1 : 0, column == 2 ? 1 : 0));
            // Rotations are quarter turns, so snap away the float noise.
            m[column] = Math.rint(r.x);
            m[3 + column] = Math.rint(r.y);
            m[6 + column] = Math.rint(r.z);
        }
        return m;
    }

    /**
     * The yaw-and-pitch rotation that turns a block exactly the same way as {@code index}, or -1 when
     * the turn needs a roll.
     *
     * <p>Mirroring a block can come out as a rolled rotation that is the same turn as a plain one: a
     * half roll with a half yaw is simply upside down. Scripts only deal in yaw and pitch, so such
     * rotations are folded back.
     */
    public static int withoutRoll(int index) {
        if (index >= 0 && index < COUNT) return index;
        double[] target = matrix(index);
        for (int candidate = 0; candidate < COUNT; candidate++) {
            if (Arrays.equals(matrix(candidate), target)) return candidate;
        }
        return -1;
    }

    /** "yaw 90" or "yaw 90, pitch 180", leaving pitch out when it is zero. */
    public static String label(int index) {
        int pitch = pitch(index);
        return pitch == 0 ? "yaw " + yaw(index) : "yaw " + yaw(index) + ", pitch " + pitch;
    }

    /**
     * Describes a set of allowed rotations in the terms an agent passes them.
     *
     * <p>Grouped by pitch, because that is how the asset families are shaped: a roof allows every yaw
     * at pitch 0 and again at pitch 180, a pipe allows only some yaws once it is laid down.
     */
    public static String describe(List<Integer> rotations) {
        Map<Integer, List<Integer>> yawsByPitch = new LinkedHashMap<>();
        for (int pitch : DEGREES) {
            for (int r : rotations) {
                if (r < 0 || r >= COUNT || pitch(r) != pitch) continue;
                yawsByPitch.computeIfAbsent(pitch, p -> new ArrayList<>()).add(yaw(r));
            }
        }
        if (yawsByPitch.isEmpty() || (yawsByPitch.size() == 1 && yawsByPitch.containsKey(0)
            && yawsByPitch.get(0).equals(List.of(0)))) {
            return "no rotation (yaw 0, pitch 0 only)";
        }

        boolean sameYaws = yawsByPitch.values().stream().distinct().count() == 1;
        if (sameYaws) {
            String yaws = "yaw " + join(yawsByPitch.values().iterator().next(), "/");
            List<Integer> pitches = new ArrayList<>(yawsByPitch.keySet());
            if (pitches.equals(List.of(0))) return yaws;
            return yaws + " with pitch " + join(pitches, " or ");
        }

        List<String> parts = new ArrayList<>();
        for (Map.Entry<Integer, List<Integer>> e : yawsByPitch.entrySet()) {
            parts.add("pitch " + e.getKey() + " with yaw " + join(e.getValue(), "/"));
        }
        return String.join("; ", parts);
    }

    private static String join(List<Integer> values, String separator) {
        StringBuilder out = new StringBuilder();
        for (int v : values) {
            if (!out.isEmpty()) out.append(separator);
            out.append(v);
        }
        return out.toString();
    }

    /**
     * Which way a footprint reaches from the placed cell, in compass terms. North is -Z and east is
     * +X, the same convention the face textures use.
     */
    public static String reach(BlockInfo.Footprint f) {
        List<String> parts = new ArrayList<>();
        if (f.minZ() < 0) parts.add(-f.minZ() + " north (-Z)");
        if (f.maxZ() > 0) parts.add(f.maxZ() + " south (+Z)");
        if (f.minX() < 0) parts.add(-f.minX() + " west (-X)");
        if (f.maxX() > 0) parts.add(f.maxX() + " east (+X)");
        if (f.maxY() > 0) parts.add(f.maxY() + " up");
        if (f.minY() < 0) parts.add(-f.minY() + " down");
        return parts.isEmpty() ? "only the placed cell" : "extends " + String.join(", ", parts);
    }

    /** Footprint offsets as "x 0, y 0..1, z -1..0". */
    public static String offsets(BlockInfo.Footprint f) {
        return "x " + range(f.minX(), f.maxX()) + ", y " + range(f.minY(), f.maxY())
            + ", z " + range(f.minZ(), f.maxZ());
    }

    private static String range(int lo, int hi) {
        return lo == hi ? String.valueOf(lo) : lo + ".." + hi;
    }
}

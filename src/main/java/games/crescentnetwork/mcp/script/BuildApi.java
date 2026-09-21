package games.crescentnetwork.mcp.script;

import games.crescentnetwork.mcp.palette.BlockCatalog;
import games.crescentnetwork.mcp.palette.BlockInfo;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyExecutable;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The globals a build script sees.
 *
 * <p>Exposed as {@link ProxyExecutable} rather than as host objects, so the engine can keep host
 * access switched off entirely: a proxy is a polyglot value, not a Java object handed across the
 * boundary, so nothing here opens a route from script code back into the JVM.
 *
 * <p>There is deliberately no separate {@code fluid()} call. {@link #place} routes a fluid name into
 * the fluid layer, which is safe because the 14 fluid ids collide with none of the 2,952 block ids
 * even case-insensitively, and which means every geometry helper works on fluids for free:
 * {@code box()} fills a lake, {@code sphere()} carves a bubble of lava. A parallel fluid API would
 * have needed its own {@code fluidBox}, {@code fluidSphere}, {@code fluidLine}, or left fluids stuck
 * at one cell per call.
 *
 * <p>Arguments are coerced at this boundary rather than deeper in, so a bad argument throws inside the
 * caller's own stack frame where the engine can still attach a useful line number.
 */
public final class BuildApi {

    private static final int MAX_LOG_ENTRIES = 200;
    private static final int DEFAULT_SEARCH_LIMIT = 25;

    private final BlockCatalog catalog;
    private final BuildRecorder recorder;
    private final XorShiftRandom random;

    /** Names that resolved to nothing, counted so every typo can be reported at once at the end. */
    private final Map<String, Integer> unknownNames = new LinkedHashMap<>();
    private final List<String> log = new ArrayList<>();

    public BuildApi(BlockCatalog catalog, BuildRecorder recorder, long seed) {
        this.catalog = catalog;
        this.recorder = recorder;
        this.random = new XorShiftRandom(seed);
    }

    public Map<String, Integer> unknownNames() {
        return unknownNames;
    }

    public List<String> log() {
        return log;
    }

    // ---------------------------------------------------------------- installation

    public void install(Value bindings) {
        bindings.putMember("block", (ProxyExecutable) a -> {
            require(a, 4, "block(x, y, z, name [, opts])");
            place(toInt(a[0], "block", "x"), toInt(a[1], "block", "y"), toInt(a[2], "block", "z"),
                toName(a[3], "block"), opts(a, 4));
            return null;
        });

        bindings.putMember("box", (ProxyExecutable) a -> {
            require(a, 7, "box(x1, y1, z1, x2, y2, z2, name [, opts])");
            int x1 = toInt(a[0], "box", "x1"), y1 = toInt(a[1], "box", "y1"), z1 = toInt(a[2], "box", "z1");
            int x2 = toInt(a[3], "box", "x2"), y2 = toInt(a[4], "box", "y2"), z2 = toInt(a[5], "box", "z2");
            String name = toName(a[6], "box");
            Value o = opts(a, 7);

            int loX = Math.min(x1, x2), hiX = Math.max(x1, x2);
            int loY = Math.min(y1, y2), hiY = Math.max(y1, y2);
            int loZ = Math.min(z1, z2), hiZ = Math.max(z1, z2);
            long volume = (long) (hiX - loX + 1) * (hiY - loY + 1) * (hiZ - loZ + 1);
            recorder.checkExpansion(volume, "box()");

            boolean hollow = optBoolean(o, "hollow", false);
            for (int x = loX; x <= hiX; x++) {
                for (int z = loZ; z <= hiZ; z++) {
                    for (int y = loY; y <= hiY; y++) {
                        if (hollow && x != loX && x != hiX && y != loY && y != hiY && z != loZ && z != hiZ) continue;
                        place(x, y, z, name, o);
                    }
                }
            }
            return null;
        });

        bindings.putMember("line", (ProxyExecutable) a -> {
            require(a, 7, "line(x1, y1, z1, x2, y2, z2, name [, opts])");
            drawLine(toInt(a[0], "line", "x1"), toInt(a[1], "line", "y1"), toInt(a[2], "line", "z1"),
                toInt(a[3], "line", "x2"), toInt(a[4], "line", "y2"), toInt(a[5], "line", "z2"),
                toName(a[6], "line"), opts(a, 7));
            return null;
        });

        bindings.putMember("sphere", (ProxyExecutable) a -> {
            require(a, 5, "sphere(cx, cy, cz, r, name [, opts])");
            int r = toInt(a[3], "sphere", "r");
            drawEllipsoid(toInt(a[0], "sphere", "cx"), toInt(a[1], "sphere", "cy"), toInt(a[2], "sphere", "cz"),
                r, r, r, toName(a[4], "sphere"), opts(a, 5), "sphere()");
            return null;
        });

        bindings.putMember("ellipsoid", (ProxyExecutable) a -> {
            require(a, 7, "ellipsoid(cx, cy, cz, rx, ry, rz, name [, opts])");
            drawEllipsoid(toInt(a[0], "ellipsoid", "cx"), toInt(a[1], "ellipsoid", "cy"),
                toInt(a[2], "ellipsoid", "cz"), toInt(a[3], "ellipsoid", "rx"),
                toInt(a[4], "ellipsoid", "ry"), toInt(a[5], "ellipsoid", "rz"),
                toName(a[6], "ellipsoid"), opts(a, 7), "ellipsoid()");
            return null;
        });

        bindings.putMember("cylinder", (ProxyExecutable) a -> {
            require(a, 6, "cylinder(cx, cy, cz, r, h, name [, axis] [, opts])");
            int ccx = toInt(a[0], "cylinder", "cx"), ccy = toInt(a[1], "cylinder", "cy");
            int ccz = toInt(a[2], "cylinder", "cz");
            int r = toInt(a[3], "cylinder", "r"), h = toInt(a[4], "cylinder", "h");
            String name = toName(a[5], "cylinder");
            // axis is optional and may be omitted with opts still supplied, so sniff the type.
            String axis = "y";
            int optIndex = 6;
            if (a.length > 6 && a[6] != null && a[6].isString()) {
                axis = toName(a[6], "cylinder").toLowerCase(Locale.ROOT);
                optIndex = 7;
            }
            drawCylinder(ccx, ccy, ccz, r, h, name, axis, opts(a, optIndex));
            return null;
        });

        bindings.putMember("blockAt", (ProxyExecutable) a -> {
            require(a, 3, "blockAt(x, y, z)");
            BuildRecorder.Placement p = recorder.blockAt(
                toInt(a[0], "blockAt", "x"), toInt(a[1], "blockAt", "y"), toInt(a[2], "blockAt", "z"));
            return p == null ? null : p.name();
        });

        bindings.putMember("fluidAt", (ProxyExecutable) a -> {
            require(a, 3, "fluidAt(x, y, z)");
            BuildRecorder.FluidPlacement p = recorder.fluidAt(
                toInt(a[0], "fluidAt", "x"), toInt(a[1], "fluidAt", "y"), toInt(a[2], "fluidAt", "z"));
            return p == null ? null : p.name();
        });

        bindings.putMember("clear", (ProxyExecutable) a -> {
            require(a, 3, "clear(x, y, z)");
            recorder.clear(toInt(a[0], "clear", "x"), toInt(a[1], "clear", "y"), toInt(a[2], "clear", "z"));
            return null;
        });

        bindings.putMember("mirrorX", (ProxyExecutable) a -> {
            require(a, 1, "mirrorX(planeX)");
            mirror(Axis.X, toInt(a[0], "mirrorX", "planeX"));
            return null;
        });
        bindings.putMember("mirrorY", (ProxyExecutable) a -> {
            require(a, 1, "mirrorY(planeY)");
            mirror(Axis.Y, toInt(a[0], "mirrorY", "planeY"));
            return null;
        });
        bindings.putMember("mirrorZ", (ProxyExecutable) a -> {
            require(a, 1, "mirrorZ(planeZ)");
            mirror(Axis.Z, toInt(a[0], "mirrorZ", "planeZ"));
            return null;
        });

        bindings.putMember("setAnchor", (ProxyExecutable) a -> {
            require(a, 3, "setAnchor(x, y, z)");
            recorder.setAnchor(toInt(a[0], "setAnchor", "x"), toInt(a[1], "setAnchor", "y"),
                toInt(a[2], "setAnchor", "z"));
            return null;
        });

        bindings.putMember("findBlocks", (ProxyExecutable) a -> {
            Value o = a.length > 0 ? a[0] : null;
            BlockCatalog.Query q = new BlockCatalog.Query(
                optString(o, "query", null),
                parseKind(optString(o, "kind", null)),
                optString(o, "group", null),
                optString(o, "pack", null),
                optString(o, "drawType", null),
                null,
                Math.max(1, Math.min(optInt(o, "limit", DEFAULT_SEARCH_LIMIT), 500)));
            List<BlockInfo> hits = catalog.matching(q);
            List<Object> names = new ArrayList<>(hits.size());
            for (BlockInfo hit : hits) names.add(hit.id());
            // A proxy array, not a Java List: host access is off, so a List would be opaque to JS.
            return ProxyArray.fromList(names);
        });

        bindings.putMember("nearestBlock", (ProxyExecutable) a -> {
            require(a, 1, "nearestBlock(\"#rrggbb\")");
            Integer rgb = parseHex(toName(a[0], "nearestBlock"));
            if (rgb == null) {
                throw new ScriptError(ScriptError.Phase.RUNTIME,
                    "nearestBlock() expects a colour like \"#8a8a8a\".");
            }
            List<BlockInfo> hits = catalog.matching(new BlockCatalog.Query(
                null, BlockInfo.Kind.BLOCK, null, null, null, rgb, 1));
            return hits.isEmpty() ? null : hits.get(0).id();
        });

        bindings.putMember("rng", (ProxyExecutable) a -> random.nextDouble());

        bindings.putMember("log", (ProxyExecutable) a -> {
            if (a.length > 0 && log.size() < MAX_LOG_ENTRIES) {
                log.add(asText(a[0]));
            }
            return null;
        });
    }

    // ---------------------------------------------------------------- placement

    private void place(int x, int y, int z, String name, @Nullable Value o) {
        BlockInfo info = catalog.find(name);
        if (info == null) {
            // Collected rather than thrown, so one run reports every typo instead of only the first.
            unknownNames.merge(name, 1, Integer::sum);
            return;
        }
        // The canonical id is stored, not what the script typed: names are case-insensitive in Hytale
        // but the prefab file should carry the registry's own spelling.
        if (info.isFluid()) {
            recorder.putFluid(x, y, z, info.id(), optInt(o, "level", info.defaultFluidLevel()));
        } else {
            recorder.putBlock(x, y, z, info.id(), optInt(o, "rotation", 0));
        }
    }

    private void drawLine(int x1, int y1, int z1, int x2, int y2, int z2, String name, @Nullable Value o) {
        int dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1), dz = Math.abs(z2 - z1);
        int steps = Math.max(dx, Math.max(dy, dz));
        recorder.checkExpansion(steps + 1L, "line()");
        if (steps == 0) {
            place(x1, y1, z1, name, o);
            return;
        }
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            place((int) Math.round(x1 + (x2 - x1) * t),
                (int) Math.round(y1 + (y2 - y1) * t),
                (int) Math.round(z1 + (z2 - z1) * t), name, o);
        }
    }

    private void drawEllipsoid(int cx, int cy, int cz, int rx, int ry, int rz,
                               String name, @Nullable Value o, String primitive) {
        if (rx < 0 || ry < 0 || rz < 0) {
            throw new ScriptError(ScriptError.Phase.RUNTIME, primitive + " radii must not be negative.");
        }
        long volume = (2L * rx + 1) * (2L * ry + 1) * (2L * rz + 1);
        recorder.checkExpansion(volume, primitive);

        boolean hollow = optBoolean(o, "hollow", false);
        double erx = rx + 0.5, ery = ry + 0.5, erz = rz + 0.5;
        for (int x = -rx; x <= rx; x++) {
            double fx = (x / erx) * (x / erx);
            for (int z = -rz; z <= rz; z++) {
                double fz = (z / erz) * (z / erz);
                for (int y = -ry; y <= ry; y++) {
                    double fy = (y / ery) * (y / ery);
                    if (fx + fy + fz > 1.0) continue;
                    if (hollow) {
                        // A cell is on the shell when stepping one further out along any axis leaves
                        // the surface; cheaper and steadier than a second radius test.
                        double ox = ((Math.abs(x) + 1) / erx) * ((Math.abs(x) + 1) / erx);
                        double oy = ((Math.abs(y) + 1) / ery) * ((Math.abs(y) + 1) / ery);
                        double oz = ((Math.abs(z) + 1) / erz) * ((Math.abs(z) + 1) / erz);
                        boolean shell = (ox + fy + fz > 1.0) || (fx + oy + fz > 1.0) || (fx + fy + oz > 1.0);
                        if (!shell) continue;
                    }
                    place(cx + x, cy + y, cz + z, name, o);
                }
            }
        }
    }

    private void drawCylinder(int cx, int cy, int cz, int r, int h, String name, String axis,
                              @Nullable Value o) {
        if (r < 0) throw new ScriptError(ScriptError.Phase.RUNTIME, "cylinder() radius must not be negative.");
        int height = Math.abs(h);
        long volume = (2L * r + 1) * (2L * r + 1) * (height + 1L);
        recorder.checkExpansion(volume, "cylinder()");

        boolean hollow = optBoolean(o, "hollow", false);
        double er = r + 0.5;
        int sign = h < 0 ? -1 : 1;
        for (int a1 = -r; a1 <= r; a1++) {
            for (int a2 = -r; a2 <= r; a2++) {
                double d = (a1 / er) * (a1 / er) + (a2 / er) * (a2 / er);
                if (d > 1.0) continue;
                if (hollow) {
                    double o1 = ((Math.abs(a1) + 1) / er) * ((Math.abs(a1) + 1) / er);
                    double o2 = ((Math.abs(a2) + 1) / er) * ((Math.abs(a2) + 1) / er);
                    boolean shell = (o1 + (a2 / er) * (a2 / er) > 1.0) || ((a1 / er) * (a1 / er) + o2 > 1.0);
                    if (!shell) continue;
                }
                for (int i = 0; i < Math.max(1, height); i++) {
                    int step = i * sign;
                    switch (axis) {
                        case "x" -> place(cx + step, cy + a1, cz + a2, name, o);
                        case "z" -> place(cx + a1, cy + a2, cz + step, name, o);
                        default -> place(cx + a1, cy + step, cz + a2, name, o);
                    }
                }
            }
        }
    }

    private enum Axis { X, Y, Z }

    /**
     * Duplicates everything placed so far across a plane. Reads a snapshot first, so the copies being
     * written do not feed back into the iteration.
     */
    private void mirror(Axis axis, int plane) {
        List<Map.Entry<Long, BuildRecorder.Placement>> blocks = new ArrayList<>(recorder.blocks().entrySet());
        List<Map.Entry<Long, BuildRecorder.FluidPlacement>> fluids = new ArrayList<>(recorder.fluids().entrySet());

        for (var e : blocks) {
            long k = e.getKey();
            int[] m = reflect(axis, plane,
                BuildRecorder.unpackX(k), BuildRecorder.unpackY(k), BuildRecorder.unpackZ(k));
            recorder.putBlock(m[0], m[1], m[2], e.getValue().name(), e.getValue().rotation());
        }
        for (var e : fluids) {
            long k = e.getKey();
            int[] m = reflect(axis, plane,
                BuildRecorder.unpackX(k), BuildRecorder.unpackY(k), BuildRecorder.unpackZ(k));
            recorder.putFluid(m[0], m[1], m[2], e.getValue().name(), e.getValue().level());
        }
    }

    private static int[] reflect(Axis axis, int plane, int x, int y, int z) {
        return switch (axis) {
            case X -> new int[]{2 * plane - x, y, z};
            case Y -> new int[]{x, 2 * plane - y, z};
            case Z -> new int[]{x, y, 2 * plane - z};
        };
    }

    // ---------------------------------------------------------------- coercion

    private static void require(Value[] args, int count, String signature) {
        if (args.length < count) {
            throw new ScriptError(ScriptError.Phase.RUNTIME,
                "Not enough arguments: expected " + signature + " but got " + args.length + ".");
        }
    }

    /** JavaScript ToNumber followed by truncation, so 2.9 and "2.9" both give 2. */
    private static int toInt(@Nullable Value value, String fn, String arg) {
        double d = toNumber(value);
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new ScriptError(ScriptError.Phase.RUNTIME,
                fn + "() got a non-finite value for " + arg + ": " + describe(value));
        }
        return (int) (d < 0 ? Math.ceil(d) : Math.floor(d));
    }

    private static double toNumber(@Nullable Value value) {
        if (value == null || value.isNull()) return 0;
        if (value.isNumber()) return value.asDouble();
        if (value.isBoolean()) return value.asBoolean() ? 1 : 0;
        if (value.isString()) {
            String s = value.asString().trim();
            if (s.isEmpty()) return 0;
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    private static String describe(@Nullable Value value) {
        return asText(value);
    }

    /**
     * A script-facing value as text.
     *
     * <p>{@code Value.toString()} on a guest string returns the internal TruffleString class name
     * rather than the text, so a string has to be unwrapped explicitly.
     */
    private static String asText(@Nullable Value value) {
        if (value == null) return "undefined";
        if (value.isNull()) return "null";
        if (value.isString()) return value.asString();
        return String.valueOf(value);
    }

    private static String toName(@Nullable Value value, String fn) {
        if (value == null || value.isNull()) {
            throw new ScriptError(ScriptError.Phase.RUNTIME, fn + "() got no block name.");
        }
        String s = asText(value).trim();
        if (s.isEmpty()) {
            throw new ScriptError(ScriptError.Phase.RUNTIME, fn + "() got an empty block name.");
        }
        return s;
    }

    @Nullable
    private static Value opts(Value[] args, int index) {
        if (args.length > index) {
            Value candidate = args[index];
            if (candidate != null && !candidate.isNull() && candidate.hasMembers()) return candidate;
        }
        return null;
    }

    private static int optInt(@Nullable Value o, String key, int fallback) {
        Value v = read(o, key);
        if (v == null) return fallback;
        double d = toNumber(v);
        if (Double.isNaN(d) || Double.isInfinite(d)) return fallback;
        return (int) d;
    }

    private static boolean optBoolean(@Nullable Value o, String key, boolean fallback) {
        Value v = read(o, key);
        if (v == null) return fallback;
        if (v.isBoolean()) return v.asBoolean();
        if (v.isNumber()) return v.asDouble() != 0;
        if (v.isString()) return !v.asString().isEmpty();
        return true;
    }

    @Nullable
    private static String optString(@Nullable Value o, String key, @Nullable String fallback) {
        Value v = read(o, key);
        if (v == null) return fallback;
        String s = asText(v).trim();
        return s.isEmpty() ? fallback : s;
    }

    @Nullable
    private static Value read(@Nullable Value o, String key) {
        if (o == null || !o.hasMembers() || !o.hasMember(key)) return null;
        Value v = o.getMember(key);
        return v == null || v.isNull() ? null : v;
    }

    @Nullable
    private static BlockInfo.Kind parseKind(@Nullable String raw) {
        if (raw == null) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "fluid", "fluids" -> BlockInfo.Kind.FLUID;
            case "block", "blocks" -> BlockInfo.Kind.BLOCK;
            default -> null;
        };
    }

    @Nullable
    public static Integer parseHex(String raw) {
        String s = raw.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() != 6) return null;
        try {
            return Integer.parseInt(s, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Seeded xorshift32, so a run with the same seed reproduces exactly. {@code Math.random} is left
     * in place for scripts that do not care, but anything seeded routes through here.
     */
    static final class XorShiftRandom {
        private int state;

        XorShiftRandom(long seed) {
            int s = (int) seed;
            this.state = s == 0 ? 1 : s;
        }

        double nextDouble() {
            state ^= state << 13;
            state ^= state >>> 17;
            state ^= state << 5;
            return (state & 0xFFFFFFFFL) / 4294967296.0;
        }
    }
}

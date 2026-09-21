package games.crescentnetwork.mcp.script;

import games.crescentnetwork.mcp.palette.BlockCatalog;
import games.crescentnetwork.mcp.palette.BlockInfo;
import games.crescentnetwork.mcp.palette.Orientation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rejects a build in which a multi-cell block's footprint runs into something else.
 *
 * <p>A shallow roof, a bed or a door fills more than the cell it is placed at: the prefab writer
 * adds filler cells for the rest, exactly as the game does when a player places one. A filler cell
 * cannot share its cell with another block, and Hytale's prefab validator reports a multi-cell block
 * with missing fillers as broken. Failing here, with the offending coordinates, is how an agent
 * finds out how big a block really is.
 *
 * <p>Fluids are a separate layer and never conflict, so a roof can hang over water and a bed can be
 * waterlogged.
 */
final class FootprintCheck {

    private static final int MAX_REPORTED = 10;

    private FootprintCheck() {
    }

    static void verify(BuildRecorder recorder, BlockCatalog catalog) {
        Map<String, BlockInfo> infoByName = new HashMap<>();
        Map<Long, Long> claimedBy = null;
        List<String> conflicts = new ArrayList<>();
        int conflictCount = 0;

        for (Map.Entry<Long, BuildRecorder.Placement> entry : recorder.blocks().entrySet()) {
            BuildRecorder.Placement p = entry.getValue();
            BlockInfo info = infoByName.computeIfAbsent(p.name(), catalog::find);
            if (info == null || !info.geometry().isMultiCell()) continue;
            BlockInfo.Footprint f = info.geometry().footprint(p.rotation());
            if (f.isSingle()) continue;

            // Built lazily: most builds contain no multi-cell block at all.
            if (claimedBy == null) claimedBy = new HashMap<>();

            long base = entry.getKey();
            int bx = BuildRecorder.unpackX(base), by = BuildRecorder.unpackY(base), bz = BuildRecorder.unpackZ(base);
            for (int dx = f.minX(); dx <= f.maxX(); dx++) {
                for (int dy = f.minY(); dy <= f.maxY(); dy++) {
                    for (int dz = f.minZ(); dz <= f.maxZ(); dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        int x = bx + dx, y = by + dy, z = bz + dz;
                        String problem = null;
                        if (y < BuildRecorder.MIN_Y || y > BuildRecorder.MAX_Y) {
                            problem = "y=" + y + " is outside the " + BuildRecorder.MIN_Y + ".."
                                + BuildRecorder.MAX_Y + " range prefabs can hold";
                        } else {
                            long cell = BuildRecorder.key(x, y, z);
                            BuildRecorder.Placement occupant = recorder.blocks().get(cell);
                            if (occupant != null) {
                                problem = at(x, y, z) + " already holds " + occupant.name();
                            } else {
                                Long owner = claimedBy.putIfAbsent(cell, base);
                                if (owner != null && owner != base) {
                                    BuildRecorder.Placement other = recorder.blocks().get(owner);
                                    problem = at(x, y, z) + " is also filled by "
                                        + (other == null ? "another multi-cell block" : other.name())
                                        + " at " + at(BuildRecorder.unpackX(owner), BuildRecorder.unpackY(owner),
                                        BuildRecorder.unpackZ(owner));
                                }
                            }
                        }
                        if (problem == null) continue;
                        if (++conflictCount <= MAX_REPORTED) {
                            conflicts.add(p.name() + " at " + at(bx, by, bz) + " (" + Orientation.label(p.rotation())
                                + ") fills " + f.size() + " cells, " + span(bx, by, bz, f) + "; " + problem + ".");
                        }
                    }
                }
            }
        }

        if (conflicts.isEmpty()) return;
        StringBuilder message = new StringBuilder(
            "Multi-cell blocks overlap other blocks. A multi-cell block fills every cell of its footprint "
                + "with filler, so nothing else can occupy those cells:\n");
        for (String c : conflicts) message.append("  ").append(c).append('\n');
        if (conflictCount > MAX_REPORTED) {
            message.append("  ...and ").append(conflictCount - MAX_REPORTED).append(" more.\n");
        }
        message.append("get_block_texture shows each block's footprint at every yaw. Move the block, turn it, "
            + "or clear the cells it needs.");
        throw new ScriptError(ScriptError.Phase.VALIDATE, message.toString());
    }

    private static String at(int x, int y, int z) {
        return "(" + x + ", " + y + ", " + z + ")";
    }

    private static String span(int bx, int by, int bz, BlockInfo.Footprint f) {
        return "x " + range(bx + f.minX(), bx + f.maxX()) + ", y " + range(by + f.minY(), by + f.maxY())
            + ", z " + range(bz + f.minZ(), bz + f.maxZ());
    }

    private static String range(int lo, int hi) {
        return lo == hi ? String.valueOf(lo) : lo + ".." + hi;
    }
}

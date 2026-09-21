package games.crescentnetwork.mcp;

import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.server.core.asset.type.blockhitbox.BlockBoundingBoxes;

/**
 * A hitbox built from boxes in code rather than loaded from an asset, so tests get footprints from
 * the server's own rotation code instead of from a re-implementation of it.
 */
final class TestHitbox extends BlockBoundingBoxes {

    TestHitbox(String id, Box... boxes) {
        this.id = id;
        this.baseDetailBoxes = boxes;
        processConfig();
    }

    /** {@code Stairs_Shallow}, the hitbox of a shallow roof: 1x1x2, reaching one cell north. */
    static TestHitbox stairsShallow() {
        return new TestHitbox("Stairs_Shallow",
            new Box(0, 0, -1, 1, 0.5, 1),
            new Box(0, 0.5, -1, 1, 1, 0));
    }

    /** {@code Bed}, 3x2x2 and lopsided about its placed cell. */
    static TestHitbox bed() {
        return new TestHitbox("Bed",
            new Box(-1.4, 0.85, 0.25, -1.15, 1.2, 1.75),
            new Box(0.65, 0.85, 0.25, 0.9, 1.5, 1.75),
            new Box(-1.4, 0, 0.25, 0.9, 0.85, 1.75));
    }
}

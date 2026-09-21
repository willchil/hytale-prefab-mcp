package games.crescentnetwork.mcp;

import games.crescentnetwork.mcp.palette.BlockCatalog;
import games.crescentnetwork.mcp.palette.BlockInfo;
import games.crescentnetwork.mcp.palette.Orientation;
import games.crescentnetwork.mcp.render.TextureCache;
import games.crescentnetwork.mcp.render.model.BakedModel;
import games.crescentnetwork.mcp.render.model.BlockyModel;
import games.crescentnetwork.mcp.render.model.ModelLibrary;
import org.junit.jupiter.api.Test;

import static games.crescentnetwork.mcp.TestModels.GREEN;
import static games.crescentnetwork.mcp.TestModels.Node;
import static games.crescentnetwork.mcp.TestModels.RED;
import static games.crescentnetwork.mcp.TestModels.WHITE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing and placing {@code .blockymodel} geometry, and which texel each face shows.
 *
 * <p>The face orientation tests pin the rule ported from the Hytale Blockbench plugin's importer.
 * They cannot prove that rule is what the client does; the preview dump's side-by-side comparison
 * with the game's own inventory icons is what checks that.
 */
class BlockyModelTest {

    private static final double EPS = 1e-9;

    private static BlockyModel parse(Node... roots) {
        return BlockyModel.parse(TestModels.model(roots));
    }

    private static BakedModel bake(byte[] texture, int rotation, Node... roots) {
        TextureCache.Texture t = new TextureCache(path -> texture).get("texture.png");
        return BakedModel.bake(parse(roots), t, 0x808080, 1, rotation);
    }

    private static void assertBounds(double[] expected, double[] actual) {
        assertArrayEquals(expected, actual, 1e-6);
    }

    // ------------------------------------------------------------------ parsing

    @Test
    void fullCellCubeSpansTheCell() {
        BlockyModel model = parse(TestModels.fullCube());
        assertBounds(new double[]{-0.5, 0, -0.5}, model.min());
        assertBounds(new double[]{0.5, 1, 0.5}, model.max());

        BakedModel baked = BakedModel.bake(model, null, 0, 1, 0);
        assertBounds(new double[]{0, 0, 0, 1, 1, 1}, baked.bounds());
    }

    @Test
    void childrenInheritTheirParentsPositionAndTurn() {
        // A parent turned a quarter about +Y carries its child's +X offset round to -Z, the same way
        // the server's bounds parser composes node transforms.
        BlockyModel model = parse(new Node().at(0, 16, 0).turnedY(90)
            .child(new Node().at(8, 0, 0).box(2, 2, 2)));
        assertBounds(new double[]{-1 / 32.0, 15 / 32.0, -9 / 32.0}, model.min());
        assertBounds(new double[]{1 / 32.0, 17 / 32.0, -7 / 32.0}, model.max());
    }

    @Test
    void shapeOffsetTurnsWithItsOwnNode() {
        BlockyModel model = parse(new Node().at(0, 16, 0).turnedY(90).offset(8, 0, 0).box(2, 2, 2));
        assertBounds(new double[]{-1 / 32.0, 15 / 32.0, -9 / 32.0}, model.min());
    }

    @Test
    void childPositionsAddUpThroughTheTree() {
        // The debug gizmo's layout: an arm's end cap sits on the end of the arm, not at the arm's origin.
        BlockyModel model = parse(new Node().at(0, 16, 0)
            .child(new Node().at(8, 0, 0).box(16, 1, 1).allFaces()
                .child(new Node().at(8, 0, 0).box(3, 3, 3).allFaces())));
        assertEquals(17.5 / 32, model.max()[0], EPS);
    }

    @Test
    void anInvisibleNodeHidesItsWholeSubtree() {
        BlockyModel model = parse(TestModels.fullCube(),
            new Node().hidden().child(new Node().at(64, 0, 0).box(4, 4, 4)));
        assertEquals(1, model.shapes().size());
        assertEquals(0.5, model.max()[0], EPS);
    }

    @Test
    void quadSizeIsMappedOntoItsPlane() {
        // An X-facing quad's two sides lie along Z and Y, as the Blockbench importer maps them.
        BlockyModel model = parse(new Node().at(0, 16, 0).quad("+X", 10, 20));
        BlockyModel.Shape quad = model.shapes().get(0);
        assertEquals(0, quad.quadAxis());
        assertArrayEquals(new double[]{0, 20, 10}, quad.texels(), EPS);
        assertEquals(10 / 64.0, model.max()[2], EPS);
        assertEquals(0, model.max()[0], EPS);
    }

    @Test
    void negativeStretchMirrorsWithoutMovingTheShape() {
        BlockyModel model = parse(new Node().at(0, 16, 0).box(32, 32, 32).stretch(-1, 1, 1).allFaces());
        assertBounds(new double[]{-0.5, 0, -0.5}, model.min());
        assertBounds(new double[]{0.5, 1, 0.5}, model.max());
    }

    @Test
    void garbageIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> BlockyModel.parse("not json".getBytes()));
        assertThrows(IllegalArgumentException.class, () -> BlockyModel.parse("{}".getBytes()));
    }

    // ------------------------------------------------------------------ placement in the cell

    @Test
    void modelScaleShrinksAboutTheBottomCentre() {
        BakedModel baked = BakedModel.bake(parse(TestModels.fullCube()), null, 0, 0.5, 0);
        assertBounds(new double[]{0.25, 0, 0.25, 0.75, 0.5, 0.75}, baked.bounds());
    }

    @Test
    void yawTurnsAModelTheWayItsHitboxTurns() {
        // Like a shallow roof: two cells long, reaching one cell north of where it is placed.
        Node longBox = new Node().at(0, 8, -16).box(32, 16, 64).allFaces();
        BakedModel yaw0 = bake(TestModels.quadrants(), 0, longBox);
        BakedModel yaw90 = bake(TestModels.quadrants(), Orientation.index(90, 0), longBox);

        assertBounds(new double[]{0, 0, -1, 1, 0.5, 1}, yaw0.bounds());
        assertBounds(new double[]{-1, 0, 0, 1, 0.5, 1}, yaw90.bounds());

        // The hitbox the game uses for a shallow roof turns the same way, so the model always lies
        // over the cells the footprint check reserves for it.
        var footprints = BlockCatalog.footprintsOf(TestHitbox.stairsShallow());
        assertEquals(new BlockInfo.Footprint(0, 0, -1, 0, 0, 0), footprints.get(0));
        assertEquals(new BlockInfo.Footprint(-1, 0, 0, 0, 0, 0), footprints.get(Orientation.index(90, 0)));
    }

    @Test
    void pitch180TurnsAModelUpsideDown() {
        // A bottom half slab becomes a top half slab.
        Node slab = new Node().at(0, 8, 0).box(32, 16, 32).allFaces();
        assertBounds(new double[]{0, 0.5, 0, 1, 1, 1},
            bake(TestModels.quadrants(), Orientation.index(0, 180), slab).bounds());
    }

    // ------------------------------------------------------------------ which texel each face shows

    /** Samples the texel where a ray hits a unit cube textured with red, green, blue and white quadrants. */
    private static int hitColour(Node cube, double ox, double oy, double oz, double dx, double dy, double dz) {
        BakedModel baked = bake(TestModels.quadrants(), 0, cube);
        BakedModel.Hit hit = new BakedModel.Hit();
        assertTrue(baked.intersect(ox, oy, oz, dx, dy, dz, 1e-6, 100, hit), "ray should hit the cube");
        return hit.argb;
    }

    @Test
    void eachFaceReadsLeftToRightAndTopToBottomFromOutside() {
        // In every case the ray hits the face's upper-left quarter as seen from outside, which shows
        // the texture's upper-left quadrant, red.
        Node cube = TestModels.fullCube();
        assertEquals(RED, hitColour(cube, 0.25, 0.75, 2, 0, 0, -1), "front (+Z, south)");
        assertEquals(RED, hitColour(cube, 0.75, 0.75, -1, 0, 0, 1), "back (-Z, north)");
        assertEquals(RED, hitColour(cube, 2, 0.75, 0.75, -1, 0, 0), "right (+X, east)");
        assertEquals(RED, hitColour(cube, -1, 0.75, 0.25, 1, 0, 0), "left (-X, west)");
        assertEquals(RED, hitColour(cube, 0.25, 2, 0.25, 0, -1, 0), "top, north up");
        assertEquals(RED, hitColour(cube, 0.25, -1, 0.75, 0, 1, 0), "bottom, south up");
    }

    @Test
    void angleTurnsTheRectangleClockwiseAboutItsOffset() {
        Node cube = new Node().at(0, 16, 0).box(32, 32, 32).face("front", 32, 0, 90, false, false);
        assertEquals(GREEN, hitColour(cube, 0.25, 0.75, 2, 0, 0, -1));

        Node half = new Node().at(0, 16, 0).box(32, 32, 32).face("front", 32, 32, 180, false, false);
        assertEquals(WHITE, hitColour(half, 0.25, 0.75, 2, 0, 0, -1));
    }

    @Test
    void mirrorFlipsTheRectangleBackFromItsOffset() {
        Node cube = new Node().at(0, 16, 0).box(32, 32, 32).face("front", 32, 0, 0, true, false);
        assertEquals(GREEN, hitColour(cube, 0.25, 0.75, 2, 0, 0, -1));
    }

    @Test
    void negativeStretchMirrorsTheTexture() {
        Node cube = new Node().at(0, 16, 0).box(32, 32, 32).stretch(-1, 1, 1).allFaces();
        assertEquals(GREEN, hitColour(cube, 0.25, 0.75, 2, 0, 0, -1));
    }

    @Test
    void faceWithoutALayoutIsNotDrawn() {
        Node cube = new Node().at(0, 16, 0).box(32, 32, 32).face("top", 0, 0, 0, false, false);
        BakedModel baked = bake(TestModels.quadrants(), 0, cube);
        BakedModel.Hit hit = new BakedModel.Hit();
        assertFalse(baked.intersect(0.5, 0.5, 2, 0, 0, -1, 1e-6, 100, hit));
        assertTrue(baked.intersect(0.5, 2, 0.5, 0, -1, 0, 1e-6, 100, hit));
    }

    @Test
    void transparentTexelsAreHoles() {
        BakedModel baked = bake(TestModels.png(32, 32, (x, y) -> x < 16 ? 0x00000000 : RED), 0,
            TestModels.fullCube());
        BakedModel.Hit hit = new BakedModel.Hit();
        assertFalse(baked.intersect(0.25, 0.5, 2, 0, 0, -1, 1e-6, 100, hit), "clear half lets the ray through");
        assertTrue(baked.intersect(0.75, 0.5, 2, 0, 0, -1, 1e-6, 100, hit));
        assertEquals(RED, hit.argb);
    }

    @Test
    void untexturedModelUsesTheFallbackColour() {
        BakedModel baked = BakedModel.bake(parse(TestModels.fullCube()), null, 0x123456, 1, 0);
        BakedModel.Hit hit = new BakedModel.Hit();
        assertTrue(baked.intersect(0.5, 0.5, 2, 0, 0, -1, 1e-6, 100, hit));
        assertEquals(0xFF123456, hit.argb);
    }

    // ------------------------------------------------------------------ hits

    @Test
    void singleSidedQuadIsInvisibleFromBehind() {
        Node quad = new Node().at(0, 16, 0).quad("+Z", 32, 32).face("front", 0, 0, 0, false, false);
        BakedModel baked = bake(TestModels.solid(RED), 0, quad);
        BakedModel.Hit hit = new BakedModel.Hit();
        assertTrue(baked.intersect(0.5, 0.5, 2, 0, 0, -1, 1e-6, 100, hit), "front side");
        assertEquals(1, hit.nz, EPS);
        assertFalse(baked.intersect(0.5, 0.5, -1, 0, 0, 1, 1e-6, 100, hit), "back side");
    }

    @Test
    void doubleSidedQuadIsLitFromWhicheverSideIsSeen() {
        Node quad = new Node().at(0, 16, 0).quad("+Z", 32, 32).face("front", 0, 0, 0, false, false).doubleSided();
        BakedModel baked = bake(TestModels.solid(RED), 0, quad);
        BakedModel.Hit hit = new BakedModel.Hit();
        assertTrue(baked.intersect(0.5, 0.5, -1, 0, 0, 1, 1e-6, 100, hit));
        assertEquals(-1, hit.nz, EPS);
        assertEquals(1.5, hit.t, EPS);
    }

    @Test
    void nearestShapeWins() {
        BakedModel baked = bake(TestModels.solid(RED), 0,
            new Node().at(0, 16, 8).box(32, 32, 16).allFaces(),
            new Node().at(0, 16, -8).box(32, 32, 16).allFaces());
        BakedModel.Hit hit = new BakedModel.Hit();
        assertTrue(baked.intersect(0.5, 0.5, 2, 0, 0, -1, 1e-6, 100, hit));
        assertEquals(1, hit.t, EPS);
        assertTrue(baked.intersect(0.5, 0.5, -1, 0, 0, 1, 1e-6, 100, hit));
        assertEquals(1, hit.t, EPS);
    }

    @Test
    void rayStartingInsideABoxSeesNoBackFaces() {
        BakedModel baked = bake(TestModels.solid(RED), 0, TestModels.fullCube());
        assertFalse(baked.intersect(0.5, 0.5, 0.5, 0, 0, 1, 1e-6, 100, new BakedModel.Hit()));
    }

    // ------------------------------------------------------------------ library

    @Test
    void libraryCachesFailuresAndSuccesses() {
        TestModels.Assets assets = new TestModels.Assets()
            .put("good.blockymodel", TestModels.model(TestModels.fullCube()))
            .put("bad.blockymodel", "{ not a model".getBytes())
            .put("t.png", TestModels.quadrants());
        ModelLibrary library = new ModelLibrary(new TextureCache(assets), assets);

        assertNull(library.model("bad.blockymodel"));
        assertNull(library.model("missing.blockymodel"));
        assertEquals(1, library.model("good.blockymodel").shapes().size());

        BlockInfo.ModelRef ref = new BlockInfo.ModelRef("good.blockymodel", "t.png", 1f);
        assertSame(library.baked(ref, 0, 0), library.baked(ref, 0, 0), "baked once per rotation");
        assertNull(library.baked(new BlockInfo.ModelRef("bad.blockymodel", "t.png", 1f), 0, 0));
    }
}

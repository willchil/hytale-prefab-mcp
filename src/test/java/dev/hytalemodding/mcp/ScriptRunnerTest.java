package dev.hytalemodding.mcp;

import dev.hytalemodding.mcp.script.BuildRecorder;
import dev.hytalemodding.mcp.script.ScriptError;
import dev.hytalemodding.mcp.script.ScriptRunner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers the sandbox, the failure paths, and the block/fluid routing that {@code block()} does. */
class ScriptRunnerTest {

    private static final ScriptRunner.Options FAST = new ScriptRunner.Options(5_000, 300_000, 42);

    private static ScriptRunner runner() {
        return new ScriptRunner(TestPalette.catalog());
    }

    private static BuildRecorder run(String code) {
        return runner().run(code, FAST).recorder();
    }

    private static ScriptError failure(String code) {
        return assertThrows(ScriptError.class, () -> runner().run(code, FAST));
    }

    // ------------------------------------------------------------------ the engine works at all

    @Test
    void runsModernJavaScriptOnThisJvm() {
        // Guards the one real integration risk: Rhino running under Java 25.
        BuildRecorder recorder = run("""
            const names = ["Rock_Stone", "Wood_Hardwood_Planks"];
            names.forEach((name, i) => block(i, 0, 0, name));
            let [a, b] = [10, 20];
            block(a, b, 0, `Rock_Stone`);
            """);
        assertEquals(3, recorder.blockCount());
        assertEquals("Wood_Hardwood_Planks", recorder.blockAt(1, 0, 0).name());
        assertEquals("Rock_Stone", recorder.blockAt(10, 20, 0).name());
    }

    @Test
    void seededRngIsReproducible() {
        String code = "for (let i = 0; i < 20; i++) block(Math.floor(rng() * 50), 0, i, 'Rock_Stone');";
        assertEquals(run(code).blocks().keySet(), run(code).blocks().keySet());
    }

    // ------------------------------------------------------------------ sandbox

    @Test
    void javaIsUnreachable() {
        // Every one of these is a route out of the sandbox if the class shutter is not doing its job.
        for (String attempt : new String[]{
            "java.lang.System.exit(1);",
            "Packages.java.lang.System.exit(1);",
            "new JavaAdapter(java.lang.Runnable, {});",
            "this.getClass().getClassLoader();"
        }) {
            assertThrows(ScriptError.class, () -> runner().run(attempt, FAST),
                "expected to be blocked: " + attempt);
        }
    }

    @Test
    void evalCannotReachJavaEither() {
        ScriptError error = failure("eval('java.lang.System.exit(1)');");
        assertNotNull(error.getMessage());
    }

    @Test
    void infiniteLoopIsStopped() {
        // A thread interrupt cannot break a spinning script; only the instruction observer can.
        ScriptError error = assertThrows(ScriptError.class,
            () -> runner().run("while (true) {}", new ScriptRunner.Options(600, 300_000, 0)));
        assertEquals(ScriptError.Phase.TIMEOUT, error.phase());
    }

    // ------------------------------------------------------------------ failure reporting

    @Test
    void syntaxErrorReportsTheOffendingLine() {
        ScriptError error = failure("""
            block(0, 0, 0, 'Rock_Stone');
            this is not javascript;
            """);
        assertEquals(ScriptError.Phase.PARSE, error.phase());
        assertEquals(2, error.line());
    }

    @Test
    void runtimeErrorReportsTheOffendingLine() {
        ScriptError error = failure("""
            block(0, 0, 0, 'Rock_Stone');
            boxx(0, 0, 0, 1, 1, 1, 'Rock_Stone');
            """);
        assertEquals(ScriptError.Phase.RUNTIME, error.phase());
        assertEquals(2, error.line());
    }

    @Test
    void unknownNamesAreCollectedWithSuggestions() {
        ScriptError error = failure("""
            block(0, 0, 0, 'Rock_Ston');
            block(1, 0, 0, 'Wood_Hardwood_Plank');
            """);
        assertEquals(ScriptError.Phase.VALIDATE, error.phase());
        // Both typos are reported at once, so one retry can fix the lot.
        assertEquals(2, error.unknownNames().size());
        assertTrue(error.unknownNames().get(0).suggestions().contains("Rock_Stone"),
            "expected a suggestion of Rock_Stone, got " + error.unknownNames().get(0).suggestions());
    }

    @Test
    void typeScriptIsRejectedWithItsOwnPhase() {
        ScriptError error = failure("""
            interface Wall { height: number; }
            block(0, 0, 0, 'Rock_Stone');
            """);
        assertEquals(ScriptError.Phase.TYPESCRIPT, error.phase());
    }

    @Test
    void plainJavaScriptIsNotMistakenForTypeScript() {
        // Guards against the TypeScript check being too eager: all of this is valid JavaScript.
        BuildRecorder recorder = run("""
            const palette = { type: 'Rock_Stone', count: 2 };
            const label = palette.count > 1 ? 'many' : 'one';
            for (let i = 0; i < palette.count; i++) block(i, 0, 0, palette.type);
            log(label);
            """);
        assertEquals(2, recorder.blockCount());
    }

    @Test
    void emptyBuildIsRejected() {
        ScriptError error = failure("const unused = 1;");
        assertEquals(ScriptError.Phase.VALIDATE, error.phase());
    }

    @Test
    void oversizedPrimitiveFailsBeforeExpanding() {
        ScriptError error = failure("box(0, 0, 0, 100000, 500, 100000, 'Rock_Stone');");
        assertEquals(ScriptError.Phase.LIMIT, error.phase());
    }

    @Test
    void yOutsidePackableRangeIsRejected() {
        // BlockUtil packs Y into 9 signed bits, so anything past 511 cannot round-trip.
        ScriptError error = failure("block(0, 900, 0, 'Rock_Stone');");
        assertEquals(ScriptError.Phase.VALIDATE, error.phase());
        assertTrue(error.getMessage().contains("y=900"), error.getMessage());
    }

    // ------------------------------------------------------------------ blocks and fluids share block()

    @Test
    void fluidNamePassedToBlockGoesToTheFluidLayer() {
        BuildRecorder recorder = run("block(1, 2, 3, 'Water_Source');");
        assertEquals(0, recorder.blockCount());
        assertEquals(1, recorder.fluidCount());
        assertEquals("Water_Source", recorder.fluidAt(1, 2, 3).name());
    }

    @Test
    void fluidLevelDefaultsFromTheName() {
        BuildRecorder recorder = run("""
            block(0, 0, 0, 'Water_Source');
            block(1, 0, 0, 'Water');
            """);
        assertEquals(1, recorder.fluidAt(0, 0, 0).level(), "a *_Source cell is a full source");
        assertEquals(8, recorder.fluidAt(1, 0, 0).level(), "a flowing name defaults to full flow");
    }

    @Test
    void explicitLevelOverridesTheDefault() {
        BuildRecorder recorder = run("block(0, 0, 0, 'Water', { level: 3 });");
        assertEquals(3, recorder.fluidAt(0, 0, 0).level());
    }

    @Test
    void geometryHelpersWorkOnFluidsToo() {
        // This is the whole point of merging the two: box() fills a lake with no extra API.
        BuildRecorder recorder = run("box(0, 0, 0, 3, 0, 3, 'Water_Source');");
        assertEquals(16, recorder.fluidCount());
        assertEquals(0, recorder.blockCount());
    }

    @Test
    void aCellCanHoldBothABlockAndAFluid() {
        // 15,659 cells in the shipped prefab corpus do exactly this, almost all underwater plants.
        BuildRecorder recorder = run("""
            block(0, 0, 0, 'Plant_Seaweed_Dead_Stack');
            block(0, 0, 0, 'Water');
            """);
        assertEquals(1, recorder.blockCount());
        assertEquals(1, recorder.fluidCount());
        assertEquals("Plant_Seaweed_Dead_Stack", recorder.blockAt(0, 0, 0).name());
        assertEquals("Water", recorder.fluidAt(0, 0, 0).name());
    }

    @Test
    void namesAreCaseInsensitiveButStoredCanonically() {
        BuildRecorder recorder = run("block(0, 0, 0, 'rock_STONE');");
        assertEquals("Rock_Stone", recorder.blockAt(0, 0, 0).name());
    }

    // ------------------------------------------------------------------ geometry

    @Test
    void lastWriteWins() {
        BuildRecorder recorder = run("""
            box(0, 0, 0, 4, 0, 4, 'Rock_Stone');
            block(2, 0, 2, 'Wood_Hardwood_Planks');
            """);
        assertEquals(25, recorder.blockCount());
        assertEquals("Wood_Hardwood_Planks", recorder.blockAt(2, 0, 2).name());
    }

    @Test
    void hollowBoxKeepsOnlyTheShell() {
        BuildRecorder solid = run("box(0, 0, 0, 4, 4, 4, 'Rock_Stone');");
        BuildRecorder hollow = run("box(0, 0, 0, 4, 4, 4, 'Rock_Stone', { hollow: true });");
        assertEquals(125, solid.blockCount());
        assertEquals(125 - 27, hollow.blockCount());
        assertNull(hollow.blockAt(2, 2, 2));
    }

    @Test
    void clearRemovesFromBothLayers() {
        BuildRecorder recorder = run("""
            block(0, 0, 0, 'Rock_Stone');
            block(0, 0, 0, 'Water');
            block(5, 0, 0, 'Rock_Stone');
            clear(0, 0, 0);
            """);
        assertNull(recorder.blockAt(0, 0, 0));
        assertNull(recorder.fluidAt(0, 0, 0));
        assertEquals(1, recorder.blockCount());
    }

    @Test
    void boundsIgnoreClearedCells() {
        BuildRecorder recorder = run("""
            block(0, 0, 0, 'Rock_Stone');
            block(50, 0, 0, 'Rock_Stone');
            clear(50, 0, 0);
            """);
        BuildRecorder.Bounds bounds = recorder.bounds();
        assertEquals(1, bounds.width(), "a cleared cell must not leave the bounding box inflated");
    }

    @Test
    void mirrorDuplicatesAcrossThePlane() {
        BuildRecorder recorder = run("""
            block(1, 0, 0, 'Rock_Stone');
            mirrorX(0);
            """);
        assertEquals(2, recorder.blockCount());
        assertNotNull(recorder.blockAt(-1, 0, 0));
    }

    @Test
    void blockAtReadsBackWhatWasPlaced() {
        BuildRecorder recorder = run("""
            block(0, 0, 0, 'Rock_Stone');
            if (blockAt(0, 0, 0) === 'Rock_Stone') block(0, 1, 0, 'Wood_Hardwood_Planks');
            if (blockAt(9, 9, 9) === null) block(0, 2, 0, 'Cloth_Block_Wool_White');
            """);
        assertEquals(3, recorder.blockCount());
    }

    @Test
    void paletteQueriesAreAvailableInScript() {
        BuildRecorder recorder = run("""
            const stone = findBlocks({ group: 'Stone', limit: 5 });
            for (let i = 0; i < stone.length; i++) block(i, 0, 0, stone[i]);
            block(0, 5, 0, nearestBlock('#8a8a8a'));
            """);
        assertEquals(3, recorder.blockCount());
        assertEquals("Rock_Stone", recorder.blockAt(0, 5, 0).name());
    }

    @Test
    void badArgumentsFailWithTheFunctionNamed() {
        ScriptError error = failure("block(0, NaN, 0, 'Rock_Stone');");
        assertEquals(ScriptError.Phase.RUNTIME, error.phase());
        assertTrue(error.getMessage().contains("block()"), error.getMessage());
    }

    @Test
    void logIsReturnedToTheCaller() {
        ScriptRunner.Result result = runner().run("""
            log('placed the base');
            block(0, 0, 0, 'Rock_Stone');
            """, FAST);
        assertEquals(1, result.log().size());
        assertEquals("placed the base", result.log().get(0));
        assertFalse(result.recorder().isEmpty());
    }
}

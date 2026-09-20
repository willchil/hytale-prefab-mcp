package dev.hytalemodding.mcp;

import dev.hytalemodding.mcp.script.BuildRecorder;
import dev.hytalemodding.mcp.script.ScriptRunner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Locks in the JavaScript semantics that made the engine choice.
 *
 * <p>Rhino 1.8.0 and 1.8.1 fail the first two of these: a {@code const} declared inside a loop body
 * keeps its first iteration's value forever, and {@code let} has no per-iteration closure capture.
 * Neither raises an error, so the only symptom is a build that is quietly wrong, which is why they
 * are asserted here rather than left to be noticed in a render.
 */
class EngineSemanticsTest {

    private static BuildRecorder run(String code) {
        return new ScriptRunner(TestPalette.catalog())
            .run(code, new ScriptRunner.Options(5_000, 300_000, 1))
            .recorder();
    }

    @Test
    void constInsideALoopBodyIsReevaluatedEachPass() {
        // A tapering tower: each tier derives its radius with a const. If that const is stale, every
        // tier gets tier 0's radius and the taper silently disappears.
        BuildRecorder recorder = run("""
            for (let tier = 0; tier < 4; tier++) {
              const radius = 4 - tier;
              block(radius, tier, 0, 'Rock_Stone');
            }
            """);
        assertEquals(4, recorder.blockCount());
        for (int tier = 0; tier < 4; tier++) {
            assertNotNull(recorder.blockAt(4 - tier, tier, 0),
                "tier " + tier + " should sit at x=" + (4 - tier) + "; a stale const puts them all at x=4");
        }
    }

    @Test
    void constInsideNestedLoopsIsReevaluated() {
        // The distance test that decides a ring. A stale const makes the condition constant, and the
        // usual result is that nothing is placed at all.
        BuildRecorder recorder = run("""
            let hits = 0;
            const r = 6;
            for (let x = -r; x <= r; x++) {
              for (let z = -r; z <= r; z++) {
                const d = x * x + z * z;
                if (d <= r * r && d >= (r - 1) * (r - 1)) { block(x, 0, z, 'Rock_Stone'); hits++; }
              }
            }
            log('hits=' + hits);
            """);
        // 44 is the exact count of integer cells with 25 <= x^2 + z^2 <= 36.
        assertEquals(44, recorder.blockCount());
    }

    @Test
    void letIsCapturedPerIterationByClosures() {
        BuildRecorder recorder = run("""
            const fns = [];
            for (let i = 0; i < 3; i++) fns.push(() => i);
            for (const fn of fns) block(fn(), 0, 0, 'Rock_Stone');
            """);
        // Per-iteration capture gives x = 0, 1, 2. Shared capture would put all three at x = 3,
        // collapsing to a single cell.
        assertEquals(3, recorder.blockCount());
        assertNotNull(recorder.blockAt(0, 0, 0));
        assertNotNull(recorder.blockAt(1, 0, 0));
        assertNotNull(recorder.blockAt(2, 0, 0));
    }

    @Test
    void modernSyntaxIsSupported() {
        BuildRecorder recorder = run("""
            const parts = [{ x: 0, name: 'Rock_Stone' }, { x: 1, name: 'Wood_Hardwood_Planks' }];
            for (const { x, name } of parts) block(x, 0, 0, name);
            const [first, ...rest] = [5, 6, 7];
            block(first, 0, 0, 'Rock_Stone');
            rest.forEach((v, i) => block(v, i + 1, 0, `Rock_Stone`));
            const tally = parts.map(p => p.x).reduce((a, b) => a + b, 0);
            block(tally, 9, 0, 'Rock_Stone');
            """);
        // Six distinct cells: (0,0,0) (1,0,0) (5,0,0) (6,1,0) (7,2,0) (1,9,0).
        assertEquals(6, recorder.blockCount());
    }
}

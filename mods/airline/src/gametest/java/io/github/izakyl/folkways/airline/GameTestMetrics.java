package io.github.izakyl.folkways.airline;

import java.lang.management.ManagementFactory;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Per-test wall time, ticks and CPU time, without changing the test scheduler or tick budget. */
public final class GameTestMetrics implements GameTestListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(GameTestMetrics.class);
    private long started;
    private long tick;
    private long serverCpu;

    public void testStructureLoaded(GameTestInfo info) {
        started = System.nanoTime();
        tick = info.getLevel().getGameTime();
        serverCpu = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
    }

    public void testPassed(GameTestInfo info, GameTestRunner runner) {
        report(info);
    }

    public void testFailed(GameTestInfo info, GameTestRunner runner) {
        report(info);
    }

    public void testAddedForRerun(GameTestInfo original, GameTestInfo rerun, GameTestRunner runner) {
        rerun.addListener(new GameTestMetrics());
    }

    private void report(GameTestInfo info) {
        LOGGER.info("AIRLINE_TEST_RESULT name={} passed={} error={}",
            info.getTestName(), info.hasSucceeded(), String.valueOf(info.getError()));
        long ticks = info.getLevel().getGameTime() - tick;
        double millis = (System.nanoTime() - started) / 1.0e6;
        LOGGER.info("AIRLINE_TEST_METRICS name={} ticks={} testTicks={} wallMs={} mspt={}"
                + " serverCpuMs={}",
            info.getTestName(), ticks, ((GameTestTicks.Repeating) info).airline$testTick(),
            millis, millis / Math.max(1, ticks),
            (ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime() - serverCpu) / 1.0e6);
    }
}

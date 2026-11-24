package io.github.izakyl.folkways.core.testing;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;

/** Releases what a test holds once it ends, whether it passed or failed. */
public final class Cleanup {
    private Cleanup() {
    }

    public static void on(GameTestHelper helper, Runnable close) {
        on(helper, close, close);
    }

    public static void on(GameTestHelper helper, Runnable passed, Runnable failed) {
        helper.testInfo.addListener(new GameTestListener() {
            public void testStructureLoaded(GameTestInfo info) {
            }

            public void testPassed(GameTestInfo info, GameTestRunner runner) {
                passed.run();
            }

            public void testFailed(GameTestInfo info, GameTestRunner runner) {
                failed.run();
            }

            public void testAddedForRerun(GameTestInfo original, GameTestInfo rerun, GameTestRunner runner) {
            }
        });
    }
}

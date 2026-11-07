package io.github.izakyl.folkways.cookery.testing;

import io.github.izakyl.folkways.core.plan.api.Grown;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.gametest.framework.GameTestHelper;

// The older name for RecoveryAsking's repeated-sweep check.
public final class KitchenRecoveryWanted {
    private KitchenRecoveryWanted() { }

    public static void assertKept(GameTestHelper helper, Grown original, Supplier<List<Grown>> wanted) {
        RecoveryAsking.assertKept(helper, original, wanted);
    }
}

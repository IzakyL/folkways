package io.github.izakyl.folkways.cookery.testing;

import io.github.izakyl.folkways.core.api.colony.Asking;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.cookery.CookeryContent;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.minecraft.gametest.framework.GameTestHelper;

// Drive the real Asking reconciler against native appliance goals. The sink only records root
// submissions/withdrawals; the same native test independently executes and settles the physical batch.
public final class KitchenRecoveryWanted {
    private KitchenRecoveryWanted() { }
    public static void assertKept(GameTestHelper helper, Grown original, Supplier<List<Grown>> wanted) {
        var submissions = new AtomicInteger();
        List<UUID> withdrawn = new ArrayList<>();
        Colony sink = (Colony) Proxy.newProxyInstance(Colony.class.getClassLoader(), new Class<?>[] { Colony.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "submit" -> { submissions.incrementAndGet(); yield null; }
                case "withdraw" -> { withdrawn.add((UUID) args[1]); yield null; }
                case "toString" -> "native kitchen recovery Asking observer";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
        Asking asking = new Asking(sink, CookeryContent.ID, () -> {});
        asking.offer(Map.of(helper.getLevel().dimension(), List.of(original)));
        for (int sweep = 0; sweep < 4; sweep++) {
            List<Grown> next = wanted.get();
            helper.assertTrue(next.size() == 1 && next.getFirst() == original,
                "a booked native recovery remains the identical original Grown across repeated sweeps");
            asking.offer(Map.of(helper.getLevel().dimension(), next));
        }
        helper.assertValueEqual(submissions.get(), 1, "the actual Asking reconciler submits the original root only once");
        helper.assertTrue(withdrawn.isEmpty(), "the actual Asking reconciler never revokes a still-booked native batch as replaced");
    }
}

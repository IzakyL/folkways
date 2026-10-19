package io.github.izakyl.folkways.cookery.testing;

import io.github.izakyl.folkways.core.api.colony.Asking;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.cookery.CookeryContent;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.gametest.framework.GameTestHelper;

/** Real Asking lifecycle, with only its colony submission/withdrawal ports observed. */
public final class RecoveryAsking {
    private final GameTestHelper helper;
    private final Asking asking;
    private int submissions;
    private int withdrawals;
    private BiConsumer<UUID, Ending> ended;

    @SuppressWarnings("unchecked")
    public RecoveryAsking(GameTestHelper helper) {
        this.helper = helper;
        Colony colony = (Colony) Proxy.newProxyInstance(Colony.class.getClassLoader(), new Class<?>[] { Colony.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "submit" -> { submissions++; ended = (BiConsumer<UUID, Ending>) args[3]; yield null; }
                case "withdraw" -> { withdrawals++; yield null; }
                default -> throw new AssertionError(method.getName());
            });
        asking = new Asking(colony, CookeryContent.ID, () -> {});
    }
    public void offer(List<Grown> goals) { asking.offer(Map.of(helper.getLevel().dimension(), goals)); }
    public void assertKept(Grown original, List<Grown> goals) {
        helper.assertTrue(goals.size() == 1 && goals.getFirst() == original, "the booked native recovery remains the exact originally offered Grown");
        offer(goals);
        helper.assertValueEqual(submissions, 1, "Asking submits the recovery once across repeated offers");
        helper.assertValueEqual(withdrawals, 0, "a booked recovery never disappears from wanted and is never withdrawn as replaced");
    }
    public void finished(Grown original, Ending how) {
        ended.accept(original.nodes().getFirst().id(), how);
        helper.assertTrue(asking.live().isEmpty(), "actual completion settles Asking's one original root");
    }
}

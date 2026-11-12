package io.github.izakyl.folkways.core.shell.crafting;

import java.util.HashMap;
import java.util.Map;

/** Only native crafting consumption during this synchronous transfer can explain missing storage growth. */
public final class InsertionReceipts implements AutoCloseable {
    private static final ThreadLocal<InsertionReceipts> CURRENT = new ThreadLocal<>();
    private final InsertionReceipts previous;
    private final Map<Object, Long> consumed = new HashMap<>();

    public InsertionReceipts() {
        previous = CURRENT.get();
        CURRENT.set(this);
    }

    public static void consumed(Object key, long count) {
        InsertionReceipts receipt = CURRENT.get();
        if (receipt != null && count > 0) {
            receipt.consumed.merge(key, count, Math::addExact);
        }
    }

    public long count(Object key) {
        return consumed.getOrDefault(key, 0L);
    }

    @Override
    public void close() {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}

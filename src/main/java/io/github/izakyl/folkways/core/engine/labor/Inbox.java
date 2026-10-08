package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.core.engine.plan.Message;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Predicate;

final class Inbox {

    private final Deque<Message> waiting = new ConcurrentLinkedDeque<>();

    private List<Message> handed = List.of();

    void raise(Message message) {
        waiting.addLast(message);
    }

    boolean any() {
        return !waiting.isEmpty();
    }

    void discard(Predicate<Message> stale) {
        waiting.removeIf(stale);
    }

    int size() {
        return waiting.size();
    }

    List<Message> hand() {
        if (!handed.isEmpty()) {
            throw new IllegalStateException(
                "a batch is still in flight; a plan was begun while another had not returned");
        }
        List<Message> batch = new ArrayList<>();
        for (Message message = waiting.pollFirst(); message != null; message = waiting.pollFirst()) {
            batch.add(message);
        }
        handed = List.copyOf(batch);
        return handed;
    }

    void digested() {
        handed = List.of();
    }

    void giveBack() {
        for (int at = handed.size() - 1; at >= 0; at--) {
            waiting.addFirst(handed.get(at));
        }
        handed = List.of();
    }
}

package io.github.izakyl.folkways.core.engine.plan;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

public final class Cooldowns {

    private static final long FIRST_WAIT = 20;

    private static final long LONGEST_WAIT = 640;

    private record Bar(int failures, long retryAt) {
    }

    private final Map<Choice, Bar> bars = new LinkedHashMap<>();
    private final Map<UUID, Choice> madeBy = new LinkedHashMap<>();

    private long now;

    public boolean shut(Choice choice) {
        Bar bar = bars.get(choice);
        return bar != null && now < bar.retryAt();
    }

    public void failed(Choice choice) {
        Bar had = bars.get(choice);
        int failures = had == null ? 1 : had.failures() + 1;
        long wait = failures == 1 ? 0 : Math.min(LONGEST_WAIT, FIRST_WAIT << Math.min(failures - 2, 16));
        bars.put(choice, new Bar(failures, now + wait));
    }

    public void succeeded(Choice choice) {
        bars.remove(choice);
    }

    public void made(UUID node, Choice by) {
        madeBy.put(node, by);
    }

    public Optional<Choice> behind(UUID node) {
        return Optional.ofNullable(madeBy.get(node));
    }

    public void forget(UUID node) {
        madeBy.remove(node);
    }

    public void round(long tick) {
        now = tick;
        bars.values().removeIf(bar -> now >= bar.retryAt() + LONGEST_WAIT);
    }

    public OptionalLong nextOpening() {
        return bars.values().stream().mapToLong(Bar::retryAt).filter(at -> at > now).min();
    }
}

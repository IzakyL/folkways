package io.github.izakyl.folkways.plugins.groundwork;

import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.front.api.PastDay;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;

// The work one zone asks for, as the last sweep found it, and the pieces of it under way.
final class Chores {

    @FunctionalInterface
    interface Maker {

        Node make(ServerLevel level, Chore chore);
    }

    record Chore(UUID id, Job job, Chores tending) {
    }

    private final UUID zone;
    // Which kind of zone, so a zone redrawn as another kind asks for its work under new ids.
    private final String kind;
    private final Maker maker;
    private final Runnable changed;
    private final PastDay made;

    private volatile List<Job> standing = List.of();

    // A piece in the plan stands as it was asked for until it ends, whatever the zone says since.
    private final Map<String, Job> busy = new ConcurrentHashMap<>();
    private final Set<String> spent = ConcurrentHashMap.newKeySet();

    Chores(UUID zone, String kind, Maker maker, Runnable changed, PastDay made) {
        this.zone = zone;
        this.kind = kind;
        this.maker = maker;
        this.changed = changed;
        this.made = made;
    }

    PastDay made() {
        return made;
    }

    void settle(List<Job> jobs) {
        standing = List.copyOf(jobs);
        spent.clear();
    }

    void goals(ServerLevel level, List<Grown> into) {
        busy.values().forEach(job -> {
            if (job.site().where().in(level)) {
                into.add(Grown.of(maker.make(level, chore(job))));
            }
        });
        for (Job job : standing) {
            if (busy.containsKey(job.key()) || spent.contains(job.key()) || !job.site().where().in(level)) {
                continue;
            }
            into.add(Grown.of(maker.make(level, chore(job))));
        }
    }

    void planned(Chore chore) {
        busy.putIfAbsent(chore.job().key(), chore.job());
        changed.run();
    }

    void ended(Chore chore, Ending how) {
        if (how instanceof Ending.Done) {
            spent.add(chore.job().key());
        }
        busy.remove(chore.job().key());
        changed.run();
    }

    private Chore chore(Job job) {
        return new Chore(UUID.nameUUIDFromBytes(("folkways:ground/" + kind + "/" + zone + "/" + job.key())
            .getBytes(StandardCharsets.UTF_8)), job, this);
    }
}

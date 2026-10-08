package io.github.izakyl.folkways.plugins.farming;

import io.github.izakyl.folkways.core.api.work.Before;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.front.api.PastDay;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;

final class FieldChores {

    @FunctionalInterface
    interface Maker {

        Node make(FarmNode.Chore chore);
    }

    private final String kind;
    private final Maker maker;
    private final Runnable changed;
    private final PastDay made;

    // A batch in the plan, with the cells of it whose work has not yet ended.
    private record Busy(Batch batch, Set<BlockPos> open) {
    }

    private volatile List<Batch> standing = List.of();

    private final Map<UUID, Busy> busy = new ConcurrentHashMap<>();
    private final Set<BlockPos> spent = ConcurrentHashMap.newKeySet();

    FieldChores(String kind, Maker maker, Runnable changed, PastDay made) {
        this.kind = kind;
        this.maker = maker;
        this.changed = changed;
        this.made = made;
    }

    PastDay made() {
        return made;
    }

    void settle(List<Batch> batches) {
        standing = List.copyOf(batches);
        spent.clear();
    }

    void goals(ServerLevel level, List<Grown> into) {
        Set<BlockPos> taken = new HashSet<>();
        busy.values().forEach(held -> {
            held.batch().jobs().forEach(job -> taken.add(job.cell()));
            if (held.batch().at().where().in(level)) {
                grow(held.batch(), job -> held.open().contains(job.cell())).ifPresent(into::add);
            }
        });
        for (Batch batch : standing) {
            if (busy.containsKey(idOf(batch)) || !batch.at().where().in(level)
                || batch.jobs().stream().anyMatch(job -> taken.contains(job.cell()))) {
                continue;
            }
            grow(batch, job -> !spent.contains(job.cell())).ifPresent(into::add);
        }
    }

    void planned(FarmNode.Chore chore) {
        busy.computeIfAbsent(idOf(chore.batch()), id -> new Busy(chore.batch(), ConcurrentHashMap.newKeySet()))
            .open().add(chore.job().cell());
        changed.run();
    }

    void ended(FarmNode.Chore chore, Ending how) {
        if (how instanceof Ending.Done) {
            spent.add(chore.job().cell());
        }
        busy.computeIfPresent(idOf(chore.batch()), (id, held) -> {
            held.open().remove(chore.job().cell());
            return held.open().isEmpty() ? null : held;
        });
        changed.run();
    }

    // The cells still to work, one node each, done in order by one worker from where the batch stands.
    private Optional<Grown> grow(Batch batch, Predicate<Job> left) {
        List<Node> cells = new ArrayList<>();
        List<Before> order = new ArrayList<>();
        for (Job job : batch.jobs()) {
            if (!left.test(job)) {
                continue;
            }
            Node node = maker.make(new FarmNode.Chore(idOf(batch, job), batch, job, this));
            if (!cells.isEmpty()) {
                order.add(new Before(cells.getLast().id(), node.id()));
            }
            cells.add(node);
        }
        if (cells.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Grown(cells, order).byOneWorker());
    }

    private UUID idOf(Batch batch) {
        return idOf(batch, batch.jobs().getFirst());
    }

    private UUID idOf(Batch batch, Job job) {
        return UUID.nameUUIDFromBytes(("folkways:farm/" + kind + "/"
            + BuiltInRegistries.BLOCK.getKey(batch.crop().block()) + "/" + batch.at().where() + "/"
            + job.cell()).getBytes(StandardCharsets.UTF_8));
    }
}

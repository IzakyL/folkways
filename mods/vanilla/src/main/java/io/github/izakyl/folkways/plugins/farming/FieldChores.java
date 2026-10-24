package io.github.izakyl.folkways.plugins.farming;

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
import net.minecraft.core.BlockPos;
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

    // A cell in the plan, as it was asked for: its work stands until that work ends, whatever the field says since.
    private record Busy(Crop crop, Job job) {
    }

    private volatile Crop crop;
    private volatile Set<BlockPos> allowed;


    private volatile List<Job> standing = List.of();

    private final Map<BlockPos, Busy> busy = new ConcurrentHashMap<>();
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

    void settle(Crop crop, List<Job> jobs, Set<BlockPos> allowed) {
        this.crop = crop;
        this.allowed = Set.copyOf(allowed);
        standing = List.copyOf(jobs);
        spent.clear();
    }

    void close() {
        allowed = Set.of();
        standing = List.of();
    }

    boolean permits(FarmNode.Chore chore) {
        Set<BlockPos> current = allowed;
        return current == null || current.contains(chore.job().cell());
    }

    // Every cell is its own work, done from wherever in reach of it the way there is easiest.
    void goals(ServerLevel level, List<Grown> into) {
        busy.values().forEach(held -> {
            if (held.job().ground().in(level)) {
                into.add(Grown.of(maker.make(chore(held.crop(), held.job()))));
            }
        });
        Crop growing = crop;
        for (Job job : standing) {
            if (busy.containsKey(job.cell()) || spent.contains(job.cell()) || !job.ground().in(level)) {
                continue;
            }
            into.add(Grown.of(maker.make(chore(growing, job))));
        }
    }

    List<BlockPos> neighbors(Job origin, int extra) {
        java.util.LinkedHashSet<BlockPos> found = new java.util.LinkedHashSet<>(origin.cells());
        int added = 0;
        boolean changed;
        do {
            changed = false;
            for (Job candidate : standing) {
                if (added >= extra) { break; }
                if (busy.containsKey(candidate.cell()) || found.containsAll(candidate.cells())) { continue; }
                if (candidate.cells().stream().anyMatch(at -> found.stream().anyMatch(old ->
                        Math.abs(at.getX() - old.getX()) + Math.abs(at.getZ() - old.getZ()) == 1
                        && at.getY() == old.getY()))) {
                    found.addAll(candidate.cells());
                    added++;
                    changed = true;
                }
            }
        } while (changed && added < extra);
        return List.copyOf(found);
    }

    void planned(FarmNode.Chore chore) {
        busy.putIfAbsent(chore.job().cell(), new Busy(chore.crop(), chore.job()));
        changed.run();
    }

    void ended(FarmNode.Chore chore, Ending how) {
        if (how instanceof Ending.Done) {
            spent.add(chore.job().cell());
        }
        busy.remove(chore.job().cell());
        changed.run();
    }

    private FarmNode.Chore chore(Crop growing, Job job) {
        return new FarmNode.Chore(idOf(growing, job), growing, job, this);
    }

    private UUID idOf(Crop growing, Job job) {
        return UUID.nameUUIDFromBytes(("folkways:farm/" + kind + "/"
            + growing.id() + "/" + growing.harvestMethod().map(HarvestMethods::idOf).orElse(HarvestMethods.PICK)
            + "/" + job.ground() + "/"
            + job.cell()).getBytes(StandardCharsets.UTF_8));
    }
}

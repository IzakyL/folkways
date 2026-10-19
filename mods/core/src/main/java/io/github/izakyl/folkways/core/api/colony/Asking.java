package io.github.izakyl.folkways.core.api.colony;

import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

public final class Asking {

    private final Colony colony;
    private final ResourceLocation owner;
    private final BiConsumer<UUID, Ending> ended;
    private final Set<UUID> live = new LinkedHashSet<>();
    private final Set<UUID> withdrawing = new HashSet<>();

    public Asking(Colony colony, ResourceLocation owner, Runnable changed) {
        this(colony, owner, (node, how) -> changed.run());
    }

    public Asking(Colony colony, ResourceLocation owner, BiConsumer<UUID, Ending> ended) {
        this.colony = colony;
        this.owner = owner;
        this.ended = ended;
    }

    // Asks for every node wanted that is not asked yet, and withdraws every node asked that is not wanted any more.
    // A node still wanted after it ended is asked for again.
    public void offer(Map<ResourceKey<Level>, List<Grown>> wanted) {
        Set<UUID> keep = new HashSet<>();
        wanted.values().forEach(asks -> asks.forEach(work -> keep.addAll(work.completions())));
        for (UUID id : List.copyOf(live)) {
            if (!keep.contains(id) && withdrawing.add(id)) {
                colony.withdraw(owner, id);
            }
        }
        wanted.forEach((dimension, asks) -> {
            for (Grown work : asks) {
                Set<UUID> fresh = new LinkedHashSet<>(work.completions());
                fresh.removeAll(live);
                if (!fresh.isEmpty()) {
                    live.addAll(fresh);
                    colony.submit(owner, dimension, work.remaining(fresh), this::end);
                }
            }
        });
    }

    public void offer(MinecraftServer server, Function<ColonyView, List<Grown>> goals) {
        Map<ResourceKey<Level>, List<Grown>> work = new LinkedHashMap<>();
        for (ColonyView view : colony.views(server)) {
            work.put(view.dimension(), goals.apply(view));
        }
        offer(work);
    }

    public Set<UUID> live() {
        return Set.copyOf(live);
    }

    private void end(UUID id, Ending how) {
        if (live.remove(id)) {
            withdrawing.remove(id);
            ended.accept(id, how);
        }
    }
}

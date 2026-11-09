package io.github.izakyl.folkways.vanilla.pasture;

import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.front.api.PastDay;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.minecraft.server.level.ServerLevel;

final class PenChores {

    private final UUID zone;
    private final Herd.Job job;
    private volatile Pen pen;
    private final Runnable changed;
    private final PastDay made;
    private final Function<ServerLevel, Optional<Pen>> current;

    private volatile List<Quarry> standing = List.of();

    private final Set<UUID> worked = ConcurrentHashMap.newKeySet();

    PenChores(Pen pen, Herd.Job job, Runnable changed, PastDay made,
            Function<ServerLevel, Optional<Pen>> current) {
        this.changed = changed;
        this.made = made;
        this.zone = pen.zone();
        this.job = job;
        this.pen = pen;
        this.current = current;
    }

    void settle(Pen pen, List<Quarry> found) {
        this.pen = pen;
        standing = List.copyOf(found);
        worked.clear();
    }

    void goals(ServerLevel level, List<Grown> into) {
        goals(level, into, java.util.Optional.empty());
    }

    void goals(ServerLevel level, List<Grown> into, java.util.Optional<UUID> nurturer) {
        if (job == Herd.Job.NURTURE && nurturer.isEmpty()) { return; }
        for (Quarry beast : standing) {
            if (!worked.contains(beast.subject()) && beast.at().where().in(level)) {
                into.add(Grown.of(node(level, new PenNode.Chore(idOf(beast), beast,
                    () -> {
                        worked.add(beast.subject());
                        changed.run();
                    }, made), nurturer)));
            }
        }
    }

    private Node node(ServerLevel level, PenNode.Chore chore, java.util.Optional<UUID> nurturer) {
        return switch (job) {
            case SHEAR -> new ShearNode(chore);
            case NURTURE -> new NurtureNode(chore, pen.keeps().feed(), nurturer.orElseThrow());
            case FEED -> new FeedNode(chore, pen.keeps().feed(), pen, current);
            case CULL -> new CullNode(chore, current);
            case GLEAN -> new GleanNode(level, chore);
        };
    }

    private UUID idOf(Quarry beast) {
        return UUID.nameUUIDFromBytes(("folkways_vanilla:pasture/" + zone + "/" + job + "/" + beast.subject()
                + beast.mate().map(mate -> "/" + mate).orElse(""))
            .getBytes(StandardCharsets.UTF_8));
    }
}

package io.github.izakyl.folkways.plugins.pasture;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.front.api.PastDay;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;

final class PenChores {

    private final UUID zone;
    private final Herd.Job job;
    private final ItemSpec feed;
    private final Runnable changed;
    private final PastDay made;

    private volatile List<Quarry> standing = List.of();

    private final Set<UUID> worked = ConcurrentHashMap.newKeySet();

    PenChores(Pen pen, Herd.Job job, Runnable changed, PastDay made) {
        this.changed = changed;
        this.made = made;
        this.zone = pen.zone();
        this.job = job;
        this.feed = pen.keeps().feed();
    }

    void settle(List<Quarry> found) {
        standing = List.copyOf(found);
        worked.clear();
    }

    void goals(ServerLevel level, List<Grown> into) {
        for (Quarry beast : standing) {
            if (!worked.contains(beast.subject()) && beast.at().where().in(level)) {
                into.add(Grown.of(node(new PenNode.Chore(idOf(beast), beast,
                    () -> {
                        worked.add(beast.subject());
                        changed.run();
                    }, made))));
            }
        }
    }

    private Node node(PenNode.Chore chore) {
        return switch (job) {
            case SHEAR -> new ShearNode(chore);
            case FEED -> new FeedNode(chore, feed);
            case CULL -> new CullNode(chore);
            case GLEAN -> new GleanNode(chore);
        };
    }

    private UUID idOf(Quarry beast) {
        return UUID.nameUUIDFromBytes(("folkways:pasture/" + zone + "/" + job + "/" + beast.subject())
            .getBytes(StandardCharsets.UTF_8));
    }
}
